package app.castify.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.castify.phone.ui.CastifyTheme
import app.castify.phone.ui.UpdateDialog

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
            CastifyTheme {
                BrowserScreen(
                    viewModel,
                    installSniffer = { webView -> container.installSniffer(webView, viewModel) },
                    savePreview = container::savePreview,
                    loadPreview = container::loadPreview,
                    version = container.updater.currentVersion.label,
                )
                UpdateDialog(container.updater)
            }
        }
    }
}
