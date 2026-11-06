package app.castify.domain.inspection

/** Minimal DASH MPD reader: duration, live flag, video heights. */
object DashManifestParser {

    /** [sizes] = (width, height) of each video representation. */
    data class Manifest(val durationMs: Long?, val isLive: Boolean, val sizes: List<Pair<Int?, Int>>) {
        val heights: List<Int> get() = sizes.map { it.second }
    }

    fun parse(text: String): Manifest? {
        val mpd = Regex("""<MPD\b[^>]*>""", RegexOption.IGNORE_CASE).find(text)?.value ?: return null
        val isLive = attr(mpd, "type") == "dynamic"
        val duration = attr(mpd, "mediaPresentationDuration")?.let(::parseIsoDuration)
        val sizes = Regex("""<Representation\b[^>]*>""").findAll(text)
            .mapNotNull { rep ->
                val height = attr(rep.value, "height")?.toIntOrNull() ?: return@mapNotNull null
                attr(rep.value, "width")?.toIntOrNull() to height
            }
            .toList()
        return Manifest(durationMs = if (isLive) null else duration, isLive = isLive, sizes = sizes)
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** ISO 8601 duration as used by DASH, e.g. `PT1H2M3.5S`, `P1DT2H`. */
    internal fun parseIsoDuration(value: String): Long? {
        val match = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?)?$""").find(value) ?: return null
        val (d, h, m, s) = match.destructured
        val seconds = (d.toDoubleOrNull() ?: 0.0) * 86_400 + (h.toDoubleOrNull() ?: 0.0) * 3_600 +
            (m.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
        return (seconds * 1000).toLong()
    }
}
