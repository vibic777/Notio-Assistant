package com.notio.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DNS-SD name a device announces itself under (D229): a separate name while waiting, never over 63 bytes of UTF-8. */
class DiscoveryTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun shortNameIsKept() {
        assertEquals("Notio Pixel 7", Discovery.serviceName("Pixel 7", false))
        assertEquals("Notio Pixel 7 (pairing)", Discovery.serviceName("Pixel 7", true))
    }

    @Test
    fun waitingChangesTheName() {
        assertNotEquals(Discovery.serviceName("Tab", false), Discovery.serviceName("Tab", true))
    }

    @Test
    fun longCyrillicNameFitsOneDnsLabel() {
        val name = "Планшет Samsung Galaxy Tab S9 гостиная".take(40)
        for (waiting in listOf(false, true)) {
            val s = Discovery.serviceName(name, waiting)
            assertTrue(s, bytes(s) <= 63)
            assertTrue(s, s.startsWith("Notio Планшет"))
            if (waiting) assertTrue(s, s.endsWith(" (pairing)"))
        }
    }

    @Test
    fun onlyLocalAddressesAreAccepted() {
        fun local(a: String) = SyncEngine.isLocal(java.net.InetAddress.getByName(a))
        listOf("127.0.0.1", "192.168.1.20", "10.0.0.5", "172.16.3.4", "169.254.1.1", "100.64.0.9", "fe80::1", "fd12:3456::1", "::1")
            .forEach { assertTrue(it, local(it)) }
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2a00:1450:4010::65", "203.0.113.7")
            .forEach { assertTrue(it, !local(it)) }
    }

    @Test
    fun surrogatePairIsNotCutInHalf() {
        val s = Discovery.serviceName("😀".repeat(20), true)
        assertTrue(bytes(s) <= 63)
        assertTrue(s, !s.removeSuffix(" (pairing)").last().isHighSurrogate())
    }
}
