package app.castify.phone.data

import android.util.Log
import app.castify.domain.model.TvDevice
import app.castify.domain.port.TvDiscovery
import app.castify.protocol.CastifyProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * Finds Castify TVs by broadcasting a UDP query on the Wi-Fi ([CastifyProtocol.DISCOVERY_QUERY]).
 * Each round is a fresh socket, so a Wi-Fi drop or an IP change heals by itself on the next
 * round, unlike Android's mDNS resolve which can stay stuck until the app is killed.
 * A TV that stops answering disappears after [EXPIRY_MS].
 */
class UdpTvDiscovery(private val wifi: WifiNetwork) : TvDiscovery {

    override fun devices(): Flow<List<TvDevice>> = flow {
        val lock = wifi.multicastLock("castify-discovery")
        runCatching { lock.acquire() }
        val seen = HashMap<String, Pair<TvDevice, Long>>()
        try {
            while (currentCoroutineContext().isActive) {
                val network = wifi.current()
                if (network == null) {
                    seen.clear()
                } else {
                    runCatching { round(network, seen) }.onFailure { Log.w(TAG, "Discovery round failed: ${it.message}") }
                }
                val now = System.currentTimeMillis()
                seen.entries.removeAll { now - it.value.second > EXPIRY_MS }
                emit(seen.values.map { it.first }.sortedBy { it.name })
                if (network == null) delay(ROUND_MS)
            }
        } finally {
            runCatching { lock.release() }
        }
    }.flowOn(Dispatchers.IO)

    /** Sends the query, then collects the replies for [ROUND_MS]. */
    private fun round(network: android.net.Network, seen: MutableMap<String, Pair<TvDevice, Long>>) {
        DatagramSocket().use { socket ->
            runCatching { network.bindSocket(socket) }
            socket.broadcast = true
            socket.soTimeout = 250
            val query = CastifyProtocol.DISCOVERY_QUERY.toByteArray()
            wifi.broadcastAddresses(network).forEach { address ->
                runCatching { socket.send(DatagramPacket(query, query.size, address, CastifyProtocol.DISCOVERY_PORT)) }
            }
            val buffer = ByteArray(1_024)
            val deadline = System.currentTimeMillis() + ROUND_MS
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val reply = CastifyProtocol.parseDiscoveryReply(packet.data, packet.length) ?: continue
                val host = packet.address.hostAddress ?: continue
                seen["$host:${reply.port}"] = TvDevice(reply.name, host, reply.port) to System.currentTimeMillis()
            }
        }
    }

    private companion object {
        const val TAG = "Castify"
        const val ROUND_MS = 1_500L
        const val EXPIRY_MS = 6_000L
    }
}
