package org.mubox.reader.feature.reader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Network classes shared with the native range engine. Native maps 1 to mobile and
 * 2 to Wi-Fi; every other value is treated as unknown.
 */
internal const val NETWORK_CLASS_UNKNOWN = 0
internal const val NETWORK_CLASS_MOBILE = 1
internal const val NETWORK_CLASS_WIFI = 2

/**
 * Classifies an active network into the native network classes. Kept as a pure
 * function over transports so the decision is unit-testable without a device.
 */
internal fun networkClassFromTransports(transports: Set<Int>): Int = when {
    NetworkCapabilities.TRANSPORT_WIFI in transports ||
        NetworkCapabilities.TRANSPORT_ETHERNET in transports -> NETWORK_CLASS_WIFI

    NetworkCapabilities.TRANSPORT_CELLULAR in transports -> NETWORK_CLASS_MOBILE
    else -> NETWORK_CLASS_UNKNOWN
}

/** Connectivity-backed network class source for the reader prefetch engine. */
class AndroidNetworkClassProvider(context: Context) : () -> Int {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun invoke(): Int {
        val network = connectivityManager.activeNetwork ?: return NETWORK_CLASS_UNKNOWN
        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return NETWORK_CLASS_UNKNOWN
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NETWORK_CLASS_UNKNOWN
        }
        val transports = TRANSPORT_VALUES.filter(capabilities::hasTransport).toSet()
        return networkClassFromTransports(transports)
    }

    private companion object {
        val TRANSPORT_VALUES = listOf(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN,
        )
    }
}
