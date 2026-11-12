package app.castify.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.castify.domain.model.MediaStream
import app.castify.domain.model.NowPlaying
import app.castify.domain.model.PlaybackState
import app.castify.domain.model.PlaybackStats
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.playback.RemoteControl
import app.castify.domain.subtitles.SubtitleMenu
import app.castify.domain.subtitles.SubtitleOption
import app.castify.domain.playback.RemoteKey
import app.castify.domain.port.CastReceiver
import app.castify.domain.port.Player
import app.castify.domain.port.NetworkMonitor
import app.castify.domain.port.TvSettings
import app.castify.domain.series.EpisodeNumber
import app.castify.domain.series.EpisodeRequest
import app.castify.domain.series.SeriesInfo
import app.castify.domain.status.TvPresence
import app.castify.domain.update.AppUpdater
import app.castify.domain.update.ReleaseNote
import app.castify.domain.update.WhatsNew
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import app.castify.domain.usecase.HandlePlayerCommand
import app.castify.domain.usecase.PlayStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TvScreen { WAITING, PLAYING, ERROR }

/** Buttons of the error screen (mockup T1 / T2): Retry restarts where it stopped, Home stops. */
enum class ErrorButton { RETRY, HOME }

/** "What's new" pop-up buttons (mockup `whats-new.html`, T1): OK first. */
enum class WhatsNewButton { OK, ALL_VERSIONS }

/** Update dialog buttons (mockup `app-update.html`, T1 / T3): Update or Retry first, then Not now. */
enum class UpdateButton { PRIMARY, NOT_NOW }

/** Subtitle menu opened from the `CC` button: same options and order as the phone ([SubtitleMenu]). */
data class SubtitleMenuState(val options: List<SubtitleOption>, val focused: Int)

/**
 * Netflix-style player controls (▼). The progress bar sits above the button row; no Stop
 * button (user: Back / Home already stop). Room is left for episode buttons later.
 */
enum class ControlFocus { PROGRESS, BACK_10, PLAY_PAUSE, FORWARD_10, SUBTITLES, EPISODES }

/** EPISODES only shows for a series. */
val CONTROL_BUTTONS = listOf(ControlFocus.BACK_10, ControlFocus.PLAY_PAUSE, ControlFocus.FORWARD_10, ControlFocus.SUBTITLES, ControlFocus.EPISODES)

/** An episode the TV asked the phone for (mockup T5): loading, then failed with Retry / Back. */
data class EpisodeLoad(val number: EpisodeNumber, val failed: Boolean = false, val focus: ErrorButton = ErrorButton.RETRY)

/**
 * Rows of the Episodes panel (mockup T2), top to bottom: Auto next sits right of the series
 * title (user). The panel opens on the playing episode.
 */
enum class EpisodesRow { AUTO_NEXT, SEASONS, EPISODES }

data class EpisodesPanelState(val season: Int, val row: EpisodesRow, val episodeIndex: Int)

data class ControlsState(val focus: ControlFocus = ControlFocus.PLAY_PAUSE)

data class TvUiState(
    val deviceName: String = "",
    val screen: TvScreen = TvScreen.WAITING,
    val current: MediaStream? = null,
    val stats: PlaybackStats = PlaybackStats(),
    val diagnosticsVisible: Boolean = false,
    /** Null = closed. */
    val subtitleMenu: SubtitleMenuState? = null,
    /** Time bar shown for [OSD_AFTER_SEEK_MS] after a seek (it is always shown while paused). */
    val timeBarAfterSeek: Boolean = false,
    /** Cumulative ±10 s jumps ("+30 s"), 0 = no bubble. */
    val seekBubbleMs: Long = 0,
    /** Null = controls hidden (remote shortcuts apply). */
    val controls: ControlsState? = null,
    /** Back hid the pause time bar; it shows again at the next pause. */
    val pausedBarDismissed: Boolean = false,
    /** First Back with nothing on screen: "Press Back again to stop the video". */
    val stopArmed: Boolean = false,
    /** Welcome screen state (dot + label): Offline / Standby / Connected. */
    val presence: TvPresence = TvPresence.STANDBY,
    /** Error screen: which button has the focus (Retry first). */
    val errorFocus: ErrorButton = ErrorButton.RETRY,
    /** Where the video was and which subtitle was on, for Retry. */
    val lastPositionMs: Long = 0,
    val lastSubtitleId: String? = null,
    /** In-app update, shown on the welcome screen only. */
    val update: AppUpdater.State = AppUpdater.State.Idle,
    val updateFocus: UpdateButton = UpdateButton.PRIMARY,
    /** `v0.5.0`, bottom right of the welcome screen. */
    val versionLabel: String = "",
    /** TV setting shared by every phone: the next episode starts by itself at the end. */
    val autoNext: Boolean = false,
    /** Asked to the phones through the status, until a new video arrives. */
    val episodeRequest: EpisodeRequest? = null,
    val episodeLoad: EpisodeLoad? = null,
    /** Null = closed. */
    val episodesPanel: EpisodesPanelState? = null,
    /** Back hid `Next` for this episode. */
    val nextDismissed: Boolean = false,
    /** Auto next countdown, seconds left; null = none. */
    val nextCountdown: Int? = null,
    /** "What's new" pop-up after an update (T1); null = none. */
    val whatsNew: ReleaseNote? = null,
    val whatsNewFocus: WhatsNewButton = WhatsNewButton.OK,
    /** Every version's notes (T2): null = closed, else the first visible version. */
    val releaseNotesFrom: Int? = null,
    /** ▼ on the welcome screen focuses the version label; OK opens every version's notes. */
    val versionFocused: Boolean = false,
) {
    val series: SeriesInfo? get() = current?.series

    /** `Next episode` (mockup T3 / T4): the last 30 s, or the end, when the next episode is known. */
    val nextVisible: Boolean
        get() {
            if (series?.next == null || nextDismissed || episodeLoad != null) return false
            if (controls != null || episodesPanel != null || subtitleMenu != null || stats.isLive) return false
            if (stats.state == PlaybackState.ENDED) return true
            val duration = stats.durationMs ?: return false
            val playing = stats.state == PlaybackState.PLAYING || stats.state == PlaybackState.PAUSED || stats.state == PlaybackState.BUFFERING
            return playing && duration - stats.positionMs in 0..NEXT_BEFORE_END_MS
        }

    /** Update dialog on screen: welcome screen + something to say (Android's own UI once installing). */
    val updateDialogVisible: Boolean
        get() = screen == TvScreen.WAITING && whatsNew == null && releaseNotesFrom == null &&
            (update is AppUpdater.State.Available || update is AppUpdater.State.Downloading || update is AppUpdater.State.Failed)

    val timeBarVisible: Boolean
        get() = timeBarAfterSeek || (stats.state == PlaybackState.PAUSED && !pausedBarDismissed)

    /** Something drawn over the video that Back should remove first. */
    val overlayVisible: Boolean
        get() = controls != null || subtitleMenu != null || timeBarVisible || diagnosticsVisible || seekBubbleMs != 0L
}

/** User rule: `Next episode` shows 30 s before the end. */
const val NEXT_BEFORE_END_MS = 30_000L
/** Auto next waits this long at the end, Back cancels (mockup T4). */
const val AUTO_NEXT_COUNTDOWN_S = 10
/** No new video from a phone in this time = the episode failed. */
const val EPISODE_REQUEST_TIMEOUT_MS = 60_000L

/** User choice: the time bar stays 3 s after the last ◀ / ▶. */
const val OSD_AFTER_SEEK_MS = 3_000L
/** Controls hide after 5 s without a key press, never while paused or in the subtitle menu. */
const val CONTROLS_IDLE_MS = 5_000L
/** Window for the second Back that stops the video (user: an accidental Back must not stop it). */
const val STOP_CONFIRM_MS = 3_000L
/** Same as the phone remote: the "+30 s" bubble resets 1.2 s after the last press. */
const val SEEK_BUBBLE_MS = 1_200L

class TvViewModel(
    private val receiver: CastReceiver,
    private val player: Player,
    private val playStream: PlayStream,
    private val handlePlayerCommand: HandlePlayerCommand,
    private val network: NetworkMonitor,
    private val updater: AppUpdater,
    private val settings: TvSettings,
    private val whatsNew: WhatsNew,
    /** The welcome screen shows (again): check for an update (throttled by the caller). */
    private val onHomeShown: () -> Unit,
) : ViewModel() {

    /** Every version's notes, newest first (T2). */
    val releaseNotes: List<ReleaseNote> = whatsNew.all

    /** Subtitle to turn back on once the retried stream lists its tracks. */
    private var pendingSubtitleId: String? = null

    private val _state = MutableStateFlow(
        TvUiState(versionLabel = updater.currentVersion.label, autoNext = settings.autoNext, whatsNew = whatsNew.popup()),
    )
    val state: StateFlow<TvUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            receiver.playRequests.collect { stream ->
                // A video wins over the update prompt (the install would stop it): next launch asks again.
                updater.dismiss()
                pendingSubtitleId = null
                countdownJob?.cancel()
                requestTimeoutJob?.cancel()
                _state.update {
                    it.copy(
                        screen = TvScreen.PLAYING,
                        lastPositionMs = 0,
                        lastSubtitleId = null,
                        current = stream,
                        subtitleMenu = null,
                        timeBarAfterSeek = false,
                        seekBubbleMs = 0,
                        controls = null,
                        pausedBarDismissed = false,
                        stopArmed = false,
                        episodeRequest = null,
                        episodeLoad = null,
                        episodesPanel = null,
                        nextDismissed = false,
                        nextCountdown = null,
                    )
                }
                playStream(stream)
            }
        }
        // Commands from phones go through the same path as the TV remote (time bar included).
        viewModelScope.launch {
            receiver.commands.collect { command ->
                when (command) {
                    is PlayerCommand.SetAutoNext -> setAutoNext(command.enabled)
                    is PlayerCommand.EpisodeFailed -> onEpisodeFailed(command.requestId)
                    else -> if (_state.value.screen == TvScreen.PLAYING) apply(command)
                }
            }
        }
        viewModelScope.launch {
            player.stats.collect { stats ->
                _state.update {
                    val screen = when {
                        stats.state == PlaybackState.ERROR -> TvScreen.ERROR
                        it.screen == TvScreen.ERROR -> TvScreen.ERROR
                        stats.state == PlaybackState.IDLE -> TvScreen.WAITING
                        else -> it.screen
                    }
                    val playing = screen == TvScreen.PLAYING
                    it.copy(
                        stats = stats,
                        screen = screen,
                        errorFocus = if (screen == TvScreen.ERROR && it.screen != TvScreen.ERROR) ErrorButton.RETRY else it.errorFocus,
                        // Remembered for Retry while the video really plays (the error resets nothing).
                        lastPositionMs = if (playing && stats.positionMs > 0) stats.positionMs else it.lastPositionMs,
                        lastSubtitleId = if (playing && pendingSubtitleId == null) stats.selectedSubtitleId else it.lastSubtitleId,
                        // A dismissed pause bar comes back at the next pause.
                        pausedBarDismissed = it.pausedBarDismissed && stats.state == PlaybackState.PAUSED,
                        stopArmed = it.stopArmed && screen == TvScreen.PLAYING,
                        subtitleMenu = it.subtitleMenu.takeIf { screen == TvScreen.PLAYING },
                        controls = it.controls.takeIf { screen == TvScreen.PLAYING },
                        episodesPanel = it.episodesPanel.takeIf { screen == TvScreen.PLAYING },
                    )
                }
                maybeCountDown()
            }
        }
    }

    init {
        // Back on the welcome screen (video ended or stopped): look for an update.
        viewModelScope.launch {
            var previous = _state.value.screen
            _state.collect { s ->
                if (s.screen == TvScreen.WAITING && previous != TvScreen.WAITING) onHomeShown()
                previous = s.screen
            }
        }
        // Welcome screen state: re-evaluated every second (a phone stops calling = Standby).
        val ticker = flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(1_000) } }
        viewModelScope.launch {
            combine(network.online, receiver.lastPhoneCallAtMs, ticker) { online, lastCall, _ ->
                TvPresence.of(online, lastCall, System.currentTimeMillis())
            }.collect { presence -> _state.update { it.copy(presence = presence) } }
        }
        // Update dialog: the focus goes back to Update / Retry each time its content changes.
        viewModelScope.launch {
            updater.state.collect { update ->
                _state.update {
                    val reset = update::class != it.update::class
                    it.copy(update = update, updateFocus = if (reset) UpdateButton.PRIMARY else it.updateFocus)
                }
            }
        }
        // Retry: turn the subtitle back on once the new stream lists its tracks.
        viewModelScope.launch {
            player.stats.collect { stats ->
                val id = pendingSubtitleId ?: return@collect
                if (stats.subtitles.any { it.id == id }) {
                    pendingSubtitleId = null
                    player.selectSubtitle(id)
                }
            }
        }
    }

    /** Error screen, Retry: same video, same place, same subtitle. Nothing retries by itself. */
    private fun retry() {
        val s = _state.value
        val stream = s.current ?: return home()
        pendingSubtitleId = s.lastSubtitleId
        _state.update {
            it.copy(screen = TvScreen.PLAYING, controls = null, subtitleMenu = null, stopArmed = false, timeBarAfterSeek = false, seekBubbleMs = 0)
        }
        playStream(stream, s.lastPositionMs)
    }

    /** Error screen, Home (or Back): stop, back to the welcome screen. */
    private fun home() {
        pendingSubtitleId = null
        player.stop()
        _state.update { it.copy(screen = TvScreen.WAITING, current = null, lastPositionMs = 0, lastSubtitleId = null) }
    }

    fun start(deviceName: String) {
        _state.update { it.copy(deviceName = deviceName) }
        // The app comes back to the front (it rarely really closes on a Chromecast).
        if (_state.value.screen == TvScreen.WAITING) onHomeShown()
        receiver.start(deviceName) {
            val current = _state.value
            if (current.screen == TvScreen.PLAYING) NowPlaying.from(player.stats.value, current.current, current.autoNext, current.episodeRequest)
            else NowPlaying.IDLE.copy(autoNext = current.autoNext)
        }
    }

    fun stop() {
        receiver.stop()
        player.stop()
        _state.update { it.copy(screen = TvScreen.WAITING, current = null) }
    }

    /** Returns true when the key was consumed. Unhandled keys (Back while waiting) go to the system. */
    fun onKey(key: RemoteKey): Boolean {
        return when (_state.value.screen) {
            TvScreen.WAITING -> onWelcomeKey(key)
            TvScreen.ERROR -> when (key) {
                RemoteKey.LEFT -> { _state.update { it.copy(errorFocus = ErrorButton.RETRY) }; true }
                RemoteKey.RIGHT -> { _state.update { it.copy(errorFocus = ErrorButton.HOME) }; true }
                RemoteKey.OK -> { if (_state.value.errorFocus == ErrorButton.RETRY) retry() else home(); true }
                RemoteKey.BACK -> { home(); true }
                else -> true
            }
            TvScreen.PLAYING -> {
                _state.value.episodeLoad?.let { return onEpisodeLoadKey(it, key) }
                _state.value.subtitleMenu?.let { return onMenuKey(it, key).also { touchControls() } }
                _state.value.episodesPanel?.let { return onPanelKey(it, key).also { touchControls() } }
                _state.value.controls?.let { return onControlsKey(it, key) }
                if (_state.value.nextVisible) {
                    when (key) {
                        RemoteKey.OK -> { _state.value.series?.next?.let { requestEpisode(it.number) }; return true }
                        RemoteKey.BACK -> { dismissNext(); return true }
                        else -> Unit
                    }
                }
                if (key == RemoteKey.BACK) return onBackWithoutControls()
                when (val command = RemoteControl.commandFor(key) ?: return false) {
                    PlayerCommand.ToggleDiagnostics -> _state.update { it.copy(diagnosticsVisible = !it.diagnosticsVisible) }
                    PlayerCommand.ShowControls -> showControls()
                    PlayerCommand.OpenSubtitleMenu -> openSubtitleMenu()
                    // OK while playing = pause AND show the controls (user request): the next OK
                    // then lands on Play/Pause and resumes.
                    PlayerCommand.TogglePause -> {
                        val pausing = _state.value.stats.state != PlaybackState.PAUSED
                        apply(command)
                        if (pausing) showControls()
                    }
                    else -> apply(command)
                }
                true
            }
        }
    }

    /**
     * Update dialog: ◀ ▶ move between Update / Retry and Not now, OK presses, Back = Not now
     * (or Cancel while downloading, its only button). Every key is swallowed while it shows.
     */
    private fun onUpdateKey(key: RemoteKey): Boolean {
        val s = _state.value
        val downloading = s.update is AppUpdater.State.Downloading
        when (key) {
            RemoteKey.LEFT -> if (!downloading) _state.update { it.copy(updateFocus = UpdateButton.PRIMARY) }
            RemoteKey.RIGHT -> if (!downloading) _state.update { it.copy(updateFocus = UpdateButton.NOT_NOW) }
            RemoteKey.OK -> if (downloading || s.updateFocus == UpdateButton.NOT_NOW) updater.dismiss() else updater.accept()
            RemoteKey.BACK -> updater.dismiss()
            RemoteKey.UP, RemoteKey.DOWN -> Unit
        }
        return true
    }

    /**
     * Welcome screen, in order: every version's notes (▲ ▼ scroll, OK / Back close), the
     * "What's new" pop-up (◀ ▶, OK; Back = OK), the update dialog, else ▼ / ▲ move the focus
     * to / from the version label and OK on it opens every version's notes.
     */
    private fun onWelcomeKey(key: RemoteKey): Boolean {
        val s = _state.value
        s.releaseNotesFrom?.let { from ->
            when (key) {
                RemoteKey.UP -> _state.update { it.copy(releaseNotesFrom = (from - 1).coerceAtLeast(0)) }
                RemoteKey.DOWN -> _state.update { it.copy(releaseNotesFrom = (from + 1).coerceAtMost((releaseNotes.size - 1).coerceAtLeast(0))) }
                RemoteKey.OK, RemoteKey.BACK -> _state.update { it.copy(releaseNotesFrom = null) }
                RemoteKey.LEFT, RemoteKey.RIGHT -> Unit
            }
            return true
        }
        if (s.whatsNew != null) {
            when (key) {
                RemoteKey.LEFT -> _state.update { it.copy(whatsNewFocus = WhatsNewButton.OK) }
                RemoteKey.RIGHT -> _state.update { it.copy(whatsNewFocus = WhatsNewButton.ALL_VERSIONS) }
                RemoteKey.OK -> closeWhatsNew(openAll = s.whatsNewFocus == WhatsNewButton.ALL_VERSIONS)
                RemoteKey.BACK -> closeWhatsNew(openAll = false)
                RemoteKey.UP, RemoteKey.DOWN -> Unit
            }
            return true
        }
        if (s.updateDialogVisible) return onUpdateKey(key)
        return when (key) {
            RemoteKey.DOWN -> { _state.update { it.copy(versionFocused = true) }; true }
            RemoteKey.UP -> { _state.update { it.copy(versionFocused = false) }; true }
            RemoteKey.OK -> {
                if (s.versionFocused) _state.update { it.copy(releaseNotesFrom = 0) }
                s.versionFocused
            }
            RemoteKey.BACK -> if (s.versionFocused) { _state.update { it.copy(versionFocused = false) }; true } else false
            RemoteKey.LEFT, RemoteKey.RIGHT -> false
        }
    }

    private fun closeWhatsNew(openAll: Boolean) {
        whatsNew.markSeen()
        _state.update { it.copy(whatsNew = null, releaseNotesFrom = if (openAll) 0 else null) }
    }

    private var stopConfirmJob: Job? = null

    /**
     * Back, controls hidden: (1) anything over the video (time bar, bubble, diagnostics) →
     * hide it, the video goes on; (2) nothing → arm "Press Back again to stop the video" for
     * [STOP_CONFIRM_MS]; (3) armed → stop.
     */
    private fun onBackWithoutControls(): Boolean {
        val s = _state.value
        when {
            s.overlayVisible -> {
                timeBarJob?.cancel()
                bubbleJob?.cancel()
                _state.update {
                    it.copy(
                        timeBarAfterSeek = false,
                        pausedBarDismissed = it.stats.state == PlaybackState.PAUSED,
                        diagnosticsVisible = false,
                        seekBubbleMs = 0,
                    )
                }
            }
            s.stopArmed -> {
                stopConfirmJob?.cancel()
                _state.update { it.copy(stopArmed = false) }
                apply(PlayerCommand.Stop)
            }
            else -> {
                _state.update { it.copy(stopArmed = true) }
                stopConfirmJob?.cancel()
                stopConfirmJob = viewModelScope.launch {
                    delay(STOP_CONFIRM_MS)
                    _state.update { it.copy(stopArmed = false) }
                }
            }
        }
        return true
    }

    private var controlsJob: Job? = null
    @Volatile private var lastControlsKeyAt = 0L

    private fun showControls() {
        _state.update { it.copy(controls = ControlsState(), timeBarAfterSeek = false) }
        touchControls()
        controlsJob?.cancel()
        controlsJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val s = _state.value
                if (s.controls == null) break
                val idle = System.currentTimeMillis() - lastControlsKeyAt > CONTROLS_IDLE_MS
                if (idle && s.subtitleMenu == null && s.episodesPanel == null && s.stats.state != PlaybackState.PAUSED) {
                    hideControls()
                    break
                }
            }
        }
    }

    private fun touchControls() {
        lastControlsKeyAt = System.currentTimeMillis()
    }

    private fun hideControls() = _state.update { it.copy(controls = null) }

    /**
     * Inside the controls: ◀ ▶ move between buttons (or seek ±10 s on the progress bar),
     * ▲ ▼ switch between progress bar and buttons, OK activates, Back hides the controls.
     */
    private fun onControlsKey(controls: ControlsState, key: RemoteKey): Boolean {
        touchControls()
        val stats = _state.value.stats
        val seekable = !stats.isLive && (stats.durationMs ?: 0) > 0
        fun focus(f: ControlFocus) = _state.update { it.copy(controls = controls.copy(focus = f)) }
        when (key) {
            RemoteKey.BACK -> hideControls()
            RemoteKey.UP -> if (controls.focus != ControlFocus.PROGRESS && seekable) focus(ControlFocus.PROGRESS)
            RemoteKey.DOWN -> if (controls.focus == ControlFocus.PROGRESS) focus(ControlFocus.PLAY_PAUSE)
            RemoteKey.LEFT, RemoteKey.RIGHT -> {
                val forward = key == RemoteKey.RIGHT
                if (controls.focus == ControlFocus.PROGRESS) {
                    apply(PlayerCommand.SeekBy(if (forward) RemoteControl.SEEK_STEP_MS else -RemoteControl.SEEK_STEP_MS))
                } else {
                    val hasSeries = _state.value.series != null
                    val buttons = CONTROL_BUTTONS.filter {
                        (seekable || (it != ControlFocus.BACK_10 && it != ControlFocus.FORWARD_10)) && (hasSeries || it != ControlFocus.EPISODES)
                    }
                    val index = buttons.indexOf(controls.focus).coerceAtLeast(0)
                    focus(buttons[(index + if (forward) 1 else -1).coerceIn(0, buttons.lastIndex)])
                }
            }
            RemoteKey.OK -> when (controls.focus) {
                ControlFocus.PROGRESS, ControlFocus.PLAY_PAUSE -> apply(PlayerCommand.TogglePause)
                ControlFocus.BACK_10 -> apply(PlayerCommand.SeekBy(-RemoteControl.SEEK_STEP_MS))
                ControlFocus.FORWARD_10 -> apply(PlayerCommand.SeekBy(RemoteControl.SEEK_STEP_MS))
                ControlFocus.SUBTITLES -> openSubtitleMenu()
                ControlFocus.EPISODES -> openEpisodesPanel()
            }
        }
        return true
    }

    private var timeBarJob: Job? = null
    private var bubbleJob: Job? = null

    /** Applies a player command; seeks show the time bar, ±10 s also feed the bubble. */
    private fun apply(command: PlayerCommand) {
        handlePlayerCommand(command)
        when (command) {
            is PlayerCommand.SeekBy -> onSeek(command.deltaMs)
            is PlayerCommand.SeekTo -> onSeek(null)
            else -> Unit
        }
    }

    private fun onSeek(deltaMs: Long?) {
        _state.update { s ->
            val bubble = when {
                deltaMs == null -> s.seekBubbleMs
                s.seekBubbleMs != 0L && (s.seekBubbleMs > 0) != (deltaMs > 0) -> deltaMs // direction changed
                else -> s.seekBubbleMs + deltaMs
            }
            s.copy(timeBarAfterSeek = true, seekBubbleMs = bubble)
        }
        timeBarJob?.cancel()
        timeBarJob = viewModelScope.launch {
            delay(OSD_AFTER_SEEK_MS)
            _state.update { it.copy(timeBarAfterSeek = false) }
        }
        if (deltaMs != null) {
            bubbleJob?.cancel()
            bubbleJob = viewModelScope.launch {
                delay(SEEK_BUBBLE_MS)
                _state.update { it.copy(seekBubbleMs = 0) }
            }
        }
    }

    private fun openSubtitleMenu() {
        val stats = player.stats.value
        val options = SubtitleMenu.options(stats.subtitles)
        val focused = options.indexOfFirst { it.trackId == stats.selectedSubtitleId }.coerceAtLeast(0)
        _state.update { it.copy(subtitleMenu = SubtitleMenuState(options, focused)) }
    }

    /** ▲ ▼ move, OK picks and closes, Back closes without change. Other keys are swallowed. */
    private fun onMenuKey(menu: SubtitleMenuState, key: RemoteKey): Boolean {
        when (key) {
            RemoteKey.UP -> _state.update { it.copy(subtitleMenu = menu.copy(focused = (menu.focused - 1).coerceAtLeast(0))) }
            RemoteKey.DOWN -> _state.update {
                it.copy(subtitleMenu = menu.copy(focused = (menu.focused + 1).coerceAtMost(menu.options.lastIndex)))
            }
            RemoteKey.OK -> {
                // Only "Off" in the list = no subtitles: OK just closes.
                if (menu.options.size > 1) handlePlayerCommand(PlayerCommand.SelectSubtitle(menu.options[menu.focused].trackId))
                _state.update { it.copy(subtitleMenu = null) }
            }
            RemoteKey.BACK -> _state.update { it.copy(subtitleMenu = null) }
            RemoteKey.LEFT, RemoteKey.RIGHT -> Unit
        }
        return true
    }

    // ---------- TV shows (mockup docs/mockups/episodes.html, T1–T5) ----------

    private var countdownJob: Job? = null
    private var requestTimeoutJob: Job? = null

    /** Asks the phones for [number] (through the status); the phone that sent the series answers. */
    private fun requestEpisode(number: EpisodeNumber) {
        val id = System.currentTimeMillis()
        countdownJob?.cancel()
        _state.update {
            it.copy(
                episodeRequest = EpisodeRequest(id, number),
                episodeLoad = EpisodeLoad(number),
                episodesPanel = null,
                controls = null,
                nextCountdown = null,
            )
        }
        requestTimeoutJob?.cancel()
        requestTimeoutJob = viewModelScope.launch {
            delay(EPISODE_REQUEST_TIMEOUT_MS)
            onEpisodeFailed(id)
        }
    }

    private fun onEpisodeFailed(requestId: Long) {
        _state.update {
            if (it.episodeRequest?.id != requestId) it
            else it.copy(episodeRequest = null, episodeLoad = it.episodeLoad?.copy(failed = true, focus = ErrorButton.RETRY))
        }
    }

    /** Loading: Back cancels. Failed: ◀ ▶ Retry / Back, OK presses, Back closes. */
    private fun onEpisodeLoadKey(load: EpisodeLoad, key: RemoteKey): Boolean {
        fun close() {
            requestTimeoutJob?.cancel()
            _state.update { it.copy(episodeRequest = null, episodeLoad = null) }
        }
        if (!load.failed) {
            if (key == RemoteKey.BACK) close()
            return true
        }
        when (key) {
            RemoteKey.LEFT -> _state.update { it.copy(episodeLoad = load.copy(focus = ErrorButton.RETRY)) }
            RemoteKey.RIGHT -> _state.update { it.copy(episodeLoad = load.copy(focus = ErrorButton.HOME)) }
            RemoteKey.OK -> if (load.focus == ErrorButton.RETRY) requestEpisode(load.number) else close()
            RemoteKey.BACK -> close()
            RemoteKey.UP, RemoteKey.DOWN -> Unit
        }
        return true
    }

    private fun setAutoNext(enabled: Boolean) {
        settings.autoNext = enabled
        if (!enabled) countdownJob?.cancel()
        _state.update { it.copy(autoNext = enabled, nextCountdown = if (enabled) it.nextCountdown else null) }
    }

    /** Back on `Next`: hidden for this episode, and no Auto next. */
    private fun dismissNext() {
        countdownJob?.cancel()
        _state.update { it.copy(nextDismissed = true, nextCountdown = null) }
    }

    /** Auto next: at the end, [AUTO_NEXT_COUNTDOWN_S] s on `Next`, then the next episode. */
    private fun maybeCountDown() {
        val s = _state.value
        val next = s.series?.next
        val due = s.autoNext && next != null && s.stats.state == PlaybackState.ENDED && s.nextVisible
        if (!due) {
            if (s.nextCountdown != null && s.stats.state != PlaybackState.ENDED) {
                countdownJob?.cancel()
                _state.update { it.copy(nextCountdown = null) }
            }
            return
        }
        if (countdownJob?.isActive == true) return
        countdownJob = viewModelScope.launch {
            for (left in AUTO_NEXT_COUNTDOWN_S downTo 1) {
                _state.update { it.copy(nextCountdown = left) }
                delay(1_000)
            }
            requestEpisode(next!!.number)
        }
    }

    private fun openEpisodesPanel() {
        val series = _state.value.series ?: return
        val season = series.current.season
        val index = series.show.episodesOf(season).indexOfFirst { it.number == series.current }.coerceAtLeast(0)
        val row = if (series.episodes.isEmpty()) EpisodesRow.AUTO_NEXT else EpisodesRow.EPISODES
        _state.update { it.copy(episodesPanel = EpisodesPanelState(season, row, index)) }
    }

    /**
     * Episodes panel: ▲ ▼ between seasons, episodes and Auto next; ◀ ▶ inside a row; OK picks
     * the season / plays the episode / ticks Auto next; Back closes. Other keys are swallowed.
     */
    private fun onPanelKey(panel: EpisodesPanelState, key: RemoteKey): Boolean {
        val series = _state.value.series ?: return true.also { _state.update { it.copy(episodesPanel = null) } }
        val show = series.show
        val seasons = show.seasons
        val episodes = show.episodesOf(panel.season)
        val rows = if (series.episodes.isEmpty()) listOf(EpisodesRow.AUTO_NEXT) else EpisodesRow.entries
        fun set(p: EpisodesPanelState) = _state.update { it.copy(episodesPanel = p) }
        when (key) {
            RemoteKey.UP -> set(panel.copy(row = rows[(rows.indexOf(panel.row) - 1).coerceAtLeast(0)]))
            RemoteKey.DOWN -> set(panel.copy(row = rows[(rows.indexOf(panel.row) + 1).coerceAtMost(rows.lastIndex)]))
            RemoteKey.LEFT, RemoteKey.RIGHT -> {
                val step = if (key == RemoteKey.RIGHT) 1 else -1
                when (panel.row) {
                    EpisodesRow.SEASONS -> {
                        val i = (seasons.indexOf(panel.season) + step).coerceIn(0, seasons.lastIndex)
                        set(panel.copy(season = seasons[i], episodeIndex = 0))
                    }
                    EpisodesRow.EPISODES -> set(panel.copy(episodeIndex = (panel.episodeIndex + step).coerceIn(0, (episodes.size - 1).coerceAtLeast(0))))
                    EpisodesRow.AUTO_NEXT -> Unit
                }
            }
            RemoteKey.OK -> when (panel.row) {
                EpisodesRow.SEASONS -> set(panel.copy(row = EpisodesRow.EPISODES))
                EpisodesRow.EPISODES -> episodes.getOrNull(panel.episodeIndex)?.let { episode ->
                    if (episode.number == series.current) _state.update { it.copy(episodesPanel = null) }
                    else requestEpisode(episode.number)
                }
                EpisodesRow.AUTO_NEXT -> setAutoNext(!_state.value.autoNext)
            }
            RemoteKey.BACK -> _state.update { it.copy(episodesPanel = null) }
        }
        return true
    }

    override fun onCleared() {
        receiver.stop()
        player.release()
    }

    companion object {
        fun factory(container: TvContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TvViewModel(
                    container.receiver,
                    container.player,
                    container.playStream,
                    container.handlePlayerCommand,
                    container.network,
                    container.updater,
                    container.settings,
                    container.whatsNew,
                    container::checkForUpdate,
                )
            }
        }
    }
}
