package app.castify.phone.data

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.castify.domain.model.CastDevice
import app.castify.domain.port.CastDeviceScanner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lists Google Cast devices from their mDNS advertisement (`_googlecast._tcp`), which every
 * Cast device keeps up whatever app runs on it. Only reads the advertisement: nothing is sent
 * to the devices. TXT records: `fn` = friendly name, `md` = model, `ca` = capability bits
 * (video out = 1, multizone group = 32). These keys are widely used but not documented by
 * Google: missing ones fall back to the service name and "has video".
 */
class NsdCastDeviceScanner(private val resolver: NsdResolver) : CastDeviceScanner {

    override fun devices(): Flow<List<CastDevice>> = callbackFlow {
        val found = ConcurrentHashMap<String, CastDevice>()
        val open = AtomicBoolean(true)
        fun publish() { trySend(found.values.sortedBy { it.name }) }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.resolve(info, priority = false, wanted = { open.get() }) { resolved ->
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress
                    if (host != null) {
                        found[resolved.serviceName] = toDevice(resolved, host)
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
                Log.w(TAG, "Cast discovery failed: $errorCode"); publish()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        resolver.nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { open.set(false); runCatching { resolver.nsd.stopServiceDiscovery(listener) } }
    }

    private fun toDevice(info: NsdServiceInfo, host: String): CastDevice {
        val txt = info.attributes.orEmpty()
        fun text(key: String) = txt[key]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
        val capabilities = text("ca")?.toIntOrNull()
        val model = text("md")
        return CastDevice(
            name = text("fn") ?: info.serviceName,
            host = host,
            hasVideo = capabilities == null || capabilities and VIDEO_OUT != 0,
            isGroup = model == GROUP_MODEL || (capabilities != null && capabilities and MULTIZONE_GROUP != 0),
        )
    }

    private companion object {
        const val TAG = "Castify"
        const val SERVICE_TYPE = "_googlecast._tcp."
        const val VIDEO_OUT = 1
        const val MULTIZONE_GROUP = 32
        const val GROUP_MODEL = "Google Cast Group"
    }
}
