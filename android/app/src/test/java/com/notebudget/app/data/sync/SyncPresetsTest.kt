package com.notebudget.app.data.sync

import com.notebudget.app.data.transfer.ImportMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D316: what is set beforehand for a connection, and when a press of Synchronize starts without asking. */
class SyncPresetsTest {
    private val fresh = Pairing(id = "p", peerDeviceId = "d", peerName = "Phone", secret = "s", createdAt = 1)

    @Test
    fun aNewConnectionAsksEverything() {
        assertTrue(fresh.askDirection && fresh.askMode && fresh.askFolders)
        assertFalse(SyncPresets.startsAtOnce(fresh))
    }

    @Test
    fun aConnectionKeptBeforeThisVersionAsksEverything() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(
            Pairing.serializer(),
            """{"id":"p","peerDeviceId":"d","peerName":"Phone","secret":"s","createdAt":1,"direction":"SEND","mode":"NEW_IDS","allFolders":false,"folderIds":["a"]}""",
        )
        assertTrue(old.askDirection && old.askMode && old.askFolders)
        assertEquals(SyncDirection.SEND, old.direction)
    }

    @Test
    fun withAllThreeSetItStartsAtOnce() {
        var p = SyncPresets.withDirection(fresh, SyncDirection.BOTH)
        assertFalse(SyncPresets.startsAtOnce(p))
        p = SyncPresets.withMode(p, ImportMode.MERGE)
        assertFalse(SyncPresets.startsAtOnce(p))
        p = SyncPresets.withFolders(p, false, null)
        assertTrue(SyncPresets.startsAtOnce(p))
        assertEquals(null, SyncPresets.foldersOf(p))
        // Chosen folders.
        p = SyncPresets.withFolders(p, false, listOf("a", "b"))
        assertTrue(SyncPresets.startsAtOnce(p))
        assertEquals(setOf("a", "b"), SyncPresets.foldersOf(p))
        // Chosen, but none: nothing to sync, the dialog opens.
        assertFalse(SyncPresets.startsAtOnce(SyncPresets.withFolders(p, false, emptyList())))
        // Back to asking.
        assertFalse(SyncPresets.startsAtOnce(SyncPresets.withFolders(p, true, null)))
        assertFalse(SyncPresets.startsAtOnce(SyncPresets.withDirection(p, null)))
        assertFalse(SyncPresets.startsAtOnce(SyncPresets.withMode(p, null)))
    }

    @Test
    fun aReplacementIsAlwaysShownFirst() {
        var p = SyncPresets.withDirection(fresh, SyncDirection.SEND)
        p = SyncPresets.withFolders(p, false, null)
        p = SyncPresets.withMode(p, ImportMode.REPLACE)
        assertEquals(ImportMode.REPLACE, SyncPresets.modeOf(p))
        assertFalse(SyncPresets.startsAtOnce(p))
    }

    @Test
    fun aReplacementThatIsNoLongerAllowedBecomesAMerge() {
        var p = SyncPresets.withDirection(fresh, SyncDirection.RECEIVE)
        p = SyncPresets.withFolders(p, false, null)
        p = SyncPresets.withMode(p, ImportMode.REPLACE)
        assertTrue(SyncPresets.replaceAllowed(p))
        // Both directions: a replacement has one.
        assertEquals(ImportMode.MERGE, SyncPresets.modeOf(SyncPresets.withDirection(p, SyncDirection.BOTH)))
        // Some folders: a replacement of them only (D325).
        assertEquals(ImportMode.REPLACE, SyncPresets.modeOf(SyncPresets.withFolders(p, false, listOf("a"))))
        // What is asked may still allow it: left as it is.
        assertEquals(ImportMode.REPLACE, SyncPresets.modeOf(SyncPresets.withDirection(p, null)))
        assertFalse(SyncPresets.replaceAllowed(SyncPresets.withDirection(fresh, SyncDirection.BOTH)))
    }

    /** D333: the other device is not asked only when none of the three is asked — whatever the way is, a replacement too. */
    @Test
    fun theOtherDeviceIsNotAskedOnlyWhenNothingIsAsked() {
        var p = SyncPresets.withDirection(fresh, SyncDirection.SEND)
        assertFalse(SyncPresets.nothingAsked(p))
        p = SyncPresets.withFolders(p, false, null)
        assertFalse(SyncPresets.nothingAsked(p))
        p = SyncPresets.withMode(p, ImportMode.REPLACE)
        assertTrue(SyncPresets.nothingAsked(p))
        // The device that starts still shows a replacement before it begins.
        assertFalse(SyncPresets.startsAtOnce(p))
        // Any one of the three asked again: asked on the other device too.
        assertFalse(SyncPresets.nothingAsked(SyncPresets.withDirection(p, null)))
        assertFalse(SyncPresets.nothingAsked(SyncPresets.withMode(p, null)))
        assertFalse(SyncPresets.nothingAsked(SyncPresets.withFolders(p, true, null)))
    }

    /** D324: the same folder has another id on the other device, so a folder is chosen by its name. */
    @Test
    fun aFolderIsChosenByItsName() {
        val local = listOf(FolderInfo("here-notes", "Notes"), FolderInfo("here-work", "Work"))
        val peer = listOf(FolderInfo("there-notes", "notes "), FolderInfo("there-home", "Home"))
        val known = local + peer
        // One line for a name, this device's folder first.
        assertEquals(listOf("there-home", "here-notes", "here-work"), SyncFolders.choices(local, peer).map { it.id })
        // What is asked of the other device has its id of that name too.
        assertEquals(setOf("here-notes", "there-notes"), SyncFolders.expand(setOf("here-notes"), known))
        // An id nobody knows any more is left as it is.
        assertEquals(setOf("gone"), SyncFolders.expand(setOf("gone"), known))
        // Chosen under the other device's id before: the line is ticked, counted once, and cleared whole.
        val before = setOf("there-notes", "here-work")
        assertTrue(SyncFolders.isChosen(before, local[0], known))
        assertEquals(2, SyncFolders.count(before + "here-notes", known))
        assertEquals(setOf("here-work"), SyncFolders.toggled(before, local[0], false, known))
        assertEquals(before + "here-notes", SyncFolders.toggled(before, local[0], true, known))
    }
}
