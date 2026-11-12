package app.castify.domain.series

/** A series as the sites name it: a TMDB number or an IMDb `tt…` id. */
sealed interface ShowId {
    data class Tmdb(val id: Int) : ShowId
    data class Imdb(val id: String) : ShowId
}

data class EpisodeNumber(val season: Int, val episode: Int) : Comparable<EpisodeNumber> {
    override fun compareTo(other: EpisodeNumber): Int =
        compareValuesBy(this, other, EpisodeNumber::season, EpisodeNumber::episode)

    /** `S5 E2` */
    val label: String get() = "S$season E$episode"
}

data class Episode(
    val number: EpisodeNumber,
    val title: String? = null,
    val runtimeMin: Int? = null,
    val imageUrl: String? = null,
)

/**
 * A series and all its episodes (specials, season 0, are left out). Episodes are kept in
 * order: season, then episode.
 */
data class Show(val name: String, val episodes: List<Episode>) {
    val seasons: List<Int> get() = episodes.map { it.number.season }.distinct()

    fun episodesOf(season: Int): List<Episode> = episodes.filter { it.number.season == season }

    fun find(number: EpisodeNumber): Episode? = episodes.firstOrNull { it.number == number }

    /**
     * The episode after [current]: the next one of the season, else the first of the next
     * season (user rule). Null after the last episode of the series.
     */
    fun next(current: EpisodeNumber): Episode? = episodes.firstOrNull { it.number > current }

    companion object {
        fun of(name: String, episodes: List<Episode>) =
            Show(name, episodes.filter { it.number.season > 0 && it.number.episode > 0 }.distinctBy { it.number }.sortedBy { it.number })
    }
}

/**
 * The address of one episode, page or embedded player, in the convention most streaming
 * sites share: `…/tv/<id>/<season>/<episode>` (also `series`, `show`), or query parameters
 * (`?tmdb=<id>&season=<s>&episode=<e>`). [withEpisode] builds the address of another episode
 * of the same series by changing only the two numbers.
 */
class EpisodeAddress private constructor(
    val url: String,
    val show: ShowId,
    val number: EpisodeNumber,
    private val seasonRange: IntRange,
    private val episodeRange: IntRange,
) {
    fun withEpisode(other: EpisodeNumber): String {
        // Replace the later range first so the earlier one's offsets stay valid.
        val (first, second) = listOf(seasonRange to other.season, episodeRange to other.episode).sortedBy { it.first.first }
        return url.replaceRange(second.first, second.second.toString()).replaceRange(first.first, first.second.toString())
    }

    override fun equals(other: Any?) = other is EpisodeAddress && other.url == url
    override fun hashCode() = url.hashCode()
    override fun toString() = "EpisodeAddress($url, $show, ${number.label})"

    companion object {
        private val PATH = Regex("""/(?:tv|series|show|shows|serie|tv-show)/(tt\d{5,10}|\d{1,9})/(\d{1,3})/(\d{1,4})(?=[/?#]|$)""", RegexOption.IGNORE_CASE)
        private val ID_PARAMS = listOf("tmdb", "tmdb_id", "tmdbid", "imdb", "imdb_id", "imdbid", "id")
        private val SEASON_PARAMS = listOf("season", "s", "sea")
        private val EPISODE_PARAMS = listOf("episode", "e", "ep", "epi")

        /** Null when [url] does not point to one episode. */
        fun parse(url: String): EpisodeAddress? {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            val pathEnd = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
            PATH.find(url.substring(0, pathEnd))?.let { m ->
                val show = showId(m.groupValues[1]) ?: return null
                val season = m.groups[2]!!
                val episode = m.groups[3]!!
                return of(url, show, season.value, season.range, episode.value, episode.range)
            }
            return fromQuery(url, pathEnd)
        }

        private fun fromQuery(url: String, queryStart: Int): EpisodeAddress? {
            if (queryStart >= url.length || url[queryStart] != '?') return null
            val queryEnd = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
            // name -> (value, range of the value in url)
            val params = mutableMapOf<String, Pair<String, IntRange>>()
            var start = queryStart + 1
            while (start < queryEnd) {
                val end = url.indexOf('&', start).let { if (it < 0 || it > queryEnd) queryEnd else it }
                val eq = url.indexOf('=', start)
                if (eq in start until end) params.putIfAbsent(url.substring(start, eq).lowercase(), url.substring(eq + 1, end) to (eq + 1 until end))
                start = end + 1
            }
            fun first(names: List<String>) = names.firstNotNullOfOrNull { params[it] }
            val id = first(ID_PARAMS) ?: return null
            val season = first(SEASON_PARAMS) ?: return null
            val episode = first(EPISODE_PARAMS) ?: return null
            // `id` alone is too common: only with a tv-looking path.
            if (params[ID_PARAMS.last()] === id && !url.substring(0, queryStart).contains("tv", ignoreCase = true)) return null
            val show = showId(id.first) ?: return null
            return of(url, show, season.first, season.second, episode.first, episode.second)
        }

        private fun of(url: String, show: ShowId, season: String, seasonRange: IntRange, episode: String, episodeRange: IntRange): EpisodeAddress? {
            val s = season.toIntOrNull()?.takeIf { it in 1..200 } ?: return null
            val e = episode.toIntOrNull()?.takeIf { it in 1..5000 } ?: return null
            return EpisodeAddress(url, show, EpisodeNumber(s, e), seasonRange, episodeRange)
        }

        private fun showId(raw: String): ShowId? = when {
            raw.matches(Regex("""tt\d{5,10}""")) -> ShowId.Imdb(raw)
            raw.matches(Regex("""\d{1,9}""")) -> raw.toIntOrNull()?.takeIf { it > 0 }?.let(ShowId::Tmdb)
            else -> null
        }
    }
}

/**
 * What the TV gets with an episode: enough to show the series' episodes and its own place
 * in it (the episode list comes from [ShowCatalog], fetched by the phone).
 */
data class SeriesInfo(
    val showName: String,
    val current: EpisodeNumber,
    val episodes: List<Episode>,
    /** The episode's address ([EpisodeAddress]): any phone can find the series again from it. */
    val addressUrl: String? = null,
) {
    val show: Show get() = Show(showName, episodes)
    val currentEpisode: Episode? get() = show.find(current)
    val next: Episode? get() = show.next(current)

    /** `Show · S5 E2` */
    val title: String get() = "$showName · ${current.label}"
}

/** The TV asks the phone for another episode (picked on the TV, `Next`, `Auto next`). */
data class EpisodeRequest(val id: Long, val number: EpisodeNumber)

/**
 * Several phones can see the same episode request: the first phone to claim it answers,
 * the others leave it (one video sent, not one per phone).
 */
class EpisodeClaims {
    private var claimed: Long? = null

    @Synchronized
    fun claim(requestId: Long): Boolean {
        if (claimed == requestId) return false
        claimed = requestId
        return true
    }
}
