package com.notio.shared.sync

import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.SettingsStore
import com.notio.shared.transfer.BackupService
import com.notio.shared.transfer.DemoBackup
import com.notio.shared.transfer.ImportMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Two desktop devices with real databases pair and sync over a socket: the notes, the budget and the drawing arrive. */
class RealDataSyncTest {
    private val dir: File = Files.createTempDirectory("notio-realsync").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val box = object : SecretBox {
        override fun seal(plain: String) = "sealed:$plain"
        override fun open(sealed: String) = sealed.removePrefix("sealed:")
    }

    private inner class Device(name: String) {
        val home = File(dir, name)
        val db = NotioDatabase.open(File(home, "notio.db"))
        val files = AttachmentStore(File(home, "attachments"))
        val backup = BackupService(db, files, SettingsStore(File(home, "settings.json")))
        val store = SyncStore(File(home, "sync.json"), box).apply { deviceName = name }
        val engine = SyncEngine(store, null, DesktopSyncData(db, backup), File(home, "tmp"), scope)
    }

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun demoDataGoesFromOneComputerToTheOther() = runBlocking {
        val a = Device("PC A")
        val b = Device("PC B")
        val demo = ByteArrayOutputStream().also { DemoBackup.write(it) }.toByteArray()
        a.backup.importFrom({ ByteArrayInputStream(demo) }, ImportMode.MERGE)
        b.engine.startListening()
        scope.launch { b.engine.prompt.filterNotNull().collect { it.answer.complete(true) } }
        b.engine.startPairing()
        val pairing = a.engine.pair("127.0.0.1", b.engine.listening.value!!.port, b.engine.pairingCode.value!!).getOrThrow()
        val outcome = a.engine.sync(pairing.id, SyncDirection.SEND, ImportMode.MERGE, null).getOrThrow()
        assertEquals(8, outcome.sent!![2]) // notes added there
        assertEquals(a.db.notes().map { it.id }.sorted(), b.db.notes().map { it.id }.sorted())
        assertEquals(69_000L, b.db.budgetNodesOf("demo-budget").single { it.parentId == null }.cachedSumMinor)
        assertNotNull(b.files.file("demo-drawing", "drawing.png"))
        b.engine.stopListening()
        a.db.close(); b.db.close()
    }
    /** D319: a sync without the reminders sends none and leaves the reminders of the receiving device as they are. */
    @Test
    fun withoutTheRemindersTheyStayWhereTheyAre() = runBlocking {
        val a = Device("PC A")
        val b = Device("PC B")
        val demo = ByteArrayOutputStream().also { DemoBackup.write(it) }.toByteArray()
        a.backup.importFrom({ ByteArrayInputStream(demo) }, ImportMode.MERGE)
        assertEquals(listOf("demo-reminder"), a.db.reminders().map { it.id })
        b.engine.startListening()
        scope.launch { b.engine.prompt.filterNotNull().collect { it.answer.complete(true) } }
        b.engine.startPairing()
        val pairing = a.engine.pair("127.0.0.1", b.engine.listening.value!!.port, b.engine.pairingCode.value!!).getOrThrow()

        a.engine.sync(pairing.id, SyncDirection.SEND, ImportMode.MERGE, null, reminders = false).getOrThrow()
        assertEquals(a.db.notes().map { it.id }.sorted(), b.db.notes().map { it.id }.sorted())
        assertEquals(emptyList(), b.db.reminders().map { it.id })
        assertEquals(false, a.store.get(pairing.id)!!.reminders)

        // The receiving device has a reminder of its own on a note that the next sync updates: it stays.
        val own = a.db.reminders().single().copy(id = "own-of-b")
        b.db.upsertReminder(own)
        val note = a.db.note("demo-meeting")!!
        a.db.upsertNote(note.copy(title = "Встреча перенесена", updatedAt = System.currentTimeMillis() + 60_000))
        a.engine.sync(pairing.id, SyncDirection.SEND, ImportMode.MERGE, null, reminders = false).getOrThrow()
        assertEquals("Встреча перенесена", b.db.note("demo-meeting")!!.title)
        assertEquals(listOf("own-of-b"), b.db.reminders().map { it.id })

        // With the reminders they travel again, with a note that changed (an unchanged note is passed over, as in any merge).
        a.db.upsertNote(a.db.note("demo-meeting")!!.copy(title = "Встреча во вторник", updatedAt = System.currentTimeMillis() + 120_000))
        a.engine.sync(pairing.id, SyncDirection.SEND, ImportMode.MERGE, null, reminders = true).getOrThrow()
        assertEquals(setOf("own-of-b", "demo-reminder"), b.db.reminders().map { it.id }.toSet())
        b.engine.stopListening()
        a.db.close(); b.db.close()
    }
    /** D322, D333: a sync set beforehand on the starting device is not asked about on the other one, a replacement neither. */
    @Test
    fun setBeforehandItIsNotAskedAboutOnTheOtherDevice() = runBlocking {
        val a = Device("PC A")
        val b = Device("PC B")
        val demo = ByteArrayOutputStream().also { DemoBackup.write(it) }.toByteArray()
        a.backup.importFrom({ ByteArrayInputStream(demo) }, ImportMode.MERGE)
        b.engine.startListening()
        // The person of B answers the pairing and whatever else is asked, and every question is counted.
        val asked = java.util.concurrent.CopyOnWriteArrayList<SyncPrompt>()
        scope.launch { b.engine.prompt.filterNotNull().collect { asked += it; it.answer.complete(true) } }
        b.engine.startPairing()
        val pairing = a.engine.pair("127.0.0.1", b.engine.listening.value!!.port, b.engine.pairingCode.value!!).getOrThrow()
        assertEquals(1, asked.size)

        // Everything set beforehand: the data arrives and nobody was asked.
        a.engine.sync(pairing.id, SyncDirection.SEND, ImportMode.MERGE, null, unattended = true).getOrThrow()
        assertEquals(a.db.notes().map { it.id }.sorted(), b.db.notes().map { it.id }.sorted())
        assertEquals(1, asked.size)
        assertTrue(b.store.log.value.any { it.contains("without a question") })

        // Something was chosen in the dialog: asked, as before.
        a.engine.sync(pairing.id, SyncDirection.SEND, ImportMode.MERGE, null).getOrThrow()
        assertEquals(2, asked.size)

        // A replacement with everything set beforehand: not asked either (D333) — and it did replace.
        b.db.upsertNote(b.db.note("demo-word")!!.copy(id = "only-on-b"))
        a.engine.sync(pairing.id, SyncDirection.SEND, ImportMode.REPLACE, null, unattended = true).getOrThrow()
        assertEquals(2, asked.size)
        assertEquals(null, b.db.note("only-on-b"))
        // A replacement with something chosen in the dialog: asked.
        a.engine.sync(pairing.id, SyncDirection.SEND, ImportMode.REPLACE, null).getOrThrow()
        assertEquals(3, asked.size)
        b.engine.stopListening()
        a.db.close(); b.db.close()
    }

    /**
     * D324: a folder chosen on this device is found on the other one by its name. Each device made its own "Личное", so the
     * two have different ids; asked for by this device's id alone, the other device had nothing to send and the sync ended
     * "done" with nothing changed.
     */
    @Test
    fun aFolderChosenHereIsFoundThereByItsName() = runBlocking {
        val a = Device("PC A")
        val b = Device("PC B")
        val demo = ByteArrayOutputStream().also { DemoBackup.write(it) }.toByteArray()
        b.backup.importFrom({ ByteArrayInputStream(demo) }, ImportMode.MERGE)
        val theirs = b.db.folders().single { it.id == "demo-personal" }
        a.db.upsertFolder(theirs.copy(id = "mine"))
        b.engine.startListening()
        scope.launch { b.engine.prompt.filterNotNull().collect { it.answer.complete(true) } }
        b.engine.startPairing()
        val pairing = a.engine.pair("127.0.0.1", b.engine.listening.value!!.port, b.engine.pairingCode.value!!).getOrThrow()

        val outcome = a.engine.sync(pairing.id, SyncDirection.RECEIVE, ImportMode.MERGE, setOf("mine")).getOrThrow()
        val expected = b.db.notes().filter { it.folderId == "demo-personal" }.map { it.id }.sorted()
        assertTrue(expected.isNotEmpty())
        assertEquals(expected.size, outcome.received!![2])
        // They are in the folder of this device: the two folders are one by their name.
        assertEquals(expected, a.db.notes().filter { it.folderId == "mine" }.map { it.id }.sorted())
        assertEquals(listOf("mine"), a.db.folders().map { it.id })
        // What the person chose is kept as chosen; the other device's folders are known now.
        assertEquals(listOf("mine"), a.store.get(pairing.id)!!.folderIds)
        assertTrue(a.store.get(pairing.id)!!.peerFolders.any { it.id == "demo-personal" })
        b.engine.stopListening()
        a.db.close(); b.db.close()
    }

    /** D325: a replacement of chosen folders — they become what comes, the rest of the device stays. */
    @Test
    fun aReplacementOfChosenFoldersLeavesTheOthers() = runBlocking {
        val a = Device("PC A")
        val b = Device("PC B")
        val demo = ByteArrayOutputStream().also { DemoBackup.write(it) }.toByteArray()
        a.backup.importFrom({ ByteArrayInputStream(demo) }, ImportMode.MERGE)
        b.backup.importFrom({ ByteArrayInputStream(demo) }, ImportMode.MERGE)
        // On B: a note more in the folder that is replaced, one more in another folder, and a newer version of a note of A.
        val shopping = b.db.note("demo-shopping")!!
        b.db.upsertNote(shopping.copy(id = "only-b", title = "Только на B"))
        b.db.upsertNote(shopping.copy(title = "Правка на B", updatedAt = System.currentTimeMillis() + 60_000))
        b.db.upsertNote(b.db.note("demo-word")!!.copy(id = "other-b", title = "В другой папке"))
        b.engine.startListening()
        scope.launch { b.engine.prompt.filterNotNull().collect { it.answer.complete(true) } }
        b.engine.startPairing()
        val pairing = a.engine.pair("127.0.0.1", b.engine.listening.value!!.port, b.engine.pairingCode.value!!).getOrThrow()

        // Both ways is not a replacement.
        assertTrue(a.engine.sync(pairing.id, SyncDirection.BOTH, ImportMode.REPLACE, setOf("demo-personal")).isFailure)
        a.engine.sync(pairing.id, SyncDirection.SEND, ImportMode.REPLACE, setOf("demo-personal")).getOrThrow()

        // The folder is what A has: the note of B alone is in the trash, the newer version of B gave way.
        assertNotNull(b.db.note("only-b")!!.deletedAt)
        assertEquals(a.db.note("demo-shopping")!!.title, b.db.note("demo-shopping")!!.title)
        assertEquals(
            a.db.notes().filter { it.folderId == "demo-personal" }.map { it.id }.sorted(),
            b.db.notes().filter { it.folderId == "demo-personal" && it.deletedAt == null }.map { it.id }.sorted(),
        )
        // The other folders of B were not touched.
        assertEquals(null, b.db.note("other-b")!!.deletedAt)
        assertEquals("В другой папке", b.db.note("other-b")!!.title)
        b.engine.stopListening()
        a.db.close(); b.db.close()
    }
}
