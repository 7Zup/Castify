package app.castify.domain

import app.castify.domain.inspection.DashManifestParser
import app.castify.domain.inspection.HlsPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParsingTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English, main",URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud"
        1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud"
        https://other.cdn.com/720/index.m3u8?t=1
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI="iframes.m3u8"
    """.trimIndent()

    @Test
    fun `master playlist gives variants and child urls`() {
        val parsed = HlsPlaylistParser.parse(master, "https://cdn.com/v/master.m3u8") as HlsPlaylistParser.Master
        assertEquals(listOf(1080, 720), parsed.variants.map { it.height })
        assertEquals(listOf(5_000_000L, 2_500_000L), parsed.variants.map { it.bandwidth })
        assertEquals("https://cdn.com/v/1080/index.m3u8", parsed.variants[0].url)
        assertEquals("https://other.cdn.com/720/index.m3u8?t=1", parsed.variants[1].url)
        assertEquals(
            setOf(
                "https://cdn.com/v/1080/index.m3u8",
                "https://other.cdn.com/720/index.m3u8?t=1",
                "https://cdn.com/v/audio/en.m3u8",
                "https://cdn.com/v/iframes.m3u8",
            ),
            parsed.childUrls,
        )
    }

    @Test
    fun `quoted attributes may contain commas`() {
        val attrs = HlsPlaylistParser.attributes("""TYPE=AUDIO,NAME="English, main",URI="a.m3u8"""")
        assertEquals("English, main", attrs["NAME"])
        assertEquals("a.m3u8", attrs["URI"])
    }

    @Test
    fun `vod media playlist sums segment durations`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            s1.ts
            #EXTINF:9.5,
            s2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsed = HlsPlaylistParser.parse(media, "https://cdn.com/x.m3u8") as HlsPlaylistParser.Media
        assertEquals(19_500L, parsed.durationMs)
        assertFalse(parsed.isLive)
    }

    @Test
    fun `media playlist without endlist is live`() {
        val parsed = HlsPlaylistParser.parse("#EXTM3U\n#EXTINF:6,\na.ts", "https://c.com/l.m3u8") as HlsPlaylistParser.Media
        assertTrue(parsed.isLive)
    }

    @Test
    fun `non playlist text is rejected`() {
        assertNull(HlsPlaylistParser.parse("<html>403</html>", "https://c.com/x.m3u8"))
    }

    @Test
    fun `dash manifest gives duration and heights`() {
        val mpd = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1H2M3.5S">
              <Period><AdaptationSet mimeType="video/mp4">
                <Representation id="1" bandwidth="5000000" width="1920" height="1080"/>
                <Representation id="2" bandwidth="1000000" width="854" height="480"/>
              </AdaptationSet><AdaptationSet mimeType="audio/mp4">
                <Representation id="a" bandwidth="128000"/>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val parsed = DashManifestParser.parse(mpd)!!
        assertEquals(3_723_500L, parsed.durationMs)
        assertFalse(parsed.isLive)
        assertEquals(listOf(1080, 480), parsed.heights)
        assertEquals(listOf(1920 to 1080, 854 to 480), parsed.sizes)
    }

    @Test
    fun `widescreen sizes get their usual names`() {
        val labels = app.castify.domain.inspection.QualityLabel.qualities(
            listOf(1920 to 800, 1280 to 532, 854 to 356, 640 to 266),
        )
        assertEquals(listOf("1080p", "720p", "480p", "360p"), labels.map { it.label })
        assertEquals(listOf(800, 532, 356, 266), labels.map { it.height })
    }

    @Test
    fun `quality without width falls back to height, odd sizes keep their height`() {
        assertEquals("720p", app.castify.domain.inspection.QualityLabel.of(null, 720))
        assertEquals("1080p", app.castify.domain.inspection.QualityLabel.of(1904, 800))
        assertEquals("144p", app.castify.domain.inspection.QualityLabel.of(256, 144))
    }

    @Test
    fun `two sizes with the same label keep the larger`() {
        val q = app.castify.domain.inspection.QualityLabel.qualities(listOf(1920 to 800, 1920 to 1080))
        assertEquals(listOf(app.castify.domain.model.Quality(1080, "1080p")), q)
    }

    @Test
    fun `dynamic dash is live without duration`() {
        val parsed = DashManifestParser.parse("""<MPD type="dynamic" mediaPresentationDuration="PT10S"></MPD>""")!!
        assertTrue(parsed.isLive)
        assertNull(parsed.durationMs)
    }

    @Test
    fun `iso durations`() {
        assertEquals(90_000L, DashManifestParser.parseIsoDuration("PT1M30S"))
        assertEquals(93_600_000L, DashManifestParser.parseIsoDuration("P1DT2H"))
        assertNull(DashManifestParser.parseIsoDuration("garbage"))
    }
}
