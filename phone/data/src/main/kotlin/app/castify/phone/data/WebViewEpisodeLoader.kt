package app.castify.phone.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.castify.domain.detection.VideoCatalog
import app.castify.domain.inspection.InspectStream
import app.castify.domain.model.DetectedVideo
import app.castify.domain.model.ObservedRequest
import app.castify.domain.model.VideoElement
import app.castify.domain.port.EpisodeLoader
import app.castify.domain.subtitles.SubtitleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads an episode's page in a WebView nobody sees (never attached to the screen), lets its
 * player start muted, and returns the video the page plays: the same detection as the
 * visible browser ([VideoCatalog]), the longest watchable video once one is found.
 *
 * [appVisible]: the browser pauses every WebView's timers when the app leaves the screen
 * (`pauseTimers` is global); they run again while this page loads, then are paused again if
 * the app is still in the background.
 */
class WebViewEpisodeLoader(
    private val context: Context,
    private val appVisible: () -> Boolean,
) : EpisodeLoader {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun findVideo(url: String, timeoutMs: Long): DetectedVideo? = withContext(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val catalog = VideoCatalog(InspectStream(HttpManifestFetcher()), scope)
        val web = WebView(context.applicationContext)
        try {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            // The player must start by itself (it is muted below): that is when the video is requested.
            web.settings.mediaPlaybackRequiresUserGesture = false
            // No popups: windows are never created.
            web.settings.setSupportMultipleWindows(true)
            web.webChromeClient = WebChromeClient()
            // A real size: some players do not start in a 0x0 view.
            web.layout(0, 0, 1280, 720)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, MUTE_SCRIPT, setOf("*"))
            }
            val client = SniffingWebViewClient(eventsFor(catalog))
            client.install(web)
            web.webViewClient = client
            web.resumeTimers()
            catalog.reset()
            web.loadUrl(url)
            withTimeoutOrNull(timeoutMs) {
                catalog.videos.first { videos -> videos.any(::watchable) }
                // Ads and intros come first on some players: give the episode a moment, keep the longest.
                delay(SETTLE_MS)
                catalog.videos.value.filter(::watchable).maxByOrNull { it.details?.durationMs ?: 0 }
            }
        } finally {
            web.stopLoading()
            web.destroy()
            scope.cancel()
            if (!appVisible()) web.pauseTimers()
        }
    }

    private fun watchable(video: DetectedVideo): Boolean {
        val details = video.details ?: return false
        return details.isLive || (details.durationMs ?: 0) >= MIN_EPISODE_MS
    }

    private fun eventsFor(catalog: VideoCatalog) = object : BrowserEvents {
        override fun onRequest(request: ObservedRequest) = catalog.onRequest(request)
        override fun onVideoElement(element: VideoElement) = catalog.onVideoElement(element)
        override fun onPageImage(url: String) = catalog.onPageImage(url)
        override fun onPageTitle(title: String) = catalog.onPageTitle(title)
        override fun onSubtitleTrack(source: SubtitleSource) = catalog.onSubtitle(source, fromTrackElement = true)
        // A single-page site settling its address must not wipe what was found.
        override fun onPageStarted(url: String) = Unit
        override fun onPageChanged(url: String, canGoBack: Boolean, canGoForward: Boolean) = Unit
        override fun onPageError(url: String, connectionFailure: Boolean) = Unit
    }

    private companion object {
        /** Shorter videos are trailers, intros or ads. */
        const val MIN_EPISODE_MS = 3 * 60_000L
        const val SETTLE_MS = 2_000L

        /** Every frame: media elements play muted (the phone must stay silent). */
        val MUTE_SCRIPT = """
            (function() {
              var p = HTMLMediaElement.prototype, play = p.play;
              p.play = function() { try { this.muted = true; this.volume = 0; } catch (e) {} return play.apply(this, arguments); };
              document.addEventListener('playing', function(e) { try { e.target.muted = true; } catch (x) {} }, true);
            })();
        """.trimIndent()
    }
}
