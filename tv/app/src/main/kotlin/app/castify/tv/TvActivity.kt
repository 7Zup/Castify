package app.castify.tv

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.castify.domain.playback.RemoteKey

class TvActivity : ComponentActivity() {

    private val container get() = (application as CastifyTvApp).container
    private val viewModel: TvViewModel by viewModels { TvViewModel.factory(container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Silent update check, shown on the welcome screen only. Also each time the welcome
        // screen shows again (TvViewModel), throttled by the container.
        container.checkForUpdate()
        setContent { TvScreenContent(viewModel, container.videoSurface) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.start(deviceName())
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's "Install unknown apps" screen: the update goes on by itself.
        container.updater.onResume()
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }

    /** Back DOWN handled by the view model: its UP must not reach the system either. */
    private var backDownConsumed = false

    /** The Google TV remote has no media keys: D-pad, OK and Back drive everything. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val key = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> RemoteKey.OK
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteKey.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteKey.RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> RemoteKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.DOWN
                KeyEvent.KEYCODE_BACK -> RemoteKey.BACK
                else -> null
            }
            val consumed = key != null && viewModel.onKey(key)
            if (event.keyCode == KeyEvent.KEYCODE_BACK) backDownConsumed = consumed
            if (consumed) return true
        } else if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK && backDownConsumed) {
            backDownConsumed = false
            return true // the matching DOWN was consumed
        }
        return super.dispatchKeyEvent(event)
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
}
