package app.castify.tv

import android.app.Application
import androidx.media3.ui.PlayerView
import app.castify.domain.update.AppKind
import app.castify.domain.update.AppUpdater
import app.castify.domain.update.AppVersion
import app.castify.domain.usecase.HandlePlayerCommand
import app.castify.domain.usecase.PlayStream
import app.castify.tv.data.ExoPlayerAdapter
import app.castify.tv.data.LanCastReceiver
import app.castify.tv.data.PrefsTvSettings
import app.castify.domain.update.UpdateCheckThrottle
import app.castify.domain.update.WhatsNew
import app.castify.update.PrefsSeenVersionStore
import app.castify.update.wasUpdated
import app.castify.tv.data.AndroidNetworkMonitor
import app.castify.update.ApkInstaller
import app.castify.update.GitHubReleaseSource
import app.castify.update.PrefsAcceptedUpdateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val receiver = LanCastReceiver(app)
    val player = ExoPlayerAdapter(app)
    val playStream = PlayStream(player)
    val network = AndroidNetworkMonitor(app)
    val handlePlayerCommand = HandlePlayerCommand(player)
    val settings = PrefsTvSettings(app)

    // In-app update from the public GitHub releases (docs/features/app-update.md).
    private val apkInstaller = ApkInstaller(app)
    val updater = AppUpdater(
        AppVersion.parse(app.packageManager.getPackageInfo(app.packageName, 0).versionName) ?: AppVersion(0, 0, 0),
        AppKind.TV,
        GitHubReleaseSource(UPDATE_REPO),
        apkInstaller,
        PrefsAcceptedUpdateStore(app),
        appScope,
    )

    /** "What's new" after an update, and every version's notes (mockup docs/mockups/whats-new.html). */
    val whatsNew = WhatsNew(updater.currentVersion, PrefsSeenVersionStore(app), wasUpdated(app))

    private val updateThrottle = UpdateCheckThrottle()

    /**
     * Silent check each time the home screen shows, at most every 10 min (a Chromecast rarely
     * really closes the app). A leftover APK from a previous update is removed first.
     */
    fun checkForUpdate() {
        if (!updateThrottle.due()) return
        if (updater.state.value == AppUpdater.State.Idle) apkInstaller.cleanUp()
        updater.check()
    }

    /** Video surface binding for the UI, which only sees a Media3 PlayerView. */
    val videoSurface = VideoSurface(attach = player::attach, detach = player::detach)

    private companion object {
        const val UPDATE_REPO = "7Zup/Castify"
    }
}

class VideoSurface(val attach: (PlayerView) -> Unit, val detach: () -> Unit)
