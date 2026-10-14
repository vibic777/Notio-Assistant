package com.notebudget.app.data.sync

import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.core.security.SecretBox
import com.notebudget.app.data.transfer.ImportMode
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device sync (D226) end to end over a real socket on this machine: two engines, pairing with a code, a wrong code, a sync both
 * ways with chosen folders, a refusal, Test connection and removing the pairing on both sides. The app data is a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The Keystore is not there in tests: a box that keeps the text as it is. */
    private val box = object : SecretBox {
        override fun seal(plain: String) = "sealed:$plain"
        override fun open(sealed: String) = sealed.removePrefix("sealed:")
    }

    private class FakeData(val label: String) : SyncData {
        val imported = ArrayList<Pair<String, ImportMode>>()
        var exportedFolders: Set<String>? = setOf("none yet")
        override suspend fun folders() = listOf(FolderInfo("f-$label", "Folder $label"))
        override suspend fun export(out: OutputStream, folderIds: Set<String>?) {
            exportedFolders = folderIds
            // More than one chunk, so the framing is exercised.
            out.write(("data of $label;").repeat(10_000).toByteArray())
        }
        override suspend fun import(file: File, mode: ImportMode): List<Int> {
            imported += file.readText().substringBefore(';') to mode
            return listOf(1, 0, 2, 3, 0, 4)
        }
    }

    private lateinit var dataA: FakeData
    private lateinit var dataB: FakeData
    private lateinit var storeA: SyncStore
    private lateinit var storeB: SyncStore
    private lateinit var a: SyncEngine
    private lateinit var b: SyncEngine
    private var answerB = true
    private var freeA = Long.MAX_VALUE
    private var freeB = Long.MAX_VALUE

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        dataA = FakeData("A"); dataB = FakeData("B")
        storeA = SyncStore(context, box, "sync-a").apply { deviceName = "Phone A" }
        storeB = SyncStore(context, box, "sync-b").apply { deviceName = "Phone B" }
        a = SyncEngine(storeA, null, dataA, File(context.cacheDir, "sync-a"), scope, freeBytes = { freeA })
        b = SyncEngine(storeB, null, dataB, File(context.cacheDir, "sync-b"), scope, freeBytes = { freeB })
        b.startListening()
        // The person at B answers every question.
        scope.launch { b.prompt.filterNotNull().collect { it.answer.complete(answerB) } }
    }

    @After
    fun tearDown() {
        b.stopListening()
        scope.cancel()
    }

    private fun port() = b.listening.value!!.port

    private fun paired(): Pairing = runBlocking {
        b.startPairing()
        val code = b.pairingCode.value!!
        a.pair("127.0.0.1", port(), code.lowercase().replace("-", " ")).getOrThrow()
    }

    @Test
    fun `a port chosen by hand is the one listened on, and a taken one is said and no other is opened (D357)`() {
        b.stopListening()
        // A free port of this machine, found by asking for any.
        val free = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(b.isPortFree(free))
        storeB.listenPort = free
        b.startListening()
        assertEquals(free, b.listening.value!!.port)
        assertNull(b.portBusy.value)
        // The port it listens on itself is free for it.
        assertTrue(b.isPortFree(free))
        b.stopListening()

        java.net.ServerSocket(free).use {
            assertFalse(b.isPortFree(free))
            b.startListening()
            assertNull(b.listening.value)
            assertEquals(free, b.portBusy.value)
        }
        // By itself again: it listens, on the usual port or on any free one.
        storeB.listenPort = 0
        b.startListening()
        assertNotNull(b.listening.value)
        assertNull(b.portBusy.value)
    }

    @Test
    fun `a port below 1024 or above 65535 is not kept (D357)`() {
        storeB.listenPort = 80
        assertEquals(0, storeB.listenPort)
        storeB.listenPort = 70000
        assertEquals(0, storeB.listenPort)
        storeB.listenPort = 50123
        assertEquals(50123, storeB.listenPort)
        storeB.listenPort = 0
    }

    @Test
    fun `pairing with the code keeps the connection on both devices and the code is gone`() {
        val p = paired()
        assertEquals("Phone B", p.peerName)
        assertEquals(storeB.deviceId, p.peerDeviceId)
        val onB = storeB.pairings.value.single()
        assertEquals(p.id, onB.id)
        assertEquals("Phone A", onB.peerName)
        assertEquals(storeA.secretOf(p)!!.toList(), storeB.secretOf(onB)!!.toList())
        assertEquals(null, b.pairingCode.value)
    }

    @Test
    fun `a wrong code is refused and nothing is kept`() = runBlocking {
        b.startPairing()
        val code = b.pairingCode.value!!
        val wrong = (if (code[0] == 'A') "B" else "A") + code.substring(1)
        val result = a.pair("127.0.0.1", port(), wrong)
        assertEquals(SyncReasons.WRONG_CODE, (result.exceptionOrNull() as SyncException).reason)
        assertTrue(storeA.pairings.value.isEmpty())
        assertTrue(storeB.pairings.value.isEmpty())
        // Without waiting there is no pairing at all.
        b.stopPairing()
        val late = a.pair("127.0.0.1", port(), code)
        assertEquals(SyncReasons.NOT_WAITING, (late.exceptionOrNull() as SyncException).reason)
    }

    @Test
    fun `a sync both ways moves each side's data to the other, with the chosen folders`() = runBlocking {
        val p = paired()
        val outcome = a.sync(p.id, SyncDirection.BOTH, ImportMode.MERGE, setOf("f-A")).getOrThrow()
        assertEquals(listOf("data of A" to ImportMode.MERGE), dataB.imported)
        assertEquals(listOf("data of B" to ImportMode.MERGE), dataA.imported)
        assertEquals(setOf("f-A"), dataA.exportedFolders)
        assertEquals(setOf("f-A"), dataB.exportedFolders)
        assertEquals(listOf(1, 0, 2, 3, 0, 4), outcome.received)
        assertEquals(listOf(1, 0, 2, 3, 0, 4), outcome.sent)
        assertTrue(outcome.bytesOut > SyncSession.CHUNK)
        val kept = storeA.get(p.id)!!
        assertNotEquals(0L, kept.lastSyncAt)
        assertFalse(kept.allFolders)
        assertEquals(listOf("f-A"), kept.folderIds)
        // Only receiving: nothing goes to B.
        a.sync(p.id, SyncDirection.RECEIVE, ImportMode.NEW_IDS, null).getOrThrow()
        assertEquals(1, dataB.imported.size)
        assertEquals("data of B" to ImportMode.NEW_IDS, dataA.imported.last())
        assertEquals(null, dataB.exportedFolders)
    }

    @Test
    fun `a device with too little free storage does not receive, and says why`() = runBlocking {
        val p = paired()
        // The other device is nearly full: it refuses before anything is sent.
        freeB = SyncSession.SPACE_RESERVE - 1
        val there = a.sync(p.id, SyncDirection.SEND, ImportMode.MERGE, null)
        assertEquals(SyncReasons.NO_SPACE, (there.exceptionOrNull() as SyncException).reason)
        assertTrue(dataB.imported.isEmpty())
        // This device is nearly full: nothing is asked of the other one.
        freeB = Long.MAX_VALUE
        freeA = SyncSession.SPACE_RESERVE - 1
        val here = a.sync(p.id, SyncDirection.RECEIVE, ImportMode.MERGE, null)
        assertEquals(SyncReasons.NO_SPACE, (here.exceptionOrNull() as SyncException).reason)
        // Room for the start but not for the data: the stream stops, nothing is imported, the next sync works.
        freeA = SyncSession.SPACE_RESERVE + 50_000
        val midway = a.sync(p.id, SyncDirection.RECEIVE, ImportMode.MERGE, null)
        assertEquals(SyncReasons.NO_SPACE, (midway.exceptionOrNull() as SyncException).reason)
        assertTrue(dataA.imported.isEmpty())
        freeA = Long.MAX_VALUE
        a.sync(p.id, SyncDirection.RECEIVE, ImportMode.MERGE, null).getOrThrow()
        assertEquals(listOf("data of B" to ImportMode.MERGE), dataA.imported)
    }

    @Test
    fun `the other device can refuse, and replace needs one direction`() = runBlocking {
        val p = paired()
        answerB = false
        val refused = a.sync(p.id, SyncDirection.SEND, ImportMode.MERGE, null)
        assertEquals(SyncReasons.DENIED, (refused.exceptionOrNull() as SyncException).reason)
        assertTrue(dataB.imported.isEmpty())
        answerB = true
        assertTrue(a.sync(p.id, SyncDirection.BOTH, ImportMode.REPLACE, null).isFailure)
        // A replacement of chosen folders (D325) is refused by data that does not know it: it must not wipe the device instead.
        assertTrue(a.sync(p.id, SyncDirection.SEND, ImportMode.REPLACE, setOf("f-A")).isFailure)
        assertTrue(dataB.imported.isEmpty())
        a.sync(p.id, SyncDirection.SEND, ImportMode.REPLACE, null).getOrThrow()
        assertEquals(listOf("data of A" to ImportMode.REPLACE), dataB.imported)
    }

    @Test
    fun `test connection goes step by step, and removing the pairing removes it on both devices`() = runBlocking {
        val p = paired()
        val checks = a.test(p.id)
        val byStep = checks.associateBy { it.step }
        assertTrue(byStep.getValue("connect").ok)
        assertTrue(byStep.getValue("handshake").ok)
        assertTrue(byStep.getValue("clock").ok)
        assertTrue(byStep.getValue("reply").ok)
        assertEquals(listOf(FolderInfo("f-B", "Folder B")), a.fetchFolders(p.id).getOrThrow())
        assertEquals(listOf(FolderInfo("f-B", "Folder B")), storeA.get(p.id)!!.peerFolders)
        assertTrue(a.unpair(p.id))
        assertTrue(storeA.pairings.value.isEmpty())
        assertTrue(storeB.pairings.value.isEmpty())
    }

    @Test
    fun `a device with a pairing the other side does not know is refused at the handshake`() = runBlocking {
        val p = paired()
        storeB.remove(p.id)
        val result = a.sync(p.id, SyncDirection.SEND, ImportMode.MERGE, null)
        assertEquals(SyncReasons.UNKNOWN_PAIRING, (result.exceptionOrNull() as SyncException).reason)
        assertTrue(dataB.imported.isEmpty())
    }
}
