package com.sensirion.sps30.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds a handle on the Wi-Fi network so sockets can be bound to it explicitly.
 *
 * What this actually fixes: **a Wi-Fi network with no internet**, such as a
 * sensor's own access point. Android keeps cellular as the default network in
 * that situation, so unbound sockets go out over mobile data towards an address
 * that only exists on the Wi-Fi. Requesting the Wi-Fi network and using its
 * SocketFactory sends them to the right place. This matters for ESP32
 * provisioning later.
 *
 * What it deliberately does **not** fix, despite the obvious appeal:
 * **escaping a full-tunnel VPN**. Measured on a real device (Galaxy S24, VPN
 * active): binding to the Wi-Fi network fails with `EPERM` even with
 * CHANGE_NETWORK_STATE granted and the network obtained via `requestNetwork`.
 * That is Android enforcing its own guarantee - an app subject to a VPN cannot
 * route around it, and only the VPN itself may permit that via
 * `VpnService.Builder.allowBypass()`. So a VPN that captures or blocks LAN
 * traffic cannot be worked around from here, and pretending otherwise would be
 * worse than useless. [vpnActive] exists so the app can *say* that instead.
 *
 * Binding failure is never fatal: the socket falls back to the default route,
 * which is correct whenever Wi-Fi already is the default route.
 *
 * The request is held for the process lifetime rather than per-connection:
 * requestNetwork is not cheap, and dropping it between reconnects would give up
 * the binding exactly when a flaky link needs it most.
 */
object WifiNetworkBinder {

    private const val TAG = "WifiBinder"
    private const val WAIT_MS = 4_000L

    private val network = AtomicReference<Network?>(null)
    private var registered = false
    private var available: CountDownLatch = CountDownLatch(1)

    /** Start tracking the Wi-Fi network. Idempotent. */
    @Synchronized
    fun start(context: Context) {
        if (registered) return
        val cm = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Deliberately no NET_CAPABILITY_INTERNET: a sensor's own access
            // point has no internet, and requiring it would rule out exactly the
            // network we most need to reach.
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                network.set(net)
                available.countDown()
            }

            override fun onLost(net: Network) {
                if (network.get() == net) {
                    network.set(null)
                    available = CountDownLatch(1)
                }
            }
        }
        try {
            // requestNetwork, not registerNetworkCallback: the latter only lets us
            // watch a network, and a handle obtained that way cannot be used -
            // binding a socket to it fails with EPERM. Requesting it is what
            // grants the app permission to send traffic over it, and is why this
            // needs CHANGE_NETWORK_STATE.
            cm.requestNetwork(request, callback)
            registered = true
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot request the Wi-Fi network", e)
        }
    }

    /** The Wi-Fi network, waiting briefly for it if the callback has not fired. */
    fun awaitNetwork(): Network? {
        network.get()?.let { return it }
        return try {
            available.await(WAIT_MS, TimeUnit.MILLISECONDS)
            network.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * An unconnected socket already tied to the Wi-Fi network, or a plain one if
     * no Wi-Fi network is known.
     *
     * Deliberately *not* `Network.bindSocket(Socket())`: a freshly constructed
     * Java Socket has no file descriptor yet - the OS creates it lazily on bind
     * or connect - so binding one fails with EPERM, silently, while the
     * connection still succeeds over the default route. That failure mode is
     * invisible until a VPN actually captures the default route, which is
     * exactly when the binding was supposed to help. The network's own
     * SocketFactory hands back a socket that is associated from the start.
     */
    fun createSocket(): Socket {
        val net = network.get() ?: run {
            Log.d(TAG, "no Wi-Fi network yet; using an unbound socket")
            return Socket()
        }
        return try {
            net.socketFactory.createSocket().also { Log.d(TAG, "socket bound to Wi-Fi") }
        } catch (e: Exception) {
            Log.d(TAG, "falling back to an unbound socket", e)
            Socket()
        }
    }

    /** True when a VPN is currently the phone's default network. */
    fun vpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
