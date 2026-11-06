package app.castify.domain.subtitles

import java.util.Locale

/** Subtitle file formats the TV player can read when side-loaded. */
enum class SubtitleFormat { VTT, SRT, SSA, TTML }

/** A subtitle file seen on the page, sent to the TV with the video. */
data class SubtitleSource(
    val url: String,
    val format: SubtitleFormat,
    /** BCP 47 / ISO 639 code (`fr`, `en-US`, `fre`…), null when unknown. */
    val language: String? = null,
    /** Name given by the site (`English (Forced)`, `SDH`…), if any. */
    val label: String? = null,
)

/** A text track available on the TV (from the stream itself or a side-loaded file). */
data class SubtitleTrack(
    val id: String,
    val language: String? = null,
    val label: String? = null,
)

/** One line of the subtitle menu. [trackId] null = `Off`. */
data class SubtitleOption(val trackId: String?, val name: String)

/** Language codes and names, always in English (user rule). */
object LanguageName {

    /** ISO 639-2/B codes the JDK does not resolve. */
    private val bibliographic = mapOf(
        "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el",
        "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu",
        "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
        "tib" to "bo", "wel" to "cy",
    )

    /** Language names written by sites, in English or in the language itself. */
    private val names = mapOf(
        "english" to "en", "anglais" to "en", "french" to "fr", "francais" to "fr", "français" to "fr",
        "spanish" to "es", "espanol" to "es", "español" to "es", "castellano" to "es", "german" to "de",
        "deutsch" to "de", "italian" to "it", "italiano" to "it", "portuguese" to "pt", "portugues" to "pt",
        "português" to "pt", "brazilian" to "pt", "dutch" to "nl", "nederlands" to "nl", "russian" to "ru",
        "arabic" to "ar", "japanese" to "ja", "korean" to "ko", "chinese" to "zh", "polish" to "pl",
        "polski" to "pl", "turkish" to "tr", "turkce" to "tr", "swedish" to "sv", "danish" to "da",
        "norwegian" to "no", "finnish" to "fi", "greek" to "el", "hebrew" to "he", "hindi" to "hi",
        "czech" to "cs", "hungarian" to "hu", "romanian" to "ro", "vietnamese" to "vi", "thai" to "th",
        "indonesian" to "id", "ukrainian" to "uk",
    )

    /** ISO 639-2/T (3 letters, e.g. `fra`) → ISO 639-1 (`fr`), from the JDK's own table. */
    private val iso3to2: Map<String, String> by lazy {
        Locale.getISOLanguages().mapNotNull { two ->
            runCatching { Locale.forLanguageTag(two).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to two }
        }.toMap()
    }

    /** `fr`, `fr-CA`, `fra`, `fre` → `French`. Null when unknown (`und`, garbage). */
    fun englishName(code: String?): String? {
        val raw = code?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        val primary = raw.substringBefore('-')
        val normalized = bibliographic[primary] ?: iso3to2[primary] ?: primary
        if (normalized.length !in 2..3 || !normalized.all { it in 'a'..'z' }) return null
        val name = Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.ENGLISH)
        return name.takeIf { it.isNotBlank() && !it.equals(normalized, ignoreCase = true) }
    }

    /** True if [word] names [englishLanguage]: `French`, `fr`, `fra`, `Français`, `日本語`… */
    fun sameLanguage(word: String, englishLanguage: String): Boolean {
        if (word.equals(englishLanguage, ignoreCase = true)) return true
        codeFrom(word)?.let { if (englishName(it) == englishLanguage) return true }
        val code = Locale.getISOLanguages().firstOrNull {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) == englishLanguage
        } ?: return false
        val native = Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }
        return native.isNotBlank() && word.equals(native, ignoreCase = true)
    }

    /** Code for a language name or code found in a label or a file name; null if none. */
    fun codeFrom(text: String?): String? {
        val t = text?.trim()?.lowercase() ?: return null
        names[t]?.let { return it }
        return t.takeIf { englishName(it) != null }
    }
}

/**
 * The subtitle menu, identical on phone and TV (user rules): `Off` first, then English
 * tracks, then other languages alphabetically by English name, `Unknown` tracks last.
 * Two tracks of one language are told apart by the site's label (`English (Forced)`).
 */
object SubtitleMenu {

    const val OFF = "Off"
    const val UNKNOWN = "Unknown"

    fun options(tracks: List<SubtitleTrack>): List<SubtitleOption> {
        val named = tracks.map { track ->
            val language = LanguageName.englishName(track.language)
                ?: LanguageName.englishName(LanguageName.codeFrom(track.label))
            Triple(track, language, extraLabel(track.label, language))
        }
        val known = named.filter { it.second != null }
            .sortedWith(
                compareBy<Triple<SubtitleTrack, String?, String?>> { if (it.second == "English") 0 else 1 }
                    .thenBy { it.second }
                    .thenBy { it.third.orEmpty() },
            )
            .map { (track, language, extra) -> SubtitleOption(track.id, if (extra != null) "$language ($extra)" else language!!) }
        val unknown = named.filter { it.second == null }
        val unknownOptions = unknown.mapIndexed { index, (track, _, _) ->
            SubtitleOption(track.id, if (unknown.size > 1) "$UNKNOWN ${index + 1}" else UNKNOWN)
        }
        return listOf(SubtitleOption(null, OFF)) + dedupe(known) + unknownOptions
    }

    /**
     * What the label adds besides the language: `Français (Forced)` → `Forced`,
     * `English (SDH)` → `SDH`, `Español` → nothing. Words naming the language (in English, in
     * the language itself, or as a code) are dropped.
     */
    private fun extraLabel(label: String?, language: String?): String? {
        val l = label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (language == null) return l
        val words = l.split(' ', '(', ')', '[', ']', '-', ',', '/').filter { it.isNotBlank() }
        val rest = words.filterNot { LanguageName.sameLanguage(it, language) }
        return rest.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /** Same visible name twice → number them (`French`, `French 2`). */
    private fun dedupe(options: List<SubtitleOption>): List<SubtitleOption> {
        val seen = mutableMapOf<String, Int>()
        return options.map { option ->
            val count = (seen[option.name] ?: 0) + 1
            seen[option.name] = count
            if (count == 1) option else option.copy(name = "${option.name} $count")
        }
    }
}
