package com.chalkboard.keyboard.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether this device has a network at all.
 *
 * The only question asked here is "is there a route to anywhere", and the only thing it
 * decides is which of two messages the user reads: section E1 ("Server grammar
 * unavailable" — coral, something is wrong at the other end) or section E4 ("No internet
 * connection" — muted, nothing is wrong and nothing to fix). Without it every tunnel,
 * flight and dead spot gets reported as a server fault.
 *
 * This costs the `ACCESS_NETWORK_STATE` permission. It is a normal permission, granted
 * at install without a prompt, and it discloses nothing about the user — no address, no
 * SSID, no traffic. Nothing here observes *what* is sent, and this file must never grow
 * a caller that does.
 *
 * When the platform will not answer, the result is `true`: a failure is then attributed
 * to the server that actually failed rather than to an outage invented on its behalf.
 */
object Connectivity {

    fun hasNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
