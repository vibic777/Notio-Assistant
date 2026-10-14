package com.notio.shared.sync

import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A Notio device seen on the local network while its Sync page is open (Android D226). */
data class FoundDevice(val deviceId: String, val name: String, val host: String, val port: Int, val waitingToPair: Boolean)

/** Finding Notio devices on the same network without typing addresses (mDNS / DNS-SD), and this computer's addresses. */
interface Discovery {
    /** Announces this device on [port] until the returned function is called. */
    fun advertise(port: Int, deviceId: String, name: String, waitingToPair: Boolean): () -> Unit

    /** The Notio devices announcing themselves now; the list follows them coming and going. */
    fun devices(ownDeviceId: String): Flow<List<FoundDevice>>

    /** This computer's addresses on the local network (IPv4), to show and to type on the other device. */
    fun ownAddresses(): List<String>

    /** Whether this computer is on a local network at all. */
    fun onLocalNetwork(): Boolean = ownAddresses().isNotEmpty()

    /** Stops everything (the Sync page closed). */
    fun close() {}

    companion object {
        /** The Android service type `_notio-sync._tcp.` as JmDNS writes it. */
        const val SERVICE_TYPE = "_notio-sync._tcp.local."

        /**
         * The DNS-SD instance name, exactly as the phone makes it (Android D229): "Notio <name>", plus " (pairing)" while waiting with a
         * code, cut to the 63 bytes of UTF-8 a DNS label allows.
         */
        fun serviceName(name: String, waitingToPair: Boolean): String {
            val suffix = if (waitingToPair) " (pairing)" else ""
            var base = "Notio " + name.trim()
            while (base.isNotEmpty() && (base + suffix).toByteArray(Charsets.UTF_8).size > 63) base = base.dropLast(1)
            if (base.lastOrNull()?.isHighSurrogate() == true) base = base.dropLast(1)
            return base.trimEnd() + suffix
        }

        /** The IPv4 addresses of the up, non-loopback, non-virtual interfaces that are on a local network. */
        fun localAddresses(): List<Inet4Address> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !isVirtualAdapter(it) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { SyncEngine.isLocal(it) && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }.getOrDefault(emptyList())

        /** Hyper-V, VirtualBox, VMware and VPN adapters: announcing there only confuses the phone. */
        private fun isVirtualAdapter(n: NetworkInterface): Boolean = isVirtualAdapterName(n.displayName ?: n.name, n.name)

        /**
         * Told by the name. Windows shows a description ("VMware Virtual Ethernet Adapter"); Linux has only the short name of
         * the interface, whose beginning says what made it (D129): bridges of virtual machines and containers, tunnels, VPNs.
         */
        internal fun isVirtualAdapterName(displayName: String, name: String): Boolean {
            val shown = displayName.lowercase()
            if (listOf("virtualbox", "vmware", "hyper-v", "vethernet", "wsl", "docker", "tap-", "tunnel", "vpn").any { it in shown }) return true
            val short = name.lowercase()
            return listOf("virbr", "veth", "docker", "br-", "tun", "tap", "wg", "zt", "tailscale", "lxc", "lxd", "vmnet", "vboxnet").any { short.startsWith(it) }
        }
    }
}

/**
 * [Discovery] with JmDNS, one responder per local IPv4 address (a PC often has Wi-Fi and Ethernet). It lives only while the Sync
 * page is open ([close]), like the phone's announcement.
 */
class JmdnsDiscovery(private val log: (String) -> Unit = {}) : Discovery {
    private val lock = Any()
    private var responders: List<JmDNS>? = null

    private fun responders(): List<JmDNS> = synchronized(lock) {
        responders ?: Discovery.localAddresses().mapNotNull { address ->
            runCatching { JmDNS.create(address, "notio-" + address.hostAddress.replace('.', '-')) }
                .onFailure { log("discovery: could not start on ${address.hostAddress} (${it.javaClass.simpleName})") }
                .getOrNull()
        }.also {
            responders = it
            if (it.isEmpty()) log("discovery: no local network address to announce on")
        }
    }

    override fun advertise(port: Int, deviceId: String, name: String, waitingToPair: Boolean): () -> Unit {
        val infos = responders().mapNotNull { mdns ->
            val info = ServiceInfo.create(
                Discovery.SERVICE_TYPE, Discovery.serviceName(name, waitingToPair), port, 0, 0,
                mapOf("id" to deviceId, "n" to name.take(60), "pair" to if (waitingToPair) "1" else "0"),
            )
            runCatching { mdns.registerService(info); mdns to info }
                .onFailure { log("discovery: announcing this device failed (${it.javaClass.simpleName}); other devices can still connect by the address") }
                .getOrNull()
        }
        return { infos.forEach { (mdns, info) -> runCatching { mdns.unregisterService(info) } } }
    }

    override fun devices(ownDeviceId: String): Flow<List<FoundDevice>> = callbackFlow {
        val found = LinkedHashMap<String, FoundDevice>()
        val seen = LinkedHashSet<String>()
        fun publish() { trySend(synchronized(found) { found.values.toList() }) }
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                event.dns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                if (synchronized(found) { found.remove(event.name) } != null) publish()
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val id = info.getPropertyString("id") ?: return
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: info.hostAddresses.firstOrNull() ?: return
                if (id == ownDeviceId) return
                val device = FoundDevice(id, info.getPropertyString("n") ?: info.name, host, info.port, info.getPropertyString("pair") == "1")
                synchronized(found) {
                    // One entry per device: the same device under its other name (waiting / not) replaces it.
                    found.entries.removeAll { it.value.deviceId == id && it.key != info.name }
                    found[info.name] = device
                    seen += device.name
                }
                publish()
            }
        }
        trySend(emptyList())
        val all = responders()
        all.forEach { it.addServiceListener(Discovery.SERVICE_TYPE, listener) }
        awaitClose {
            all.forEach { runCatching { it.removeServiceListener(Discovery.SERVICE_TYPE, listener) } }
            val names = synchronized(found) { seen.toList() }
            log(if (names.isEmpty()) "discovery: no other Notio device was seen on this network" else "discovery: seen " + names.joinToString(", "))
        }
    }

    override fun ownAddresses(): List<String> = Discovery.localAddresses().mapNotNull { it.hostAddress }

    override fun close() {
        val all = synchronized(lock) { responders.also { responders = null } }.orEmpty()
        all.forEach { runCatching { it.unregisterAllServices(); it.close() } }
    }

}
