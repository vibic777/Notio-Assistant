package com.notio.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The address of a paired device typed by hand (D280). */
class SyncAddressTest {
    @Test
    fun `an IPv4 address or a host name is taken, anything else refused`() {
        assertEquals("192.168.1.153", SyncAddress.host(" 192.168.1.153 "))
        assertEquals("laptop.local", SyncAddress.host("laptop.local"))
        assertNull(SyncAddress.host(""))
        assertNull(SyncAddress.host("192.168.1.300"))
        assertNull(SyncAddress.host("192.168.1"))
        assertNull(SyncAddress.host("192.168..1"))
        assertNull(SyncAddress.host("http://192.168.1.2"))
        assertNull(SyncAddress.host("-bad-.local"))
    }

    @Test
    fun `a port is 1 to 65535`() {
        assertEquals(47821, SyncAddress.port("47821"))
        assertEquals(1, SyncAddress.port(" 1 "))
        assertNull(SyncAddress.port("0"))
        assertNull(SyncAddress.port("65536"))
        assertNull(SyncAddress.port("abc"))
    }

    @Test
    fun `a port to listen on is 1024 to 65535`() {
        assertEquals(47821, SyncAddress.listenPort(" 47821 "))
        assertEquals(1024, SyncAddress.listenPort("1024"))
        assertNull(SyncAddress.listenPort("1023"))
        assertNull(SyncAddress.listenPort("65536"))
        assertNull(SyncAddress.listenPort(""))
    }
}
