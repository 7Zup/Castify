package app.castify.domain

import app.castify.domain.model.MediaStream
import app.castify.domain.model.NowPlaying
import app.castify.domain.model.PlaybackState
import app.castify.domain.model.PlaybackStats
import app.castify.domain.model.StreamType
import app.castify.domain.model.TvDevice
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.port.TvClient
import app.castify.domain.session.TvSession
import app.castify.domain.session.TvSession.SendResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSessionTest {

    /** A fake TV shared by several phones. */
    private class FakeTv : TvClient {
        var nowPlaying = NowPlaying.IDLE
        var reachable = true
        val played = mutableListOf<MediaStream>()
        val commands = mutableListOf<PlayerCommand>()
        var statusCalls = 0

        private fun <T> answer(value: () -> T): Result<T> =
            if (reachable) Result.success(value()) else Result.failure(java.io.IOException("timeout"))

        override suspend fun play(device: TvDevice, stream: MediaStream) = answer {
            played += stream
            nowPlaying = NowPlaying(PlaybackState.BUFFERING, thumbnailUrl = stream.thumbnailUrl)
        }
        override suspend fun status(device: TvDevice) = answer { statusCalls++; nowPlaying }
        override suspend fun control(device: TvDevice, command: PlayerCommand) = answer { commands += command; Unit }
    }

    private val tv = TvDevice("MyCast", "10.0.0.42", 47800)
    private val stream = MediaStream("https://cdn.com/m.m3u8", StreamType.HLS, "https://site.com", thumbnailUrl = "https://img/t.jpg")

    @Test
    fun `connect polls the tv status`() = runTest {
        val fake = FakeTv().apply { nowPlaying = NowPlaying(PlaybackState.PLAYING, positionMs = 5_000) }
        val session = TvSession(fake, backgroundScope)
        session.connect(tv)
        runCurrent()
        assertEquals(PlaybackState.PLAYING, session.state.value.nowPlaying?.state)

        fake.nowPlaying = fake.nowPlaying.copy(positionMs = 6_000)
        advanceTimeBy(1_001)
        assertEquals(6_000L, session.state.value.nowPlaying?.positionMs)
    }

    @Test
    fun `polls slower when nothing plays`() = runTest {
        val fake = FakeTv()
        val session = TvSession(fake, backgroundScope)
        session.connect(tv)
        runCurrent()
        advanceTimeBy(2_500)
        assertEquals(1, fake.statusCalls)
        advanceTimeBy(600)
        assertEquals(2, fake.statusCalls)
    }

    @Test
    fun `send goes straight when nothing plays, with quality and thumbnail`() = runTest {
        val fake = FakeTv()
        val session = TvSession(fake, backgroundScope)
        session.connect(tv)
        assertEquals(SendResult.Sent, session.send(stream, height = 800))
        assertEquals(800, fake.played.single().preferredHeight)
        assertEquals("https://img/t.jpg", session.state.value.nowPlaying?.thumbnailUrl)
    }

    @Test
    fun `replacing a running video needs confirmation`() = runTest {
        val fake = FakeTv().apply { nowPlaying = NowPlaying(PlaybackState.PAUSED) }
        val session = TvSession(fake, backgroundScope)
        session.connect(tv)
        assertEquals(SendResult.NeedsConfirmation, session.send(stream, null))
        assertTrue(fake.played.isEmpty())
        assertEquals(SendResult.Sent, session.send(stream, null, replace = true))
        assertEquals(1, fake.played.size)
    }

    @Test
    fun `send without connection fails`() = runTest {
        val session = TvSession(FakeTv(), backgroundScope)
        assertTrue(session.send(stream, null) is SendResult.Failed)
    }

    @Test
    fun `two phones see and control the same video`() = runTest {
        val fake = FakeTv()
        val mine = TvSession(fake, backgroundScope).apply { connect(tv) }
        val hers = TvSession(fake, backgroundScope).apply { connect(tv) }
        mine.send(stream, null)
        advanceTimeBy(3_001)
        assertEquals(PlaybackState.BUFFERING, hers.state.value.nowPlaying?.state)

        hers.control(PlayerCommand.TogglePause)
        runCurrent()
        assertEquals(listOf<PlayerCommand>(PlayerCommand.TogglePause), fake.commands)
    }

    @Test
    fun `diagnostics toggle is never sent by a phone`() = runTest {
        val fake = FakeTv()
        val session = TvSession(fake, backgroundScope).apply { connect(tv) }
        session.control(PlayerCommand.ToggleDiagnostics)
        runCurrent()
        assertTrue(fake.commands.isEmpty())
    }

    @Test
    fun `connecting until the tv answers`() = runTest {
        val session = TvSession(FakeTv(), backgroundScope)
        session.connect(tv)
        assertTrue(session.state.value.connecting)
        assertFalse(session.state.value.live)
        runCurrent()
        assertFalse(session.state.value.connecting)
        assertTrue(session.state.value.live)
    }

    @Test
    fun `a tv that never answers is not connected, and says so`() = runTest {
        val fake = FakeTv().apply { reachable = false }
        val session = TvSession(fake, backgroundScope)
        val failures = mutableListOf<TvSession.ConnectFailed>()
        backgroundScope.launch { session.connectFailures.collect { failures += it } }
        runCurrent()
        session.connect(tv)
        runCurrent()
        assertFalse(session.state.value.connected)
        assertEquals(listOf(TvSession.ConnectFailed(tv)), failures)
        val calls = fake.statusCalls
        advanceTimeBy(10_000)
        assertEquals(calls, fake.statusCalls) // no more polling
    }

    @Test
    fun `send to a tv that never answers reports not connected`() = runTest {
        val fake = FakeTv().apply { reachable = false }
        val session = TvSession(fake, backgroundScope)
        session.connect(tv)
        assertEquals(SendResult.NotConnected, session.send(stream, null))
        assertTrue(fake.played.isEmpty())
    }

    @Test
    fun `the session follows the tv to its new address`() = runTest {
        val fake = FakeTv()
        val session = TvSession(fake, backgroundScope)
        session.connect(tv)
        runCurrent()
        val moved = tv.copy(host = "10.0.0.27")
        session.follow(listOf(TvDevice("Other", "10.0.0.9", 47800), moved))
        assertEquals(moved, session.state.value.device)
        session.follow(listOf(TvDevice("Other", "10.0.0.9", 47800)))
        assertEquals(moved, session.state.value.device)
    }

    @Test
    fun `unreachable tv keeps the connection, retry recovers`() = runTest {
        val fake = FakeTv()
        val session = TvSession(fake, backgroundScope)
        session.connect(tv)
        runCurrent()
        fake.reachable = false
        advanceTimeBy(3_001)
        assertTrue(session.state.value.connected)
        assertFalse(session.state.value.reachable)

        fake.reachable = true
        session.refresh()
        runCurrent()
        assertTrue(session.state.value.reachable)
    }

    @Test
    fun `disconnect stops polling and clears the state`() = runTest {
        val fake = FakeTv()
        val session = TvSession(fake, backgroundScope)
        session.connect(tv)
        runCurrent()
        session.disconnect()
        val calls = fake.statusCalls
        advanceTimeBy(10_000)
        assertEquals(calls, fake.statusCalls)
        assertNull(session.state.value.device)
    }

    @Test
    fun `now playing on the tv exposes only thumbnail headers`() {
        val s = stream.copy(headers = mapOf("Referer" to "https://site.com/", "Cookie" to "secret=1", "User-Agent" to "UA"))
        val now = NowPlaying.from(
            PlaybackStats(state = PlaybackState.PLAYING, isLive = true, positionMs = 60_000, bufferedAheadMs = 45_000),
            s,
        )
        assertEquals(105_000L, now.bufferedPositionMs)
        assertEquals(mapOf("Referer" to "https://site.com/", "User-Agent" to "UA"), now.thumbnailHeaders)
        assertTrue(now.isLive)
        assertEquals(NowPlaying.IDLE, NowPlaying.from(PlaybackStats(), null))
    }
}
