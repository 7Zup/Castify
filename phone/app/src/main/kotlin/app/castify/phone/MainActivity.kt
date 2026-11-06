package app.castify.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.castify.phone.ui.CastifyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels {
        BrowserViewModel.factory((application as CastifyApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as CastifyApp).container.cleanPreviews()
        enableEdgeToEdge()
        setContent {
            CastifyTheme {
                val container = (application as CastifyApp).container
                BrowserScreen(
                    viewModel,
                    installSniffer = { webView -> container.installSniffer(webView, viewModel) },
                    savePreview = container::savePreview,
                    loadPreview = container::loadPreview,
                )
            }
        }
    }
}
