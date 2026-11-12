package app.castify.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.castify.domain.browser.BrowserTabs
import app.castify.domain.browser.BrowsingHistory
import app.castify.domain.browser.Favorite
import app.castify.domain.browser.Favorites
import app.castify.domain.browser.HistoryEntry
import app.castify.domain.browser.TabsState
import app.castify.domain.detection.VideoCatalog
import app.castify.domain.model.DetectedVideo
import app.castify.domain.model.NearbyTv
import app.castify.domain.model.TvDevice
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.session.TvSession
import app.castify.domain.series.EpisodeNumber
import app.castify.domain.series.EpisodePlayer
import app.castify.domain.port.NetworkMonitor
import app.castify.domain.status.PageError
import app.castify.domain.session.TvSession.SendResult
import app.castify.domain.usecase.ObserveNearbyTvs
import app.castify.domain.usecase.OpenPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A video waiting for the user to confirm it replaces the one playing on the TV. */
data class PendingReplace(val video: DetectedVideo, val height: Int?, val tvName: String)

data class BrowserUiState(
    val addressBar: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val sendingTo: String? = null,
    val pendingReplace: PendingReplace? = null,
    /** The page failed to load (mockup P1 / P2); null = no error. */
    val pageError: PageErrorUi? = null,
)

/** [host] is the site that could not be reached (`Can't reach <host>`). */
data class PageErrorUi(val kind: PageError, val host: String)

class BrowserViewModel(
    private val catalog: VideoCatalog,
    observeNearbyTvs: ObserveNearbyTvs,
    private val session: TvSession,
    private val openPage: OpenPage,
    private val tabs: BrowserTabs,
    private val history: BrowsingHistory,
    private val favorites: Favorites,
    private val network: NetworkMonitor,
    private val episodes: EpisodePlayer,
) : ViewModel() {

    /** The series the TV plays (sent from this phone): episodes, Auto next. Null = not a series. */
    val series: StateFlow<EpisodePlayer.State?> = episodes.state

    fun playEpisode(number: EpisodeNumber) = episodes.play(number)

    fun setAutoNext(enabled: Boolean) = episodes.setAutoNext(enabled)


    /** Phone has internet (home screen banner P3). */
    val online: StateFlow<Boolean> = network.online

    private val _state = MutableStateFlow(BrowserUiState(addressBar = tabs.state.value.active.url.orEmpty()))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    val videos: StateFlow<List<DetectedVideo>> = catalog.videos

    /** Tabs (only the active one is loaded), typed-address history, favorites. */
    val tabsState: StateFlow<TabsState> = tabs.state
    val historyEntries: StateFlow<List<HistoryEntry>> = history.entries
    val favoriteList: StateFlow<List<Favorite>> = favorites.list

    /** Connection to a TV and what it plays (polled by the domain session). */
    val tv: StateFlow<TvSession.State> = session.state

    private val discoveryRuns = MutableStateFlow(0)

    /**
     * TVs found on the Wi-Fi (Castify ready or not); null = search just (re)started. Discovery runs only while the
     * UI observes it. [refreshDevices] really restarts it (stops and starts mDNS browsing).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val devices: StateFlow<List<NearbyTv>?> = discoveryRuns
        .flatMapLatest { run ->
            val found = observeNearbyTvs()
            if (run == 0) {
                found.onStart<List<NearbyTv>?> { emit(null) }
            } else {
                // Refresh: show the search for at least REFRESH_SEARCH_MS (user request), so the
                // list does not flash "Searching…" for half a second and come straight back.
                val searchShown = flow { emit(false); delay(REFRESH_SEARCH_MS); emit(true) }
                combine(found.onStart { emit(emptyList()) }, searchShown) { list, shown -> if (shown) list else null }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refreshDevices() = discoveryRuns.update { it + 1 }

    /** URLs to load in the active tab's WebView (same tab navigation). */
    private val _loadRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val loadRequests: SharedFlow<String> = _loadRequests.asSharedFlow()

    /** Retry on the error screen: reload the active page (nothing reloads by itself, user rule). */
    private val _reloadRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reloadRequests: SharedFlow<Unit> = _reloadRequests.asSharedFlow()

    // ---------- page errors (mockup P1 / P2 / P3) ----------

    /** A new load starts: the error screen goes away (it comes back if the load fails again). */
    fun onPageLoading() = _state.update { it.copy(pageError = null) }

    fun onPageError(url: String, connectionFailure: Boolean) {
        network.refresh()
        val kind = PageError.of(connectionFailure, network.online.value) ?: return
        val host = url.substringAfter("://").substringBefore('/').removePrefix("www.")
        _state.update { it.copy(pageError = PageErrorUi(kind, host)) }
    }

    fun retryPage() {
        network.refresh()
        _reloadRequests.tryEmit(Unit)
    }

    /** Home screen banner Retry: checks the connection again. */
    fun retryNetwork() = network.refresh()

    /** `Go to home` on the error screen: the active tab shows the home screen. */
    fun goHome() {
        tabs.goHome()
        catalog.reset()
        _state.update { it.copy(pageError = null, addressBar = "", canGoBack = false, canGoForward = false) }
    }

    /**
     * The video leaves for the TV: the page's own player must pause (no double download, user
     * rule; a phone still playing is confusing). Fired when the send starts and once it worked.
     */
    private val _pausePageMedia = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pausePageMedia: SharedFlow<Unit> = _pausePageMedia.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // Remember how many videos the active tab had (badge in the tab grid).
        viewModelScope.launch {
            catalog.videos.map { it.size }.distinctUntilChanged().collect { tabs.onVideoCount(it) }
        }
        // An episode page: fetch its series' episodes now, so sending it is not slowed down.
        viewModelScope.launch {
            catalog.episodeAddress.collect { address -> if (address != null) episodes.showOf(address) }
        }
        // The picked TV never answered: the Cast icon is grey again, say why.
        viewModelScope.launch {
            session.connectFailures.collect {
                _messages.emit("${it.device.name} isn't responding. Check it's on the same Wi-Fi, or restart it.")
            }
        }
    }

    fun isFavorite(url: String?): Boolean = favorites.isFavorite(url)

    fun showMessage(text: String) = _messages.tryEmit(text)

    // ---------- address bar ----------

    fun onAddressChange(text: String) = _state.update { it.copy(addressBar = text) }

    fun onAddressSubmit() = open(_state.value.addressBar)

    /** Typed text (address bar) opened in the active tab; the history records it once loaded. */
    fun open(input: String) {
        val url = openPage(input) ?: return
        navigate(url)
    }

    /** A favorite or a history entry: opened as is in the active tab. */
    fun openUrl(url: String) = navigate(url)

    private fun navigate(url: String) {
        val wasHome = tabs.state.value.active.url == null
        tabs.onNavigated(url)
        _state.update { it.copy(addressBar = url) }
        // From the home screen the WebView is created with this URL; otherwise load it.
        if (!wasHome) _loadRequests.tryEmit(url)
    }

    fun onPageChanged(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        if (!url.startsWith("http")) return
        tabs.onNavigated(url)
        history.visit(url) // every visited page (user rule)
        _state.update { it.copy(addressBar = url, canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun onPageTitle(title: String) = tabs.onTitle(title)

    fun toggleFavorite() {
        val tab = tabs.state.value.active
        val url = tab.url ?: return
        val adding = !favorites.isFavorite(url)
        favorites.toggle(url, tab.title)
        _messages.tryEmit(if (adding) "Added to favorites" else "Removed from favorites")
    }

    // ---------- tabs ----------

    fun newTab(url: String? = null) {
        if (tabs.open(url) == null) {
            _messages.tryEmit("You can have up to ${BrowserTabs.MAX_TABS} tabs. Close one first.")
            return
        }
        onTabChanged()
    }

    fun selectTab(id: String) {
        if (id == tabs.state.value.activeId) return
        tabs.select(id)
        onTabChanged()
    }

    fun closeTab(id: String) {
        val wasActive = id == tabs.state.value.activeId
        tabs.close(id)
        if (wasActive) onTabChanged()
    }

    fun closeAllTabs() {
        tabs.closeAll()
        onTabChanged()
    }

    /** A different tab is shown: its WebView is (re)created with its saved address. */
    private fun onTabChanged() {
        catalog.reset()
        _state.update {
            it.copy(addressBar = tabs.state.value.active.url.orEmpty(), canGoBack = false, canGoForward = false, pageError = null)
        }
    }

    // ---------- history & favorites ----------

    fun searchHistory(query: String): List<HistoryEntry> = history.search(query)
    fun removeHistory(entry: HistoryEntry) = history.remove(entry)
    fun clearHistory() = history.clear()

    fun renameFavorite(id: String, title: String) = favorites.rename(id, title)
    fun removeFavorite(id: String) = favorites.remove(id)

    // ---------- TV ----------

    fun connect(device: TvDevice) = session.connect(device)

    fun disconnect() = session.disconnect()

    /** Connects first when not connected yet (TV picked in the send flow). */
    fun send(video: DetectedVideo, height: Int?, device: TvDevice? = null) {
        device?.let(session::connect)
        doSend(video, height, replace = false)
    }

    fun confirmReplace() {
        val pending = _state.value.pendingReplace ?: return
        _state.update { it.copy(pendingReplace = null) }
        doSend(pending.video, pending.height, replace = true)
    }

    fun cancelReplace() = _state.update { it.copy(pendingReplace = null) }

    fun control(command: PlayerCommand) = session.control(command)

    fun retry() = session.refresh()

    private fun doSend(video: DetectedVideo, height: Int?, replace: Boolean) {
        val tvName = session.state.value.device?.name ?: return
        val address = catalog.episodeAddress.value
        _state.update { it.copy(sendingTo = tvName) }
        _pausePageMedia.tryEmit(Unit)
        viewModelScope.launch {
            // An episode carries its series (episodes and Next on the TV).
            val stream = episodes.withSeries(video.stream.copy(thumbnailUrl = video.thumbnailUrl, subtitles = video.subtitles), address)
            val result = session.send(stream, height, replace)
            if (result == SendResult.Sent) episodes.onSent(stream, address)
            _state.update { it.copy(sendingTo = null) }
            when (result) {
                SendResult.Sent -> {
                    _pausePageMedia.tryEmit(Unit)
                    _messages.emit("Playing on $tvName")
                }
                SendResult.NeedsConfirmation ->
                    _state.update { it.copy(pendingReplace = PendingReplace(video, height, tvName)) }
                is SendResult.Failed -> _messages.emit(result.reason)
                SendResult.NotConnected -> Unit // connectFailures already said it
            }
        }
    }

    companion object {
        private const val REFRESH_SEARCH_MS = 2_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BrowserViewModel(
                    container.videoCatalog,
                    container.observeNearbyTvs,
                    container.tvSession,
                    container.openPage,
                    container.tabs,
                    container.history,
                    container.favorites,
                    container.network,
                    container.episodePlayer,
                )
            }
        }
    }
}
