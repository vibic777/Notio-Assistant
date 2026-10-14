package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merging an income / expense into an account (D237). */
class BudgetMergePlannerTest {
    private var next = 0
    private fun id() = "n${++next}"

    private fun node(id: String, parent: String?, title: String, sum: Long = 0, goal: Long = 0, group: Boolean = false, qty: Int = 1, note: String = "acc") =
        BudgetNode(
            id = id, noteId = note, parentId = parent, title = title, kind = if (group) BudgetNodeKind.GROUP else BudgetNodeKind.LEAF,
            manualSumMinor = sum, manualGoalMinor = goal, comment = null, cachedSumMinor = 0, cachedGoalMinor = 0, cachedRemainderMinor = 0,
            isExpanded = true, sortOrder = 0, createdAt = 0, updatedAt = 0, quantity = qty,
        )

    // The account: Total > Food > Bread 100; Total > Rent 500
    private val account = listOf(
        node("r", null, "", group = true),
        node("food", "r", "Food", group = true),
        node("bread", "food", "Bread", sum = 100),
        node("rent", "r", "Rent", sum = 500),
    )

    // The income: Total > Salary > Bread 30 (x2); Total > Bonus 1000
    private val income = listOf(
        node("sr", null, "", group = true, note = "inc"),
        node("sal", "sr", "Salary", group = true, note = "inc"),
        node("sb", "sal", " bread ", sum = 30, qty = 2, note = "inc"),
        node("bonus", "sr", "Bonus", sum = 1000, goal = 50, note = "inc"),
    )

    private fun leaf(plan: MergePlan, title: String) = plan.nodes.single { it.kind == BudgetNodeKind.LEAF && it.title == title }

    @Test
    fun `by name an income adds to the item with the same name and creates the others at the root`() {
        val plan = BudgetMergePlanner.plan(income, account, MergeOptions(sign = 1), ::id, now = 7)
        assertEquals(160, leaf(plan, "Bread").manualSumMinor) // 100 + 30 x 2, the name matched whatever the case and spaces
        val bonus = leaf(plan, "Bonus")
        assertEquals("r", bonus.parentId)
        assertEquals(1000, bonus.manualSumMinor)
        assertEquals(0, bonus.manualGoalMinor) // goals not carried by default
        assertEquals(1, plan.changed)
        assertEquals(1, plan.created)
        assertEquals(500, leaf(plan, "Rent").manualSumMinor)
    }

    @Test
    fun `an expense subtracts, and the goal is carried when chosen`() {
        val plan = BudgetMergePlanner.plan(income, account, MergeOptions(sign = -1, sum = true, goal = true), ::id, now = 7)
        assertEquals(40, leaf(plan, "Bread").manualSumMinor)
        assertEquals(-1000, leaf(plan, "Bonus").manualSumMinor)
        assertEquals(-50, leaf(plan, "Bonus").manualGoalMinor)
    }

    @Test
    fun `by path only the same groups match, and the missing groups are made`() {
        val plan = BudgetMergePlanner.plan(income, account, MergeOptions(sign = 1, byPath = true), ::id, now = 7)
        // "Salary / bread" is not "Food / Bread": a new item under a new group "Salary".
        assertEquals(100, plan.nodes.single { it.id == "bread" }.manualSumMinor)
        val salary = plan.nodes.single { it.kind == BudgetNodeKind.GROUP && it.title == "Salary" }
        assertEquals("r", salary.parentId)
        val newBread = plan.nodes.single { it.kind == BudgetNodeKind.LEAF && it.parentId == salary.id }
        assertEquals(60, newBread.manualSumMinor)
        assertEquals(3, plan.created) // the group, the bread, the bonus
    }

    @Test
    fun `without same items everything is new, without transfer all nothing new is made`() {
        val separate = BudgetMergePlanner.plan(income, account, MergeOptions(sign = 1, sameItems = false), ::id, now = 7)
        assertEquals(100, leaf(separate, "Bread").manualSumMinor)
        assertEquals(2, separate.created)
        val onlyMatches = BudgetMergePlanner.plan(income, account, MergeOptions(sign = 1, transferAll = false), ::id, now = 7)
        assertEquals(1, onlyMatches.changed)
        assertEquals(0, onlyMatches.created)
        assertTrue(onlyMatches.nodes.none { it.title == "Bonus" })
    }

    @Test
    fun `an item of several pieces becomes one piece of its whole value, so the addition is exact`() {
        val acc = account.map { if (it.id == "bread") it.copy(manualSumMinor = 25, quantity = 4) else it }
        val plan = BudgetMergePlanner.plan(income, acc, MergeOptions(sign = 1), ::id, now = 7)
        val bread = leaf(plan, "Bread")
        assertEquals(1, bread.quantity)
        assertEquals(160, bread.manualSumMinor)
    }

    @Test
    fun `the record for undoing keeps the account as it was`() {
        val record = MergeRecord.of("inc", "Salary", BudgetKind.INCOME, account)
        val back = MergeRecord.decode(record.encode())
        assertNotNull(back)
        assertEquals(account.map { it.id to it.manualSumMinor }, back!!.toNodes("acc").map { it.id to it.manualSumMinor })
        assertNull(MergeRecord.decode("not json"))
        assertEquals(BudgetKind.ACCOUNT, BudgetKind.of(null))
        assertEquals(-1, BudgetKind.EXPENSE.sign)
    }
}
