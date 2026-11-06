package app.castify.domain.subtitles

/**
 * Recognizes subtitle files among the page requests and guesses their language from the
 * address (`…/movie_fr.vtt`, `…/en/subs.srt`, `…/French.vtt`). Segments of HLS subtitle
 * playlists (`sub_00012.vtt`) are ignored: the TV reads those from the stream itself.
 */
object SubtitleClassifier {

    private val byExtension = mapOf(
        ".vtt" to SubtitleFormat.VTT, ".webvtt" to SubtitleFormat.VTT, ".srt" to SubtitleFormat.SRT,
        ".ass" to SubtitleFormat.SSA, ".ssa" to SubtitleFormat.SSA, ".ttml" to SubtitleFormat.TTML,
        ".dfxp" to SubtitleFormat.TTML,
    )
    private val byMime = mapOf(
        "text/vtt" to SubtitleFormat.VTT, "application/x-subrip" to SubtitleFormat.SRT,
        "text/x-ssa" to SubtitleFormat.SSA, "application/ttml+xml" to SubtitleFormat.TTML,
    )
    private val segmentName = Regex("""(^|[_\-.])(seg|segment|chunk|frag|part)?[_\-]?\d{3,}$""", RegexOption.IGNORE_CASE)

    fun classify(url: String, mimeType: String? = null): SubtitleSource? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val lowerPath = path.lowercase()
        val format = byExtension.entries.firstOrNull { lowerPath.endsWith(it.key) }?.value
            ?: mimeType?.lowercase()?.substringBefore(';')?.trim()?.let { byMime[it] }
            ?: return null
        val fileName = path.substringAfterLast('/').substringBeforeLast('.')
        if (segmentName.containsMatchIn(fileName)) return null
        return SubtitleSource(url = url, format = format, language = guessLanguage(path))
    }

    /** Last token of the file name first (`movie.fr`, `movie_eng`), then the parent folder. */
    internal fun guessLanguage(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val fileName = segments.lastOrNull()?.substringBeforeLast('.') ?: return null
        val fileTokens = fileName.split('.', '_', '-', ' ', '[', ']', '(', ')').filter { it.isNotEmpty() }
        val candidates = fileTokens.reversed() + listOfNotNull(segments.dropLast(1).lastOrNull())
        return candidates.firstNotNullOfOrNull { token ->
            // 2-3 letter codes only when clearly a token on their own; names always.
            if (token.length > 3) LanguageName.codeFrom(token).takeIf { token.lowercase() != it }
            else LanguageName.codeFrom(token)?.takeIf { token.length >= 2 }
        }
    }
}
