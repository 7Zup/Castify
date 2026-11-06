package app.castify.phone

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import app.castify.domain.browser.BrowserTabs
import app.castify.domain.browser.BrowsingHistory
import app.castify.domain.browser.Favorites
import app.castify.phone.data.PrefsFavoriteStore
import app.castify.phone.data.PrefsHistoryStore
import app.castify.phone.data.PrefsTabStore
import app.castify.phone.data.TabPreviewStore
import android.util.Log
import android.webkit.WebView
import app.castify.domain.detection.VideoCatalog
import app.castify.domain.inspection.InspectStream
import app.castify.domain.model.ObservedRequest
import app.castify.domain.model.VideoElement
import app.castify.domain.subtitles.SubtitleSource
import app.castify.domain.session.TvSession
import app.castify.domain.usecase.ObserveNearbyTvs
import app.castify.domain.usecase.ObserveTvDevices
import app.castify.domain.usecase.OpenPage
import app.castify.phone.data.BrowserEvents
import app.castify.phone.data.HttpManifestFetcher
import app.castify.phone.data.HttpTvClient
import app.castify.phone.data.NsdCastDeviceScanner
import app.castify.phone.data.NsdResolver
import app.castify.phone.data.NsdTvDiscovery
import app.castify.phone.data.UdpTvDiscovery
import app.castify.phone.data.WifiNetwork
import app.castify.phone.data.AndroidNetworkMonitor
import app.castify.phone.data.PrefsLastUrlStore
import app.castify.phone.data.SniffingWebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Composition root: the only place where adapters are wired to use cases. */
class CastifyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val videoCatalog = VideoCatalog(InspectStream(HttpManifestFetcher()), appScope)
    private val wifi = WifiNetwork(app).also { it.keepAppOnWifi() }
    val network = AndroidNetworkMonitor(app)
    // UDP first: its addresses are plain IPv4 and it heals after a Wi-Fi drop; mDNS as backup.
    private val nsd = NsdResolver(app)
    private val observeTvDevices = ObserveTvDevices(UdpTvDiscovery(wifi), NsdTvDiscovery(nsd))

    // Castify TVs plus every other Google Cast device, shown as "App not launched".
    val observeNearbyTvs = ObserveNearbyTvs(observeTvDevices, NsdCastDeviceScanner(nsd))
    val tvSession = TvSession(HttpTvClient(wifi::open), appScope)
    val history = BrowsingHistory(PrefsHistoryStore(app))
    val openPage = OpenPage()
    val favorites = Favorites(PrefsFavoriteStore(app))
    val tabs = BrowserTabs(PrefsTabStore(app)).also { tabs ->
        // One-time migration: before tabs, only the last visited page was remembered.
        val legacy = PrefsLastUrlStore(app)
        legacy.get()?.let { last ->
            if (tabs.state.value.tabs.size == 1 && tabs.state.value.active.url == null) tabs.onNavigated(last)
            legacy.clear()
        }
    }

    /** Tab grid previews (presentation glue: the UI only sees bitmaps). */
    private val previews = TabPreviewStore(app)
    fun savePreview(tabId: String, bitmap: Bitmap) = previews.save(tabId, bitmap)
    fun loadPreview(tabId: String): Bitmap? = previews.load(tabId)
    fun cleanPreviews() = previews.keepOnly(tabs.state.value.tabs.map { it.id }.toSet())

    init {
        // Show the media notification as soon as the connected TV plays something.
        appScope.launch {
            tvSession.state
                .map { it.connected && it.nowPlaying?.isActive == true }
                .distinctUntilChanged()
                .collect { active -> if (active) RemoteControlService.start(app) }
        }
        appScope.launch {
            tvSession.state.map { it.connected }.distinctUntilChanged().collect { connected ->
                if (!connected) RemoteControlService.stop(app)
            }
        }
    }

    /** Debug builds only: logs media-looking requests (tag `CastifyNet`) to compare sources. */
    private val logMedia = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Installs the sniffing adapter on the browser view; the UI only sees a WebView. */
    fun installSniffer(webView: WebView, viewModel: BrowserViewModel) {
        val events = object : BrowserEvents {
            override fun onRequest(request: ObservedRequest) {
                if (logMedia && MEDIA_HINT.containsMatchIn(request.url)) Log.d("CastifyNet", "req ${request.url}")
                videoCatalog.onRequest(request)
            }
            override fun onVideoElement(element: VideoElement) {
                if (logMedia) Log.d("CastifyNet", "video src=${element.src} duration=${element.durationMs} size=${element.width}x${element.height}")
                videoCatalog.onVideoElement(element)
            }
            override fun onPageImage(url: String) = videoCatalog.onPageImage(url)
            override fun onPageTitle(title: String) {
                videoCatalog.onPageTitle(title)
                viewModel.onPageTitle(title)
            }
            override fun onSubtitleTrack(source: SubtitleSource) {
                if (logMedia) Log.d("CastifyNet", "track ${source.url} lang=${source.language} label=${source.label}")
                videoCatalog.onSubtitle(source, fromTrackElement = true)
            }
            override fun onPageStarted(url: String) {
                if (logMedia) Log.d("CastifyNet", "page $url")
                videoCatalog.reset()
                viewModel.onPageLoading()
            }
            override fun onPageChanged(url: String, canGoBack: Boolean, canGoForward: Boolean) =
                viewModel.onPageChanged(url, canGoBack, canGoForward)
            override fun onPageError(url: String, connectionFailure: Boolean) =
                viewModel.onPageError(url, connectionFailure)
        }
        val client = SniffingWebViewClient(events)
        client.install(webView)
        webView.webViewClient = client
    }

    private companion object {
        val MEDIA_HINT = Regex("""\.(m3u8|mpd|mp4|webm|ts|m4s|aac|vtt|srt|ass|ssa|ttml|dfxp)(\?|$)|/hls/|/dash/|video|stream|subtitle""", RegexOption.IGNORE_CASE)
    }
}
