package com.sensirion.sps30.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class DiscoveryTest {

    @Test
    fun localSubnetBasesFromRoutingProbeOnly() {
        // Probe-only path (interfaces empty): simulates the S24 with a VPN -
        // 192.168.0.1 -> Wi-Fi LAN; 10.0.0.1 and the public default route both
        // -> the same VPN tunnel /24; 172.16 -> none.
        val routes = mapOf(
            "192.168.0.1" to "192.168.1.156",
            "10.0.0.1" to "10.198.0.5",
            "172.16.0.1" to null,
            "8.8.8.8" to "10.198.0.5",
        )
        val bases = Discovery.localSubnetBases(interfaces = { emptyList() }, probe = { routes[it] })
        assertEquals(listOf("192.168.1.", "10.198.0."), bases)
    }

    @Test
    fun localSubnetBasesPrefersInterfacesThenAugmentsWithProbe() {
        // Interface enumeration surfaces the Wi-Fi LAN + VPN directly (the robust
        // Android path); the probe adds nothing new here. Private-first, deduped.
        val bases = Discovery.localSubnetBases(
            interfaces = { listOf("10.198.0.", "192.168.1.") },
            probe = { if (it == "8.8.8.8") "10.198.0.5" else null },
        )
        assertEquals(listOf("10.198.0.", "192.168.1."), bases)
    }

    @Test
    fun localSubnetBasesMergesInterfacesAndProbe() {
        // A base seen only by the probe is still included.
        val bases = Discovery.localSubnetBases(
            interfaces = { listOf("192.168.1.") },
            probe = { if (it == "172.16.0.1") "172.16.4.9" else null },
        )
        assertEquals(listOf("192.168.1.", "172.16.4."), bases)
    }

    @Test
    fun baseOfParsesAndRejects() {
        assertEquals("192.168.1.", Discovery.baseOf("192.168.1.156"))
        assertEquals("10.198.0.", Discovery.baseOf("10.198.0.5"))
        assertNull(Discovery.baseOf(null))
        assertNull(Discovery.baseOf("nonsense"))
        assertNull(Discovery.baseOf("1.2.3"))
        assertNull(Discovery.baseOf("1.2.3.4.5"))
    }

    @Test
    fun isPrivateBaseMatchesRfc1918() {
        assertTrue(Discovery.isPrivateBase("10.198.0."))
        assertTrue(Discovery.isPrivateBase("192.168.1."))
        assertTrue(Discovery.isPrivateBase("172.16.5."))
        assertTrue(Discovery.isPrivateBase("172.31.9."))
        assertFalse(Discovery.isPrivateBase("8.8.8."))
        assertFalse(Discovery.isPrivateBase("172.15.0."))
        assertFalse(Discovery.isPrivateBase("172.32.0."))
        assertFalse(Discovery.isPrivateBase("11.0.0."))
    }

    @Test
    fun discoverFindsDaemonAcrossAllBasesEvenWithVpn() = runBlocking {
        // Daemon lives on the Wi-Fi /24, which is NOT the default-route subnet.
        val found = Discovery.discover(
            port = 8730,
            bases = listOf("192.168.1.", "10.198.0."),
            verify = { host, _, _ -> host == "192.168.1.159" },
            workers = 32,
            dispatcher = Dispatchers.Default,
        )
        assertEquals("192.168.1.159", found)
    }

    @Test
    fun discoverSweepsFullLastOctetOfEveryBase() = runBlocking {
        val swept = ConcurrentHashMap.newKeySet<String>()
        Discovery.discover(
            port = 8730,
            bases = listOf("10.0.0.", "192.168.5."),
            verify = { host, _, _ -> swept.add(host); false },
            workers = 64,
            dispatcher = Dispatchers.Default,
        )
        assertTrue(swept.contains("10.0.0.1"))
        assertTrue(swept.contains("10.0.0.254"))
        assertTrue(swept.contains("192.168.5.1"))
        assertTrue(swept.contains("192.168.5.254"))
        assertFalse(swept.contains("10.0.0.0"))
        assertFalse(swept.contains("10.0.0.255"))
    }

    @Test
    fun discoverReturnsNullWhenNoMatch() = runBlocking {
        val found = Discovery.discover(
            port = 8730,
            bases = listOf("192.168.9."),
            verify = { _, _, _ -> false },
            dispatcher = Dispatchers.Default,
        )
        assertNull(found)
    }

    @Test
    fun discoverReturnsNullWhenNoBases() = runBlocking {
        assertNull(
            Discovery.discover(
                port = 8730,
                bases = emptyList(),
                verify = { _, _, _ -> true },
                dispatcher = Dispatchers.Default,
            ),
        )
        assertNull(
            Discovery.discover(
                port = 8730,
                verify = { _, _, _ -> true },
                dispatcher = Dispatchers.Default,
                basesProvider = { emptyList() },
            ),
        )
    }
}
