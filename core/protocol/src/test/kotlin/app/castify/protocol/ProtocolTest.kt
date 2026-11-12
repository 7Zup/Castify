package app.castify.protocol

import app.castify.domain.model.MediaStream
import app.castify.domain.model.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {

    @Test
    fun `discovery reply round trip, foreign packets ignored`() {
        val reply = CastifyProtocol.parseDiscoveryReply(CastifyProtocol.discoveryReply("MyCast"))
        assertEquals("MyCast", reply?.name)
        assertEquals(CastifyProtocol.DEFAULT_PORT, reply?.port)
        assertNull(CastifyProtocol.parseDiscoveryReply("""{"app":"other","name":"x","port":1}""".toByteArray()))
        assertNull(CastifyProtocol.parseDiscoveryReply("hello".toByteArray()))
        org.junit.Assert.assertTrue(CastifyProtocol.isDiscoveryQuery("CASTIFY_DISCOVER_1\n".toByteArray()))
        org.junit.Assert.assertFalse(CastifyProtocol.isDiscoveryQuery("M-SEARCH".toByteArray()))
    }

    private val stream = MediaStream(
        url = "https://cdn.example.com/master.m3u8",
        type = StreamType.HLS,
        pageUrl = "https://site.example.com/watch/1",
        headers = mapOf("Referer" to "https://site.example.com/", "Cookie" to "a=1"),
        title = "Episode 1",
        preferredHeight = 1080,
        thumbnailUrl = "https://img.example.com/poster.jpg",
        subtitles = listOf(
            app.castify.domain.subtitles.SubtitleSource(
                "https://cdn.example.com/fr.vtt", app.castify.domain.subtitles.SubtitleFormat.VTT, "fr", "Français",
            ),
        ),
    )

    @Test
    fun `series, auto next and episode requests cross the wire`() {
        val series = app.castify.domain.series.SeriesInfo(
            "Example Show",
            app.castify.domain.series.EpisodeNumber(1, 2),
            listOf(app.castify.domain.series.Episode(app.castify.domain.series.EpisodeNumber(1, 2), "Two", 30, "https://img/2.jpg")),
        )
        val episode = stream.copy(series = series)
        val json = CastifyProtocol.json.encodeToString(PlayRequest.serializer(), episode.toPlayRequest())
        assertEquals(series, CastifyProtocol.json.decodeFromString(PlayRequest.serializer(), json).toMediaStream()?.series)

        val now = app.castify.domain.model.NowPlaying(
            app.castify.domain.model.PlaybackState.PLAYING,
            autoNext = true,
            episodeRequest = app.castify.domain.series.EpisodeRequest(5, app.castify.domain.series.EpisodeNumber(2, 1)),
            qualities = listOf(app.castify.domain.model.Quality(1080, "1080p"), app.castify.domain.model.Quality(720, "720p")),
            qualityHeight = 720,
        )
        assertEquals(now, now.toStatusResponse().toNowPlaying())

        listOf(
            app.castify.domain.playback.PlayerCommand.SetAutoNext(true),
            app.castify.domain.playback.PlayerCommand.EpisodeFailed(5),
            app.castify.domain.playback.PlayerCommand.SelectQuality(720),
        ).forEach { assertEquals(it, it.toControlRequest()!!.toPlayerCommand()) }
    }

    @Test
    fun `unknown subtitle formats are dropped, not fatal`() {
        val request = stream.toPlayRequest().copy(
            subtitles = listOf(SubtitleFile("https://a/x.sub", "MICRODVD"), SubtitleFile("https://a/en.srt", "SRT", "en")),
        )
        assertEquals(listOf("https://a/en.srt"), request.toMediaStream()!!.subtitles.map { it.url })
    }

    @Test
    fun `subtitle selection maps both ways, menu opening is never sent`() {
        val show = app.castify.domain.playback.PlayerCommand.SelectSubtitle("0:2")
        val off = app.castify.domain.playback.PlayerCommand.SelectSubtitle(null)
        assertEquals(show, show.toControlRequest()!!.toPlayerCommand())
        assertEquals(off, off.toControlRequest()!!.toPlayerCommand())
        assertNull(app.castify.domain.playback.PlayerCommand.OpenSubtitleMenu.toControlRequest())
    }

    @Test
    fun `status survives a JSON round trip`() {
        val now = app.castify.domain.model.NowPlaying(
            state = app.castify.domain.model.PlaybackState.PAUSED,
            positionMs = 1_525_000,
            durationMs = 5_201_000,
            thumbnailUrl = "https://img/t.jpg",
            thumbnailHeaders = mapOf("Referer" to "https://site/"),
            bufferedPositionMs = 1_640_000,
            subtitles = listOf(app.castify.domain.subtitles.SubtitleTrack("0:1", "en", "SDH")),
            selectedSubtitleId = "0:1",
        )
        val json = CastifyProtocol.json.encodeToString(StatusResponse.serializer(), now.toStatusResponse())
        assertEquals(now, CastifyProtocol.json.decodeFromString(StatusResponse.serializer(), json).toNowPlaying())
    }

    @Test
    fun `commands map both ways, diagnostics is never sent`() {
        val commands = listOf(
            app.castify.domain.playback.PlayerCommand.TogglePause,
            app.castify.domain.playback.PlayerCommand.SeekBy(-10_000),
            app.castify.domain.playback.PlayerCommand.SeekTo(60_000),
            app.castify.domain.playback.PlayerCommand.Stop,
        )
        commands.forEach { assertEquals(it, it.toControlRequest()!!.toPlayerCommand()) }
        assertNull(app.castify.domain.playback.PlayerCommand.ToggleDiagnostics.toControlRequest())
        assertNull(ControlRequest("reboot").toPlayerCommand())
        assertNull(ControlRequest(ControlRequest.SEEK_TO, -5).toPlayerCommand())
        assertNull(ControlRequest(ControlRequest.SEEK_BY).toPlayerCommand())
    }

    @Test
    fun `stream survives a JSON round trip`() {
        val json = CastifyProtocol.json.encodeToString(PlayRequest.serializer(), stream.toPlayRequest())
        val decoded = CastifyProtocol.json.decodeFromString(PlayRequest.serializer(), json).toMediaStream()
        assertEquals(stream, decoded)
    }

    @Test
    fun `unknown type is rejected`() {
        assertNull(stream.toPlayRequest().copy(type = "RTMP").toMediaStream())
    }

    @Test
    fun `non http url is rejected`() {
        assertNull(stream.toPlayRequest().copy(url = "file:///sdcard/x.mp4").toMediaStream())
    }

    @Test
    fun `service name differs from the tv name and round trips for display`() {
        val name = CastifyProtocol.serviceNameFor("MyCast")
        assertEquals("MyCast · Castify", name)
        assertEquals("MyCast", CastifyProtocol.displayNameOf(name))
        assertEquals("Other", CastifyProtocol.displayNameOf("Other"))
    }

    @Test
    fun `service name fits the 63 byte mDNS limit`() {
        val name = CastifyProtocol.serviceNameFor("é".repeat(60))
        assert(name.toByteArray().size <= 63)
        assert(name.endsWith(" · Castify"))
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = """{"url":"https://a.com/v.mp4","type":"PROGRESSIVE","pageUrl":"https://a.com","future":true}"""
        val decoded = CastifyProtocol.json.decodeFromString(PlayRequest.serializer(), json).toMediaStream()
        assertEquals(StreamType.PROGRESSIVE, decoded?.type)
    }
}
