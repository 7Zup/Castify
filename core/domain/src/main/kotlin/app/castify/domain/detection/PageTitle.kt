package app.castify.domain.detection

/**
 * Turns a page title into a video title for the TV.
 * `Watch Resident Evil (2026) Online Free - StreamSite` → `Resident Evil (2026)`.
 * Null when nothing meaningful is left: the TV then shows no title.
 */
object PageTitle {

    private val separators = listOf(" | ", " - ", " – ", " — ", " · ")
    private val prefixes = listOf("watch ", "stream ", "regarder ")
    /** Longest first, so `en streaming` wins over `streaming`. */
    private val suffixes = listOf(
        " online free", " full movie", " online", " free", " in hd", " hd", " streaming", " en streaming", " vf", " vostfr",
    ).sortedByDescending { it.length }

    fun clean(raw: String?): String? {
        var title = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Site name is usually after the last separator.
        separators.firstOrNull { title.contains(it) }?.let { sep -> title = title.substringBefore(sep).trim() }
        prefixes.firstOrNull { title.lowercase().startsWith(it) }?.let { title = title.substring(it.length).trim() }
        var changed = true
        while (changed) {
            changed = false
            suffixes.firstOrNull { title.lowercase().endsWith(it) }?.let {
                title = title.dropLast(it.length).trim()
                changed = true
            }
        }
        return title.takeIf { it.length >= 2 && it.any(Char::isLetter) }
    }
}
