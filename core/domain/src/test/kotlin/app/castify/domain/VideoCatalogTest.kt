package app.castify.domain

import app.castify.domain.detection.VideoCatalog
import app.castify.domain.inspection.InspectStream
import app.castify.domain.model.ObservedRequest
import app.castify.domain.model.VideoElement
import app.castify.domain.port.ManifestFetcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCatalogTest {

    private class FakeFetcher(private val pages: Map<String, String>) : ManifestFetcher {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String, headers: Map<String, String>): Result<ManifestFetcher.Fetched> {
            fetched += url
            return pages[url]?.let { Result.success(ManifestFetcher.Fetched(url, it)) }
                ?: Result.failure(IllegalStateException("404"))
        }

        override suspend fun fetchRange(url: String, headers: Map<String, String>, start: Long, endInclusive: Long) =
            Result.failure<ByteArray>(IllegalStateException("404"))
    }

    private val master = "https://cdn.com/v/master.m3u8"
    private val pages = mapOf(
        master to """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            720.m3u8
        """.trimIndent(),
        "https://cdn.com/v/1080.m3u8" to "#EXTM3U\n#EXTINF:3600,\na.ts\n#EXT-X-ENDLIST",
    )

    private fun req(url: String) = ObservedRequest(url, "https://site.com/watch")

    private fun TestScope.catalog(fetcher: ManifestFetcher = FakeFetcher(pages)) =
        VideoCatalog(InspectStream(fetcher), this)

    @Test
    fun `hides ads`() = runTest {
        val catalog = catalog()
        catalog.onRequest(req("https://pubads.g.doubleclick.net/pre.mp4"))
        assertTrue(catalog.videos.value.isEmpty())
    }

    @Test
    fun `master playlist is inspected and its variants are merged into it`() = runTest {
        val catalog = catalog()
        catalog.onRequest(req(master))
        assertNull("details pending", catalog.videos.value.single().details)

        advanceUntilIdle()
        catalog.onRequest(req("https://cdn.com/v/1080.m3u8")) // the page player loads a variant

        val video = catalog.videos.value.single()
        assertEquals(master, video.stream.url)
        assertEquals(3_600_000L, video.details?.durationMs)
        assertEquals(listOf("1080p", "720p"), video.details?.qualities?.map { it.label })
        assertEquals(listOf(1080, 720), video.details?.qualities?.map { it.height })
    }

    @Test
    fun `unreadable manifest hides the video (no duration, not live)`() = runTest {
        val catalog = catalog(FakeFetcher(emptyMap()))
        catalog.onRequest(req("https://cdn.com/broken.m3u8"))
        assertNull("shown as … while inspecting", catalog.videos.value.single().details)
        advanceUntilIdle()
        assertTrue(catalog.videos.value.isEmpty())
    }

    @Test
    fun `live stream without duration stays visible`() = runTest {
        val live = "https://cdn.com/live.m3u8"
        val catalog = catalog(FakeFetcher(mapOf(live to "#EXTM3U\n#EXTINF:6,\na.ts")))
        catalog.onRequest(req(live))
        advanceUntilIdle()
        assertTrue(catalog.videos.value.single().details!!.isLive)
    }

    @Test
    fun `progressive takes duration, quality and poster from its video element`() = runTest {
        val catalog = catalog()
        catalog.onRequest(req("https://cdn.com/movie.mp4"))
        catalog.onVideoElement(VideoElement("https://cdn.com/movie.mp4", "https://img.com/p.jpg", 5_400_000, 532, 1280))
        val video = catalog.videos.value.single()
        assertEquals(5_400_000L, video.details?.durationMs)
        assertEquals(listOf("720p"), video.details?.qualities?.map { it.label })
        assertEquals("https://img.com/p.jpg", video.thumbnailUrl)
    }

    @Test
    fun `progressive file without known duration is hidden once inspected`() = runTest {
        val catalog = catalog()
        catalog.onRequest(req("https://site.com/watch_party/images/screenshare.webm"))
        assertNull("… while reading", catalog.videos.value.single().details)
        advanceUntilIdle()
        assertTrue(catalog.videos.value.isEmpty())
    }

    @Test
    fun `mp4 in a frame gets its duration from the file header`() = runTest {
        // Minimal MP4: moov(mvhd 90 min, trak/tkhd 1920x800) at the start.
        fun box(type: String, body: ByteArray) =
            java.nio.ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
        val mvhd = box("mvhd", java.nio.ByteBuffer.allocate(100).putInt(0).putInt(0).putInt(0).putInt(1000).putInt(5_400_000).array())
        val tkhd = box("tkhd", java.nio.ByteBuffer.allocate(84).position(76).putInt(1920 shl 16).putInt(800 shl 16).array())
        val file = box("moov", mvhd + box("trak", tkhd))
        val fetcher = object : ManifestFetcher {
            override suspend fun fetch(url: String, headers: Map<String, String>) = Result.failure<ManifestFetcher.Fetched>(IllegalStateException())
            override suspend fun fetchRange(url: String, headers: Map<String, String>, start: Long, endInclusive: Long) =
                Result.success(file.copyOfRange(start.toInt().coerceAtMost(file.size), minOf(endInclusive + 1, file.size.toLong()).toInt()))
        }
        val catalog = VideoCatalog(InspectStream(fetcher), this)
        catalog.onRequest(req("https://cdn.com/resource/h265/movie.mp4?Expires=1&Signature=x"))
        advanceUntilIdle()
        val video = catalog.videos.value.single()
        assertEquals(5_400_000L, video.details?.durationMs)
        assertEquals(listOf("1080p"), video.details?.qualities?.map { it.label })
    }

    @Test
    fun `progressive file never borrows the movie poster or page image`() = runTest {
        val catalog = catalog()
        catalog.onPageImage("https://site.com/og.jpg")
        catalog.onVideoElement(VideoElement(src = null, posterUrl = "https://img.com/movie.jpg", durationMs = null, height = null))
        catalog.onRequest(req("https://site.com/tuto.webm"))
        catalog.onVideoElement(VideoElement("https://site.com/tuto.webm", posterUrl = null, durationMs = 20_000, height = 360))
        assertNull(catalog.videos.value.single().thumbnailUrl)
    }

    @Test
    fun `thumbnail falls back to any poster, then to the page image`() = runTest {
        val catalog = catalog()
        catalog.onRequest(req(master))
        catalog.onPageImage("https://site.com/og.jpg")
        assertEquals("https://site.com/og.jpg", catalog.videos.value.single().thumbnailUrl)

        catalog.onVideoElement(VideoElement(src = null, posterUrl = "https://img.com/poster.jpg", durationMs = null, height = null))
        assertEquals("https://img.com/poster.jpg", catalog.videos.value.single().thumbnailUrl)
    }

    @Test
    fun `manifests are listed before files and duplicates are dropped`() = runTest {
        val catalog = catalog()
        catalog.onRequest(req("https://cdn.com/movie.mp4"))
        catalog.onVideoElement(VideoElement("https://cdn.com/movie.mp4", null, 60_000, 720))
        catalog.onRequest(req(master))
        catalog.onRequest(req(master))
        assertEquals(listOf(master, "https://cdn.com/movie.mp4"), catalog.videos.value.map { it.stream.url })
    }

    @Test
    fun `reset clears the page and ignores late inspection results`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val catalog = VideoCatalog(InspectStream(FakeFetcher(pages)), TestScope(dispatcher))
        catalog.onRequest(req(master))
        catalog.reset()
        advanceUntilIdle()
        assertTrue(catalog.videos.value.isEmpty())
    }
}
