package app.castify.phone.data

import app.castify.domain.model.MediaStream
import app.castify.domain.model.NowPlaying
import app.castify.domain.model.TvDevice
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.port.TvClient
import app.castify.protocol.CastifyProtocol
import app.castify.protocol.ControlRequest
import app.castify.protocol.ErrorResponse
import app.castify.protocol.PlayRequest
import app.castify.protocol.StatusResponse
import app.castify.protocol.toControlRequest
import app.castify.protocol.toNowPlaying
import app.castify.protocol.toPlayRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the TV app over plain HTTP on the LAN (see `CastifyProtocol`). */
/** [open] lets the app send TV traffic through the Wi-Fi ([WifiNetwork.open]). */
class HttpTvClient(private val open: (URL) -> java.net.URLConnection = { it.openConnection() }) : TvClient {

    override suspend fun play(device: TvDevice, stream: MediaStream): Result<Unit> =
        post(device, CastifyProtocol.PATH_PLAY, CastifyProtocol.json.encodeToString(PlayRequest.serializer(), stream.toPlayRequest()))

    override suspend fun status(device: TvDevice): Result<NowPlaying> =
        request(device, CastifyProtocol.PATH_STATUS, method = "GET", body = null, readTimeoutMs = 2_500)
            .map { CastifyProtocol.json.decodeFromString(StatusResponse.serializer(), it).toNowPlaying() }

    override suspend fun control(device: TvDevice, command: PlayerCommand): Result<Unit> {
        val request = command.toControlRequest() ?: return Result.success(Unit)
        return post(device, CastifyProtocol.PATH_CONTROL, CastifyProtocol.json.encodeToString(ControlRequest.serializer(), request))
    }

    private suspend fun post(device: TvDevice, path: String, body: String): Result<Unit> =
        request(device, path, method = "POST", body = body, readTimeoutMs = 10_000).map { }

    private suspend fun request(
        device: TvDevice,
        path: String,
        method: String,
        body: String?,
        readTimeoutMs: Int,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(URL("http://${device.host}:${device.port}$path")) as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 2_500
                connection.readTimeout = readTimeoutMs
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val raw = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val message = runCatching {
                        CastifyProtocol.json.decodeFromString(ErrorResponse.serializer(), raw).error
                    }.getOrDefault(raw.ifBlank { "HTTP $code" })
                    throw IOException(message)
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
