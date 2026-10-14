package com.notebudget.app.feature.budget

import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.TestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetGroupsTest {
    private val env = TestEnv()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun node(id: String, parent: String?, kind: BudgetNodeKind, order: Int = 0) = BudgetNode(
        id = id, noteId = "n", parentId = parent, title = id, kind = kind,
        manualSumMinor = 0, manualGoalMinor = 0, comment = null,
        cachedSumMinor = 0, cachedGoalMinor = 0, cachedRemainderMinor = 0,
        isExpanded = true, sortOrder = order, createdAt = 0, updatedAt = 0,
    )

    private val tree = listOf(
        node("root", null, BudgetNodeKind.GROUP),
        node("food", "root", BudgetNodeKind.GROUP, 0),
        node("fruit", "food", BudgetNodeKind.GROUP, 0),
        node("apple", "fruit", BudgetNodeKind.LEAF),
        node("rent", "root", BudgetNodeKind.LEAF, 1),
        node("fun", "root", BudgetNodeKind.GROUP, 2),
    )

    // ---- the drop-down choices ----

    @Test
    fun `the choices are the root and every group in tree order, indented by depth, without items`() {
        val options = groupOptions(tree)
        assertEquals(listOf("root", "food", "fruit", "fun"), options.map { it.id })
        assertEquals(listOf(0, 1, 2, 1), options.map { it.depth })
        assertEquals(listOf(true, false, false, false), options.map { it.isRoot })
    }

    @Test
    fun `a group being edited and everything below it are left out, so it cannot move into itself`() {
        assertEquals(listOf("root", "fun"), groupOptions(tree, excludeId = "food").map { it.id })
        assertEquals(listOf("root", "food", "fun"), groupOptions(tree, excludeId = "fruit").map { it.id })
    }

    @Test
    fun `an item can go to any group, including the one it is in`() {
        assertEquals(listOf("root", "food", "fruit", "fun"), groupOptions(tree, excludeId = "apple").map { it.id })
    }

    @Test
    fun `broken data with a cycle or a missing parent never loops`() {
        val broken = listOf(node("a", "b", BudgetNodeKind.GROUP), node("b", "a", BudgetNodeKind.GROUP), node("x", "gone", BudgetNodeKind.GROUP))
        assertTrue(groupOptions(broken).isEmpty()) // nothing reachable from a root
    }

    // ---- moving through the view model ----

    private suspend fun setup(): Triple<BudgetViewModel, String, Map<String, BudgetNode>> {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "Budget", NoteType.BUDGET) as DomainResult.Success).value
        val root = env.budgetRepo.getNodes(note.id).single()
        val a = (env.budget.addNode(note.id, root.id, "A", BudgetNodeKind.GROUP) as DomainResult.Success).value
        val b = (env.budget.addNode(note.id, root.id, "B", BudgetNodeKind.GROUP) as DomainResult.Success).value
        val leaf = (env.budget.addNode(note.id, a.id, "Bread", BudgetNodeKind.LEAF, sumMinor = 500, goalMinor = 800) as DomainResult.Success).value
        val vm = BudgetViewModel(note.id, env.budgetRepo, env.noteRepo, env.budget, env.notes)
        return Triple(vm, note.id, mapOf("root" to root, "a" to a, "b" to b, "leaf" to leaf))
    }

    @Test
    fun `changing the group of an item moves it there and the totals follow`() = runTest {
        val (vm, noteId, n) = setup()
        val leaf = n.getValue("leaf")

        vm.updateNode(leaf, "Bread", null, 500, 800, 1, newParentId = n.getValue("b").id)

        val nodes = env.budgetRepo.getNodes(noteId).associateBy { it.id }
        assertEquals(n.getValue("b").id, nodes.getValue(leaf.id).parentId)
        assertEquals(0, nodes.getValue(n.getValue("a").id).cachedSumMinor)
        assertEquals(500, nodes.getValue(n.getValue("b").id).cachedSumMinor)
        assertEquals(500, nodes.getValue(n.getValue("root").id).cachedSumMinor) // the root still counts it once
    }

    @Test
    fun `values edited together with the group are kept`() = runTest {
        val (vm, noteId, n) = setup()
        vm.updateNode(n.getValue("leaf"), "Rye bread", "fresh", 700, 900, 2, newParentId = n.getValue("b").id)
        val moved = env.budgetRepo.getNodes(noteId).single { it.id == n.getValue("leaf").id }
        assertEquals("Rye bread", moved.title)
        assertEquals("fresh", moved.comment)
        assertEquals(1_400, moved.cachedSumMinor) // 7.00 x 2
        assertEquals(n.getValue("b").id, moved.parentId)
    }

    @Test
    fun `a group cannot be moved into its own subtree and nothing changes`() = runTest {
        val (vm, noteId, n) = setup()
        val a = n.getValue("a")
        val child = (env.budget.addNode(noteId, a.id, "Child", BudgetNodeKind.GROUP) as DomainResult.Success).value
        val errors = ArrayList<com.notebudget.app.domain.model.DomainError>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.errors.toList(errors) }

        vm.updateNode(a, "A", null, 0, 0, 1, newParentId = child.id)

        assertEquals(listOf(com.notebudget.app.domain.model.DomainError.BUDGET_INVALID_MOVE), errors)
        assertEquals(n.getValue("root").id, env.budgetRepo.getNodes(noteId).single { it.id == a.id }.parentId)
    }

    @Test
    fun `the root has no group to choose and never moves`() = runTest {
        val (vm, noteId, n) = setup()
        val root = n.getValue("root")
        vm.updateNode(root, "Renamed", null, 0, 0, 1, newParentId = n.getValue("a").id)
        assertEquals(null, env.budgetRepo.getNodes(noteId).single { it.id == root.id }.parentId)
    }
}
