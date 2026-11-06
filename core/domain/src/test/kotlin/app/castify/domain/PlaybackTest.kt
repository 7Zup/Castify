package app.castify.domain

import app.castify.domain.model.MediaStream
import app.castify.domain.model.PlaybackStats
import app.castify.domain.model.StreamType
import app.castify.domain.playback.BufferPolicy
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.playback.RemoteControl
import app.castify.domain.playback.RemoteKey
import app.castify.domain.port.Player
import app.castify.domain.usecase.HandlePlayerCommand
import app.castify.domain.usecase.OpenPage
import app.castify.domain.usecase.PlayStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {

    private class FakePlayer : Player {
        override val stats = MutableStateFlow(PlaybackStats())
        val calls = mutableListOf<String>()
        override fun play(stream: MediaStream, policy: BufferPolicy, startPositionMs: Long) { calls += "play:${policy.maxBufferMs}" }
        override fun togglePause() { calls += "toggle" }
        override fun seekBy(deltaMs: Long) { calls += "seek:$deltaMs" }
        override fun seekTo(positionMs: Long) { calls += "seekTo:$positionMs" }
        override fun selectSubtitle(trackId: String?) { calls += "sub:${trackId ?: "off"}" }
        override fun stop() { calls += "stop" }
        override fun release() { calls += "release" }
    }

    @Test
    fun `remote keys map to commands`() {
        assertEquals(PlayerCommand.TogglePause, RemoteControl.commandFor(RemoteKey.OK))
        assertEquals(PlayerCommand.SeekBy(-10_000), RemoteControl.commandFor(RemoteKey.LEFT))
        assertEquals(PlayerCommand.SeekBy(10_000), RemoteControl.commandFor(RemoteKey.RIGHT))
        assertEquals(PlayerCommand.ToggleDiagnostics, RemoteControl.commandFor(RemoteKey.UP))
        assertEquals(PlayerCommand.Stop, RemoteControl.commandFor(RemoteKey.BACK))
        assertEquals(PlayerCommand.ShowControls, RemoteControl.commandFor(RemoteKey.DOWN))
    }

    @Test
    fun `commands reach the player, tv-only commands stay in the UI`() {
        val player = FakePlayer()
        val handle = HandlePlayerCommand(player)
        assertTrue(handle(PlayerCommand.TogglePause))
        assertTrue(handle(PlayerCommand.SeekBy(10_000)))
        assertTrue(handle(PlayerCommand.SeekTo(60_000)))
        assertTrue(handle(PlayerCommand.SelectSubtitle("0:1")))
        assertTrue(handle(PlayerCommand.SelectSubtitle(null)))
        assertTrue(handle(PlayerCommand.Stop))
        assertFalse(handle(PlayerCommand.ToggleDiagnostics))
        assertFalse(handle(PlayerCommand.OpenSubtitleMenu))
        assertFalse(handle(PlayerCommand.ShowControls))
        assertEquals(listOf("toggle", "seek:10000", "seekTo:60000", "sub:0:1", "sub:off", "stop"), player.calls)
    }

    @Test
    fun `play stream uses the v0 buffer policy`() {
        val player = FakePlayer()
        PlayStream(player)(MediaStream("https://a.com/v.mp4", StreamType.PROGRESSIVE, "https://a.com"))
        assertEquals(listOf("play:120000"), player.calls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buffer policy rejects inconsistent values`() {
        BufferPolicy(minBufferMs = 10_000, maxBufferMs = 5_000, startPlaybackMs = 1_000, resumeAfterStallMs = 1_000)
    }

    @Test
    fun `address bar input is normalized`() {
        assertEquals("https://site.com", OpenPage.normalize(" site.com "))
        assertEquals("http://10.0.0.2/x", OpenPage.normalize("http://10.0.0.2/x"))
        assertEquals("https://duckduckgo.com/?q=one+piece", OpenPage.normalize("one piece"))
        assertNull(OpenPage.normalize("   "))
    }
}
