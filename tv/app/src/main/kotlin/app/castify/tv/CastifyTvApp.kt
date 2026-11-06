package app.castify.tv

import android.app.Application
import androidx.media3.ui.PlayerView
import app.castify.domain.usecase.HandlePlayerCommand
import app.castify.domain.usecase.PlayStream
import app.castify.tv.data.ExoPlayerAdapter
import app.castify.tv.data.LanCastReceiver
import app.castify.tv.data.AndroidNetworkMonitor

/** Composition root: the only place where adapters are wired to use cases. */
class CastifyTvApp : Application() {
    lateinit var container: TvContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = TvContainer(this)
    }
}

class TvContainer(app: Application) {
    val receiver = LanCastReceiver(app)
    val player = ExoPlayerAdapter(app)
    val playStream = PlayStream(player)
    val network = AndroidNetworkMonitor(app)
    val handlePlayerCommand = HandlePlayerCommand(player)

    /** Video surface binding for the UI, which only sees a Media3 PlayerView. */
    val videoSurface = VideoSurface(attach = player::attach, detach = player::detach)
}

class VideoSurface(val attach: (PlayerView) -> Unit, val detach: () -> Unit)
