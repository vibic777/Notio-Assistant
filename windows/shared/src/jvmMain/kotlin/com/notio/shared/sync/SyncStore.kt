package com.notio.shared.sync

import com.sun.jna.platform.win32.Crypt32Util
import com.notio.shared.transfer.ImportMode
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which way data goes, seen from the device that starts the sync (Android D226). */
enum class SyncDirection { SEND, RECEIVE, BOTH }

/**
 * A device this one is paired with (Android D226), kept until the person removes it. [secret] is the pairing key sealed with
 * [SecretBox]; it never leaves this computer and is not in backups. The last choices of the sync dialog are kept per pairing.
 */
@Serializable
data class Pairing(
    val id: String,
    val peerDeviceId: String,
    val peerName: String,
    val secret: String,
    val createdAt: Long,
    val lastSyncAt: Long = 0,
    val lastResult: String? = null,
    val lastHost: String? = null,
    val lastPort: Int = 0,
    val direction: SyncDirection = SyncDirection.BOTH,
    /** `ImportMode` name: MERGE, NEW_IDS or REPLACE. */
    val mode: String = "MERGE",
    val allFolders: Boolean = true,
    val folderIds: List<String> = emptyList(),
    /** The other device's folders as it told them last time (for choosing what to receive). */
    val peerFolders: List<FolderInfo> = emptyList(),
    /**
     * What a sync of this connection asks every time (D316). What is not asked is set once on the card of the connection and
     * taken from [direction], [mode], [allFolders] and [folderIds]; what is asked opens the dialog at the last choice.
     */
    val askDirection: Boolean = true,
    val askMode: Boolean = true,
    val askFolders: Boolean = true,
    /** Whether the reminders travel with the notes (D319): chosen with the folders, kept and set beforehand with them. */
    val reminders: Boolean = true,
)

/** The choices set beforehand for a connection (D316). */
object SyncPresets {
    fun modeOf(p: Pairing): ImportMode = runCatching { ImportMode.valueOf(p.mode) }.getOrDefault(ImportMode.MERGE)

    /** `null`: all folders. */
    fun foldersOf(p: Pairing): Set<String>? = if (p.allFolders) null else p.folderIds.toSet()

    /**
     * Nothing of this connection is asked: the direction, the way and the folders are all set beforehand. A sync of such a
     * connection is not asked about on the other device either, whatever its way is (D322, D333).
     */
    fun nothingAsked(p: Pairing): Boolean = !p.askDirection && !p.askMode && !p.askFolders

    /**
     * A press of Synchronize starts at once: nothing is asked, something is chosen, and it is not a replacement — that one
     * takes away what is on the receiving device, so it is always shown before it starts.
     */
    fun startsAtOnce(p: Pairing): Boolean =
        !p.askDirection && !p.askMode && !p.askFolders && modeOf(p) != ImportMode.REPLACE && (p.allFolders || p.folderIds.isNotEmpty())

    /** A replacement needs one direction (of all folders or of chosen ones, D325); a direction still asked may allow it later. */
    fun replaceAllowed(p: Pairing): Boolean = p.askDirection || p.direction != SyncDirection.BOTH

    /** A replacement set before, which what is set now no longer allows, becomes a merge. */
    private fun settled(p: Pairing): Pairing =
        if (!p.askMode && modeOf(p) == ImportMode.REPLACE && !replaceAllowed(p)) p.copy(mode = ImportMode.MERGE.name) else p

    /** [direction] `null`: asked every time. */
    fun withDirection(p: Pairing, direction: SyncDirection?): Pairing =
        settled(if (direction == null) p.copy(askDirection = true) else p.copy(askDirection = false, direction = direction))

    /** [mode] `null`: asked every time. */
    fun withMode(p: Pairing, mode: ImportMode?): Pairing =
        settled(if (mode == null) p.copy(askMode = true) else p.copy(askMode = false, mode = mode.name))

    /** With or without the reminders (D319); it goes with the folders, asked or set as they are. */
    fun withReminders(p: Pairing, reminders: Boolean): Pairing = p.copy(reminders = reminders)

    /** [ask]: asked every time; otherwise [folders], `null` for all of them. */
    fun withFolders(p: Pairing, ask: Boolean, folders: Collection<String>?): Pairing =
        settled(if (ask) p.copy(askFolders = true) else p.copy(askFolders = false, allFolders = folders == null, folderIds = folders?.toList() ?: p.folderIds))
}

/** Seals small secrets for this user on this computer (the Android Keystore's `SecretBox`). */
interface SecretBox {
    fun seal(plain: String): String

    /** `null` when [sealed] cannot be opened (another user, another computer, damaged). */
    fun open(sealed: String): String?

    companion object {
        /** Windows DPAPI (the user's own key, AGENT.md D04); elsewhere a plain wrapper until the Linux keyring is wired. */
        fun forThisSystem(): SecretBox = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Dpapi else Plain
    }

    /** `CryptProtectData` of the current Windows user: nobody else, and no other computer, can open it. */
    object Dpapi : SecretBox {
        override fun seal(plain: String): String =
            "dpapi:" + Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain.toByteArray(Charsets.UTF_8)))

        override fun open(sealed: String): String? = runCatching {
            if (!sealed.startsWith("dpapi:")) return Plain.open(sealed)
            String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(sealed.removePrefix("dpapi:"))), Charsets.UTF_8)
        }.getOrNull()
    }

    object Plain : SecretBox {
        override fun seal(plain: String) = "plain:" + plain
        override fun open(sealed: String): String? = sealed.takeIf { it.startsWith("plain:") }?.removePrefix("plain:")
    }
}

/**
 * The folders chosen for a sync (D324). The same folder has its own id on each device until one sync has carried it over —
 * "Notes" made on the phone and "Notes" made on the computer are two ids — and an import folds folders together by their
 * name. So a folder is chosen by its name: one line for a name in the list, and every id of that name is asked for.
 */
object SyncFolders {
    private fun key(name: String) = name.trim().lowercase()

    /** The lines of the list: the folders of this device and of the other one, a name once. */
    fun choices(local: List<FolderInfo>, peer: List<FolderInfo>): List<FolderInfo> =
        (local + peer).distinctBy { key(it.name) }.sortedBy { key(it.name) }

    /** [chosen] with every id that [known] has under the same names. An id nobody knows any more stays as it is. */
    fun expand(chosen: Set<String>, known: List<FolderInfo>): Set<String> {
        val names = known.filter { it.id in chosen }.mapTo(HashSet()) { key(it.name) }
        return chosen + known.filter { key(it.name) in names }.map { it.id }
    }

    /** [chosen] after the line of [folder] is ticked or cleared: clearing takes away every id of its name. */
    fun toggled(chosen: Set<String>, folder: FolderInfo, on: Boolean, known: List<FolderInfo>): Set<String> =
        if (on) chosen + folder.id else chosen - known.filter { key(it.name) == key(folder.name) }.map { it.id }.toSet() - folder.id

    /** Whether the line of [folder] is ticked. */
    fun isChosen(chosen: Set<String>, folder: FolderInfo, known: List<FolderInfo>): Boolean =
        folder.id in chosen || known.any { it.id in chosen && key(it.name) == key(folder.name) }

    /** How many folders [chosen] is, for "Chosen: 3": a name once. */
    fun count(chosen: Collection<String>, known: List<FolderInfo>): Int {
        val names = known.associate { it.id to key(it.name) }
        return chosen.map { names[it] ?: it }.distinct().size
    }
}

/**
 * The pairings, this device's identity and the sync log (Android `SyncStore`), in `sync.json` in the data folder. Nothing here goes
 * into a backup: a restored computer is paired again.
 */
class SyncStore(private val file: File, private val box: SecretBox = SecretBox.forThisSystem()) {
    @Serializable
    private data class Stored(
        val deviceId: String? = null,
        val deviceName: String? = null,
        val pairings: List<Pairing> = emptyList(),
        val log: List<String> = emptyList(),
        val listenPort: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var stored: Stored = runCatching { com.notio.shared.data.SafeFile.readText(file)?.let { json.decodeFromString(Stored.serializer(), it) } }.getOrNull() ?: Stored()

    private val _pairings = MutableStateFlow(stored.pairings)
    val pairings: StateFlow<List<Pairing>> = _pairings.asStateFlow()

    private val _log = MutableStateFlow(stored.log)
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Synchronized
    private fun write(change: (Stored) -> Stored) {
        stored = change(stored)
        com.notio.shared.data.SafeFile.writeText(file, json.encodeToString(Stored.serializer(), stored))
        _pairings.value = stored.pairings
        _log.value = stored.log
    }

    /** A random id of this installation, made once; it tells paired devices apart, it names nobody. */
    val deviceId: String
        @Synchronized get() = stored.deviceId ?: UUID.randomUUID().toString().also { id -> write { it.copy(deviceId = id) } }

    var deviceName: String
        get() = stored.deviceName?.takeIf { it.isNotBlank() } ?: defaultName()
        set(value) = write { it.copy(deviceName = value.trim().take(40)) }

    /**
     * The port this computer listens on, chosen by hand (D135, Android D357); 0 — by itself: [SyncEngine.DEFAULT_PORT], and any
     * free one when that is taken.
     */
    var listenPort: Int
        get() = stored.listenPort.takeIf { it in SyncAddress.LISTEN_PORTS } ?: 0
        set(value) = write { it.copy(listenPort = value.takeIf { p -> p in SyncAddress.LISTEN_PORTS } ?: 0) }

    /** The computer's name, as Windows shows it. */
    private fun defaultName(): String =
        (System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC").trim().take(40)

    fun get(id: String): Pairing? = _pairings.value.firstOrNull { it.id == id }

    /** The pairing key (32 bytes), or `null` when it cannot be opened: pair again then. */
    fun secretOf(pairing: Pairing): ByteArray? = box.open(pairing.secret)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    @Synchronized
    fun add(id: String, peerDeviceId: String, peerName: String, secret: ByteArray, now: Long) {
        val sealed = box.seal(Base64.getEncoder().encodeToString(secret))
        // Pairing the same device again replaces the old pairing with it.
        write { s -> s.copy(pairings = s.pairings.filter { it.peerDeviceId != peerDeviceId && it.id != id } + Pairing(id, peerDeviceId, peerName, sealed, now)) }
    }

    @Synchronized
    fun update(id: String, change: (Pairing) -> Pairing) = write { s -> s.copy(pairings = s.pairings.map { if (it.id == id) change(it) else it }) }

    @Synchronized
    fun remove(id: String) = write { s -> s.copy(pairings = s.pairings.filter { it.id != id }) }

    /** One line of the sync log (never a secret or a code); the newest [LOG_LINES] are kept. */
    @Synchronized
    fun log(line: String, now: Long = System.currentTimeMillis()) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(now))
        write { s -> s.copy(log = (listOf("$stamp  $line") + s.log).take(LOG_LINES)) }
    }

    @Synchronized
    fun clearLog() = write { it.copy(log = emptyList()) }

    private companion object {
        const val LOG_LINES = 200
    }
}
