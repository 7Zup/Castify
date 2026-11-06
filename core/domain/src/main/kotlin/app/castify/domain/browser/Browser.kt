package app.castify.domain.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

// ---------- Tabs ----------

/**
 * A browser tab. Only the active tab is loaded; the others keep their address and title
 * and load nothing until opened (user rule). [url] null = the home screen (new tab).
 */
data class Tab(
    val id: String,
    val url: String? = null,
    val title: String? = null,
    /** Videos found the last time this tab was open (badge in the tab grid). */
    val videoCount: Int = 0,
)

data class TabsState(val tabs: List<Tab>, val activeId: String) {
    val active: Tab get() = tabs.first { it.id == activeId }
}

/** Saves tabs between app launches (address, title, active tab). */
interface TabStore {
    fun load(): TabsState?
    fun save(state: TabsState)
}

/**
 * Tabs, Chrome style. Rules: at most [MAX_TABS] tabs (user choice); the tabs of the last
 * session are restored on launch; closing the last tab leaves one empty tab (home).
 */
class BrowserTabs(private val store: TabStore, private val newId: () -> String = { UUID.randomUUID().toString() }) {

    private val _state = MutableStateFlow(store.load()?.takeIf { it.tabs.any { t -> t.id == it.activeId } } ?: fresh())
    val state: StateFlow<TabsState> = _state.asStateFlow()

    /** Opens a tab and makes it active; null when [MAX_TABS] is reached. */
    fun open(url: String? = null): Tab? {
        val current = _state.value
        if (current.tabs.size >= MAX_TABS) return null
        val tab = Tab(newId(), url)
        update(TabsState(current.tabs + tab, tab.id))
        return tab
    }

    fun select(id: String) {
        val current = _state.value
        if (current.tabs.any { it.id == id }) update(current.copy(activeId = id))
    }

    /** Closing the active tab activates its right neighbor (else left). */
    fun close(id: String) {
        val current = _state.value
        val index = current.tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val remaining = current.tabs.filterNot { it.id == id }
        if (remaining.isEmpty()) return update(fresh())
        val active = if (current.activeId == id) remaining[index.coerceAtMost(remaining.lastIndex)].id else current.activeId
        update(TabsState(remaining, active))
    }

    fun closeAll() = update(fresh())

    /** The active tab navigated (link, address bar, back/forward). */
    fun onNavigated(url: String) = edit(_state.value.activeId) { it.copy(url = url) }

    fun onTitle(title: String?) = edit(_state.value.activeId) { it.copy(title = title?.takeIf { t -> t.isNotBlank() }) }

    fun onVideoCount(count: Int) = edit(_state.value.activeId) { it.copy(videoCount = count) }

    /** Back to the home screen in the active tab. */
    fun goHome() = edit(_state.value.activeId) { it.copy(url = null, title = null, videoCount = 0) }

    private fun edit(id: String, change: (Tab) -> Tab) {
        val current = _state.value
        val updated = current.tabs.map { if (it.id == id) change(it) else it }
        if (updated != current.tabs) update(current.copy(tabs = updated))
    }

    private fun update(state: TabsState) {
        _state.value = state
        store.save(state)
    }

    private fun fresh(): TabsState {
        val tab = Tab(newId())
        return TabsState(listOf(tab), tab.id)
    }

    companion object {
        const val MAX_TABS = 100
    }
}

// ---------- History ----------

/**
 * A visited page. [text] is what the list shows: the address, or the query for a search
 * results page ([isSearch]).
 */
data class HistoryEntry(val text: String, val url: String, val isSearch: Boolean, val at: Long)

interface HistoryStore {
    fun load(): List<HistoryEntry>
    fun save(entries: List<HistoryEntry>)
}

/**
 * History of **every page visited** in any tab (user rule, changed on 11/05/2026 from "typed
 * addresses only"). Newest first; visiting a page again moves it to the top (same page =
 * same address without `#fragment`); at most [MAX_ENTRIES], oldest dropped. Search results
 * pages are shown as their query.
 */
class BrowsingHistory(private val store: HistoryStore, private val now: () -> Long = System::currentTimeMillis) {

    private val _entries = MutableStateFlow(store.load().sortedByDescending { it.at }.distinctBy(::keyOf).take(MAX_ENTRIES))
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    /** A page was loaded (link, address bar, back/forward, single-page navigation). */
    fun visit(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val query = searchQueryOf(url)
        val entry = HistoryEntry(query ?: url.substringAfter("://"), url, isSearch = query != null, at = now())
        val key = keyOf(entry)
        update(listOf(entry) + _entries.value.filterNot { keyOf(it) == key })
    }

    fun remove(entry: HistoryEntry) = update(_entries.value - entry)

    fun clear() = update(emptyList())

    /** Case-insensitive search in the shown text (address or query) and the full address. */
    fun search(query: String): List<HistoryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return _entries.value
        return _entries.value.filter { it.text.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
    }

    private fun update(entries: List<HistoryEntry>) {
        val capped = entries.take(MAX_ENTRIES)
        _entries.value = capped
        store.save(capped)
    }

    companion object {
        const val MAX_ENTRIES = 1_000
        private const val SEARCH_PREFIX = "https://duckduckgo.com/?"

        /** Same page = same address without `#fragment` / trailing `/`; same search = same query. */
        private fun keyOf(entry: HistoryEntry) =
            if (entry.isSearch) "?" + entry.text.lowercase() else entry.url.substringBefore('#').trimEnd('/')

        /** `https://duckduckgo.com/?q=resident+evil&t=h_` → `resident evil`. */
        internal fun searchQueryOf(url: String): String? {
            if (!url.startsWith(SEARCH_PREFIX)) return null
            val q = url.substringAfter('?').split('&').firstOrNull { it.startsWith("q=") }?.substringAfter("q=") ?: return null
            return runCatching { java.net.URLDecoder.decode(q, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}

// ---------- Favorites ----------

data class Favorite(val id: String, val url: String, val title: String)

interface FavoriteStore {
    fun load(): List<Favorite>
    fun save(favorites: List<Favorite>)
}

/** Favorite pages (★ in the address bar), newest first. */
class Favorites(private val store: FavoriteStore, private val newId: () -> String = { UUID.randomUUID().toString() }) {

    private val _list = MutableStateFlow(store.load())
    val list: StateFlow<List<Favorite>> = _list.asStateFlow()

    fun isFavorite(url: String?): Boolean = url != null && _list.value.any { same(it.url, url) }

    /** ★: adds the page, or removes it when already a favorite. */
    fun toggle(url: String, title: String?) {
        val existing = _list.value.firstOrNull { same(it.url, url) }
        if (existing != null) remove(existing.id)
        else update(listOf(Favorite(newId(), url, title?.takeIf { it.isNotBlank() } ?: hostOf(url))) + _list.value)
    }

    fun rename(id: String, title: String) {
        val clean = title.trim().takeIf { it.isNotEmpty() } ?: return
        update(_list.value.map { if (it.id == id) it.copy(title = clean) else it })
    }

    fun remove(id: String) = update(_list.value.filterNot { it.id == id })

    private fun update(list: List<Favorite>) {
        _list.value = list
        store.save(list)
    }

    companion object {
        /** Same page: ignores `#fragment` and a trailing `/`. */
        fun same(a: String, b: String) = key(a) == key(b)
        private fun key(url: String) = url.substringBefore('#').trimEnd('/').lowercase()
        fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.")
    }
}
