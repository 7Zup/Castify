package app.castify.phone.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLConnection

/**
 * The phone's Wi-Fi network. TVs live on the Wi-Fi: talking to them through the Wi-Fi
 * network explicitly keeps working when Android routes the rest through mobile data (e.g.
 * just after Wi-Fi was turned back on, or a Wi-Fi without internet).
 */
class WifiNetwork(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Suppress("DEPRECATION")
    fun current(): Network? = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Opens [url] on the Wi-Fi when there is one, else on the default network. */
    fun open(url: URL): URLConnection = current()?.openConnection(url) ?: url.openConnection()

    /** Subnet broadcast addresses of the Wi-Fi (e.g. 10.0.0.255), plus 255.255.255.255. */
    fun broadcastAddresses(network: Network): List<InetAddress> {
        val subnets = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
            val address = link.address as? Inet4Address ?: return@mapNotNull null
            val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val mask = if (link.prefixLength == 0) 0 else -1 shl (32 - link.prefixLength)
            val broadcast = ip or mask.inv()
            InetAddress.getByAddress(ByteArray(4) { i -> (broadcast shr (24 - 8 * i)).toByte() })
        }
        return (subnets + InetAddress.getByName("255.255.255.255")).distinct()
    }

    /**
     * "Always force the Wi-Fi" (user rule, 11/06/2026): while a Wi-Fi network is up, all the
     * app's traffic (web pages, inspection, TV) goes through it, never through mobile data,
     * even when Android would pick mobile data. Without Wi-Fi, the default network is used.
     */
    fun keepAppOnWifi() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.bindProcessToNetwork(network)
            }
            override fun onLost(network: Network) {
                if (connectivity.boundNetworkForProcess == network) connectivity.bindProcessToNetwork(current())
            }
        })
    }

    /** Some Wi-Fi drivers drop multicast/broadcast packets unless an app holds this lock. */
    fun multicastLock(tag: String): WifiManager.MulticastLock =
        wifi.createMulticastLock(tag).apply { setReferenceCounted(false) }
}
