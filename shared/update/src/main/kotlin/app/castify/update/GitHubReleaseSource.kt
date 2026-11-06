package app.castify.update

import app.castify.domain.update.Release
import app.castify.domain.update.ReleaseAsset
import app.castify.domain.update.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Latest release of a public GitHub repo, no token (60 calls / hour / IP is plenty for one
 * check per launch). 404 = nothing published yet.
 */
class GitHubReleaseSource(private val repo: String) : UpdateSource {

    override suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Castify")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parse(connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(json: String): Release {
        val root = JSONObject(json)
        val assets = root.optJSONArray("assets")
        val list = (0 until (assets?.length() ?: 0)).map { i ->
            val a = assets!!.getJSONObject(i)
            ReleaseAsset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size"))
        }
        return Release(root.getString("tag_name"), list)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
