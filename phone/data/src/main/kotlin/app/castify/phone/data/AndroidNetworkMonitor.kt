package app.castify.phone.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.castify.domain.port.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Online = the phone has a default network able to reach the internet. Note: the app binds
 * itself to the Wi-Fi ([WifiNetwork.keepAppOnWifi]); the default network is what Android
 * reports, which is enough to tell "no internet at all" from "the site does not answer".
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(isOnline(connectivity.activeNetwork))
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            override fun onLost(network: Network) { _online.value = isOnline(connectivity.activeNetwork) }
        })
    }

    /** Fresh check (Retry). */
    override fun refresh() { _online.value = isOnline(connectivity.activeNetwork) }

    private fun isOnline(network: Network?): Boolean =
        network != null && connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
