package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.BudgetLogKind
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.budget.BudgetViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Undo / Redo of a budget (D197): the tree comes back as it was, totals included. */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetUndoTest {
    private val env = TestEnv()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun budgetNote(): String {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (env.notes.create(folder.id, "B", NoteType.BUDGET) as DomainResult.Success).value.id
    }

    private suspend fun root(noteId: String) = env.budgetRepo.getNodes(noteId).single { it.parentId == null }

    private fun vm(noteId: String, steps: Int = 3) =
        BudgetViewModel(noteId, env.budgetRepo, env.noteRepo, env.budget, env.notes, flowOf(steps))

    @Test
    fun `adding, changing and deleting are undone and redone with the totals`() = runTest {
        val noteId = budgetNote()
        val vm = vm(noteId)
        vm.add(root(noteId).id, "Bread", BudgetNodeKind.LEAF, sumMinor = 1_000, goalMinor = 2_000)
        val bread = env.budgetRepo.getNodes(noteId).single { it.title == "Bread" }
        vm.adjustSum(bread.id, 500)
        assertEquals(1_500, root(noteId).cachedSumMinor)

        vm.undo()
        assertEquals(1_000, root(noteId).cachedSumMinor)
        vm.undo()
        assertTrue(env.budgetRepo.getNodes(noteId).none { it.title == "Bread" })
        assertEquals(0, root(noteId).cachedSumMinor)
        assertFalse(vm.canUndo)

        vm.redo()
        vm.redo()
        assertEquals(1_500, root(noteId).cachedSumMinor)
        assertFalse(vm.canRedo)
        val kinds = env.logRepo.rows.value.map { it.kind }
        assertEquals(2, kinds.count { it == BudgetLogKind.UNDONE })
        assertEquals(2, kinds.count { it == BudgetLogKind.REDONE })
    }

    @Test
    fun `a deleted group comes back with everything inside it`() = runTest {
        val noteId = budgetNote()
        val vm = vm(noteId)
        vm.add(root(noteId).id, "Food", BudgetNodeKind.GROUP)
        val food = env.budgetRepo.getNodes(noteId).single { it.title == "Food" }
        vm.add(food.id, "Milk", BudgetNodeKind.LEAF, sumMinor = 300)
        vm.add(food.id, "Tea", BudgetNodeKind.LEAF, sumMinor = 200)
        vm.delete(food.id)
        assertEquals(1, env.budgetRepo.getNodes(noteId).size)

        vm.undo()
        val titles = env.budgetRepo.getNodes(noteId).map { it.title }.toSet()
        assertTrue(titles.containsAll(listOf("Food", "Milk", "Tea")))
        assertEquals(500, root(noteId).cachedSumMinor)
    }

    @Test
    fun `undoing over a deleted group keeps the item that is still wanted`() = runTest {
        // An item is moved into a new group, then the tree goes back to before both: the group is deleted (a real database
        // cascades to what is inside it), but the item must stay, under the total.
        val noteId = budgetNote()
        val rootId = root(noteId).id
        env.budget.addNode(noteId, rootId, "Tea", BudgetNodeKind.LEAF, sumMinor = 200)
        val before = env.budgetRepo.getNodes(noteId)
        env.budget.addNode(noteId, rootId, "Drinks", BudgetNodeKind.GROUP)
        val drinks = env.budgetRepo.getNodes(noteId).single { it.title == "Drinks" }
        val tea = env.budgetRepo.getNodes(noteId).single { it.title == "Tea" }
        env.budget.moveNode(noteId, tea.id, drinks.id)

        env.budget.restoreNodes(noteId, before, BudgetLogKind.UNDONE)
        val after = env.budgetRepo.getNodes(noteId)
        assertTrue(after.none { it.title == "Drinks" })
        assertEquals(rootId, after.single { it.title == "Tea" }.parentId)
        assertEquals(200, root(noteId).cachedSumMinor)
    }

    @Test
    fun `opening and closing groups is not an undo step and is kept by an undo`() = runTest {
        val noteId = budgetNote()
        val vm = vm(noteId)
        vm.add(root(noteId).id, "Food", BudgetNodeKind.GROUP)
        val food = env.budgetRepo.getNodes(noteId).single { it.title == "Food" }
        vm.add(food.id, "Milk", BudgetNodeKind.LEAF, sumMinor = 300)
        vm.toggleExpanded(env.budgetRepo.getNodes(noteId).single { it.id == food.id })
        vm.undo() // undoes "Milk", not the fold
        assertTrue(env.budgetRepo.getNodes(noteId).none { it.title == "Milk" })
        assertFalse(env.budgetRepo.getNodes(noteId).single { it.id == food.id }.isExpanded)
    }

    @Test
    fun `the Change button acts on the sum, the goal or both, and never on neither`() = runTest {
        val noteId = budgetNote()
        val leaf = (env.budget.addNode(noteId, root(noteId).id, "Rent", BudgetNodeKind.LEAF, sumMinor = 10_000, goalMinor = 50_000) as DomainResult.Success).value
        val sub = com.notebudget.app.core.common.AdjustMode.SUBTRACT
        env.budget.adjustLeaf(noteId, leaf.id, sub, 1_000, sum = true, goal = true)
        var n = env.budgetRepo.getNodes(noteId).single { it.id == leaf.id }
        assertEquals(9_000L to 49_000L, n.manualSumMinor to n.manualGoalMinor)
        env.budget.adjustLeaf(noteId, leaf.id, com.notebudget.app.core.common.AdjustMode.SET, 60_000, sum = false, goal = true)
        n = env.budgetRepo.getNodes(noteId).single { it.id == leaf.id }
        assertEquals(9_000L to 60_000L, n.manualSumMinor to n.manualGoalMinor)
        assertEquals(51_000L, root(noteId).cachedRemainderMinor)
        assertTrue(env.budget.adjustLeaf(noteId, leaf.id, sub, 1, sum = false, goal = false) is DomainResult.Failure)
    }
}
