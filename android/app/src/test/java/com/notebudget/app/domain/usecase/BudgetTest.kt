package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetTest {
    private val env = TestEnv()

    private fun node(
        id: String,
        parent: String?,
        kind: BudgetNodeKind,
        sum: Long = 0,
        goal: Long = 0,
    ) = BudgetNode(
        id = id, noteId = "n", parentId = parent, title = id, kind = kind,
        manualSumMinor = sum, manualGoalMinor = goal, comment = null,
        cachedSumMinor = 0, cachedGoalMinor = 0, cachedRemainderMinor = 0,
        isExpanded = true, sortOrder = 0, createdAt = 0, updatedAt = 0,
    )

    private fun List<BudgetNode>.byId(id: String) = first { it.id == id }

    // ---- calculator ----

    @Test
    fun `leaf uses manual values and remainder is goal minus sum`() {
        val result = BudgetCalculator.recalculateAll(
            listOf(node("root", null, BudgetNodeKind.GROUP), node("a", "root", BudgetNodeKind.LEAF, 30, 100)),
        )
        assertEquals(30, result.byId("a").cachedSumMinor)
        assertEquals(70, result.byId("a").cachedRemainderMinor)
    }

    @Test
    fun `group sums children and nested groups cascade to the root`() {
        val result = BudgetCalculator.recalculateAll(
            listOf(
                node("root", null, BudgetNodeKind.GROUP),
                node("g", "root", BudgetNodeKind.GROUP),
                node("a", "g", BudgetNodeKind.LEAF, 10, 50),
                node("b", "g", BudgetNodeKind.LEAF, 20, 40),
                node("c", "root", BudgetNodeKind.LEAF, 5, 5),
            ),
        )
        assertEquals(30, result.byId("g").cachedSumMinor)
        assertEquals(90, result.byId("g").cachedGoalMinor)
        assertEquals(35, result.byId("root").cachedSumMinor)
        assertEquals(95, result.byId("root").cachedGoalMinor)
        assertEquals(60, result.byId("root").cachedRemainderMinor)
    }

    @Test
    fun `remainder may be negative`() {
        val result = BudgetCalculator.recalculateAll(
            listOf(node("root", null, BudgetNodeKind.GROUP), node("a", "root", BudgetNodeKind.LEAF, 150, 100)),
        )
        assertEquals(-50, result.byId("root").cachedRemainderMinor)
    }

    @Test
    fun `group ignores its own manual values (INV-4)`() {
        val result = BudgetCalculator.recalculateAll(
            listOf(node("root", null, BudgetNodeKind.GROUP, sum = 999, goal = 999)),
        )
        assertEquals(0, result.byId("root").cachedSumMinor)
    }

    @Test
    fun `branch recalculation matches full recalculation`() {
        val base = BudgetCalculator.recalculateAll(
            listOf(
                node("root", null, BudgetNodeKind.GROUP),
                node("g", "root", BudgetNodeKind.GROUP),
                node("a", "g", BudgetNodeKind.LEAF, 10, 50),
                node("b", "root", BudgetNodeKind.LEAF, 1, 2),
            ),
        )
        val edited = base.map { if (it.id == "a") it.copy(manualSumMinor = 77, manualGoalMinor = 80) else it }
        val branch = BudgetCalculator.recalculateBranch(edited, listOf("a"))
        assertEquals(BudgetCalculator.recalculateAll(edited), branch)
    }

    // ---- editor ----

    private suspend fun budgetNoteId(): String {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (env.notes.create(folder.id, "Budget", NoteType.BUDGET) as DomainResult.Success).value.id
    }

    private suspend fun rootId(noteId: String) = env.budgetRepo.getNodes(noteId).single { it.parentId == null }.id

    private suspend fun add(noteId: String, parent: String, title: String, kind: BudgetNodeKind) =
        (env.budget.addNode(noteId, parent, title, kind) as DomainResult.Success).value

    @Test
    fun `editing a leaf recalculates every ancestor`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val group = add(noteId, root, "Group", BudgetNodeKind.GROUP)
        val leaf = add(noteId, group.id, "Leaf", BudgetNodeKind.LEAF)

        env.budget.updateLeaf(noteId, leaf.id, sumMinor = 200_000, goalMinor = 1_000_000)

        val nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(200_000, nodes.byId(group.id).cachedSumMinor)
        assertEquals(200_000, nodes.byId(root).cachedSumMinor)
        assertEquals(800_000, nodes.byId(root).cachedRemainderMinor)
    }

    @Test
    fun `changing a leaf adds or subtracts at once and recalculates the ancestors`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val group = add(noteId, root, "Group", BudgetNodeKind.GROUP)
        val leaf = add(noteId, group.id, "Leaf", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, sumMinor = 10_000, goalMinor = 50_000)

        assertTrue(env.budget.adjustLeafSum(noteId, leaf.id, 2_550) is DomainResult.Success)
        var nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(12_550, nodes.byId(leaf.id).manualSumMinor)
        assertEquals(12_550, nodes.byId(group.id).cachedSumMinor)
        assertEquals(12_550, nodes.byId(root).cachedSumMinor)
        assertEquals(37_450, nodes.byId(root).cachedRemainderMinor)

        assertTrue(env.budget.adjustLeafSum(noteId, leaf.id, -20_000) is DomainResult.Success)
        nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(-7_450, nodes.byId(leaf.id).manualSumMinor)
        assertEquals(-7_450, nodes.byId(root).cachedSumMinor)
    }

    @Test
    fun `changing a leaf with a quantity changes the sum of one piece`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val leaf = add(noteId, root, "Bread", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, sumMinor = 1_000, goalMinor = 2_000, quantity = 3)

        env.budget.adjustLeafSum(noteId, leaf.id, 500)

        val nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(1_500, nodes.byId(leaf.id).manualSumMinor)
        assertEquals(4_500, nodes.byId(root).cachedSumMinor)
    }

    @Test
    fun `entering a sum replaces the current one and recalculates the ancestors`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val group = add(noteId, root, "Group", BudgetNodeKind.GROUP)
        val leaf = add(noteId, group.id, "Leaf", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, sumMinor = 10_000, goalMinor = 50_000, quantity = 2)

        assertTrue(env.budget.setLeafSum(noteId, leaf.id, 3_000) is DomainResult.Success)

        val nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(3_000, nodes.byId(leaf.id).manualSumMinor)
        assertEquals(50_000, nodes.byId(leaf.id).manualGoalMinor) // the goal and the quantity stay
        assertEquals(2, nodes.byId(leaf.id).quantity)
        assertEquals(6_000, nodes.byId(root).cachedSumMinor)
        assertTrue(env.budget.setLeafSum(noteId, root, 1) is DomainResult.Failure)
    }

    @Test
    fun `items and groups are put in the dragged order and a group keeps what is inside it`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val a = add(noteId, root, "A", BudgetNodeKind.GROUP)
        val b = add(noteId, root, "B", BudgetNodeKind.GROUP)
        val c = add(noteId, root, "C", BudgetNodeKind.LEAF)
        val inA1 = add(noteId, a.id, "a1", BudgetNodeKind.LEAF)
        val inA2 = add(noteId, a.id, "a2", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, inA1.id, sumMinor = 100, goalMinor = 300)
        val before = env.budgetRepo.getNodes(noteId)
        assertEquals(listOf("A", "B", "C"), com.notebudget.app.feature.budget.visibleBudgetRows(before).filter { it.depth == 1 }.map { it.node.title })

        // Drag the group A after B and C.
        assertTrue(env.budget.reorderChildren(noteId, root, listOf(b.id, c.id, a.id)) is DomainResult.Success)
        // Drag a2 in front of a1, inside A.
        assertTrue(env.budget.reorderChildren(noteId, a.id, listOf(inA2.id, inA1.id)) is DomainResult.Success)

        val after = env.budgetRepo.getNodes(noteId)
        val rows = com.notebudget.app.feature.budget.visibleBudgetRows(after)
        assertEquals(listOf("B", "C", "A", "a2", "a1"), rows.drop(1).map { it.node.title }) // the total comes first
        // The sums did not move with the order.
        assertEquals(100, after.byId(a.id).cachedSumMinor)
        assertEquals(100, after.byId(root).cachedSumMinor)
    }

    @Test
    fun `a drag that does not name exactly the children is refused, and the same order writes nothing`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val a = add(noteId, root, "A", BudgetNodeKind.LEAF)
        val b = add(noteId, root, "B", BudgetNodeKind.LEAF)
        val elsewhere = add(noteId, root, "G", BudgetNodeKind.GROUP)
        val inside = add(noteId, elsewhere.id, "inside", BudgetNodeKind.LEAF)

        assertEquals(DomainError.BUDGET_INVALID_MOVE, (env.budget.reorderChildren(noteId, root, listOf(a.id, b.id)) as DomainResult.Failure).error) // G missing
        assertEquals(DomainError.BUDGET_INVALID_MOVE, (env.budget.reorderChildren(noteId, root, listOf(a.id, b.id, elsewhere.id, inside.id)) as DomainResult.Failure).error)
        assertEquals(DomainError.BUDGET_NODE_NOT_FOUND, (env.budget.reorderChildren(noteId, "nope", listOf(a.id)) as DomainResult.Failure).error)

        val stamp = env.budgetRepo.getNodes(noteId).byId(a.id).updatedAt
        env.clock.current += 1_000
        assertTrue(env.budget.reorderChildren(noteId, root, listOf(a.id, b.id, elsewhere.id)) is DomainResult.Success)
        assertEquals(stamp, env.budgetRepo.getNodes(noteId).byId(a.id).updatedAt)
    }

    @Test
    fun `the shown order and the stored order agree, also when the newest items come first`() {
        fun node(id: String, order: Int) = BudgetNode(
            id = id, noteId = "n", parentId = "root", title = id, kind = BudgetNodeKind.LEAF, manualSumMinor = 0, manualGoalMinor = 0,
            comment = null, cachedSumMinor = 0, cachedGoalMinor = 0, cachedRemainderMinor = 0, isExpanded = true, sortOrder = order,
            createdAt = 0, updatedAt = 0,
        )
        val nodes = listOf(node("x", 0), node("y", 1), node("z", 2))

        assertEquals(listOf("x", "y", "z"), com.notebudget.app.feature.budget.displayedChildren(nodes, "root", newestFirst = false))
        assertEquals(listOf("z", "y", "x"), com.notebudget.app.feature.budget.displayedChildren(nodes, "root", newestFirst = true))
        // Shown as z, x, y with the newest first: stored as y, x, z.
        assertEquals(listOf("y", "x", "z"), com.notebudget.app.feature.budget.storageOrder(listOf("z", "x", "y"), newestFirst = true))
        val renumbered = com.notebudget.app.feature.budget.withDisplayedOrder(nodes, "root", listOf("z", "x", "y"), newestFirst = true)
        assertEquals(listOf("z", "x", "y"), com.notebudget.app.feature.budget.displayedChildren(renumbered, "root", newestFirst = true))
    }

    @Test
    fun `while a brother is dragged the groups of that level are shown closed`() {
        fun node(id: String, parent: String?, kind: BudgetNodeKind, order: Int) = BudgetNode(
            id = id, noteId = "n", parentId = parent, title = id, kind = kind, manualSumMinor = 0, manualGoalMinor = 0,
            comment = null, cachedSumMinor = 0, cachedGoalMinor = 0, cachedRemainderMinor = 0, isExpanded = true, sortOrder = order,
            createdAt = 0, updatedAt = 0,
        )
        val nodes = listOf(
            node("root", null, BudgetNodeKind.GROUP, 0), node("g1", "root", BudgetNodeKind.GROUP, 0), node("g2", "root", BudgetNodeKind.GROUP, 1),
            node("a", "g1", BudgetNodeKind.LEAF, 0), node("b", "g2", BudgetNodeKind.LEAF, 0),
        )
        assertEquals(listOf("root", "g1", "a", "g2", "b"), com.notebudget.app.feature.budget.visibleBudgetRows(nodes).map { it.node.id })
        assertEquals(listOf("root", "g1", "g2"), com.notebudget.app.feature.budget.visibleBudgetRows(nodes, collapse = setOf("g1", "g2")).map { it.node.id })
    }

    @Test
    fun `collapse all and expand all close and open every group but not the total`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val a = add(noteId, root, "A", BudgetNodeKind.GROUP)
        val inner = add(noteId, a.id, "Inner", BudgetNodeKind.GROUP)
        val leafA = add(noteId, a.id, "a1", BudgetNodeKind.LEAF)
        val leafInner = add(noteId, inner.id, "i1", BudgetNodeKind.LEAF)
        val b = add(noteId, root, "B", BudgetNodeKind.GROUP)
        add(noteId, b.id, "b1", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leafInner.id, sumMinor = 700, goalMinor = 900)
        val before = env.budgetRepo.getNodes(noteId)

        assertTrue(env.budget.setAllExpanded(noteId, false) is DomainResult.Success)
        var nodes = env.budgetRepo.getNodes(noteId)
        assertTrue(nodes.byId(root).isExpanded) // the total stays open
        assertTrue(listOf(a.id, inner.id, b.id).none { nodes.byId(it).isExpanded })
        // Only the marks changed: no sum, no title, no time.
        assertEquals(before.map { it.cachedSumMinor to it.updatedAt }, nodes.map { it.cachedSumMinor to it.updatedAt })
        // What is shown: the total and its two closed groups.
        assertEquals(listOf(root, a.id, b.id), com.notebudget.app.feature.budget.visibleBudgetRows(nodes).map { it.node.id })

        assertTrue(env.budget.setAllExpanded(noteId, true) is DomainResult.Success)
        nodes = env.budgetRepo.getNodes(noteId)
        assertTrue(listOf(root, a.id, inner.id, b.id).all { nodes.byId(it).isExpanded })
        assertTrue(com.notebudget.app.feature.budget.visibleBudgetRows(nodes).any { it.node.id == leafInner.id })
        assertEquals(DomainError.BUDGET_NODE_NOT_FOUND, (env.budget.setAllExpanded("nope", true) as DomainResult.Failure).error)
        assertEquals(before.map { it.title }, nodes.map { it.title })
        assertEquals(leafA.id, nodes.byId(leafA.id).id)
    }

    @Test
    fun `changing refuses a group, an unknown node and an amount that does not fit`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val leaf = add(noteId, root, "Leaf", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, sumMinor = Long.MAX_VALUE, goalMinor = 0)

        assertEquals(DomainError.BUDGET_NODE_NOT_LEAF, (env.budget.adjustLeafSum(noteId, root, 1) as DomainResult.Failure).error)
        assertEquals(DomainError.BUDGET_NODE_NOT_FOUND, (env.budget.adjustLeafSum(noteId, "nope", 1) as DomainResult.Failure).error)
        assertEquals(DomainError.INVALID_AMOUNT, (env.budget.adjustLeafSum(noteId, leaf.id, 1) as DomainResult.Failure).error)
        assertEquals(Long.MAX_VALUE, env.budgetRepo.getNodes(noteId).byId(leaf.id).manualSumMinor)
    }

    @Test
    fun `a new leaf keeps the sum, goal and comment it was created with and counts into the totals`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val group = add(noteId, root, "Group", BudgetNodeKind.GROUP)

        val leaf = (env.budget.addNode(
            noteId, group.id, "Rent", BudgetNodeKind.LEAF,
            comment = "monthly", sumMinor = 45_000, goalMinor = 60_000,
        ) as DomainResult.Success).value

        // The returned node and the stored one agree, without any later edit.
        assertEquals(45_000, leaf.manualSumMinor)
        assertEquals(60_000, leaf.manualGoalMinor)
        val nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(45_000, nodes.byId(leaf.id).manualSumMinor)
        assertEquals(45_000, nodes.byId(leaf.id).cachedSumMinor)
        assertEquals(60_000, nodes.byId(leaf.id).cachedGoalMinor)
        assertEquals(15_000, nodes.byId(leaf.id).cachedRemainderMinor)
        assertEquals("monthly", nodes.byId(leaf.id).comment)
        // Every ancestor already includes it.
        assertEquals(45_000, nodes.byId(group.id).cachedSumMinor)
        assertEquals(45_000, nodes.byId(root).cachedSumMinor)
        assertEquals(60_000, nodes.byId(root).cachedGoalMinor)
    }

    @Test
    fun `several new leaves add up and a blank comment is stored as none`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        env.budget.addNode(noteId, root, "A", BudgetNodeKind.LEAF, comment = "  ", sumMinor = 100, goalMinor = 300)
        env.budget.addNode(noteId, root, "B", BudgetNodeKind.LEAF, sumMinor = 250, goalMinor = 250)

        val nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(350, nodes.byId(root).cachedSumMinor)
        assertEquals(550, nodes.byId(root).cachedGoalMinor)
        assertEquals(200, nodes.byId(root).cachedRemainderMinor)
        assertNull(nodes.single { it.title == "A" }.comment)
    }

    @Test
    fun `a new group takes no values of its own`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)

        val group = (env.budget.addNode(
            noteId, root, "Group", BudgetNodeKind.GROUP, comment = "note", sumMinor = 999, goalMinor = 999,
        ) as DomainResult.Success).value

        assertEquals(0, group.manualSumMinor)
        assertEquals(0, group.manualGoalMinor)
        val nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(0, nodes.byId(group.id).cachedSumMinor)
        assertEquals(0, nodes.byId(root).cachedSumMinor)
        assertEquals("note", nodes.byId(group.id).comment) // the comment is kept
    }

    // ---- quantity (D94): "Bread x3" counts three times ----

    @Test
    fun `a quantity multiplies the sum and goal of one piece into every total`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val group = add(noteId, root, "Group", BudgetNodeKind.GROUP)
        val bread = add(noteId, group.id, "Bread", BudgetNodeKind.LEAF)
        val other = add(noteId, group.id, "Other", BudgetNodeKind.LEAF)

        env.budget.updateLeaf(noteId, bread.id, sumMinor = 10_000, goalMinor = 20_000, quantity = 3)
        env.budget.updateLeaf(noteId, other.id, sumMinor = 500, goalMinor = 500)

        val nodes = env.budgetRepo.getNodes(noteId)
        val b = nodes.byId(bread.id)
        assertEquals(3, b.quantity)
        assertEquals(10_000, b.manualSumMinor) // one piece
        assertEquals(30_000, b.cachedSumMinor) // three pieces
        assertEquals(60_000, b.cachedGoalMinor)
        assertEquals(30_000, b.cachedRemainderMinor)
        assertEquals(30_500, nodes.byId(group.id).cachedSumMinor)
        assertEquals(60_500, nodes.byId(root).cachedGoalMinor)
        assertEquals(30_000, nodes.byId(root).cachedRemainderMinor)
        assertEquals(1, nodes.byId(other.id).quantity) // the default
    }

    @Test
    fun `changing only the values keeps the quantity, changing the quantity recalculates the ancestors`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val leaf = add(noteId, root, "Leaf", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, 100, 200, quantity = 5)

        env.budget.updateLeaf(noteId, leaf.id, 120, 200) // quantity not given
        var nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(5, nodes.byId(leaf.id).quantity)
        assertEquals(600, nodes.byId(root).cachedSumMinor)

        env.budget.updateLeaf(noteId, leaf.id, 120, 200, quantity = 1)
        nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(120, nodes.byId(root).cachedSumMinor)
        assertEquals(200, nodes.byId(root).cachedGoalMinor)
    }

    @Test
    fun `a leaf can be created with its quantity, a group always has one`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)

        val leaf = (env.budget.addNode(noteId, root, "Beer", BudgetNodeKind.LEAF, sumMinor = 250, goalMinor = 250, quantity = 6)
            as DomainResult.Success).value
        val group = (env.budget.addNode(noteId, root, "Group", BudgetNodeKind.GROUP, quantity = 7)
            as DomainResult.Success).value

        assertEquals(6, leaf.quantity)
        assertEquals(1, group.quantity)
        assertEquals(1_500, env.budgetRepo.getNodes(noteId).byId(root).cachedSumMinor)
    }

    @Test
    fun `a quantity outside 1 to 9999 is refused and nothing changes`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val leaf = add(noteId, root, "Leaf", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, 100, 100, quantity = 2)

        for (bad in listOf(0, -1, 10_000)) {
            assertEquals(
                DomainResult.Failure(DomainError.INVALID_QUANTITY),
                env.budget.updateLeaf(noteId, leaf.id, 999, 999, quantity = bad),
            )
            assertEquals(
                DomainResult.Failure(DomainError.INVALID_QUANTITY),
                env.budget.addNode(noteId, root, "New", BudgetNodeKind.LEAF, quantity = bad),
            )
        }
        val nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(2, nodes.byId(leaf.id).quantity)
        assertEquals(100, nodes.byId(leaf.id).manualSumMinor)
        assertEquals(2, nodes.size) // no node was added
        // The limits themselves are allowed.
        assertTrue(env.budget.updateLeaf(noteId, leaf.id, 1, 1, quantity = 9_999) is DomainResult.Success)
    }

    @Test
    fun `a full recalculation (import, repair) also counts the quantity, and treats a broken 0 as one`() {
        val root = node("r", null, BudgetNodeKind.GROUP)
        val a = node("a", "r", BudgetNodeKind.LEAF).copy(manualSumMinor = 100, manualGoalMinor = 150, quantity = 4)
        val broken = node("b", "r", BudgetNodeKind.LEAF).copy(manualSumMinor = 10, manualGoalMinor = 10, quantity = 0)

        val result = BudgetCalculator.recalculateAll(listOf(root, a, broken))

        assertEquals(400, result.byId("a").cachedSumMinor)
        assertEquals(10, result.byId("b").cachedSumMinor)
        assertEquals(410, result.byId("r").cachedSumMinor)
        assertEquals(610, result.byId("r").cachedGoalMinor)
    }

    @Test
    fun `a leaf cannot have children and only leaves take manual values`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val leaf = add(noteId, root, "Leaf", BudgetNodeKind.LEAF)

        assertEquals(
            DomainResult.Failure(DomainError.BUDGET_PARENT_IS_LEAF),
            env.budget.addNode(noteId, leaf.id, "Child", BudgetNodeKind.LEAF),
        )
        assertEquals(
            DomainResult.Failure(DomainError.BUDGET_NODE_NOT_LEAF),
            env.budget.updateLeaf(noteId, root, 1, 1),
        )
    }

    @Test
    fun `deleting a node removes its subtree and updates the totals`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val group = add(noteId, root, "Group", BudgetNodeKind.GROUP)
        val leaf = add(noteId, group.id, "Leaf", BudgetNodeKind.LEAF)
        val other = add(noteId, root, "Other", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, 10, 20)
        env.budget.updateLeaf(noteId, other.id, 1, 2)

        env.budget.deleteNode(noteId, group.id)

        val nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(setOf(root, other.id), nodes.map { it.id }.toSet())
        assertEquals(1, nodes.byId(root).cachedSumMinor)
        assertEquals(2, nodes.byId(root).cachedGoalMinor)
    }

    @Test
    fun `the root cannot be deleted or moved`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val group = add(noteId, root, "Group", BudgetNodeKind.GROUP)
        assertEquals(DomainResult.Failure(DomainError.BUDGET_ROOT_IMMUTABLE), env.budget.deleteNode(noteId, root))
        assertEquals(DomainResult.Failure(DomainError.BUDGET_ROOT_IMMUTABLE), env.budget.moveNode(noteId, root, group.id))
    }

    @Test
    fun `moving a node recalculates both the old and the new branch`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val g1 = add(noteId, root, "G1", BudgetNodeKind.GROUP)
        val g2 = add(noteId, root, "G2", BudgetNodeKind.GROUP)
        val leaf = add(noteId, g1.id, "Leaf", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, 40, 100)

        env.budget.moveNode(noteId, leaf.id, g2.id)

        val nodes = env.budgetRepo.getNodes(noteId)
        assertEquals(0, nodes.byId(g1.id).cachedSumMinor)
        assertEquals(40, nodes.byId(g2.id).cachedSumMinor)
        assertEquals(40, nodes.byId(root).cachedSumMinor)
    }

    @Test
    fun `a group cannot be moved into its own subtree`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val outer = add(noteId, root, "Outer", BudgetNodeKind.GROUP)
        val inner = add(noteId, outer.id, "Inner", BudgetNodeKind.GROUP)

        assertEquals(DomainResult.Failure(DomainError.BUDGET_INVALID_MOVE), env.budget.moveNode(noteId, outer.id, inner.id))
        assertEquals(DomainResult.Failure(DomainError.BUDGET_INVALID_MOVE), env.budget.moveNode(noteId, outer.id, outer.id))
    }

    @Test
    fun `collapsing does not change values`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val leaf = add(noteId, root, "Leaf", BudgetNodeKind.LEAF)
        env.budget.updateLeaf(noteId, leaf.id, 5, 9)
        val before = env.budgetRepo.getNodes(noteId).byId(root)

        env.budget.setExpanded(noteId, root, false)

        val after = env.budgetRepo.getNodes(noteId).byId(root)
        assertEquals(before.cachedSumMinor, after.cachedSumMinor)
        assertEquals(before.cachedGoalMinor, after.cachedGoalMinor)
        assertEquals(false, after.isExpanded)
    }

    @Test
    fun `comment is stored and blank comment becomes null`() = runTest {
        val noteId = budgetNoteId()
        val root = rootId(noteId)
        val leaf = add(noteId, root, "Leaf", BudgetNodeKind.LEAF)

        env.budget.updateDetails(noteId, leaf.id, "Rent", "paid monthly")
        assertEquals("paid monthly", env.budgetRepo.getNodes(noteId).byId(leaf.id).comment)

        env.budget.updateDetails(noteId, leaf.id, "Rent", "  ")
        assertEquals(null, env.budgetRepo.getNodes(noteId).byId(leaf.id).comment)
        assertTrue(env.budgetRepo.getNodes(noteId).byId(leaf.id).title == "Rent")
    }
}
