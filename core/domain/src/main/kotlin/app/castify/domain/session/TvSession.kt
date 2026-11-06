package app.castify.domain.session

import app.castify.domain.model.MediaStream
import app.castify.domain.model.NowPlaying
import app.castify.domain.model.TvDevice
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.port.TvClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The phone's connection to one TV (Netflix/YouTube style "Cast" button).
 *
 * Rules:
 * - while connected, the TV status is polled: every [activePollMs] while a video is loaded,
 *   every [idlePollMs] otherwise. Any phone sees what plays, whoever started it;
 * - sending a video while another one is loaded needs an explicit confirmation;
 * - commands go straight to the TV; the last phone to press wins;
 * - a failed poll keeps the connection and marks the TV unreachable.
 */
class TvSession(
    private val client: TvClient,
    private val scope: CoroutineScope,
    private val activePollMs: Long = 1_000,
    private val idlePollMs: Long = 3_000,
) {
    data class State(
        val device: TvDevice? = null,
        /** Null until the first successful poll. */
        val nowPlaying: NowPlaying? = null,
        val reachable: Boolean = true,
    ) {
        val connected: Boolean get() = device != null
    }

    sealed interface SendResult {
        data object Sent : SendResult
        /** A video is already loaded on the TV: ask the user, then call send(replace = true). */
        data object NeedsConfirmation : SendResult
        data class Failed(val reason: String) : SendResult
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun connect(device: TvDevice) {
        if (_state.value.device == device) return
        pollJob?.cancel()
        _state.value = State(device = device)
        pollJob = scope.launch {
            while (isActive) {
                pollOnce(device)
                delay(if (_state.value.nowPlaying?.isActive == true) activePollMs else idlePollMs)
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        _state.value = State()
    }

    /** Sends [stream] to the connected TV at the chosen [height] (null = only/unknown quality). */
    suspend fun send(stream: MediaStream, height: Int?, replace: Boolean = false): SendResult {
        val device = _state.value.device ?: return SendResult.Failed("Not connected to a TV")
        if (!replace) {
            val current = client.status(device).getOrElse { return SendResult.Failed(reasonOf(it, device)) }
            if (current.isActive) return SendResult.NeedsConfirmation
        }
        return client.play(device, stream.copy(preferredHeight = height)).fold(
            onSuccess = { pollOnce(device); SendResult.Sent },
            onFailure = { SendResult.Failed(reasonOf(it, device)) },
        )
    }

    fun control(command: PlayerCommand) {
        if (command.isTvOnly) return
        val device = _state.value.device ?: return
        scope.launch {
            client.control(device, command)
            pollOnce(device)
        }
    }

    /** Poll now (e.g. "Retry" after the TV was unreachable). */
    fun refresh() {
        val device = _state.value.device ?: return
        scope.launch { pollOnce(device) }
    }

    private suspend fun pollOnce(device: TvDevice) {
        val result = client.status(device)
        _state.update { current ->
            if (current.device != device) current // disconnected or switched meanwhile
            else result.fold(
                onSuccess = { current.copy(nowPlaying = it, reachable = true) },
                onFailure = { current.copy(reachable = false) },
            )
        }
    }

    private fun reasonOf(error: Throwable, device: TvDevice) =
        "Could not reach ${device.name}: ${error.message ?: error.javaClass.simpleName}"
}
