package com.msp1974.vacompanion.data

import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import javax.inject.Inject

enum class NetworkStatus {
    Available,
    Unavailable
}

class NetworkInfo {
    var status: NetworkStatus = NetworkStatus.Unavailable
        set(value) {
            field = value
            lastChanged = System.currentTimeMillis()
            if (value == NetworkStatus.Unavailable) {
                disconnectCount++
                type = "None"
            }
        }
    var type: String = "None"
    var lastChanged: Long = 0
    var disconnectCount: Long = 0
}

class NetworkStatusManager @Inject constructor(val context: Context) {

    val networkInfo = NetworkInfo()

    @RequiresPermission("android.permission.ACCESS_NETWORK_STATE")
    fun getNetworkStatus(): Flow<NetworkInfo> = callbackFlow {
        val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onUnavailable() {
                Timber.w("Network unavailable")
                networkInfo.status = NetworkStatus.Unavailable
                networkInfo.type = "None"
                trySend(networkInfo)
            }

            override fun onAvailable(network: Network) {
                Timber.d("Network available")
                networkInfo.status = NetworkStatus.Available
                networkInfo.type = getNetworkType(connectivityManager, network)
                trySend(networkInfo)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                networkInfo.type = getNetworkType(connectivityManager, network)
                trySend(networkInfo)
            }

            override fun onLost(network: Network) {
                Timber.w("Network lost  ")
                networkInfo.status = NetworkStatus.Unavailable
                networkInfo.type = "None"
                trySend(networkInfo)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun getNetworkType(connectivityManager: ConnectivityManager, network: Network): String {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "None"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }
    }

}
