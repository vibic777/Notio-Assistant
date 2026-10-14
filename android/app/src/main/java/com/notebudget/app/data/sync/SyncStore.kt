package com.notebudget.app.data.sync

import android.content.Context
import com.notebudget.app.core.security.SecretBox
import com.notebudget.app.data.transfer.ImportMode
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Which way data goes, seen from the device that starts the sync (D226). */
enum class SyncDirection { SEND, RECEIVE, BOTH }

/**
 * A device this one is paired with (D226), kept until the person removes it: the connection is resumed by it at every later sync,
 * no code again. [secret] is the pairing key sealed with this phone's Keystore; it never leaves the phone and is not in backups.
 * The last choices of the sync dialog are kept per pairing ([direction], [mode], folders).
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
 * The pairings, this device's identity and the sync log (D226), in private preferences. Nothing here goes into a backup:
 * a restored phone is paired again.
 */
class SyncStore(context: Context, private val box: SecretBox, prefsName: String = "sync") {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val listSerializer = ListSerializer(Pairing.serializer())

    private val _pairings = MutableStateFlow(load())
    val pairings: StateFlow<List<Pairing>> = _pairings.asStateFlow()

    private val _log = MutableStateFlow(loadLog())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** A random id of this installation, made once; it tells paired devices apart, it names nobody. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName()
        set(value) { prefs.edit().putString(KEY_DEVICE_NAME, value.trim().take(40)).apply() }

    /**
     * The port this phone listens on, chosen by hand (D357); 0 — by itself: [SyncEngine.DEFAULT_PORT], and any free one when
     * that is taken.
     */
    var listenPort: Int
        get() = prefs.getInt(KEY_LISTEN_PORT, 0).takeIf { it in SyncAddress.LISTEN_PORTS } ?: 0
        set(value) { prefs.edit().putInt(KEY_LISTEN_PORT, value.takeIf { it in SyncAddress.LISTEN_PORTS } ?: 0).apply() }

    private fun defaultName(): String = (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).trim().take(40)

    private fun load(): List<Pairing> =
        prefs.getString(KEY_PAIRINGS, null)?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

    @Synchronized
    private fun save(list: List<Pairing>) {
        prefs.edit().putString(KEY_PAIRINGS, json.encodeToString(listSerializer, list)).apply()
        _pairings.value = list
    }

    fun get(id: String): Pairing? = _pairings.value.firstOrNull { it.id == id }

    /** The pairing key (32 bytes) of [pairing], or `null` when it cannot be opened (Keystore changed): pair again then. */
    fun secretOf(pairing: Pairing): ByteArray? = box.open(pairing.secret)?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }

    @Synchronized
    fun add(id: String, peerDeviceId: String, peerName: String, secret: ByteArray, now: Long) {
        val sealed = box.seal(java.util.Base64.getEncoder().encodeToString(secret))
        // Pairing the same device again replaces the old pairing with it.
        save(_pairings.value.filter { it.peerDeviceId != peerDeviceId && it.id != id } + Pairing(id, peerDeviceId, peerName, sealed, now))
    }

    @Synchronized
    fun update(id: String, change: (Pairing) -> Pairing) {
        save(_pairings.value.map { if (it.id == id) change(it) else it })
    }

    @Synchronized
    fun remove(id: String) = save(_pairings.value.filter { it.id != id })

    /** One line of the sync log (never a secret or a code); the newest [LOG_LINES] are kept. */
    @Synchronized
    fun log(line: String, now: Long = System.currentTimeMillis()) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(now))
        val list = (listOf("$stamp  $line") + _log.value).take(LOG_LINES)
        com.notebudget.app.core.common.AppLog.i("sync", line)
        prefs.edit().putString(KEY_LOG, json.encodeToString(ListSerializer(String.serializer()), list)).apply()
        _log.value = list
    }

    @Synchronized
    fun clearLog() {
        prefs.edit().remove(KEY_LOG).apply()
        _log.value = emptyList()
    }

    private fun loadLog(): List<String> =
        prefs.getString(KEY_LOG, null)?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_PAIRINGS = "pairings"
        const val KEY_LOG = "log"
        const val KEY_LISTEN_PORT = "listen_port"
        const val LOG_LINES = 200
    }
}
