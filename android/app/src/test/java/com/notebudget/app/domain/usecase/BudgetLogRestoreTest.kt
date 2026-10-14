package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.BudgetLogEntry
import com.notebudget.app.domain.model.BudgetLogKind
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A deletion of a budget is kept in its log and put back from there (D359). */
class BudgetLogRestoreTest {
    private val env = TestEnv()

    private suspend fun budget(): Pair<String, String> {
        val folderId = env.folderRepo.getAll().firstOrNull()?.id
            ?: (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value.id
        val note = (env.notes.create(folderId, "Budget " + System.nanoTime(), NoteType.BUDGET) as DomainResult.Success).value
        return note.id to env.budgetRepo.getNodes(note.id).single { it.parentId == null }.id
    }

    private suspend fun log(noteId: String): List<BudgetLogEntry> = env.logRepo.getByNote(noteId)
    private suspend fun nodes(noteId: String) = env.budgetRepo.getNodes(noteId)
    private suspend fun add(noteId: String, parent: String, title: String, kind: BudgetNodeKind, sum: Long = 0, goal: Long = 0, quantity: Int = 1) =
        (env.budget.addNode(noteId, parent, title, kind, sumMinor = sum, goalMinor = goal, quantity = quantity) as DomainResult.Success).value

    @Test
    fun `a deleted item comes back as it was, once`() = runTest {
        val (noteId, root) = budget()
        val food = add(noteId, root, "Food", BudgetNodeKind.GROUP)
        val bread = add(noteId, food.id, "Bread", BudgetNodeKind.LEAF, sum = 1_000, goal = 5_000, quantity = 3)
        add(noteId, food.id, "Milk", BudgetNodeKind.LEAF, sum = 700)
        val before = nodes(noteId).single { it.id == bread.id }

        env.budget.deleteNode(noteId, bread.id)
        val deleted = log(noteId).first()
        assertEquals(BudgetLogKind.DELETED, deleted.kind)
        assertEquals(bread.id, deleted.nodeId)
        assertEquals(700L, nodes(noteId).single { it.id == root }.cachedSumMinor)
        assertTrue(env.budget.canRestore(deleted, log(noteId)))

        assertTrue(env.budget.restoreDeleted(deleted, log(noteId)) is DomainResult.Success)

        val back = nodes(noteId).single { it.id == bread.id }
        assertEquals(before.copy(updatedAt = back.updatedAt), back)
        assertEquals(3_700L, nodes(noteId).single { it.id == food.id }.cachedSumMinor)
        assertEquals(3_700L, nodes(noteId).single { it.id == root }.cachedSumMinor)
        assertEquals(15_000L, nodes(noteId).single { it.id == root }.cachedGoalMinor)
        val line = log(noteId).first()
        assertEquals(BudgetLogKind.RESTORED, line.kind)
        assertEquals("Bread", line.title)
        assertEquals(deleted.id, line.detail)
        // Once only.
        assertFalse(env.budget.canRestore(deleted, log(noteId)))
        assertTrue(env.budget.restored(deleted, log(noteId)))
        assertTrue(env.budget.restoreDeleted(deleted, log(noteId)) is DomainResult.Failure)
        assertEquals(1, nodes(noteId).count { it.title == "Bread" })
    }

    @Test
    fun `a deleted group comes back with everything inside`() = runTest {
        val (noteId, root) = budget()
        val home = add(noteId, root, "Home", BudgetNodeKind.GROUP)
        val repair = add(noteId, home.id, "Repair", BudgetNodeKind.GROUP)
        add(noteId, repair.id, "Paint", BudgetNodeKind.LEAF, sum = 2_000, goal = 3_000)
        add(noteId, home.id, "Rent", BudgetNodeKind.LEAF, sum = 50_000)
        val before = nodes(noteId)

        env.budget.deleteNode(noteId, home.id)
        assertEquals(listOf(root), nodes(noteId).map { it.id })
        val deleted = log(noteId).first()
        assertEquals(BudgetLogKind.TREE, deleted.detail?.substringBefore('\n'))

        assertTrue(env.budget.restoreDeleted(deleted, log(noteId)) is DomainResult.Success)

        val after = nodes(noteId)
        assertEquals(before.map { it.id }.toSet(), after.map { it.id }.toSet())
        assertEquals(before.associate { it.id to it.parentId }, after.associate { it.id to it.parentId })
        assertEquals(before.associate { it.id to it.cachedSumMinor }, after.associate { it.id to it.cachedSumMinor })
        assertEquals(before.associate { it.id to it.cachedRemainderMinor }, after.associate { it.id to it.cachedRemainderMinor })
        assertEquals(52_000L, after.single { it.id == root }.cachedSumMinor)
    }

    @Test
    fun `an item whose group is gone comes back under the total`() = runTest {
        val (noteId, root) = budget()
        val food = add(noteId, root, "Food", BudgetNodeKind.GROUP)
        val bread = add(noteId, food.id, "Bread", BudgetNodeKind.LEAF, sum = 1_000)
        env.budget.deleteNode(noteId, bread.id)
        val deletedItem = log(noteId).first()
        env.budget.deleteNode(noteId, food.id)

        assertTrue(env.budget.restoreDeleted(deletedItem, log(noteId)) is DomainResult.Success)

        val back = nodes(noteId).single { it.id == bread.id }
        assertEquals(root, back.parentId)
        assertEquals(1_000L, nodes(noteId).single { it.id == root }.cachedSumMinor)
    }

    @Test
    fun `what is in the budget already is not restored, and an old line kept nothing`() = runTest {
        val (noteId, root) = budget()
        val bread = add(noteId, root, "Bread", BudgetNodeKind.LEAF, sum = 1_000)
        val snapshot = nodes(noteId)
        env.budget.deleteNode(noteId, bread.id)
        val deleted = log(noteId).first()
        // Undo of the note brought it back.
        env.budget.restoreNodes(noteId, snapshot, BudgetLogKind.UNDONE)

        assertTrue(env.budget.restoreDeleted(deleted, log(noteId)) is DomainResult.Failure)
        assertEquals(1, nodes(noteId).count { it.id == bread.id })
        assertNull(log(noteId).firstOrNull { it.kind == BudgetLogKind.RESTORED })

        // A line of an older Notio: no node, no record.
        val old = BudgetLogEntry("old", noteId, 1, BudgetLogKind.DELETED, "Gone", detail = BudgetLogKind.TREE)
        assertFalse(env.budget.canRestore(old, log(noteId)))
        assertFalse(env.budget.canRestore(old.copy(nodeId = "x"), log(noteId)))
        assertTrue(env.budget.restoreDeleted(old.copy(nodeId = "x", detail = "\nnot a record"), log(noteId)) is DomainResult.Failure)
    }
}
