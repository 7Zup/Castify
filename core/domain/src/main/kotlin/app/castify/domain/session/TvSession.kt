package app.castify.domain.session

import app.castify.domain.model.MediaStream
import app.castify.domain.model.NowPlaying
import app.castify.domain.model.TvDevice
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.port.TvClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The phone's connection to one TV (Netflix/YouTube style "Cast" button).
 *
 * Rules:
 * - connecting = waiting for the TV's first answer. Only an answer makes the phone
 *   connected; no answer drops the connection and tells why ([ConnectFailed]);
 * - while connected, the TV status is polled: every [activePollMs] while a video is loaded,
 *   every [idlePollMs] otherwise. Any phone sees what plays, whoever started it;
 * - sending a video while another one is loaded needs an explicit confirmation;
 * - commands go straight to the TV; the last phone to press wins;
 * - once connected, a failed poll keeps the connection and marks the TV unreachable;
 * - the TV's address can change (DHCP): [follow] moves the session to the TV of the same
 *   name found at a new address.
 */
class TvSession(
    private val client: TvClient,
    private val scope: CoroutineScope,
    private val activePollMs: Long = 1_000,
    private val idlePollMs: Long = 3_000,
) {
    data class State(
        val device: TvDevice? = null,
        /** Waiting for the TV's first answer. */
        val connecting: Boolean = false,
        /** Null until the first successful poll. */
        val nowPlaying: NowPlaying? = null,
        val reachable: Boolean = true,
    ) {
        /** A TV is chosen (connecting or connected). */
        val connected: Boolean get() = device != null
        /** The TV answered and still answers: the Cast icon is red. */
        val live: Boolean get() = device != null && !connecting && reachable
    }

    sealed interface SendResult {
        data object Sent : SendResult
        /** A video is already loaded on the TV: ask the user, then call send(replace = true). */
        data object NeedsConfirmation : SendResult
        data class Failed(val reason: String) : SendResult
        /** The TV never answered: [ConnectFailed] already says so. */
        data object NotConnected : SendResult
    }

    /** The TV picked by the user never answered; the session is disconnected again. */
    data class ConnectFailed(val device: TvDevice)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _connectFailures = MutableSharedFlow<ConnectFailed>(extraBufferCapacity = 4)
    val connectFailures: SharedFlow<ConnectFailed> = _connectFailures.asSharedFlow()

    private var pollJob: Job? = null

    fun connect(device: TvDevice) {
        if (_state.value.device == device) return
        start(State(device = device, connecting = true))
    }

    /** Same TV, new address (same name): keep what plays, poll the new address. */
    fun follow(found: List<TvDevice>) {
        val current = _state.value
        val device = current.device ?: return
        val moved = found.firstOrNull { it.name == device.name && it != device } ?: return
        if (found.any { it == device }) return // still answering discovery at the old address
        start(current.copy(device = moved))
    }

    private fun start(initial: State) {
        pollJob?.cancel()
        _state.value = initial
        val device = initial.device ?: return
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
        if (_state.value.connecting) {
            val settled = _state.first { it.device != device || !it.connecting }
            if (settled.device != device) return SendResult.NotConnected
        }
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
        var failed = false
        _state.update { current ->
            when {
                current.device != device -> current // disconnected or switched meanwhile
                result.isSuccess -> current.copy(nowPlaying = result.getOrThrow(), reachable = true, connecting = false)
                current.connecting -> { failed = true; State() }
                else -> current.copy(reachable = false)
            }
        }
        if (failed) {
            pollJob?.cancel()
            _connectFailures.tryEmit(ConnectFailed(device))
        }
    }

    private fun reasonOf(error: Throwable, device: TvDevice) =
        "Could not reach ${device.name}: ${error.message ?: error.javaClass.simpleName}"
}
