package app.castify.phone

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.castify.phone.ui.CastifyTheme
import app.castify.phone.ui.UpdateDialog
import app.castify.phone.ui.ReleaseNotesScreen
import app.castify.phone.ui.WhatsNewDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels {
        BrowserViewModel.factory((application as CastifyApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as CastifyApp).container
        container.cleanPreviews()
        // A launch (not a restore): silent update check, the dialog shows if there is one.
        if (savedInstanceState == null) container.checkForUpdate()
        enableEdgeToEdge()
        setContent {
            // "What's new" once after an update; it goes before the update dialog (mockup P1).
            var whatsNew by remember { mutableStateOf(container.whatsNew.popup()) }
            var notesOpen by remember { mutableStateOf(false) }
            CastifyTheme {
                BrowserScreen(
                    viewModel,
                    installSniffer = { webView -> container.installSniffer(webView, viewModel) },
                    savePreview = container::savePreview,
                    tabHistory = object : TabHistory {
                        override fun save(tabId: String, webView: WebView) = container.saveTabHistory(tabId, webView)
                        override fun restore(tabId: String, webView: WebView, url: String) = container.restoreTabHistory(tabId, webView, url)
                        override fun forget(tabId: String) = container.forgetTabHistory(tabId)
                    },
                    loadPreview = container::loadPreview,
                    version = container.updater.currentVersion.label,
                    onWhatsNew = { notesOpen = true },
                )
                val note = whatsNew
                if (note != null) {
                    WhatsNewDialog(
                        note,
                        onOk = { container.whatsNew.markSeen(); whatsNew = null },
                        onAllVersions = { container.whatsNew.markSeen(); whatsNew = null; notesOpen = true },
                    )
                } else {
                    UpdateDialog(container.updater)
                }
                if (notesOpen) {
                    ReleaseNotesScreen(container.whatsNew.all, container.updater.currentVersion.label) { notesOpen = false }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as CastifyApp).container.appVisible = true
    }

    override fun onStop() {
        (application as CastifyApp).container.appVisible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's "Install unknown apps" screen: the update goes on by itself.
        (application as CastifyApp).container.updater.onResume()
    }
}
