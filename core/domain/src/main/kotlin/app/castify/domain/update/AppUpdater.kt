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
    /** Downloads [update]'s APK, reporting bytes as they arrive. Throws on failure; cancellable. */
    suspend fun download(update: AppUpdate, onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit)

    /**
     * Installs the downloaded APK. Android shows its own confirmation (and, the first time, the
     * "Install unknown apps" permission). Returns once the user answered; on success the app
     * is killed and restarted by the system, so it usually never returns.
     */
    suspend fun install(update: AppUpdate): InstallOutcome
}

enum class InstallOutcome { INSTALLED, CANCELLED, FAILED }

/**
 * In-app update (mockup `docs/mockups/app-update.html`).
 *
 * Rules:
 * - [check] runs silently at each launch: up to date, no release, or source unreachable →
 *   nothing is shown;
 * - `Not now` ([dismiss]) closes the prompt; the next launch asks again;
 * - `Update` downloads with progress; `Cancel` stops it; a failed download offers `Retry`;
 * - then Android installs; if the user cancels Android's confirmation, nothing is shown
 *   until the next launch.
 */
class AppUpdater(
    private val current: AppVersion,
    private val kind: AppKind,
    private val source: UpdateSource,
    private val installer: UpdateInstaller,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data class Available(val update: AppUpdate) : State
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
     */
    fun check() {
        if (_state.value is State.Installing) {
            job?.cancel()
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
            val update = release?.let { AppUpdate.from(it, current, kind) } ?: return@launch
            if (_state.value == State.Idle) _state.value = State.Available(update)
        }
    }

    /** `Update` (or `Retry` after a failure). */
    fun accept() {
        val update = when (val s = _state.value) {
            is State.Available -> s.update
            is State.Failed -> s.update
            else -> return
        }
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
            _state.value = when (installer.install(update)) {
                InstallOutcome.FAILED -> State.Failed(update)
                InstallOutcome.INSTALLED, InstallOutcome.CANCELLED -> State.Idle
            }
        }
    }

    /** `Not now`, `Cancel`, or a video starting on the TV: back to nothing until the next launch. */
    fun dismiss() {
        if (_state.value is State.Installing) return
        job?.cancel()
        _state.value = State.Idle
    }
}
