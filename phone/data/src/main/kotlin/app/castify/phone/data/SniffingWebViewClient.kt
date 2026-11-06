package app.castify.phone.data

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.castify.domain.model.ObservedRequest
import app.castify.domain.model.VideoElement
import app.castify.domain.subtitles.SubtitleClassifier
import app.castify.domain.subtitles.SubtitleSource

/** What the browser reports. Implemented by the presentation layer, forwarded to the domain. */
interface BrowserEvents {
    fun onRequest(request: ObservedRequest)
    fun onVideoElement(element: VideoElement)
    fun onPageImage(url: String)
    /** `document.title` of the page (raw). */
    fun onPageTitle(title: String)
    /** A `<track>` element (subtitles/captions) with the language and label set by the site. */
    fun onSubtitleTrack(source: SubtitleSource)
    fun onPageStarted(url: String)
    fun onPageChanged(url: String, canGoBack: Boolean, canGoForward: Boolean)
    /** The page itself failed to load; [connectionFailure] = the server could not be reached. */
    fun onPageError(url: String, connectionFailure: Boolean)
}

/**
 * WebView adapter that reports what the page loads and shows.
 *
 * - [shouldInterceptRequest] sees all network requests (manifests, files, XHR).
 * - An injected script reports `<video>` elements (direct source, poster, duration,
 *   height) and the page share image (`og:image` / `twitter:image`).
 *
 * Cookies, User-Agent and Referer are added to the captured headers so the TV (and the
 * manifest inspection) can fetch the stream as the page did.
 */
class SniffingWebViewClient(private val events: BrowserEvents) : WebViewClient() {

    @Volatile private var pageUrl: String = ""
    @Volatile private var userAgent: String = ""

    /** Call once on the WebView, before the first load. */
    fun install(webView: WebView) {
        userAgent = webView.settings.userAgentString
        webView.addJavascriptInterface(PageBridge(), BRIDGE_NAME)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        pageUrl = url
        events.onPageStarted(url)
        events.onPageChanged(url, view.canGoBack(), view.canGoForward())
    }

    override fun onPageFinished(view: WebView, url: String) {
        events.onPageChanged(url, view.canGoBack(), view.canGoForward())
        view.evaluateJavascript(PAGE_SCRIPT, null)
    }

    /**
     * Also fires when a single-page site changes the address without reloading (e.g. picking
     * another movie). A new path = a new page: the detected videos and subtitles are reset,
     * otherwise the previous movie's subtitles would be sent with the new one. Query and
     * fragment changes (`?t=120`, `#comments`) are not a new page.
     */
    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        val previous = pageUrl
        pageUrl = url
        if (!isReload && previous.isNotEmpty() && pageKey(url) != pageKey(previous)) events.onPageStarted(url)
        events.onPageChanged(url, view.canGoBack(), view.canGoForward())
    }

    private fun pageKey(url: String) = url.substringBefore('#').substringBefore('?').trimEnd('/')

    /**
     * Web pages load as usual. Other schemes (`intent:`, `market:`, `tel:`, app links) would end
     * on an "unknown URL scheme" error page: they are dropped, except an `intent:` link with
     * a web fallback address, which is opened instead.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:") ||
            url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("javascript:")
        ) return false
        if (url.startsWith("intent:") && request.isForMainFrame) {
            Regex("""S\.browser_fallback_url=([^;]+)""").find(url)?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?.takeIf { it.startsWith("http") }
                ?.let(view::loadUrl)
        }
        return true
    }

    /** Only the page itself counts (a failing ad or image inside the page is ignored). */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        events.onPageError(request.url.toString(), connectionFailure = error.errorCode in CONNECTION_ERRORS)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        report(request.url.toString(), request.requestHeaders)
        return null // never alter the page's traffic (user rule: the phone refuses nothing)
    }

    private fun report(url: String, requestHeaders: Map<String, String>) {
        if (!url.startsWith("http")) return
        events.onRequest(ObservedRequest(url = url, pageUrl = pageUrl, headers = headersFor(url, requestHeaders)))
    }

    private fun headersFor(url: String, requestHeaders: Map<String, String>) = buildMap {
        putAll(requestHeaders)
        if (keys.none { it.equals("User-Agent", true) } && userAgent.isNotEmpty()) put("User-Agent", userAgent)
        if (keys.none { it.equals("Referer", true) } && pageUrl.isNotEmpty()) put("Referer", pageUrl)
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    private inner class PageBridge {
        @JavascriptInterface
        fun onVideoElement(src: String, poster: String, durationSec: Double, width: Int, height: Int) {
            val httpSrc = src.takeIf { it.startsWith("http") }
            httpSrc?.let { report(it, emptyMap()) }
            events.onVideoElement(
                VideoElement(
                    src = httpSrc,
                    posterUrl = poster.takeIf { it.startsWith("http") },
                    durationMs = durationSec.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() },
                    height = height.takeIf { it > 0 },
                    width = width.takeIf { it > 0 },
                ),
            )
        }

        @JavascriptInterface
        fun onPageImage(url: String) {
            if (url.startsWith("http")) events.onPageImage(url)
        }

        @JavascriptInterface
        fun onPageTitle(title: String) {
            if (title.isNotBlank()) events.onPageTitle(title)
        }

        @JavascriptInterface
        fun onSubtitleTrack(src: String, srclang: String, label: String) {
            val guessed = SubtitleClassifier.classify(src) ?: SubtitleClassifier.classify(src, "text/vtt") ?: return
            events.onSubtitleTrack(
                guessed.copy(
                    language = srclang.trim().takeIf { it.isNotEmpty() } ?: guessed.language,
                    label = label.trim().takeIf { it.isNotEmpty() },
                ),
            )
        }
    }

    private companion object {
        const val BRIDGE_NAME = "CastifyBridge"

        /** "Could not reach the server": no internet, DNS failure, refused, timeout, cut. */
        val CONNECTION_ERRORS = setOf(ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO)

        /** Polls the page every 2 s; reports only what changed. */
        val PAGE_SCRIPT = """
            (function() {
              if (window.__castifyInstalled) return;
              window.__castifyInstalled = true;
              var seen = {};
              var pageImage = null;
              var pageTitle = null;
              function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return ''; } }
              function scan() {
                var videos = document.querySelectorAll('video');
                for (var i = 0; i < videos.length; i++) {
                  var v = videos[i];
                  var src = v.currentSrc || v.src || '';
                  if (!src) { var s = v.querySelector('source'); if (s) src = abs(s.getAttribute('src') || ''); }
                  var poster = v.poster || '';
                  var duration = isFinite(v.duration) ? v.duration : 0;
                  var width = v.videoWidth || 0;
                  var height = v.videoHeight || 0;
                  var key = src + '|' + poster + '|' + Math.round(duration) + '|' + width + 'x' + height;
                  if (!seen[key]) { seen[key] = true; $BRIDGE_NAME.onVideoElement(src, poster, duration, width, height); }
                  var tracks = v.querySelectorAll('track');
                  for (var t = 0; t < tracks.length; t++) {
                    var tr = tracks[t];
                    var kind = (tr.kind || 'subtitles').toLowerCase();
                    if (kind !== 'subtitles' && kind !== 'captions') continue;
                    var tsrc = tr.src || abs(tr.getAttribute('src') || '');
                    var tkey = 'track|' + tsrc + '|' + (tr.srclang || '') + '|' + (tr.label || '');
                    if (tsrc && !seen[tkey]) { seen[tkey] = true; $BRIDGE_NAME.onSubtitleTrack(tsrc, tr.srclang || '', tr.label || ''); }
                  }
                }
                var meta = document.querySelector('meta[property="og:image"], meta[name="og:image"], meta[name="twitter:image"]');
                var img = meta ? abs(meta.getAttribute('content') || '') : '';
                if (img && img !== pageImage) { pageImage = img; $BRIDGE_NAME.onPageImage(img); }
                var ogTitle = document.querySelector('meta[property="og:title"]');
                var title = (ogTitle && ogTitle.getAttribute('content')) || document.title || '';
                if (title && title !== pageTitle) { pageTitle = title; $BRIDGE_NAME.onPageTitle(title); }
              }
              scan();
              setInterval(scan, 2000);
            })();
        """.trimIndent()
    }
}
