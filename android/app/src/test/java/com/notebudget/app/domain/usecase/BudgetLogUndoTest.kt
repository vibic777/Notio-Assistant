package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.AdjustMode
import com.notebudget.app.domain.model.BudgetLogEntry
import com.notebudget.app.domain.model.BudgetLogKind
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undoing one line of a budget's log (D282), as a merge is undone. */
class BudgetLogUndoTest {
    private val env = TestEnv()

    private suspend fun budget(): Pair<String, String> {
        val folderId = env.folderRepo.getAll().firstOrNull()?.id
            ?: (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value.id
        val note = (env.notes.create(folderId, "Budget " + env.budgetRepo.hashCode() + System.nanoTime(), NoteType.BUDGET) as DomainResult.Success).value
        return note.id to env.budgetRepo.getNodes(note.id).single { it.parentId == null }.id
    }

    private suspend fun log(noteId: String): List<BudgetLogEntry> = env.logRepo.getByNote(noteId)
    private suspend fun node(noteId: String, id: String) = env.budgetRepo.getNodes(noteId).single { it.id == id }

    @Test
    fun `a wrong plus is taken back by its difference and later changes stay`() = runTest {
        val (noteId, root) = budget()
        val bread = (env.budget.addNode(noteId, root, "Bread", BudgetNodeKind.LEAF, sumMinor = 1_000) as DomainResult.Success).value
        env.budget.adjustLeaf(noteId, bread.id, AdjustMode.ADD, 50_000, sum = true, goal = false) // the mistake: +500 instead of +5
        val mistake = log(noteId).first()
        env.budget.adjustLeaf(noteId, bread.id, AdjustMode.ADD, 300, sum = true, goal = false) // a right change after it
        assertEquals(bread.id, mistake.nodeId)
        assertTrue(env.budget.canUndo(mistake, log(noteId)))

        assertTrue(env.budget.undoLogEntry(mistake, log(noteId)) is DomainResult.Success)

        assertEquals(1_300L, node(noteId, bread.id).manualSumMinor)
        assertEquals(1_300L, node(noteId, root).cachedSumMinor)
        val undone = log(noteId).first()
        assertEquals(BudgetLogKind.OP_UNDONE, undone.kind)
        assertEquals(51_300L, undone.oldValue)
        assertEquals(1_300L, undone.newValue)
        // Once only.
        assertFalse(env.budget.canUndo(mistake, log(noteId)))
        assertTrue(env.budget.undone(mistake, log(noteId)))
        assertTrue(env.budget.undoLogEntry(mistake, log(noteId)) is DomainResult.Failure)
    }

    @Test
    fun `a rename, a goal and a quantity are taken back`() = runTest {
        val (noteId, root) = budget()
        val item = (env.budget.addNode(noteId, root, "Milk", BudgetNodeKind.LEAF, goalMinor = 200) as DomainResult.Success).value
        env.budget.updateLeaf(noteId, item.id, sumMinor = 0, goalMinor = 900, quantity = 4)
        val lines = log(noteId)
        env.budget.undoLogEntry(lines.first { it.kind == BudgetLogKind.GOAL && it.newValue == 900L }, lines)
        env.budget.undoLogEntry(lines.first { it.kind == BudgetLogKind.QUANTITY && it.newValue == 4L }, log(noteId))
        env.budget.updateDetails(noteId, item.id, "Oat milk", null)
        env.budget.undoLogEntry(log(noteId).first { it.kind == BudgetLogKind.RENAMED }, log(noteId))

        val now = node(noteId, item.id)
        assertEquals(200L, now.manualGoalMinor)
        assertEquals(1, now.quantity)
        assertEquals("Milk", now.title)
    }

    @Test
    fun `an added item is deleted again, and a quantity below 1 is refused`() = runTest {
        val (noteId, root) = budget()
        val group = (env.budget.addNode(noteId, root, "Trip", BudgetNodeKind.GROUP) as DomainResult.Success).value
        env.budget.addNode(noteId, group.id, "Hotel", BudgetNodeKind.LEAF, sumMinor = 7_000)
        val added = log(noteId).first { it.kind == BudgetLogKind.GROUP_ADDED }
        assertTrue(env.budget.undoLogEntry(added, log(noteId)) is DomainResult.Success)
        assertEquals(listOf(root), env.budgetRepo.getNodes(noteId).map { it.id })
        assertEquals(0L, node(noteId, root).cachedSumMinor)

        val (other, otherRoot) = budget()
        val pens = (env.budget.addNode(other, otherRoot, "Pens", BudgetNodeKind.LEAF) as DomainResult.Success).value
        env.budget.updateLeaf(other, pens.id, 0, 0, quantity = 3)
        val toThree = log(other).first { it.kind == BudgetLogKind.QUANTITY }
        env.budget.updateLeaf(other, pens.id, 0, 0, quantity = 1)
        assertEquals(DomainError.INVALID_QUANTITY, (env.budget.undoLogEntry(toThree, log(other)) as DomainResult.Failure).error)
    }

    @Test
    fun `lines without an item (made before) and moves cannot be undone`() = runTest {
        val old = BudgetLogEntry("x", "n", 0, BudgetLogKind.SUM, "Bread", 0, 100)
        assertNull(old.nodeId)
        assertFalse(env.budget.canUndo(old, listOf(old)))
        val moved = BudgetLogEntry("y", "n", 0, BudgetLogKind.MOVED, "Bread", nodeId = "b")
        assertFalse(env.budget.canUndo(moved, listOf(moved)))
    }
}
