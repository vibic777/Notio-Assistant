package com.notio.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * mDNS on this machine: one responder announces "Notio Phone X (pairing)" the way the phone does, another finds it with its TXT
 * record. Skipped (no assertion) when the computer has no local network address, as on a build server.
 */
class JmdnsDiscoveryTest {
    @Test
    fun aDeviceAnnouncedIsFound() = runBlocking {
        if (Discovery.localAddresses().isEmpty()) return@runBlocking
        val a = JmdnsDiscovery()
        val b = JmdnsDiscovery()
        val stop = a.advertise(47999, "device-x", "Phone X", waitingToPair = true)
        try {
            val found = withTimeoutOrNull(20_000) { b.devices("device-y").first { list -> list.any { it.deviceId == "device-x" } } }
                ?.first { it.deviceId == "device-x" }
            checkNotNull(found) { "not found in 20 s" }
            assertEquals("Phone X", found.name)
            assertEquals(47999, found.port)
            assertEquals(true, found.waitingToPair)
        } finally {
            stop()
            a.close()
            b.close()
        }
    }
}
