package com.example.aiagenttestapp.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/**
 * Answers "can this device actually reach the internet right now?" -- used to fail the web tools
 * fast and clearly when offline, instead of making the model wait out a doomed connection timeout
 * only to get a generic error back.
 *
 * Reads [ConnectivityManager] on demand (cheap; needs only the ACCESS_NETWORK_STATE permission the
 * app already holds) rather than holding a live callback, because the only caller asks the question
 * once, at the moment a web tool is invoked.
 */
class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext

    /**
     * True only when there is an active network that both claims internet access and has been
     * *validated* as really having it. Requiring validation means a captive portal or a
     * connected-but-dead Wi-Fi link correctly reads as offline, which is exactly the case a plain
     * "is something connected" check gets wrong.
     */
    fun isOnline(): Boolean {
        val connectivity = appContext.getSystemService<ConnectivityManager>() ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
