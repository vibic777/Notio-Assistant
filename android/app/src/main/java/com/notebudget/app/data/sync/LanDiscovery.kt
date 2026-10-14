package com.notebudget.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A Notio device seen on the local network while its Sync page is open (D226). */
data class FoundDevice(val deviceId: String, val name: String, val host: String, val port: Int, val waitingToPair: Boolean)

/**
 * Finding Notio devices on the same Wi-Fi without typing addresses (D226): the phone's own service discovery (mDNS / DNS-SD,
 * `NsdManager`). A device announces itself only while its Sync page is open, so nothing runs in the background.
 *
 * D229: a device waiting with a code announces itself under another service name ([serviceName]), so the others see a new
 * service instead of having to notice a changed TXT record (they often do not); a failed announcement is tried again; the
 * failures and what a page saw go to the sync log through [log], so a device that finds nothing can be diagnosed.
 */
class LanDiscovery(context: Context, private val log: (String) -> Unit = {}) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val handler = Handler(Looper.getMainLooper())

    /** Announces this device on [port] until the returned function is called. */
    fun advertise(port: Int, deviceId: String, name: String, waitingToPair: Boolean): () -> Unit {
        val manager = nsd ?: return {}
        val lock = Any()
        var stopped = false
        var active: NsdManager.RegistrationListener? = null
        fun register(attempt: Int) {
            synchronized(lock) { if (stopped) return }
            val info = NsdServiceInfo().apply {
                serviceName = serviceName(name, waitingToPair)
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("id", deviceId)
                setAttribute("n", name.take(60))
                setAttribute("pair", if (waitingToPair) "1" else "0")
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) { if (active === this) active = null }
                    failed(attempt, "error $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                fun failed(attempt: Int, why: String) {
                    if (attempt < REGISTER_RETRIES) {
                        log("discovery: announcing this device failed ($why), trying again")
                        handler.postDelayed({ register(attempt + 1) }, RETRY_MILLIS)
                    } else {
                        log("discovery: announcing this device failed ($why); other devices can still connect by the address")
                    }
                }
            }
            synchronized(lock) { active = listener }
            runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { e ->
                synchronized(lock) { if (active === listener) active = null }
                listener.failed(attempt, e.javaClass.simpleName)
            }
        }
        register(0)
        return {
            val listener = synchronized(lock) { stopped = true; active.also { active = null } }
            if (listener != null) runCatching { manager.unregisterService(listener) }
        }
    }

    /** The Notio devices announcing themselves now; the list follows them coming and going. Stops when collection stops. */
    @Suppress("DEPRECATION") // resolveService: one resolve at a time is all this needs, on every Android version
    fun devices(ownDeviceId: String): Flow<List<FoundDevice>> = callbackFlow {
        val manager = nsd
        if (manager == null) {
            log("discovery: this device has no network service discovery")
            trySend(emptyList()); awaitClose { }; return@callbackFlow
        }
        val found = LinkedHashMap<String, FoundDevice>()
        val seen = LinkedHashSet<String>() // names of every Notio device this page saw, for the log line at the end
        val queue = ArrayDeque<Pair<NsdServiceInfo, Int>>()
        var resolving = false
        var closed = false
        fun publish() { trySend(found.values.toList()) }
        fun next() {
            if (resolving || closed) return
            val (info, attempt) = queue.removeFirstOrNull() ?: return
            resolving = true
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(found) {
                        resolving = false
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < RESOLVE_RETRIES) {
                            handler.postDelayed({ synchronized(found) { queue.addLast(info to attempt + 1); next() } }, RETRY_MILLIS)
                        } else {
                            log("discovery: \"${info.serviceName}\" was seen but could not be reached (error $errorCode)")
                        }
                        next()
                    }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    synchronized(found) {
                        resolving = false
                        val attrs = info.attributes
                        val id = attrs["id"]?.let { String(it) }
                        val host = info.host?.hostAddress
                        if (id != null && id != ownDeviceId && host != null) {
                            val device = FoundDevice(id, attrs["n"]?.let { String(it) } ?: info.serviceName, host, info.port, attrs["pair"]?.let { String(it) } == "1")
                            // One entry per device: the same device announced under its other name (waiting / not) replaces it.
                            found.entries.removeAll { it.value.deviceId == id && it.key != info.serviceName }
                            found[info.serviceName] = device
                            seen += device.name
                            publish()
                        }
                        next()
                    }
                }
            }
            runCatching { manager.resolveService(info, listener) }.onFailure {
                resolving = false
                log("discovery: \"${info.serviceName}\" could not be looked up (${it.javaClass.simpleName})")
                next()
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("discovery: searching could not start (error $errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(found) { queue.addLast(info to 0); next() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { if (found.remove(info.serviceName) != null) publish() }
            }
        }
        trySend(emptyList())
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { log("discovery: searching could not start (${it.javaClass.simpleName})") }
        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            val names = synchronized(found) { closed = true; seen.toList() }
            log(if (names.isEmpty()) "discovery: no other Notio device was seen on this network" else "discovery: seen " + names.joinToString(", "))
        }
    }

    /** This phone's addresses on the local network (IPv4), to show and for typing on the other device when discovery fails. */
    fun ownAddresses(): List<String> = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork)
        props?.linkAddresses.orEmpty().map { it.address }.filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    /** Whether the phone is on Wi-Fi or Ethernet (a local network), not only on mobile data. */
    fun onLocalNetwork(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    companion object {
        const val SERVICE_TYPE = "_notio-sync._tcp."
        private const val REGISTER_RETRIES = 2
        private const val RESOLVE_RETRIES = 3
        private const val RETRY_MILLIS = 1500L

        /**
         * The DNS-SD instance name: "Notio <name>", plus " (pairing)" while waiting with a code, cut to the 63 bytes of UTF-8
         * a DNS label allows (a longer name, e.g. a Cyrillic one, made the announcement fail).
         */
        fun serviceName(name: String, waitingToPair: Boolean): String {
            val suffix = if (waitingToPair) " (pairing)" else ""
            var base = "Notio " + name.trim()
            while (base.isNotEmpty() && (base + suffix).toByteArray(Charsets.UTF_8).size > 63) base = base.dropLast(1)
            if (base.lastOrNull()?.isHighSurrogate() == true) base = base.dropLast(1)
            return base.trimEnd() + suffix
        }
    }
}
