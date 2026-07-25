package com.gemmory.modelinstall

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/** Connectivity seam so installer tests never touch the framework. */
interface NetworkStatusProvider {
    fun isConnected(): Boolean
    fun isMetered(): Boolean
}

class AndroidNetworkStatusProvider(context: Context) : NetworkStatusProvider {

    private val connectivityManager = context.applicationContext.getSystemService<ConnectivityManager>()

    override fun isConnected(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun isMetered(): Boolean =
        connectivityManager?.isActiveNetworkMetered ?: true
}
