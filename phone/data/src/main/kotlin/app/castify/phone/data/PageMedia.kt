package app.castify.phone.data

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * Pauses every `<video>` / `<audio>` of the page, **iframes included**: streaming sites put
 * their player in a cross-origin frame, which `evaluateJavascript` (main frame only) never
 * reaches. Each frame gets a tiny script at document start that registers itself; pausing
 * posts a message to every registered frame.
 *
 * Main thread only (WebView rule). Any frame (ads too) can talk to the injected object, but
 * the only message read is `hello` and the only one sent is `pause`.
 */
object PageMedia {
    private const val OBJECT_NAME = "castifyMedia"
    private const val HELLO = "hello"
    private const val PAUSE = "pause"
    /** Frames of pages left behind stay in the list: keep the most recent ones only. */
    private const val MAX_FRAMES = 64

    private val frames = WeakHashMap<WebView, ArrayDeque<JavaScriptReplyProxy>>()

    private val PAUSE_SCRIPT = "document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})"

    private val FRAME_SCRIPT = """
        (function() {
          var o = window.$OBJECT_NAME;
          if (!o || window.__castifyMedia) return;
          window.__castifyMedia = true;
          o.onmessage = function(e) { if (e.data === '$PAUSE') { $PAUSE_SCRIPT; } };
          o.postMessage('$HELLO');
        })();
    """.trimIndent()

    /** Call once on the WebView, before the first load. Old WebViews: main frame only. */
    fun install(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) return
        val registered = ArrayDeque<JavaScriptReplyProxy>()
        frames[webView] = registered
        val everyOrigin = setOf("*")
        WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, everyOrigin) { _, message, _, _, reply ->
            if (message.data == HELLO) {
                registered.addLast(reply)
                if (registered.size > MAX_FRAMES) registered.removeFirst()
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, FRAME_SCRIPT, everyOrigin)
    }

    fun pause(webView: WebView) {
        webView.evaluateJavascript(PAUSE_SCRIPT, null)
        frames[webView]?.forEach { frame -> runCatching { frame.postMessage(PAUSE) } }
    }
}
