package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.BudgetLogEntry
import com.notebudget.app.domain.model.BudgetLogKind
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log of a budget note (D166): every change of the tree writes a line about it. */
class BudgetLogTest {
    private val env = TestEnv()

    private suspend fun budgetNoteId(): String {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "Budget", NoteType.BUDGET) as DomainResult.Success).value
        return note.id
    }

    private suspend fun rootId(noteId: String) = env.budgetRepo.getNodes(noteId).single { it.parentId == null }.id

    private suspend fun add(noteId: String, parent: String, title: String, kind: BudgetNodeKind) =
        (env.budget.addNode(noteId, parent, title, kind) as DomainResult.Success).value

    /** The log newest line first, as the screen shows it. */
    private suspend fun log(noteId: String): List<BudgetLogEntry> = env.logRepo.getByNote(noteId)

    @Test
    fun `adding an item and a group writes what was added and where`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val group = add(noteId, root, "Food", BudgetNodeKind.GROUP)
        env.budget.addNode(noteId, group.id, "Bread", BudgetNodeKind.LEAF, sumMinor = 1_000, goalMinor = 2_500, quantity = 3)

        val lines = log(noteId)
        // Newest first: the item, then the values it started with, then the group.
        assertEquals(
            listOf(BudgetLogKind.ITEM_ADDED, BudgetLogKind.SUM, BudgetLogKind.GOAL, BudgetLogKind.QUANTITY, BudgetLogKind.GROUP_ADDED),
            lines.map { it.kind },
        )
        assertEquals("Bread", lines[0].title)
        assertEquals("Food", lines[0].detail) // added to this group
        assertEquals(null to 1_000L, lines[1].oldValue to lines[1].newValue)
        assertEquals("", lines.last().detail) // the group went into the total
    }

    @Test
    fun `an item added without values writes only the addition`() = runTest {
        val noteId = budgetNoteId()
        add(noteId, rootId(noteId), "Empty", BudgetNodeKind.LEAF)
        assertEquals(listOf(BudgetLogKind.ITEM_ADDED), log(noteId).map { it.kind })
    }

    @Test
    fun `editing an item writes only what changed`() = runTest {
        val noteId = budgetNoteId()
        val leaf = add(noteId, rootId(noteId), "Bread", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, sumMinor = 1_000, goalMinor = 2_500, quantity = 1)
        val before = log(noteId).size

        // Same values again: nothing to write.
        env.budget.updateLeaf(noteId, leaf.id, sumMinor = 1_000, goalMinor = 2_500, quantity = 1)
        assertEquals(before, log(noteId).size)

        // Only the goal and the quantity change.
        env.budget.updateLeaf(noteId, leaf.id, sumMinor = 1_000, goalMinor = 3_000, quantity = 2)
        val new = log(noteId).take(2)
        assertEquals(setOf(BudgetLogKind.GOAL, BudgetLogKind.QUANTITY), new.map { it.kind }.toSet())
        val goal = new.first { it.kind == BudgetLogKind.GOAL }
        assertEquals(2_500L to 3_000L, goal.oldValue!! to goal.newValue!!)
        val quantity = new.first { it.kind == BudgetLogKind.QUANTITY }
        assertEquals(1L to 2L, quantity.oldValue!! to quantity.newValue!!)
    }

    @Test
    fun `the Change button writes how the sum changed`() = runTest {
        val noteId = budgetNoteId()
        val leaf = add(noteId, rootId(noteId), "Bread", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, sumMinor = 10_000, goalMinor = 0)

        env.budget.adjustLeafSum(noteId, leaf.id, 2_500)
        env.budget.adjustLeafSum(noteId, leaf.id, -1_000)
        env.budget.setLeafSum(noteId, leaf.id, 500)
        env.budget.setLeafSum(noteId, leaf.id, 500) // the same value: no line

        val lines = log(noteId).take(3)
        assertEquals(listOf("SET", "SUBTRACT", "ADD"), lines.map { it.detail })
        assertEquals(listOf(11_500L to 500L, 12_500L to 11_500L, 10_000L to 12_500L), lines.map { it.oldValue!! to it.newValue!! })
        assertEquals(4, log(noteId).count { it.kind == BudgetLogKind.SUM }) // + the first edit that set 10 000
    }

    @Test
    fun `renaming, moving and deleting are written`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val a = add(noteId, root, "A", BudgetNodeKind.GROUP)
        val b = add(noteId, root, "B", BudgetNodeKind.GROUP)
        val leaf = add(noteId, a.id, "Bread", BudgetNodeKind.LEAF)

        env.budget.updateDetails(noteId, leaf.id, "Bread", "same title, new comment") // not a rename
        env.budget.updateDetails(noteId, leaf.id, "Loaf", null)
        env.budget.moveNode(noteId, leaf.id, b.id)
        env.budget.deleteNode(noteId, a.id)
        env.budget.deleteNode(noteId, leaf.id)

        val lines = log(noteId)
        assertEquals(listOf(BudgetLogKind.DELETED, BudgetLogKind.DELETED, BudgetLogKind.MOVED, BudgetLogKind.RENAMED), lines.take(4).map { it.kind })
        assertEquals("Loaf", lines[3].title)
        assertEquals("Bread", lines[3].detail) // the old name
        assertEquals("A\nB", lines[2].detail) // from group A to group B
        assertEquals("A", lines[1].title) // the (empty) group deleted first
        assertEquals("", lines[1].detail?.substringBefore('\n')) // not a tree; what was deleted follows the line break (D359)
        assertEquals("Loaf", lines[0].title) // then the item
        assertEquals("", lines[0].detail?.substringBefore('\n'))
    }

    @Test
    fun `deleting a group with things inside says so`() = runTest {
        val noteId = budgetNoteId()
        val group = add(noteId, rootId(noteId), "Food", BudgetNodeKind.GROUP)
        add(noteId, group.id, "Bread", BudgetNodeKind.LEAF)

        env.budget.deleteNode(noteId, group.id)

        val line = log(noteId).first()
        assertEquals(BudgetLogKind.DELETED, line.kind)
        assertEquals("Food", line.title)
        assertEquals(BudgetLogKind.TREE, line.detail?.substringBefore('\n'))
    }

    @Test
    fun `the log of one note can be cleared without touching another`() = runTest {
        val one = budgetNoteId()
        val folder = env.folderRepo.getAll().first()
        val two = (env.notes.create(folder.id, "Other", NoteType.BUDGET) as DomainResult.Success).value.id
        add(one, rootId(one), "X", BudgetNodeKind.LEAF)
        add(two, rootId(two), "Y", BudgetNodeKind.LEAF)

        env.logRepo.clear(one)

        assertTrue(log(one).isEmpty())
        assertEquals(listOf("Y"), log(two).map { it.title })
        // The budget itself is untouched.
        assertEquals(2, env.budgetRepo.getNodes(one).size)
    }

    @Test
    fun `a comment given with a change goes into its log line (D262)`() = runTest {
        val noteId = budgetNoteId()
        val leaf = add(noteId, rootId(noteId), "Bread", BudgetNodeKind.LEAF)
        env.budget.adjustLeaf(noteId, leaf.id, com.notebudget.app.core.common.AdjustMode.ADD, 500, sum = true, goal = false, comment = " bakery\non the corner ")
        val line = log(noteId).first()
        assertEquals(BudgetLogKind.SUM, line.kind)
        assertEquals("ADD\nbakery on the corner", line.detail)
        // Without a comment the line is as before.
        env.budget.adjustLeaf(noteId, leaf.id, com.notebudget.app.core.common.AdjustMode.SET, 100, sum = true, goal = false)
        assertEquals("SET", log(noteId).first().detail)
    }
}
