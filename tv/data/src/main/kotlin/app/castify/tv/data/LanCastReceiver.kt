package app.castify.tv.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.castify.domain.model.MediaStream
import app.castify.domain.model.NowPlaying
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.port.CastReceiver
import app.castify.protocol.CastifyProtocol
import app.castify.protocol.ControlRequest
import app.castify.protocol.ErrorResponse
import app.castify.protocol.PlayRequest
import app.castify.protocol.StatusResponse
import app.castify.protocol.toMediaStream
import app.castify.protocol.toPlayerCommand
import app.castify.protocol.toStatusResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Receives play requests from phones: a small HTTP server on the LAN, advertised with
 * mDNS (NSD) under [CastifyProtocol.SERVICE_TYPE], and answering the phones' UDP discovery
 * broadcasts ([CastifyProtocol.DISCOVERY_PORT]).
 *
 * No authentication (user's choice): any device on the Wi-Fi can play, read the status
 * and control playback.
 */
class LanCastReceiver(context: Context) : CastReceiver {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifi.createMulticastLock("castify-receiver").apply { setReferenceCounted(false) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var responder: Job? = null
    @Volatile private var responderSocket: DatagramSocket? = null
    private val _playRequests = MutableSharedFlow<MediaStream>(extraBufferCapacity = 4)
    override val playRequests: SharedFlow<MediaStream> = _playRequests.asSharedFlow()
    private val _commands = MutableSharedFlow<PlayerCommand>(extraBufferCapacity = 16)
    override val commands: SharedFlow<PlayerCommand> = _commands.asSharedFlow()
    private val _lastPhoneCall = MutableStateFlow<Long?>(null)
    override val lastPhoneCallAtMs: StateFlow<Long?> = _lastPhoneCall.asStateFlow()
    private fun phoneCalled() { _lastPhoneCall.value = System.currentTimeMillis() }

    private var server: EmbeddedServer<*, *>? = null
    private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var status: () -> NowPlaying = { NowPlaying.IDLE }

    override fun start(deviceName: String, status: () -> NowPlaying) {
        this.status = status
        if (server != null) return
        server = embeddedServer(CIO, port = CastifyProtocol.DEFAULT_PORT, host = "0.0.0.0") { routes() }
            .also { it.start(wait = false) }
        runCatching { multicastLock.acquire() }
        startResponder(deviceName)
        advertise(deviceName, CastifyProtocol.DEFAULT_PORT, attempt = 1)
    }

    override fun stop() {
        main.removeCallbacksAndMessages(null)
        responder?.cancel()
        responder = null
        responderSocket?.close()
        runCatching { multicastLock.release() }
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
    }

    private fun Application.routes() = routing {
        get("/") { call.respondText("Castify TV") }
        post(CastifyProtocol.PATH_PLAY) {
            phoneCalled()
            val stream = runCatching {
                CastifyProtocol.json.decodeFromString(PlayRequest.serializer(), call.receiveText()).toMediaStream()
            }.getOrNull()
            if (stream == null) {
                call.respondText(errorJson("Invalid play request"), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } else {
                Log.i(TAG, "Play request: ${stream.type} ${stream.url}")
                _playRequests.emit(stream)
                call.respondText("{}", ContentType.Application.Json)
            }
        }
        get(CastifyProtocol.PATH_STATUS) {
            phoneCalled()
            val body = CastifyProtocol.json.encodeToString(StatusResponse.serializer(), status().toStatusResponse())
            call.respondText(body, ContentType.Application.Json)
        }
        post(CastifyProtocol.PATH_CONTROL) {
            phoneCalled()
            val command = runCatching {
                CastifyProtocol.json.decodeFromString(ControlRequest.serializer(), call.receiveText()).toPlayerCommand()
            }.getOrNull()
            if (command == null) {
                call.respondText(errorJson("Invalid control request"), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } else {
                _commands.emit(command)
                call.respondText("{}", ContentType.Application.Json)
            }
        }
    }

    private fun errorJson(message: String) =
        CastifyProtocol.json.encodeToString(ErrorResponse.serializer(), ErrorResponse(message))

    /** Answers "CASTIFY_DISCOVER_1" broadcasts with this TV's name; reopens its socket on errors. */
    private fun startResponder(deviceName: String) {
        responder = scope.launch {
            val reply = CastifyProtocol.discoveryReply(deviceName)
            while (isActive) {
                try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(CastifyProtocol.DISCOVERY_PORT))
                    }.use { socket ->
                        responderSocket = socket
                        val buffer = ByteArray(256)
                        while (isActive) {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            if (CastifyProtocol.isDiscoveryQuery(packet.data, packet.length)) {
                                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "Discovery responder: ${e.message}")
                    delay(2_000)
                }
            }
        }
    }

    /** A failed registration is retried (it used to stay invisible until the app was reopened). */
    private fun advertise(name: String, port: Int, attempt: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = CastifyProtocol.serviceNameFor(name)
            serviceType = CastifyProtocol.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Log.i(TAG, "Advertised as ${info.serviceName}").let { }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode (attempt $attempt)")
                if (attempt < MAX_REGISTER_ATTEMPTS) main.postDelayed({
                    if (registration === this) advertise(name, port, attempt + 1)
                }, REGISTER_RETRY_MS * attempt)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private companion object {
        const val TAG = "Castify"
        const val MAX_REGISTER_ATTEMPTS = 5
        const val REGISTER_RETRY_MS = 2_000L
    }
}
