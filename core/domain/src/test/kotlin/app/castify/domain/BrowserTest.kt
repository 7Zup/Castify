package app.castify.domain

import app.castify.domain.browser.BrowserTabs
import app.castify.domain.browser.BrowsingHistory
import app.castify.domain.browser.Favorite
import app.castify.domain.browser.FavoriteStore
import app.castify.domain.browser.Favorites
import app.castify.domain.browser.HistoryEntry
import app.castify.domain.browser.HistoryStore
import app.castify.domain.browser.TabStore
import app.castify.domain.browser.TabsState
import app.castify.domain.usecase.OpenPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTest {

    private class MemoryTabs(var saved: TabsState? = null) : TabStore {
        override fun load() = saved
        override fun save(state: TabsState) { saved = state }
    }

    private class MemoryHistory(var saved: List<HistoryEntry> = emptyList()) : HistoryStore {
        override fun load() = saved
        override fun save(entries: List<HistoryEntry>) { saved = entries }
    }

    private class MemoryFavorites(var saved: List<Favorite> = emptyList()) : FavoriteStore {
        override fun load() = saved
        override fun save(favorites: List<Favorite>) { saved = favorites }
    }

    private fun ids(): () -> String { var n = 0; return { "t${++n}" } }

    // ---- tabs ----

    @Test
    fun `starts with one empty tab, restores the last session`() {
        val store = MemoryTabs()
        val tabs = BrowserTabs(store, ids())
        assertEquals(1, tabs.state.value.tabs.size)
        assertNull(tabs.state.value.active.url)

        tabs.onNavigated("https://a.com")
        tabs.open("https://b.com")
        val restored = BrowserTabs(store, ids())
        assertEquals(listOf("https://a.com", "https://b.com"), restored.state.value.tabs.map { it.url })
        assertEquals("https://b.com", restored.state.value.active.url)
    }

    @Test
    fun `closing the active tab activates its neighbor, closing the last leaves a home tab`() {
        val tabs = BrowserTabs(MemoryTabs(), ids())
        tabs.onNavigated("https://a.com")
        val b = tabs.open("https://b.com")!!
        val c = tabs.open("https://c.com")!!
        tabs.select(b.id)
        tabs.close(b.id)
        assertEquals(c.id, tabs.state.value.activeId)
        tabs.close(c.id)
        assertEquals("https://a.com", tabs.state.value.active.url)
        tabs.close(tabs.state.value.activeId)
        assertEquals(1, tabs.state.value.tabs.size)
        assertNull(tabs.state.value.active.url)
    }

    @Test
    fun `at most 100 tabs`() {
        val tabs = BrowserTabs(MemoryTabs(), ids())
        repeat(99) { tabs.open() }
        assertEquals(100, tabs.state.value.tabs.size)
        assertNull(tabs.open())
    }

    @Test
    fun `title, url and video count belong to the active tab only`() {
        val tabs = BrowserTabs(MemoryTabs(), ids())
        val first = tabs.state.value.activeId
        tabs.open()
        tabs.onNavigated("https://b.com")
        tabs.onTitle("B")
        tabs.onVideoCount(2)
        val firstTab = tabs.state.value.tabs.first { it.id == first }
        assertNull(firstTab.url)
        assertEquals("B", tabs.state.value.active.title)
        assertEquals(2, tabs.state.value.active.videoCount)
    }

    // ---- history ----

    @Test
    fun `history records every visited page newest first, revisiting moves to top`() {
        var clock = 0L
        val history = BrowsingHistory(MemoryHistory()) { ++clock }
        history.visit("https://streamsite.example/")
        history.visit("https://streamsite.example/movies/42")
        history.visit("https://streamsite.example#top")
        val entries = history.entries.value
        assertEquals(listOf("streamsite.example#top", "streamsite.example/movies/42"), entries.map { it.text })
        assertEquals("https://streamsite.example#top", entries[0].url)
        assertFalse(entries[0].isSearch)
    }

    @Test
    fun `search results pages are shown as their query, one entry per query`() {
        var clock = 0L
        val history = BrowsingHistory(MemoryHistory()) { ++clock }
        history.visit(OpenPage()("resident evil 2026")!!)
        history.visit("https://duckduckgo.com/?q=resident+evil+2026&t=h_&ia=web")
        val entries = history.entries.value
        assertEquals(listOf("resident evil 2026"), entries.map { it.text })
        assertTrue(entries[0].isSearch)
    }

    @Test
    fun `non web pages are not recorded`() {
        val history = BrowsingHistory(MemoryHistory())
        history.visit("about:blank")
        history.visit("data:text/html,x")
        assertTrue(history.entries.value.isEmpty())
    }

    @Test
    fun `history is capped at 1000, oldest dropped`() {
        var clock = 0L
        val history = BrowsingHistory(MemoryHistory()) { ++clock }
        repeat(1_005) { history.visit("https://site$it.com") }
        assertEquals(1_000, history.entries.value.size)
        assertEquals("site1004.com", history.entries.value.first().text)
        assertEquals("site5.com", history.entries.value.last().text)
    }

    @Test
    fun `history search, remove and clear`() {
        var clock = 0L
        val history = BrowsingHistory(MemoryHistory()) { ++clock }
        history.visit("https://videosite.example/tv/the-bear")
        history.visit("https://duckduckgo.com/?q=resident+evil")
        assertEquals(listOf("videosite.example/tv/the-bear"), history.search("BEAR").map { it.text })
        assertTrue(history.search("bear 2").isEmpty())
        assertEquals(2, history.search("  ").size)
        history.remove(history.entries.value.first())
        assertEquals(1, history.entries.value.size)
        history.clear()
        assertTrue(history.entries.value.isEmpty())
    }

    // ---- favorites ----

    @Test
    fun `star toggles a favorite, same page ignores fragment and trailing slash`() {
        val favorites = Favorites(MemoryFavorites(), ids())
        favorites.toggle("https://streamsite.example/movies/42/", "Resident Evil")
        assertTrue(favorites.isFavorite("https://streamsite.example/movies/42#player"))
        favorites.toggle("https://streamsite.example/movies/42", null)
        assertFalse(favorites.isFavorite("https://streamsite.example/movies/42"))
    }

    @Test
    fun `favorite without title uses the host, rename and remove`() {
        val store = MemoryFavorites()
        val favorites = Favorites(store, ids())
        favorites.toggle("https://www.streamx.example/home", "  ")
        val fav = favorites.list.value.single()
        assertEquals("streamx.example", fav.title)
        favorites.rename(fav.id, "StreamX")
        assertEquals("StreamX", Favorites(store).list.value.single().title)
        favorites.remove(fav.id)
        assertTrue(favorites.list.value.isEmpty())
    }
}
