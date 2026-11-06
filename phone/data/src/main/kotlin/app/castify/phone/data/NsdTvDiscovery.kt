package app.castify.phone.data

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.castify.domain.model.TvDevice
import app.castify.domain.port.TvDiscovery
import app.castify.protocol.CastifyProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Finds Castify TV apps advertised with mDNS on the local network. */
class NsdTvDiscovery(private val resolver: NsdResolver) : TvDiscovery {

    override fun devices(): Flow<List<TvDevice>> = callbackFlow {
        val found = ConcurrentHashMap<String, TvDevice>()
        val open = AtomicBoolean(true)
        fun publish() { trySend(found.values.sortedBy { it.name }) }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.resolve(info, priority = true, wanted = { open.get() }) { resolved ->
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress
                    if (host != null) {
                        found[resolved.serviceName] = TvDevice(
                            name = CastifyProtocol.displayNameOf(resolved.serviceName),
                            host = host,
                            port = resolved.port,
                        )
                        publish()
                    }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) publish()
            }

            override fun onDiscoveryStarted(serviceType: String) = publish()
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode"); publish()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        resolver.nsd.discoverServices(CastifyProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { open.set(false); runCatching { resolver.nsd.stopServiceDiscovery(listener) } }
    }

    private companion object { const val TAG = "Castify" }
}
