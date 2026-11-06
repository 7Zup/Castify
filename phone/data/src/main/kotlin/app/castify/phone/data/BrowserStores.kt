package app.castify.phone.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.castify.domain.browser.Favorite
import app.castify.domain.browser.FavoriteStore
import app.castify.domain.browser.HistoryEntry
import app.castify.domain.browser.HistoryStore
import app.castify.domain.browser.Tab
import app.castify.domain.browser.TabStore
import app.castify.domain.browser.TabsState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Tabs, history and favorites as small JSON documents in SharedPreferences. */
private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("castify_browser", Context.MODE_PRIVATE)

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

class PrefsTabStore(context: Context) : TabStore {
    private val prefs = prefs(context)

    override fun load(): TabsState? = runCatching {
        val json = JSONObject(prefs.getString(KEY, null) ?: return null)
        val tabs = json.getJSONArray("tabs").let { arr ->
            (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                Tab(t.getString("id"), t.optStringOrNull("url"), t.optStringOrNull("title"), t.optInt("videos"))
            }
        }
        TabsState(tabs, json.getString("active")).takeIf { tabs.isNotEmpty() }
    }.getOrNull()

    override fun save(state: TabsState) {
        val arr = JSONArray()
        state.tabs.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("url", t.url ?: JSONObject.NULL).put("title", t.title ?: JSONObject.NULL).put("videos", t.videoCount))
        }
        prefs.edit().putString(KEY, JSONObject().put("active", state.activeId).put("tabs", arr).toString()).apply()
    }

    private companion object { const val KEY = "tabs" }
}

class PrefsHistoryStore(context: Context) : HistoryStore {
    private val prefs = prefs(context)

    override fun load(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            HistoryEntry(e.getString("text"), e.getString("url"), e.optBoolean("search"), e.getLong("at"))
        }
    }.getOrDefault(emptyList())

    override fun save(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("text", it.text).put("url", it.url).put("search", it.isSearch).put("at", it.at)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "history" }
}

class PrefsFavoriteStore(context: Context) : FavoriteStore {
    private val prefs = prefs(context)

    override fun load(): List<Favorite> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            Favorite(f.getString("id"), f.getString("url"), f.getString("title"))
        }
    }.getOrDefault(emptyList())

    override fun save(favorites: List<Favorite>) {
        val arr = JSONArray()
        favorites.forEach { arr.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "favorites" }
}

/** Small screenshots of each tab for the tab grid (files in the app cache). */
class TabPreviewStore(context: Context) {
    private val dir = File(context.applicationContext.cacheDir, "tab-previews").apply { mkdirs() }

    fun file(tabId: String) = File(dir, "$tabId.jpg")

    fun save(tabId: String, bitmap: Bitmap) {
        runCatching { file(tabId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) } }
    }

    fun load(tabId: String): Bitmap? = file(tabId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun delete(tabId: String) { file(tabId).delete() }

    /** Removes previews of tabs that no longer exist. */
    fun keepOnly(tabIds: Set<String>) {
        dir.listFiles()?.filter { it.nameWithoutExtension !in tabIds }?.forEach { it.delete() }
    }
}
