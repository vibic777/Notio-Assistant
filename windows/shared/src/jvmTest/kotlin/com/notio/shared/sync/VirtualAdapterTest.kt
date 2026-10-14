package com.notio.shared.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which network adapters are not a network of the person: Notio does not announce itself there (D129). */
class VirtualAdapterTest {
    @Test
    fun `the adapters of virtual machines, containers and VPNs are told by their names on Windows and on Linux`() {
        // Windows shows a description.
        assertTrue(Discovery.isVirtualAdapterName("VMware Virtual Ethernet Adapter for VMnet8", "eth5"))
        assertTrue(Discovery.isVirtualAdapterName("vEthernet (WSL)", "eth7"))
        assertTrue(Discovery.isVirtualAdapterName("TAP-Windows Adapter V9", "net3"))
        // Linux has the short name alone.
        for (name in listOf("docker0", "br-3f2a9c1d77aa", "veth1a2b3c", "virbr0", "tun0", "tap0", "wg0", "tailscale0", "ztabc123", "lxcbr0", "vmnet8", "vboxnet0")) {
            assertTrue(Discovery.isVirtualAdapterName(name, name), name)
        }
    }

    @Test
    fun `a real adapter stays`() {
        assertFalse(Discovery.isVirtualAdapterName("Intel(R) Wi-Fi 6 AX201 160MHz", "wlan1"))
        assertFalse(Discovery.isVirtualAdapterName("Realtek PCIe GbE Family Controller", "eth0"))
        for (name in listOf("eth0", "enp3s0", "wlan0", "wlp2s0", "br0", "eno1")) {
            assertFalse(Discovery.isVirtualAdapterName(name, name), name)
        }
    }
}
