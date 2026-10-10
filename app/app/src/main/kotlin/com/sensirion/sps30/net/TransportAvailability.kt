package com.sensirion.sps30.net

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.sensirion.sps30.data.ConnectionMode

/**
 * Whether a transport can actually be used right now, and if not, why.
 *
 * The connection selector greys out both the *current* mode and any *unusable*
 * one, so it has to be able to tell the user which it is - a greyed Bluetooth
 * button means something very different when Bluetooth is simply switched off.
 */
data class TransportAvailability(
    val usable: Boolean,
    /** Short reason shown under the button when [usable] is false. Null when usable. */
    val reason: String? = null,
) {
    companion object {
        val USABLE = TransportAvailability(true)

        /** Disconnecting is always possible, so [ConnectionMode.NONE] is never blocked. */
        fun of(context: Context, mode: ConnectionMode): TransportAvailability = when (mode) {
            ConnectionMode.NONE -> USABLE
            ConnectionMode.WIFI -> wifi(context)
            ConnectionMode.BLUETOOTH -> bluetooth(context)
        }

        @Suppress("DEPRECATION") // see below: activeNetwork is the wrong question here
        private fun wifi(context: Context): TransportAvailability {
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return TransportAvailability(false, "No network service")
            // Deliberately NOT activeNetwork: with a VPN up the active network is the
            // tunnel, but the Wi-Fi LAN the sensor lives on is still reachable. We
            // want "does a Wi-Fi network exist", not "is Wi-Fi the default route".
            val onWifi = cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            return if (onWifi) USABLE else TransportAvailability(false, "Wi-Fi off")
        }

        private fun bluetooth(context: Context): TransportAvailability {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return TransportAvailability(false, "No Bluetooth")
            // Missing runtime permissions are deliberately not a block: tapping the
            // button launches the permission request, so the button must stay live.
            return if (adapter.isEnabled) USABLE
            else TransportAvailability(false, "Bluetooth off")
        }
    }
}
