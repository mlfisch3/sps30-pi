package com.sensirion.sps30.net

import com.sensirion.sps30.wire.Messages
import com.sensirion.sps30.wire.ServerMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * VPN-aware LAN discovery of the SPS30 daemon - a Kotlin port of the fixed
 * `local_subnet_bases`/`discover`/`verify_daemon` from the Termux launcher.
 *
 * The Pi IP is DHCP and `.local`/mDNS does not resolve on Android. And the S24
 * has a VPN active, so the default route (to a public address) is a tunnel
 * subnet while the real Wi-Fi LAN is only reachable for private destinations.
 * We therefore probe one representative address in each RFC1918 block plus the
 * default route, collect *every* distinct local /24, and sweep them all,
 * verifying each candidate genuinely speaks the daemon `hello`.
 */
object Discovery {

    /** One anchor per RFC1918 block, plus a public address for the default route. */
    val PROBE_TARGETS: List<String> =
        listOf("192.168.0.1", "10.0.0.1", "172.16.0.1", "8.8.8.8")

    /**
     * Local source IPv4 the OS would use to reach [dst], or null. Uses a UDP
     * `connect` - no packets are actually sent. Never throws.
     */
    fun sourceIpFor(dst: String, timeoutMs: Int = 500): String? {
        return try {
            DatagramSocket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(dst, 80))
                val addr = sock.localAddress?.hostAddress
                // A disconnected/dummy socket can report the wildcard address.
                if (addr == null || addr == "0.0.0.0") null else addr
            }
        } catch (e: Exception) {
            null
        }
    }

    /** "a.b.c.d" -> "a.b.c." ; null for anything malformed. */
    fun baseOf(ip: String?): String? {
        if (ip == null) return null
        val parts = ip.split(".")
        if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.any { !it.isDigit() } }) return null
        return parts.take(3).joinToString(".") + "."
    }

    /** True if [base] ("a.b.c.") sits in an RFC1918 private range. */
    fun isPrivateBase(base: String): Boolean {
        val parts = base.trimEnd('.').split(".")
        val o1 = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val o2 = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return when {
            o1 == 10 -> true
            o1 == 192 && o2 == 168 -> true
            o1 == 172 && o2 in 16..31 -> true
            else -> false
        }
    }

    /**
     * The /24 bases of this device's own up, non-loopback IPv4 interfaces.
     *
     * This is the primary, routing-independent source on Android: unlike the
     * `ip` command (blocked in Termux) `NetworkInterface` works in an app and
     * lists the Wi-Fi and any VPN interface directly. It also sidesteps a
     * Java/Android quirk where `DatagramSocket.getLocalAddress()` after connect
     * returns the wildcard, which left [sourceIpFor] blind.
     */
    fun interfaceBases(): List<String> {
        val bases = LinkedHashSet<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress) {
                        baseOf(addr.hostAddress)?.let { bases.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // fall through to whatever the caller's probe can find
        }
        return bases.toList()
    }

    /**
     * Ordered, de-duplicated list of this device's local /24 bases. Private-LAN
     * bases come before any public/tunnel base, since the daemon is on a private
     * LAN. Draws from interface enumeration first (robust on Android), then
     * augments with the routing probe. Both sources are injectable for tests.
     */
    fun localSubnetBases(
        interfaces: () -> List<String> = { interfaceBases() },
        probe: (String) -> String? = { sourceIpFor(it) },
    ): List<String> {
        val private = LinkedHashSet<String>()
        val public = LinkedHashSet<String>()
        fun consider(base: String?) {
            if (base == null) return
            if (isPrivateBase(base)) private.add(base) else public.add(base)
        }
        for (base in interfaces()) consider(base)
        for (dst in PROBE_TARGETS) consider(baseOf(probe(dst)))
        return (private + public).toList()
    }

    /**
     * True only if `host:port` is the SPS30 daemon (it sends a `hello` frame on
     * connect). Blocking; never throws. Reuse of [Messages.parse] guarantees the
     * verification matches how the app reads real frames.
     */
    fun verifyDaemon(host: String, port: Int, timeoutMs: Long = 1000): Boolean {
        return try {
            WifiNetworkBinder.createSocket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                sock.soTimeout = timeoutMs.toInt()
                val input = sock.getInputStream()
                val chunk = ByteArray(4096)
                val buf = StringBuilder()
                while (buf.indexOf("\n") < 0 && buf.length < 65536) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    buf.append(String(chunk, 0, n, Charsets.UTF_8))
                }
                val nl = buf.indexOf("\n")
                val line = if (nl >= 0) buf.substring(0, nl) else buf.toString()
                Messages.parse(line) is ServerMessage.Hello
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sweep the local /24 subnet(s) concurrently; return the first confirmed
     * daemon, or null. With [bases] given, sweeps exactly those; otherwise uses
     * [basesProvider] (default: [localSubnetBases]). All bases are swept in one
     * bounded pool so a VPN default route cannot hide the real Wi-Fi LAN.
     */
    suspend fun discover(
        port: Int,
        bases: List<String>? = null,
        verify: (String, Int, Long) -> Boolean = ::verifyDaemon,
        timeoutMs: Long = 1000,
        workers: Int = 64,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        basesProvider: () -> List<String> = { localSubnetBases() },
    ): String? {
        val effectiveBases = bases ?: basesProvider()
        if (effectiveBases.isEmpty()) return null
        val hosts = effectiveBases.flatMap { base -> (1..254).map { "$base$it" } }
        val semaphore = Semaphore(workers.coerceAtLeast(1))

        return withContext(dispatcher) {
            supervisorScope {
                val found = CompletableDeferred<String?>()
                val jobs = hosts.map { host ->
                    launch {
                        try {
                            semaphore.withPermit {
                                if (found.isCompleted) return@withPermit
                                if (verify(host, port, timeoutMs)) found.complete(host)
                            }
                        } catch (_: Throwable) {
                            // a single host failing must never abort the sweep
                        }
                    }
                }
                val watcher = launch {
                    jobs.joinAll()
                    found.complete(null)
                }
                val result = found.await()
                jobs.forEach { it.cancel() }
                watcher.cancel()
                result
            }
        }
    }
}
