package app.castify.domain

import app.castify.domain.inspection.Mp4Probe
import app.castify.domain.port.ManifestFetcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class Mp4ProbeTest {

    /** Serves byte ranges of a virtual file: real bytes where given, zeros elsewhere. */
    private class RangeFetcher(
        private val totalSize: Long,
        private val chunks: Map<Long, ByteArray>,
        private val supportsRanges: Boolean = true,
    ) : ManifestFetcher {
        var bytesServed = 0L
        override suspend fun fetch(url: String, headers: Map<String, String>) = Result.failure<ManifestFetcher.Fetched>(UnsupportedOperationException())
        override suspend fun fetchRange(url: String, headers: Map<String, String>, start: Long, endInclusive: Long): Result<ByteArray> {
            if (!supportsRanges) return Result.failure(java.io.IOException("HTTP 200 for a range request"))
            if (start >= totalSize) return Result.success(ByteArray(0))
            val end = minOf(endInclusive, totalSize - 1)
            val out = ByteArray((end - start + 1).toInt())
            chunks.forEach { (offset, bytes) ->
                for (i in bytes.indices) {
                    val abs = offset + i
                    if (abs in start..end) out[(abs - start).toInt()] = bytes[i]
                }
            }
            bytesServed += out.size
            return Result.success(out)
        }
    }

    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { content.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }

    /** mvhd v0: version/flags, creation, modification, timescale, duration, then padding. */
    private fun mvhd(timescale: Int, duration: Int) =
        box("mvhd", ByteBuffer.allocate(100).putInt(0).putInt(0).putInt(0).putInt(timescale).putInt(duration).array())

    /** tkhd v0: width/height (16.16) at offset 76 of the content. */
    private fun tkhd(width: Int, height: Int) =
        box("tkhd", ByteBuffer.allocate(84).position(76).putInt(width shl 16).putInt(height shl 16).array())

    private fun moov(durationSec: Int, width: Int, height: Int) = box(
        "moov",
        mvhd(timescale = 1000, duration = durationSec * 1000),
        box("trak", tkhd(0, 0)), // audio track first: no size
        box("trak", tkhd(width, height)),
    )

    private val ftyp = box("ftyp", "isom".toByteArray(), ByteArray(8))

    @Test
    fun `reads duration and size when moov is at the start`() = runTest {
        val head = ftyp + moov(durationSec = 5_400, width = 1920, height = 800)
        val mdatHeader = ByteBuffer.allocate(8).putInt(500_000).put("mdat".toByteArray()).array()
        val fetcher = RangeFetcher(head.size + 500_000L, mapOf(0L to head + mdatHeader))
        val info = Mp4Probe(fetcher).probe("https://cdn/v.mp4", emptyMap())!!
        assertEquals(5_400_000L, info.durationMs)
        assertEquals(1920, info.width)
        assertEquals(800, info.height)
    }

    @Test
    fun `jumps over a huge mdat to find moov at the end, downloading only a few KB`() = runTest {
        val mdatSize = 4_000_000_000L // > 32 bits: uses the 64-bit size form
        val mdat = ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(mdatSize).array()
        val tail = moov(durationSec = 3_600, width = 1280, height = 720)
        val moovOffset = ftyp.size + mdatSize
        val fetcher = RangeFetcher(moovOffset + tail.size, mapOf(0L to ftyp + mdat, moovOffset to tail))
        val info = Mp4Probe(fetcher).probe("https://cdn/v.mp4", emptyMap())!!
        assertEquals(3_600_000L, info.durationMs)
        assertEquals(720, info.height)
        assertTrue("downloaded ${fetcher.bytesServed} bytes", fetcher.bytesServed < 200_000)
    }

    @Test
    fun `big moov at the start is read from the first window only`() = runTest {
        // Like the real site file: a 1.2 MB moov starting at byte 28, mvhd/tkhd at its start.
        val moovSize = 1_178_020
        val moovHeader = ByteBuffer.allocate(8).putInt(moovSize).put("moov".toByteArray()).array()
        val moovStart = moovHeader + mvhd(1000, 1_337_563) + box("trak", tkhd(640, 360))
        val fetcher = RangeFetcher(ftyp.size + moovSize + 1_000_000L, mapOf(0L to ftyp + moovStart))
        val info = Mp4Probe(fetcher).probe("https://cdn/v.mp4", emptyMap())!!
        assertEquals(1_337_563L, info.durationMs)
        assertEquals(360, info.height)
        assertTrue("downloaded ${fetcher.bytesServed} bytes", fetcher.bytesServed <= 64 * 1024)
    }

    @Test
    fun `server without range support gives nothing instead of downloading the movie`() = runTest {
        val fetcher = RangeFetcher(1_000, emptyMap(), supportsRanges = false)
        assertNull(Mp4Probe(fetcher).probe("https://cdn/v.mp4", emptyMap()))
    }

    @Test
    fun `not an mp4 gives nothing`() = runTest {
        val html = "<html><body>404</body></html>".toByteArray()
        assertNull(Mp4Probe(RangeFetcher(html.size.toLong(), mapOf(0L to html))).probe("https://cdn/v.mp4", emptyMap()))
    }
}
