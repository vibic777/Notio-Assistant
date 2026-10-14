package com.notebudget.app.data.sync

import com.notebudget.app.data.transfer.ImportMode
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What sync needs from the app's data (D226): the folders, a backup stream of some folders, an import of such a stream. */
interface SyncData {
    suspend fun folders(): List<FolderInfo>
    suspend fun export(out: OutputStream, folderIds: Set<String>?)
    /** Imports [file]; returns the counts: folders added, folders merged, notes added, notes updated, notes skipped, files. */
    suspend fun import(file: File, mode: ImportMode): List<Int>

    /**
     * The same with a say about the reminders (D319): with [reminders] `false` they are left out of what is sent, and what
     * is received leaves the reminders of this device as they are. A source that knows nothing of it does as before.
     */
    suspend fun export(out: OutputStream, folderIds: Set<String>?, reminders: Boolean) = export(out, folderIds)

    suspend fun import(file: File, mode: ImportMode, reminders: Boolean): List<Int> = import(file, mode)

    /**
     * The same for a sync of chosen [folders] (D325): a replacement then replaces these folders only. A source that knows
     * nothing of it must not wipe the device instead, so it refuses.
     */
    suspend fun import(file: File, mode: ImportMode, reminders: Boolean, folders: Set<String>?): List<Int> {
        if (mode == ImportMode.REPLACE && folders != null) throw SyncException(SyncReasons.BROKEN, "a replacement of chosen folders is not known here")
        return import(file, mode, reminders)
    }
}

/** A question to the person on this device, answered on the Sync page (D226). */
sealed interface SyncPrompt {
    val answer: CompletableDeferred<Boolean>

    /** Another device typed this device's code: pair with it? */
    data class Pair(val peerName: String, override val answer: CompletableDeferred<Boolean>) : SyncPrompt

    /**
     * A paired device wants to sync. [direction] is seen from HERE: SEND = this device sends, RECEIVE = this device receives.
     * [clockOffset]: the other device's clock minus this one's (ms), shown when it is large (merge goes by time).
     */
    data class Request(
        val peerName: String,
        val direction: SyncDirection,
        val mode: ImportMode,
        val folderNames: List<String>?,
        val clockOffset: Long,
        override val answer: CompletableDeferred<Boolean>,
        /** Whether the reminders travel too (D319). */
        val reminders: Boolean = true,
    ) : SyncPrompt
}

/** The result of a sync started here: what this device received and what the other one reported. */
data class SyncOutcome(val received: List<Int>?, val sent: List<Int>?, val bytesOut: Long, val bytesIn: Long, val clockOffset: Long)

/** One step of Test connection (D226). */
data class SyncCheck(val step: String, val ok: Boolean, val detail: String)

/**
 * Sync between two Notio devices on the same local network (D226). Nothing runs unless the Sync page is open: [startListening]
 * opens a TCP port and announces the device; [stopListening] closes both. Pairing: one device waits with a code
 * ([startPairing]), the other connects with that code ([pair]); both keep the pairing, later syncs reuse it ([sync]).
 * Every connection is encrypted and authenticated ([SyncSession]); every change of data on a device is asked there first.
 */
class SyncEngine(
    private val store: SyncStore,
    private val discovery: LanDiscovery?,
    private val data: SyncData,
    private val tempDir: File,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Free storage where incoming data is put (bytes); see [SyncSession.SPACE_RESERVE]. */
    private val freeBytes: () -> Long = { tempDir.apply { mkdirs() }.usableSpace },
) {
    data class Listening(val port: Int, val addresses: List<String>)

    private val _listening = MutableStateFlow<Listening?>(null)
    val listening: StateFlow<Listening?> = _listening.asStateFlow()

    private val _portBusy = MutableStateFlow<Int?>(null)
    /**
     * The port chosen by hand that could not be opened: another program has it (D357). This device does not listen then — it
     * takes no other port by itself, the person chose this one. `null` when it listens or was not asked to.
     */
    val portBusy: StateFlow<Int?> = _portBusy.asStateFlow()

    private val _code = MutableStateFlow<String?>(null)
    /** The pairing code shown while this device waits to be paired; `null` when it does not wait. */
    val pairingCode: StateFlow<String?> = _code.asStateFlow()

    private val _prompt = MutableStateFlow<SyncPrompt?>(null)
    val prompt: StateFlow<SyncPrompt?> = _prompt.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** What an incoming connection is doing now (a key of [SyncStatus]), for the page. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _nearby = MutableStateFlow<List<FoundDevice>>(emptyList())
    val nearby: StateFlow<List<FoundDevice>> = _nearby.asStateFlow()

    /** A pairing made by the other side while this one waited (for the page to say "paired with …"). */
    private val _pairedEvent = MutableStateFlow<String?>(null)
    val pairedEvent: StateFlow<String?> = _pairedEvent.asStateFlow()

    private var server: ServerSocket? = null
    private var serverJob: Job? = null
    private var discoveryJob: Job? = null
    private var looking = true
    private var unadvertise: () -> Unit = {}
    /** Wrong pairing codes in a row: counted on the server thread, reset from the page. */
    private val wrongCodes = java.util.concurrent.atomic.AtomicInteger()
    private val clientLock = Mutex()

    // ---- listening (only while the Sync page is open) ----

    /** The port of the person when one is chosen (and nothing when it is taken); otherwise the usual one, or any free one. */
    private fun openPort(): ServerSocket? {
        val chosen = store.listenPort
        if (chosen == 0) {
            _portBusy.value = null
            return runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(DEFAULT_PORT)) } }
                .recoverCatching { ServerSocket(0) }.getOrNull()
        }
        val socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(chosen)) } }.getOrNull()
        if (socket == null && _portBusy.value != chosen) store.log("the port $chosen chosen by hand is taken by another program: not listening")
        _portBusy.value = if (socket == null) chosen else null
        return socket
    }

    /**
     * Whether [port] can be listened on now (D357): the one this device listens on already is free for it. It opens the port for
     * a moment, so it is called off the UI thread.
     */
    fun isPortFree(port: Int): Boolean =
        server?.localPort == port || runCatching { ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(port)) } }.isSuccess

    @Synchronized
    fun startListening() {
        if (server != null) return
        val socket = openPort() ?: return
        server = socket
        _listening.value = Listening(socket.localPort, discovery?.ownAddresses().orEmpty())
        advertise()
        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // Only the local network (D231): on mobile data the phone may have a public IPv6 address.
                if (!isLocal(client.inetAddress)) {
                    store.log("incoming: refused " + client.inetAddress.hostAddress + " (not on the local network)")
                    runCatching { client.close() }
                    continue
                }
                runCatching { handle(client) }.onFailure { e -> store.log("incoming: " + describe(e)) }
                runCatching { client.close() }
                _status.value = null
            }
        }
        discoveryJob = if (looking) look() else null
    }

    private fun look(): Job? = discovery?.let { d -> scope.launch { d.devices(store.deviceId).collect { _nearby.value = it } } }

    /**
     * The search of the network and the announcement of this device are for the time Notio is on the screen (D345, D353). The
     * search asks the network again and again; the announcement keeps the phone in the multicast group, so the Wi-Fi wakes it
     * for every such packet. While the phone only waits in the background (D342) both are off and only the port waits: a
     * paired device reaches it by the address of the last sync. [stopListening] turns them back on for the next time.
     */
    @Synchronized
    fun lookForDevices(on: Boolean) {
        looking = on
        if (!on) {
            discoveryJob?.cancel(); discoveryJob = null
            _nearby.value = emptyList()
            unadvertise(); unadvertise = {}
        } else if (server != null) {
            advertise()
            if (discoveryJob == null) discoveryJob = look()
        }
    }

    /**
     * The network changed while the port was open (D348): mobile data or a VPN at the start, Wi-Fi now. The addresses of this
     * device are read again, it is announced again and the search starts anew; the port, a code that is shown and a question
     * that is asked stay. Asked for by a button of the Sync page: nothing watches the network for it.
     */
    @Synchronized
    fun recheckNetwork() {
        val socket = server ?: return
        val addresses = discovery?.ownAddresses().orEmpty()
        _listening.value = Listening(socket.localPort, addresses)
        advertise()
        discoveryJob?.cancel(); discoveryJob = null
        _nearby.value = emptyList()
        if (looking) discoveryJob = look()
        store.log("the connections were checked again: " + addresses.ifEmpty { listOf("no local network") }.joinToString(", "))
    }

    /**
     * Before "Find a device" opens (D352): the whole of [recheckNetwork] when the addresses of this device are not the ones it
     * listens with; nothing when they are the same, so the devices already found stay.
     */
    @Synchronized
    fun recheckNetworkIfChanged() {
        val listens = _listening.value ?: return
        if (discovery?.ownAddresses().orEmpty() != listens.addresses) recheckNetwork()
    }

    @Synchronized
    fun stopListening() {
        looking = true
        unadvertise(); unadvertise = {}
        runCatching { server?.close() }
        server = null
        serverJob?.cancel(); serverJob = null
        discoveryJob?.cancel(); discoveryJob = null
        _listening.value = null
        _portBusy.value = null
        _nearby.value = emptyList()
        _code.value = null
        _prompt.value?.answer?.complete(false)
        _prompt.value = null
    }

    @Synchronized
    private fun advertise() {
        unadvertise(); unadvertise = {}
        // In the background the phone is silent on the network (D353).
        if (!looking) return
        val port = server?.localPort ?: return
        unadvertise = discovery?.advertise(port, store.deviceId, store.deviceName, _code.value != null) ?: {}
    }

    /** Server role of pairing: shows a new code and waits for a device that types it. */
    @Synchronized
    fun startPairing() {
        wrongCodes.set(0)
        _pairedEvent.value = null
        _code.value = SyncCrypto.newCode()
        advertise()
    }

    @Synchronized
    fun stopPairing() {
        _code.value = null
        advertise()
    }

    fun clearPairedEvent() { _pairedEvent.value = null }

    private fun hello(purpose: String, pairingId: String? = null) = Hello(purpose = purpose, deviceId = store.deviceId, name = store.deviceName, pairingId = pairingId)

    private suspend fun ask(prompt: SyncPrompt): Boolean {
        _prompt.value = prompt
        val ok = withTimeoutOrNull(PROMPT_MILLIS) { prompt.answer.await() } ?: false
        if (_prompt.value === prompt) _prompt.value = null
        return ok
    }

    // ---- the answering side ----

    private suspend fun handle(socket: Socket) {
        // Connections are answered one at a time: one that does not finish its handshake soon must not hold the others up.
        socket.soTimeout = HANDSHAKE_TIMEOUT
        var pairing: Pairing? = null
        val session = try {
            SyncSession.accept(socket.getInputStream(), socket.getOutputStream(), hello("sync"), keyFor = { h, salt ->
                runCatching {
                    when (h.purpose) {
                        "pair" -> SyncCrypto.stretchCode(_code.value ?: throw SyncException(SyncReasons.NOT_WAITING), salt)
                        else -> {
                            val p = store.pairings.value.firstOrNull { it.id == h.pairingId && it.peerDeviceId == h.deviceId }
                                ?: throw SyncException(SyncReasons.UNKNOWN_PAIRING)
                            pairing = p
                            store.secretOf(p) ?: throw SyncException(SyncReasons.UNKNOWN_PAIRING)
                        }
                    }
                }
            }, now = now)
        } catch (e: SyncException) {
            if (e.reason == SyncReasons.WRONG_CODE && _code.value != null && wrongCodes.incrementAndGet() >= 3) {
                // Three wrong codes: a new one, so guessing gains nothing.
                store.log("pairing: 3 wrong codes, a new code was made")
                startPairing()
            }
            throw e
        }
        socket.soTimeout = IO_TIMEOUT // a known device now: a big backup may take its time
        if (session.peer.purpose == "pair") return answerPairing(session)
        val p = pairing ?: return
        store.update(p.id) { it.copy(lastHost = socket.inetAddress.hostAddress, peerName = session.peer.name.ifBlank { it.peerName }) }
        while (true) {
            val message = try { session.receive() } catch (e: SyncException) { return }
            when (message.t) {
                "ping" -> session.send(SyncMessage("pong", time = now()))
                "list-folders" -> session.send(SyncMessage("folders", folderList = data.folders()))
                "unpair" -> {
                    store.remove(p.id)
                    store.log("${p.peerName}: removed the pairing")
                    session.send(SyncMessage("ok"))
                    return
                }
                "request" -> answerRequest(session, p, message)
                "bye" -> return
                else -> session.send(SyncMessage("error", reason = SyncReasons.BROKEN))
            }
        }
    }

    private suspend fun answerPairing(session: SyncSession) {
        val name = session.peer.name.ifBlank { "?" }
        _status.value = SyncStatus.PAIRING
        if (!ask(SyncPrompt.Pair(name, CompletableDeferred()))) {
            session.send(SyncMessage("error", reason = SyncReasons.DENIED))
            store.log("pairing with $name: refused here")
            return
        }
        val id = UUID.randomUUID().toString()
        val secret = SyncCrypto.randomBytes(32)
        session.send(SyncMessage("pair-ok", pairingId = id, secret = java.util.Base64.getEncoder().encodeToString(secret)))
        store.add(id, session.peer.deviceId, name, secret, now())
        store.log("paired with $name")
        _code.value = null
        advertise()
        _pairedEvent.value = name
    }

    private suspend fun answerRequest(session: SyncSession, p: Pairing, message: SyncMessage) {
        val theirDirection = runCatching { SyncDirection.valueOf(message.direction.orEmpty()) }.getOrNull()
        val mode = runCatching { ImportMode.valueOf(message.mode.orEmpty()) }.getOrNull()
        if (theirDirection == null || mode == null || !allowed(theirDirection, mode, message.folders)) {
            session.send(SyncMessage("decision", ok = false, reason = SyncReasons.BROKEN)); return
        }
        val here = when (theirDirection) { SyncDirection.SEND -> SyncDirection.RECEIVE; SyncDirection.RECEIVE -> SyncDirection.SEND; SyncDirection.BOTH -> SyncDirection.BOTH }
        if (here != SyncDirection.SEND && freeBytes() < SyncSession.SPACE_RESERVE) {
            store.log("${p.peerName}: sync refused here, too little free storage")
            session.send(SyncMessage("decision", ok = false, reason = SyncReasons.NO_SPACE)); return
        }
        val ids = message.folders?.toSet()
        // A device of an older Notio says nothing of the reminders: they travel, as they did (D319).
        val withReminders = message.reminders != false
        val names = ids?.let { set -> (data.folders() + p.peerFolders).filter { it.id in set }.distinctBy { it.id }.map { it.name } }
        // The starting device has everything set beforehand for this connection (D322): no question here — a replacement
        // included (D333, the owner's rule: nothing asked there, nothing asked here). A device of an older Notio never says
        // "set beforehand" and is asked.
        val asked = message.unattended != true
        val ok = if (asked) ask(SyncPrompt.Request(p.peerName, here, mode, names, session.clockOffset, CompletableDeferred(), reminders = withReminders)) else true
        if (!asked) store.log("${p.peerName}: sync accepted without a question (everything is set beforehand there)")
        session.send(SyncMessage("decision", ok = ok, reason = if (ok) null else SyncReasons.DENIED))
        if (!ok) { store.log("${p.peerName}: sync refused here"); return }
        store.log("${p.peerName}: sync started (${here.name.lowercase()} here, ${mode.name.lowercase()})")
        var received: List<Int>? = null
        if (theirDirection != SyncDirection.RECEIVE) {
            _status.value = SyncStatus.RECEIVING
            received = receiveAndImport(session, mode, withReminders, ids)
            session.send(SyncMessage("result", ok = true, text = received.joinToString(",")))
        }
        if (theirDirection != SyncDirection.SEND) {
            _status.value = SyncStatus.SENDING
            session.sendData { out -> data.export(out, ids, withReminders) }
            session.receive() // their result
        }
        store.update(p.id) { it.copy(lastSyncAt = now(), lastResult = received?.joinToString(",")) }
        store.log("${p.peerName}: sync done")
    }

    private suspend fun receiveAndImport(session: SyncSession, mode: ImportMode, reminders: Boolean, folders: Set<String>?): List<Int> {
        tempDir.mkdirs()
        val file = File(tempDir, "incoming-" + UUID.randomUUID() + ".zip")
        try {
            session.receiveData(file, freeBytes = freeBytes)
            return data.import(file, mode, reminders, folders)
        } finally {
            file.delete()
        }
    }

    // ---- the connecting side ----

    /** Where [pairing]'s device is now: found on the network by its id, else the address it had last time. */
    fun locate(pairing: Pairing): Pair<String, Int>? {
        _nearby.value.firstOrNull { it.deviceId == pairing.peerDeviceId }?.let { return it.host to it.port }
        return pairing.lastHost?.let { it to (pairing.lastPort.takeIf { p -> p > 0 } ?: DEFAULT_PORT) }
    }

    private fun open(host: String, port: Int): Socket = Socket().apply {
        connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
        soTimeout = IO_TIMEOUT
    }

    /** Client role of pairing: connects to [host]:[port] with the [code] shown there; the pairing is kept on both sides. */
    suspend fun pair(host: String, port: Int, code: String): Result<Pairing> = clientLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                open(host, port).use { socket ->
                    val session = SyncSession.connect(socket.getInputStream(), socket.getOutputStream(), hello("pair"), { salt -> SyncCrypto.stretchCode(code, salt) }, now)
                    socket.soTimeout = (PROMPT_MILLIS + IO_TIMEOUT).toInt() // the person there is asked first
                    val answer = session.receive()
                    if (answer.t != "pair-ok") throw SyncException(answer.reason ?: SyncReasons.BROKEN)
                    val id = answer.pairingId ?: throw SyncException(SyncReasons.BROKEN)
                    val secret = java.util.Base64.getDecoder().decode(answer.secret ?: throw SyncException(SyncReasons.BROKEN))
                    store.add(id, session.peer.deviceId, session.peer.name.ifBlank { host }, secret, now())
                    store.update(id) { it.copy(lastHost = host, lastPort = port) }
                    store.log("paired with ${session.peer.name}")
                    store.get(id) ?: throw SyncException(SyncReasons.BROKEN)
                }
            }.onFailure { store.log("pairing with $host: " + describe(it)) }
        }
    }

    private fun connectPaired(pairing: Pairing): Pair<Socket, SyncSession> {
        val (host, port) = locate(pairing) ?: throw SyncException(NOT_FOUND)
        val socket = try { open(host, port) } catch (e: java.io.IOException) { throw SyncException(NOT_FOUND, e.message ?: NOT_FOUND, e) }
        try {
            val secret = store.secretOf(pairing) ?: throw SyncException(SyncReasons.UNKNOWN_PAIRING)
            val session = SyncSession.connect(socket.getInputStream(), socket.getOutputStream(), hello("sync", pairing.id), { secret }, now)
            store.update(pairing.id) { it.copy(lastHost = host, lastPort = port, peerName = session.peer.name.ifBlank { it.peerName }) }
            return socket to session
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** The other device's folders, kept in the pairing for choosing what to receive. */
    suspend fun fetchFolders(pairingId: String): Result<List<FolderInfo>> = clientLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val pairing = store.get(pairingId) ?: throw SyncException(SyncReasons.UNKNOWN_PAIRING)
                val (socket, session) = connectPaired(pairing)
                socket.use {
                    session.send(SyncMessage("list-folders"))
                    val list = session.receive().folderList.orEmpty()
                    session.send(SyncMessage("bye"))
                    store.update(pairingId) { it.copy(peerFolders = list) }
                    list
                }
            }
        }
    }

    /**
     * Syncs with a paired device: [direction] seen from here, [mode] as in an import, [folderIds] `null` = all folders.
     * The other device asks its person first. The choices are remembered in the pairing.
     *
     * Chosen folders go by their names (D324): the other device is asked for its folders first, and every id that either
     * device has under a chosen name is synced. Before that a folder chosen here was unknown there under its id, the other
     * device had nothing to send for it, and the sync ended "done" with nothing changed.
     */
    suspend fun sync(pairingId: String, direction: SyncDirection, mode: ImportMode, folderIds: Set<String>?, reminders: Boolean = true, unattended: Boolean = false): Result<SyncOutcome> = clientLock.withLock {
        withContext(Dispatchers.IO) {
            val pairing = store.get(pairingId)
            runCatching {
                pairing ?: throw SyncException(SyncReasons.UNKNOWN_PAIRING)
                if (!allowed(direction, mode, folderIds?.toList())) throw SyncException(SyncReasons.BROKEN, "replace needs one direction")
                if (direction != SyncDirection.SEND && freeBytes() < SyncSession.SPACE_RESERVE) throw SyncException(SyncReasons.NO_SPACE, "on this device")
                store.update(pairingId) { it.copy(direction = direction, mode = mode.name, allFolders = folderIds == null, folderIds = folderIds?.toList() ?: it.folderIds, reminders = reminders) }
                val (socket, session) = connectPaired(pairing)
                socket.use {
                    val folders = folderIds?.let { chosen ->
                        session.send(SyncMessage("list-folders"))
                        val theirs = session.receive().folderList.orEmpty()
                        if (theirs.isNotEmpty()) store.update(pairingId) { it.copy(peerFolders = theirs) }
                        SyncFolders.expand(chosen, data.folders() + theirs)
                    }
                    socket.soTimeout = (PROMPT_MILLIS + IO_TIMEOUT).toInt()
                    session.send(SyncMessage("request", direction = direction.name, mode = mode.name, folders = folders?.toList(), reminders = reminders, unattended = if (unattended) true else null))
                    val decision = session.receive()
                    if (decision.ok != true) throw SyncException(decision.reason ?: SyncReasons.DENIED)
                    socket.soTimeout = IO_TIMEOUT
                    store.log("${pairing.peerName}: sync started (${direction.name.lowercase()}, ${mode.name.lowercase()})")
                    var sent: List<Int>? = null
                    var received: List<Int>? = null
                    var bytesOut = 0L
                    var bytesIn = 0L
                    if (direction != SyncDirection.RECEIVE) {
                        bytesOut = session.sendData { out -> data.export(out, folders, reminders) }
                        sent = session.receive().text?.split(",")?.mapNotNull { it.toIntOrNull() }
                    }
                    if (direction != SyncDirection.SEND) {
                        tempDir.mkdirs()
                        val file = File(tempDir, "incoming-" + UUID.randomUUID() + ".zip")
                        try {
                            bytesIn = session.receiveData(file, freeBytes = freeBytes)
                            received = data.import(file, mode, reminders, folders)
                        } finally {
                            file.delete()
                        }
                        session.send(SyncMessage("result", ok = true, text = received.joinToString(",")))
                    }
                    runCatching { session.send(SyncMessage("bye")) }
                    store.update(pairingId) { it.copy(lastSyncAt = now(), lastResult = (received ?: sent)?.joinToString(",")) }
                    store.log("${pairing.peerName}: sync done")
                    SyncOutcome(received, sent, bytesOut, bytesIn, session.clockOffset)
                }
            }.onFailure { store.log((pairing?.peerName ?: "?") + ": sync failed: " + describe(it)) }
        }
    }

    /** Removes the pairing here and, when the other device is reachable now, there too. */
    suspend fun unpair(pairingId: String): Boolean = clientLock.withLock {
        withContext(Dispatchers.IO) {
            val pairing = store.get(pairingId) ?: return@withContext false
            val told = runCatching {
                val (socket, session) = connectPaired(pairing)
                socket.use { session.send(SyncMessage("unpair")); session.receive() }
            }.isSuccess
            store.remove(pairingId)
            store.log("${pairing.peerName}: pairing removed" + if (told) " on both devices" else " here (the other device was not reachable)")
            told
        }
    }

    /** Test connection (D226): each step with its result; nothing is transferred. */
    suspend fun test(pairingId: String): List<SyncCheck> = clientLock.withLock {
        withContext(Dispatchers.IO) {
            val out = ArrayList<SyncCheck>()
            val pairing = store.get(pairingId) ?: return@withContext listOf(SyncCheck("pairing", false, "not found"))
            val local = discovery?.onLocalNetwork() ?: true
            val addresses = discovery?.ownAddresses().orEmpty()
            out += SyncCheck("network", local, if (local) addresses.joinToString(", ").ifBlank { "?" } else "no Wi-Fi / Ethernet")
            out += SyncCheck("listening", _listening.value != null, _listening.value?.let { "port " + it.port } ?: "off")
            val seen = _nearby.value.firstOrNull { it.deviceId == pairing.peerDeviceId }
            val target = locate(pairing)
            out += SyncCheck("find", target != null, when {
                seen != null -> "found: ${seen.host}:${seen.port}"
                target != null -> "not announced now; last address ${target.first}:${target.second}"
                else -> "not found (open Settings -> Sync on the other device)"
            })
            if (target == null) return@withContext out
            val started = System.nanoTime()
            val socket = try { open(target.first, target.second) } catch (e: Exception) {
                out += SyncCheck("connect", false, describe(e)); return@withContext out
            }
            out += SyncCheck("connect", true, "${(System.nanoTime() - started) / 1_000_000} ms")
            socket.use {
                val session = try {
                    val secret = store.secretOf(pairing) ?: throw SyncException(SyncReasons.UNKNOWN_PAIRING)
                    SyncSession.connect(socket.getInputStream(), socket.getOutputStream(), hello("sync", pairing.id), { secret }, now)
                } catch (e: Exception) {
                    out += SyncCheck("handshake", false, describe(e)); return@withContext out
                }
                out += SyncCheck("handshake", true, "AES-256-GCM, ECDH P-256; ${session.peer.name}")
                val offset = session.clockOffset
                out += SyncCheck("clock", kotlin.math.abs(offset) < CLOCK_TOLERANCE, "${offset / 1000} s")
                val pingAt = System.nanoTime()
                val reply = runCatching { session.send(SyncMessage("ping")); session.receive() }
                out += SyncCheck("reply", reply.isSuccess, reply.fold({ "${(System.nanoTime() - pingAt) / 1_000_000} ms" }, { describe(it) }))
                runCatching { session.send(SyncMessage("bye")) }
            }
            store.log("${pairing.peerName}: test " + if (out.all { it.ok }) "ok" else "failed at " + out.first { !it.ok }.step)
            out
        }
    }

    companion object {
        const val DEFAULT_PORT = 47821
        const val NOT_FOUND = "not-found"
        private const val CONNECT_TIMEOUT = 5_000
        /** Hellos and proofs are a few hundred bytes each way; the stretching of a pairing code is well under a second. */
        private const val HANDSHAKE_TIMEOUT = 15_000
        /** Long enough for the other side to import a big backup before it answers. */
        private const val IO_TIMEOUT = 300_000
        private const val PROMPT_MILLIS = 90_000L
        /** Merge compares times of change: clocks further apart than this are shown as a problem. */
        const val CLOCK_TOLERANCE = 120_000L

        /**
         * A replacement takes away what is on the receiving side — everything, or what is in the chosen [folders] (D325) — so
         * only in one direction. A device of an older Notio refuses a replacement of chosen folders: nothing is lost there.
         */
        @Suppress("UNUSED_PARAMETER")
        fun allowed(direction: SyncDirection, mode: ImportMode, folders: List<String>?): Boolean =
            mode != ImportMode.REPLACE || direction != SyncDirection.BOTH

        /**
         * An address of the local network: loopback, link-local, private IPv4 (10/8, 172.16/12, 192.168/16), IPv6 unique local
         * (fc00::/7), or the shared carrier range 100.64/10 some hotspots use.
         */
        fun isLocal(address: java.net.InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            val b = address.address
            return when (b.size) {
                4 -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
                16 -> (b[0].toInt() and 0xFE) == 0xFC
                else -> false
            }
        }

        fun describe(e: Throwable): String = when (e) {
            is SyncException -> e.reason + if (e.message != null && e.message != e.reason) " (" + e.message + ")" else ""
            is java.net.SocketTimeoutException -> "timeout"
            is java.net.ConnectException -> "refused: " + (e.message ?: "")
            else -> (e.message ?: e.javaClass.simpleName).take(200)
        }
    }
}

/** What an incoming connection is doing (shown on the page). */
object SyncStatus {
    const val PAIRING = "pairing"
    const val RECEIVING = "receiving"
    const val SENDING = "sending"
}
