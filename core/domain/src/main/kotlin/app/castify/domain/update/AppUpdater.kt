package app.castify.domain.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where releases are published (GitHub Releases). */
interface UpdateSource {
    /** The latest release, or null when none is published. Throws when unreachable. */
    suspend fun latest(): Release?
}

/** Downloads an APK and hands it to the system installer. */
interface UpdateInstaller {
    /** True once the user allowed "Install unknown apps" for Castify. */
    fun canInstall(): Boolean

    /**
     * Opens Android's "Install unknown apps" switch for Castify. False when the device has no
     * such screen: the install then asks by itself.
     */
    fun requestInstallPermission(): Boolean

    /** Downloads [update]'s APK, reporting bytes as they arrive. Throws on failure; cancellable. */
    suspend fun download(update: AppUpdate, onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit)

    /**
     * Installs the downloaded APK. Android shows its own confirmation. Returns once the user
     * answered; on success the app is killed and restarted by the system, so it usually never
     * returns.
     */
    suspend fun install(update: AppUpdate): InstallOutcome
}

/**
 * The version the user said `Update` to, kept across a process death: Android 8–10 kill the
 * app when "Install unknown apps" is switched on.
 */
interface AcceptedUpdateStore {
    var accepted: AppVersion?
}

enum class InstallOutcome { INSTALLED, CANCELLED, FAILED }

/**
 * In-app update (mockup `docs/mockups/app-update.html`).
 *
 * Rules:
 * - [check] runs silently at each launch: up to date, no release, or source unreachable →
 *   nothing is shown;
 * - `Not now` ([dismiss]) closes the prompt; the next launch asks again;
 * - `Update` first opens Android's "Install unknown apps" switch when it is off; back with it
 *   on, the download starts by itself, even if Android restarted the app meanwhile;
 * - the download shows progress; `Cancel` stops it; a failed download offers `Retry`;
 * - then Android installs; if the user cancels Android's confirmation, nothing is shown
 *   until the next launch.
 */
class AppUpdater(
    private val current: AppVersion,
    private val kind: AppKind,
    private val source: UpdateSource,
    private val installer: UpdateInstaller,
    private val acceptedStore: AcceptedUpdateStore,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data class Available(val update: AppUpdate) : State
        /** In Android's "Install unknown apps" screen: nothing of ours is shown. */
        data class WaitingPermission(val update: AppUpdate) : State
        data class Downloading(val update: AppUpdate, val downloadedBytes: Long, val totalBytes: Long) : State
        data class Failed(val update: AppUpdate) : State
        /** Handed to Android's installer: nothing of ours is shown. */
        data class Installing(val update: AppUpdate) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentVersion: AppVersion get() = current

    private var job: Job? = null

    /**
     * Silent check (each launch). Ignored while an update is shown or downloading. A launch
     * while still "installing" means Android's confirmation was left unanswered: start over.
     * If the user already said `Update` to this version and the permission is now on (the app
     * was restarted by Android), the download starts straight away.
     */
    fun check() {
        if (_state.value is State.Installing || _state.value is State.WaitingPermission) {
            // Left unanswered: ask again rather than resuming by itself.
            job?.cancel()
            acceptedStore.accepted = null
            _state.value = State.Idle
        }
        if (_state.value != State.Idle || job?.isActive == true) return
        job = scope.launch {
            val release = try {
                source.latest()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // offline / GitHub down: stay silent
            }
            val update = release?.let { AppUpdate.from(it, current, kind) }
            if (update == null) {
                acceptedStore.accepted = null
                return@launch
            }
            if (_state.value != State.Idle) return@launch
            _state.value = State.Available(update)
            if (acceptedStore.accepted == update.version && installer.canInstall()) startDownload(update)
        }
    }

    /** `Update` (or `Retry` after a failure). */
    fun accept() {
        val update = when (val s = _state.value) {
            is State.Available -> s.update
            is State.Failed -> s.update
            else -> return
        }
        acceptedStore.accepted = update.version
        if (!installer.canInstall() && installer.requestInstallPermission()) {
            job?.cancel()
            _state.value = State.WaitingPermission(update)
            return
        }
        startDownload(update)
    }

    /**
     * The app is back on screen. From the "Install unknown apps" screen: switch on → download;
     * still off → the prompt again, so the user can still say `Not now`.
     */
    fun onResume() {
        val s = _state.value as? State.WaitingPermission ?: return
        if (installer.canInstall()) startDownload(s.update) else _state.value = State.Available(s.update)
    }

    /** `Not now`, `Cancel`, or a video starting on the TV: back to nothing until the next launch. */
    fun dismiss() {
        if (_state.value is State.Installing) return
        job?.cancel()
        acceptedStore.accepted = null
        _state.value = State.Idle
    }

    private fun startDownload(update: AppUpdate) {
        job?.cancel()
        _state.value = State.Downloading(update, 0, update.sizeBytes)
        job = scope.launch {
            try {
                installer.download(update) { done, total ->
                    if (_state.value is State.Downloading) {
                        _state.value = State.Downloading(update, done, if (total > 0) total else update.sizeBytes)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = State.Failed(update)
                return@launch
            }
            _state.value = State.Installing(update)
            val outcome = installer.install(update)
            if (outcome != InstallOutcome.FAILED) acceptedStore.accepted = null
            _state.value = if (outcome == InstallOutcome.FAILED) State.Failed(update) else State.Idle
        }
    }
}
