package app.castify.domain

import app.castify.domain.model.DetectedVideo
import app.castify.domain.model.MediaStream
import app.castify.domain.model.NowPlaying
import app.castify.domain.model.PlaybackState
import app.castify.domain.model.PlaybackStats
import app.castify.domain.model.StreamType
import app.castify.domain.model.TvDevice
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.port.EpisodeLoader
import app.castify.domain.port.ShowCatalog
import app.castify.domain.port.TvClient
import app.castify.domain.series.Episode
import app.castify.domain.series.EpisodeAddress
import app.castify.domain.series.EpisodeNumber
import app.castify.domain.series.EpisodePlayer
import app.castify.domain.series.EpisodeRequest
import app.castify.domain.series.Show
import app.castify.domain.series.ShowId
import app.castify.domain.session.TvSession
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesTest {

    @Test
    fun `episode addresses are read from the path`() {
        val a = EpisodeAddress.parse("https://streamsite.example/play/tv/60573/5/2")!!
        assertEquals(ShowId.Tmdb(60573), a.show)
        assertEquals(EpisodeNumber(5, 2), a.number)
        assertEquals("https://streamsite.example/play/tv/60573/6/1", a.withEpisode(EpisodeNumber(6, 1)))
    }

    @Test
    fun `embedded players with imdb ids and queries are read too`() {
        val embed = EpisodeAddress.parse("https://player.example/embed/tv/tt2575988/1/10?color=e50914")!!
        assertEquals(ShowId.Imdb("tt2575988"), embed.show)
        assertEquals("https://player.example/embed/tv/tt2575988/1/11?color=e50914", embed.withEpisode(EpisodeNumber(1, 11)))

        val query = EpisodeAddress.parse("https://player.example/embed/tv?tmdb=1399&season=2&episode=9")!!
        assertEquals(ShowId.Tmdb(1399), query.show)
        assertEquals(EpisodeNumber(2, 9), query.number)
        assertEquals("https://player.example/embed/tv?tmdb=1399&season=3&episode=1", query.withEpisode(EpisodeNumber(3, 1)))
    }

    @Test
    fun `movies and other pages are not episodes`() {
        assertNull(EpisodeAddress.parse("https://streamsite.example/play/movie/550"))
        assertNull(EpisodeAddress.parse("https://streamsite.example/tv/60573"))
        assertNull(EpisodeAddress.parse("https://shop.example/item?id=3&s=2&e=5"))
        assertNull(EpisodeAddress.parse("https://streamsite.example/tv/60573/0/1"))
    }

    private val show = Show.of(
        "Example Show",
        listOf(
            Episode(EpisodeNumber(1, 1), "Pilot"), Episode(EpisodeNumber(1, 2), "Two"),
            Episode(EpisodeNumber(2, 1), "Three"), Episode(EpisodeNumber(0, 1), "Special"),
        ),
    )

    @Test
    fun `next goes to the next season after the last episode, specials are left out`() {
        assertEquals(EpisodeNumber(1, 2), show.next(EpisodeNumber(1, 1))?.number)
        assertEquals(EpisodeNumber(2, 1), show.next(EpisodeNumber(1, 2))?.number)
        assertNull(show.next(EpisodeNumber(2, 1)))
        assertEquals(listOf(1, 2), show.seasons)
    }

    // ---- EpisodePlayer ----

    private class FakeTv : TvClient {
        var nowPlaying = NowPlaying.IDLE
        /** Another phone already claimed every request. */
        var claimsLost = false
        val played = mutableListOf<MediaStream>()
        val commands = mutableListOf<PlayerCommand>()
        override suspend fun play(device: TvDevice, stream: MediaStream) = Result.success(Unit).also {
            played += stream
            nowPlaying = NowPlaying.from(PlaybackStats(PlaybackState.PLAYING, durationMs = 1_800_000), stream)
        }
        override suspend fun status(device: TvDevice) = Result.success(nowPlaying)
        override suspend fun control(device: TvDevice, command: PlayerCommand): Result<Unit> {
            commands += command
            return if (command is PlayerCommand.ClaimEpisode && claimsLost) Result.failure(java.io.IOException("409")) else Result.success(Unit)
        }
    }

    private class FakeLoader(var found: Boolean = true) : EpisodeLoader {
        val asked = mutableListOf<String>()
        override suspend fun findVideo(url: String, timeoutMs: Long): DetectedVideo? {
            asked += url
            return if (found) DetectedVideo(MediaStream("$url/v.m3u8", StreamType.HLS, url), thumbnailUrl = null, details = null) else null
        }
    }

    private val catalog = object : ShowCatalog { override suspend fun show(id: ShowId) = show }
    private val address = EpisodeAddress.parse("https://streamsite.example/play/tv/42/1/1")!!
    private val tvDevice = TvDevice("MyCast", "10.0.0.42", 47800)

    @Test
    fun `a sent episode carries its series and the title shown on the TV`() = runTest {
        val tv = FakeTv()
        val session = TvSession(tv, backgroundScope).apply { connect(tvDevice) }
        val episodes = EpisodePlayer(catalog, FakeLoader(), session, backgroundScope)
        val stream = episodes.withSeries(MediaStream("https://cdn/a.m3u8", StreamType.HLS, address.url), address)
        assertEquals("Example Show · S1 E1 · Pilot", stream.title)
        assertEquals(3, stream.series?.episodes?.size)
        episodes.onSent(stream, address, height = 720)
        assertEquals(EpisodeNumber(1, 2), episodes.state.value?.next?.number)
    }

    @Test
    fun `playing another episode loads its address and sends it in the same quality`() = runTest {
        val tv = FakeTv()
        val session = TvSession(tv, backgroundScope).apply { connect(tvDevice) }
        runCurrent()
        val loader = FakeLoader()
        val episodes = EpisodePlayer(catalog, loader, session, backgroundScope)
        episodes.onSent(episodes.withSeries(MediaStream("https://cdn/a.m3u8", StreamType.HLS, address.url), address), address, height = 720)

        episodes.play(EpisodeNumber(2, 1))
        runCurrent()
        assertEquals("https://streamsite.example/play/tv/42/2/1", loader.asked.single())
        assertEquals(720, tv.played.last().preferredHeight)
        assertEquals(EpisodeNumber(2, 1), tv.played.last().series?.current)
        assertEquals(EpisodeNumber(2, 1), episodes.state.value?.current)
    }

    @Test
    fun `an episode asked by the tv that cannot be found is reported to it`() = runTest {
        val tv = FakeTv()
        val session = TvSession(tv, backgroundScope).apply { connect(tvDevice) }
        runCurrent()
        val episodes = EpisodePlayer(catalog, FakeLoader(found = false), session, backgroundScope)
        episodes.onSent(episodes.withSeries(MediaStream("https://cdn/a.m3u8", StreamType.HLS, address.url), address), address, null)

        tv.nowPlaying = NowPlaying(PlaybackState.PLAYING, seriesPageUrl = address.url, episodeRequest = EpisodeRequest(7, EpisodeNumber(1, 2)))
        session.refresh()
        runCurrent()
        assertTrue(tv.commands.contains(PlayerCommand.EpisodeFailed(7)))
        assertEquals(EpisodeNumber(1, 2), episodes.state.value?.failed)
    }

    @Test
    fun `a phone that connects while an episode plays gets the series from the tv`() = runTest {
        val tv = FakeTv().apply {
            nowPlaying = NowPlaying(PlaybackState.PLAYING, seriesPageUrl = "https://streamsite.example/play/tv/42/1/2", preferredHeight = 1080)
        }
        val session = TvSession(tv, backgroundScope)
        val episodes = EpisodePlayer(catalog, FakeLoader(), session, backgroundScope)
        session.connect(tvDevice)
        runCurrent()
        val state = episodes.state.value!!
        assertEquals(EpisodeNumber(1, 2), state.current)
        assertEquals(1080, state.height)
        assertEquals(EpisodeNumber(2, 1), state.next?.number)
    }

    @Test
    fun `a request another phone claimed first is left to it`() = runTest {
        val tv = FakeTv().apply { claimsLost = true }
        val session = TvSession(tv, backgroundScope).apply { connect(tvDevice) }
        runCurrent()
        val loader = FakeLoader()
        val episodes = EpisodePlayer(catalog, loader, session, backgroundScope)
        episodes.onSent(episodes.withSeries(MediaStream("https://cdn/a.m3u8", StreamType.HLS, address.url), address), address, null)

        tv.nowPlaying = NowPlaying(PlaybackState.PLAYING, seriesPageUrl = address.url, episodeRequest = EpisodeRequest(8, EpisodeNumber(1, 2)))
        session.refresh()
        runCurrent()
        assertTrue(loader.asked.isEmpty())
    }
}
