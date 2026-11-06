package app.castify.domain.inspection

import app.castify.domain.model.Variant
import java.net.URI

/** Minimal HLS (RFC 8216) reader: what Castify needs to show duration and qualities. */
object HlsPlaylistParser {

    sealed interface Playlist
    data class Master(val variants: List<Variant>, val childUrls: Set<String>) : Playlist
    data class Media(val durationMs: Long, val isLive: Boolean) : Playlist

    fun parse(text: String, baseUrl: String): Playlist? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") return null
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) parseMaster(lines, baseUrl) else parseMedia(lines)
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): Master {
        val variants = mutableListOf<Variant>()
        val children = mutableSetOf<String>()
        lines.forEachIndexed { index, line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                    val url = resolve(baseUrl, uri)
                    variants += Variant(
                        width = attrs["RESOLUTION"]?.substringBefore('x')?.toIntOrNull(),
                        height = attrs["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull(),
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull(),
                        url = url,
                    )
                    children += url
                }
                line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ->
                    attributes(line.substringAfter(':'))["URI"]?.let { children += resolve(baseUrl, it) }
            }
        }
        return Master(variants, children)
    }

    private fun parseMedia(lines: List<String>): Media {
        val durationSec = lines.filter { it.startsWith("#EXTINF:") }
            .sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 }
        val ended = lines.any { it == "#EXT-X-ENDLIST" } ||
            lines.any { it.startsWith("#EXT-X-PLAYLIST-TYPE:") && it.endsWith("VOD") }
        return Media(durationMs = (durationSec * 1000).toLong(), isLive = !ended)
    }

    /** Parses `KEY=value,KEY="quoted, value"` attribute lists. */
    internal fun attributes(list: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
        regex.findAll(list).forEach { result[it.groupValues[1]] = it.groupValues[2].trim('"') }
        return result
    }

    internal fun resolve(baseUrl: String, uri: String): String =
        runCatching { URI(baseUrl).resolve(uri.replace(" ", "%20")).toString() }.getOrDefault(uri)
}
