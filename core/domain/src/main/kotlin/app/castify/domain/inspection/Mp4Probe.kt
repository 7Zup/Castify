package app.castify.domain.inspection

import app.castify.domain.port.ManifestFetcher

/**
 * Reads duration and video size of an MP4/MOV file without downloading it.
 *
 * Needed when the page plays the file in a frame Castify cannot read (no `<video>` info).
 * MP4 = a sequence of boxes (size + type). The `moov` box holds `mvhd` (duration) and one
 * `trak/tkhd` per track (video width/height). `moov` is either at the start (web-optimized
 * files) or after the huge `mdat` box: we read box headers window by window, jumping over
 * `mdat` with its declared size, so only a few KB are downloaded.
 */
class Mp4Probe(private val fetcher: ManifestFetcher) {

    data class Info(val durationMs: Long?, val width: Int?, val height: Int?)

    suspend fun probe(url: String, headers: Map<String, String>): Info? {
        var offset = 0L
        repeat(MAX_WINDOWS) {
            val window = fetcher.fetchRange(url, headers, offset, offset + WINDOW_BYTES - 1).getOrNull() ?: return null
            if (window.size < 8) return null
            var pos = 0
            while (pos + 8 <= window.size) {
                val header = boxHeader(window, pos) ?: return null
                if (header.type == "moov") {
                    if (pos + header.size <= window.size) {
                        return parseMoov(window.copyOfRange(pos, pos + header.size.toInt()))
                    }
                    // mvhd and the first tracks sit at the start of moov: the part already
                    // downloaded is usually enough (moov can weigh MBs because of sample tables).
                    val partial = parseMoov(window.copyOfRange(pos, window.size))
                    if (partial.durationMs != null && partial.height != null) return partial
                    if (header.size > MAX_MOOV_BYTES) return partial.takeIf { it.durationMs != null }
                    val start = offset + pos
                    val moov = fetcher.fetchRange(url, headers, start, start + header.size - 1).getOrNull()
                        ?: return partial.takeIf { it.durationMs != null }
                    return parseMoov(moov)
                }
                if (header.size <= 0) return null // box runs to end of file: no moov after it
                val next = pos + header.size
                if (next + 8 > window.size) { // next header is outside this window: jump there
                    offset += next
                    return@repeat
                }
                pos = next.toInt()
            }
            return null
        }
        return null
    }

    private data class Header(val type: String, val size: Long, val headerSize: Int)

    /** Box at [pos]: 32-bit size + type, or size 1 + 64-bit size. Size 0 = to end of file. */
    private fun boxHeader(b: ByteArray, pos: Int): Header? {
        if (pos + 8 > b.size) return null
        val size32 = u32(b, pos)
        val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
        return when (size32) {
            1L -> if (pos + 16 <= b.size) Header(type, u64(b, pos + 8), 16) else null
            0L -> Header(type, 0, 8)
            else -> if (size32 >= 8) Header(type, size32, 8) else null
        }
    }

    internal fun parseMoov(moov: ByteArray): Info {
        var durationMs: Long? = null
        var width: Int? = null
        var height: Int? = null
        forEachChild(moov, 8, moov.size) { type, start, end ->
            when (type) {
                "mvhd" -> durationMs = parseMvhd(moov, start)
                "trak" -> forEachChild(moov, start, end) { childType, childStart, _ ->
                    if (childType == "tkhd" && width == null) {
                        parseTkhd(moov, childStart)?.let { (w, h) -> width = w; height = h }
                    }
                }
            }
        }
        return Info(durationMs, width, height)
    }

    /** Calls [block] with (type, contentStart, end) for each box between [from] and [to]. */
    private fun forEachChild(b: ByteArray, from: Int, to: Int, block: (String, Int, Int) -> Unit) {
        var pos = from
        while (pos + 8 <= to) {
            val header = boxHeader(b, pos) ?: return
            val end = if (header.size == 0L) to else (pos + header.size).coerceAtMost(to.toLong()).toInt()
            if (end <= pos) return
            block(header.type, pos + header.headerSize, end)
            pos = end
        }
    }

    /** mvhd: version, flags, then times; duration in `timescale` units. */
    private fun parseMvhd(b: ByteArray, start: Int): Long? {
        val version = b.getOrNull(start)?.toInt() ?: return null
        val (timescale, duration) = if (version == 1) {
            if (start + 32 > b.size) return null
            u32(b, start + 20) to u64(b, start + 24)
        } else {
            if (start + 20 > b.size) return null
            u32(b, start + 12) to u32(b, start + 16)
        }
        if (timescale <= 0 || duration <= 0) return null
        return duration * 1000 / timescale
    }

    /** tkhd: width/height (16.16 fixed point) at the end; 0 for audio tracks. */
    private fun parseTkhd(b: ByteArray, start: Int): Pair<Int, Int>? {
        val version = b.getOrNull(start)?.toInt() ?: return null
        val sizeOffset = start + if (version == 1) 88 else 76
        if (sizeOffset + 8 > b.size) return null
        val w = (u32(b, sizeOffset) shr 16).toInt()
        val h = (u32(b, sizeOffset + 4) shr 16).toInt()
        return if (w > 0 && h > 0) w to h else null
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    private companion object {
        const val WINDOW_BYTES = 64L * 1024
        const val MAX_WINDOWS = 6
        const val MAX_MOOV_BYTES = 16L * 1024 * 1024
    }
}
