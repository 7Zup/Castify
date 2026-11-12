package app.castify.phone.data

import app.castify.domain.port.ShowCatalog
import app.castify.domain.series.Episode
import app.castify.domain.series.EpisodeNumber
import app.castify.domain.series.Show
import app.castify.domain.series.ShowId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Episode lists from TVmaze (public, no key; credit shown in the app). Sites name series by
 * their TMDB number, which TVmaze does not know: Wikidata (public, no key) maps it to the
 * TVmaze id, or to the IMDb id TVmaze can look up.
 */
class TvMazeShowCatalog : ShowCatalog {

    override suspend fun show(id: ShowId): Show? = withContext(Dispatchers.IO) {
        runCatching {
            val tvMazeId = when (id) {
                is ShowId.Imdb -> lookupImdb(id.id)
                is ShowId.Tmdb -> fromWikidata(id.id)
            } ?: return@runCatching null
            val json = JSONObject(get("$TVMAZE/shows/$tvMazeId?embed=episodes"))
            val episodes = json.optJSONObject("_embedded")?.optJSONArray("episodes") ?: JSONArray()
            Show.of(
                json.getString("name"),
                (0 until episodes.length()).mapNotNull { i ->
                    val e = episodes.getJSONObject(i)
                    val season = e.optInt("season", 0)
                    val number = e.optInt("number", 0)
                    if (season <= 0 || number <= 0) return@mapNotNull null
                    Episode(
                        EpisodeNumber(season, number),
                        title = e.optString("name").takeIf { it.isNotBlank() && it != "null" },
                        runtimeMin = e.optInt("runtime", 0).takeIf { it > 0 },
                        imageUrl = e.optJSONObject("image")?.optString("medium")?.takeIf { it.startsWith("http") }
                            ?.replace("http://", "https://"),
                    )
                },
            )
        }.getOrNull()
    }

    /** TVmaze answers a lookup with a redirect to the show. */
    private fun lookupImdb(imdb: String): Int? =
        runCatching { JSONObject(get("$TVMAZE/lookup/shows?imdb=$imdb")).getInt("id") }.getOrNull()

    private fun fromWikidata(tmdb: Int): Int? {
        val query = """SELECT ?tvmaze ?imdb WHERE { ?item wdt:P4983 "$tmdb". OPTIONAL { ?item wdt:P8600 ?tvmaze. } OPTIONAL { ?item wdt:P345 ?imdb. } } LIMIT 1"""
        val body = get("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(query, "UTF-8"))
        val row = JSONObject(body).getJSONObject("results").getJSONArray("bindings").optJSONObject(0) ?: return null
        row.optJSONObject("tvmaze")?.optString("value")?.toIntOrNull()?.let { return it }
        return row.optJSONObject("imdb")?.optString("value")?.takeIf { it.startsWith("tt") }?.let(::lookupImdb)
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            // Wikidata asks every client to say who it is.
            connection.setRequestProperty("User-Agent", "Castify (https://github.com/7Zup/Castify)")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TVMAZE = "https://api.tvmaze.com"
    }
}
