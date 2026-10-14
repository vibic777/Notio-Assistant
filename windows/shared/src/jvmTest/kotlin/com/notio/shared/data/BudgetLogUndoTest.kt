package com.notio.shared.data

import com.notio.shared.SequentialIds
import com.notio.shared.domain.AdjustMode
import com.notio.shared.domain.BudgetLogKind
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.Clock
import com.notio.shared.domain.DomainError
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.visibleBudgetRows
import com.notio.shared.transfer.BudgetLogRecord
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Undoing one line of a budget's log (Android D282) and the kept folding of groups (Android D279), on the desktop database. */
class BudgetLogUndoTest {
    private val dir = Files.createTempDirectory("notio-budget-undo").toFile()
    private var now = 1_000L
    private val clock = Clock { now++ }
    private val ids = SequentialIds()
    private val db = NotioDatabase.inMemory()
    private val editor = NotioEditor(db, AttachmentStore(dir), clock, ids)
    private val folder = editor.createFolder("F", "#3366FF", "SOFT")

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun budget(): BudgetSession = BudgetSession(db, editor.createNote(folder.id, "Budget " + now, NoteType.BUDGET).id, clock, ids)
    private fun BudgetSession.root() = nodes().single { it.parentId == null }
    private fun BudgetSession.node(title: String) = nodes().single { it.title == title }
    /** The log, newest line first, as the screen shows it. */
    private fun BudgetSession.log(): List<BudgetLogRecord> = db.budgetLogOf(noteId).sortedByDescending { it.at }

    @Test
    fun aWrongPlusIsTakenBackByItsDifferenceAndLaterChangesStay() {
        val b = budget()
        b.add(b.root().id, "Bread", BudgetNodeKind.LEAF, null, 1_000, 0, 1)
        val bread = b.node("Bread").id
        b.adjust(bread, AdjustMode.ADD, 50_000, sum = true, goal = false) // the mistake: +500 instead of +5
        val mistake = b.log().first()
        b.adjust(bread, AdjustMode.ADD, 300, sum = true, goal = false) // a right change after it
        assertEquals(bread, mistake.nodeId)

        assertNull(b.undoLogLine(mistake))

        assertEquals(1_300L, b.node("Bread").manualSumMinor)
        assertEquals(1_300L, b.root().cachedSumMinor)
        val undone = b.log().first()
        assertEquals(BudgetLogKind.OP_UNDONE, undone.kind)
        assertEquals(mistake.id + "\n" + BudgetLogKind.SUM, undone.detail)
        assertEquals(51_300L, undone.oldValue)
        assertEquals(1_300L, undone.newValue)
        // Once only.
        assertNotNull(b.undoLogLine(mistake))
        // The undo is a step of the note's Undo like any change.
        assertTrue(b.canUndo)
        b.undo()
        assertEquals(51_300L, b.node("Bread").manualSumMinor)
    }

    @Test
    fun aRenameAGoalAndAQuantityAreTakenBack() {
        val b = budget()
        b.add(b.root().id, "Milk", BudgetNodeKind.LEAF, null, 0, 200, 1)
        val milk = b.node("Milk").id
        b.update(milk, "Milk", null, 0, 900, 4, null)
        assertNull(b.undoLogLine(b.log().first { it.kind == BudgetLogKind.GOAL && it.newValue == 900L }))
        assertNull(b.undoLogLine(b.log().first { it.kind == BudgetLogKind.QUANTITY && it.newValue == 4L }))
        b.update(milk, "Oat milk", null, 0, 200, 1, null)
        assertNull(b.undoLogLine(b.log().first { it.kind == BudgetLogKind.RENAMED }))

        val n = b.nodes().single { it.id == milk }
        assertEquals(200L, n.manualGoalMinor)
        assertEquals(1, n.quantity)
        assertEquals("Milk", n.title)
    }

    @Test
    fun anAddedGroupIsDeletedAgainAndAQuantityBelowOneIsRefused() {
        val b = budget()
        b.add(b.root().id, "Trip", BudgetNodeKind.GROUP, null, 0, 0, 1)
        b.add(b.node("Trip").id, "Hotel", BudgetNodeKind.LEAF, null, 7_000, 0, 1)
        assertNull(b.undoLogLine(b.log().first { it.kind == BudgetLogKind.GROUP_ADDED }))
        assertEquals(listOf(b.root().id), b.nodes().map { it.id })
        assertEquals(0L, b.root().cachedSumMinor)

        val o = budget()
        o.add(o.root().id, "Pens", BudgetNodeKind.LEAF, null, 0, 0, 1)
        val pens = o.node("Pens").id
        o.update(pens, "Pens", null, 0, 0, 3, null)
        val toThree = o.log().first { it.kind == BudgetLogKind.QUANTITY }
        o.update(pens, "Pens", null, 0, 0, 1, null)
        assertEquals(DomainError.INVALID_QUANTITY, o.undoLogLine(toThree))
    }

    @Test
    fun linesWithoutAnItemAndMovesCannotBeUndone() {
        val b = budget()
        val old = BudgetLogRecord("x", b.noteId, 0, BudgetLogKind.SUM, "Bread", 0, 100)
        assertFalse(com.notio.shared.domain.BudgetEditor(DbBudgets(db)).canUndo(old.toEntry(), listOf(old.toEntry())))
        val moved = BudgetLogRecord("y", b.noteId, 0, BudgetLogKind.MOVED, "Bread", nodeId = "b")
        assertFalse(com.notio.shared.domain.BudgetEditor(DbBudgets(db)).canUndo(moved.toEntry(), listOf(moved.toEntry())))
    }

    @Test
    fun aDeletedItemComesBackAsItWasOnce() {
        // D139 (Android D359): the DELETED line keeps what it deleted, and Restore of the log puts it back.
        val b = budget()
        b.add(b.root().id, "Food", BudgetNodeKind.GROUP, null, 0, 0, 1)
        b.add(b.node("Food").id, "Bread", BudgetNodeKind.LEAF, "rye", 1_000, 5_000, 3)
        b.add(b.node("Food").id, "Milk", BudgetNodeKind.LEAF, null, 700, 0, 1)
        val before = b.node("Bread")

        assertNull(b.delete(before.id))
        val deleted = b.log().first()
        assertEquals(BudgetLogKind.DELETED, deleted.kind)
        assertEquals(before.id, deleted.nodeId)
        assertEquals("", deleted.detail?.substringBefore('\n'))
        assertEquals(700L, b.root().cachedSumMinor)

        assertNull(b.restoreDeleted(deleted))

        val back = b.node("Bread")
        assertEquals(before.copy(updatedAt = back.updatedAt), back)
        assertEquals(3_700L, b.node("Food").cachedSumMinor)
        assertEquals(3_700L, b.root().cachedSumMinor)
        val line = b.log().first()
        assertEquals(BudgetLogKind.RESTORED, line.kind)
        assertEquals(deleted.id, line.detail)
        // Once only.
        assertNotNull(b.restoreDeleted(deleted))
        assertEquals(1, b.nodes().count { it.title == "Bread" })
        // The restore is a step of the note's Undo like any change.
        b.undo()
        assertEquals(700L, b.root().cachedSumMinor)
    }

    @Test
    fun aDeletedGroupComesBackWithEverythingInsideAndAnItemWithoutItsGroupUnderTheTotal() {
        val b = budget()
        b.add(b.root().id, "Home", BudgetNodeKind.GROUP, null, 0, 0, 1)
        b.add(b.node("Home").id, "Repair", BudgetNodeKind.GROUP, null, 0, 0, 1)
        b.add(b.node("Repair").id, "Paint", BudgetNodeKind.LEAF, null, 2_000, 3_000, 1)
        b.add(b.node("Home").id, "Rent", BudgetNodeKind.LEAF, null, 50_000, 0, 1)
        val before = b.nodes()

        assertNull(b.delete(b.node("Home").id))
        assertEquals(listOf(b.root().id), b.nodes().map { it.id })
        val deleted = b.log().first()
        assertEquals(BudgetLogKind.TREE, deleted.detail?.substringBefore('\n'))
        assertNull(b.restoreDeleted(deleted))
        val after = b.nodes()
        assertEquals(before.associate { it.id to it.parentId }, after.associate { it.id to it.parentId })
        assertEquals(before.associate { it.id to it.cachedSumMinor }, after.associate { it.id to it.cachedSumMinor })
        assertEquals(52_000L, b.root().cachedSumMinor)

        // An item whose group was deleted after it comes back under the total.
        val paint = b.node("Paint").id
        assertNull(b.delete(paint))
        val deletedPaint = b.log().first()
        assertNull(b.delete(b.node("Home").id))
        assertNull(b.restoreDeleted(deletedPaint))
        assertEquals(b.root().id, b.nodes().single { it.id == paint }.parentId)
        assertEquals(2_000L, b.root().cachedSumMinor)
    }

    @Test
    fun whatIsInTheBudgetAlreadyIsNotRestoredAndAnOldLineKeptNothing() {
        val b = budget()
        b.add(b.root().id, "Bread", BudgetNodeKind.LEAF, null, 1_000, 0, 1)
        assertNull(b.delete(b.node("Bread").id))
        val deleted = b.log().first()
        b.undo() // Undo of the note brought it back
        assertNotNull(b.restoreDeleted(deleted))
        assertEquals(1, b.nodes().count { it.title == "Bread" })

        val editor = com.notio.shared.domain.BudgetEditor(DbBudgets(db))
        val old = BudgetLogRecord("x", b.noteId, 0, BudgetLogKind.DELETED, "Gone", detail = BudgetLogKind.TREE)
        assertFalse(editor.canRestore(old.toEntry(), listOf(old.toEntry())))
        assertNotNull(b.restoreDeleted(old.copy(nodeId = "n", detail = "\nnot a record")))
    }

    @Test
    fun collapsedGroupsStayCollapsedWhenTheBudgetIsOpenedAgain() {
        val b = budget()
        b.add(b.root().id, "Food", BudgetNodeKind.GROUP, null, 0, 0, 1)
        b.add(b.node("Food").id, "Bread", BudgetNodeKind.LEAF, null, 100, 0, 1)
        b.setAllExpanded(false)
        // "Opening the note again": a new session reads the tree afresh, as the screen does.
        val again = BudgetSession(db, b.noteId, clock, ids).nodes()
        assertFalse(again.single { it.title == "Food" }.isExpanded)
        assertEquals(listOf("Food"), visibleBudgetRows(again).drop(1).map { it.node.title })
    }
}
