package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.BudgetSortKey
import com.notebudget.app.domain.model.ListDocument
import com.notebudget.app.domain.model.ListEntry
import com.notebudget.app.domain.model.ListSortKey
import com.notebudget.app.feature.budget.BudgetLayout
import com.notebudget.app.feature.budget.visibleBudgetRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sorting the rows of a budget and of a list (D178): by name, date, sum ..., the own order kept underneath. */
class ItemSortTest {

    private fun node(
        id: String, parent: String?, order: Int, sum: Long = 0, goal: Long = 0, created: Long = 0, updated: Long = 0,
        kind: BudgetNodeKind = BudgetNodeKind.LEAF, title: String = id,
    ) = BudgetNode(
        id = id, noteId = "n", parentId = parent, title = title, kind = kind, manualSumMinor = sum, manualGoalMinor = goal, comment = null,
        cachedSumMinor = sum, cachedGoalMinor = goal, cachedRemainderMinor = goal - sum, isExpanded = true, sortOrder = order,
        createdAt = created, updatedAt = updated,
    )

    private fun rows(nodes: List<BudgetNode>, key: BudgetSortKey, desc: Boolean = false, newestFirst: Boolean = false) =
        visibleBudgetRows(nodes, BudgetLayout(sort = key.name, sortDescending = desc, newestFirst = newestFirst)).drop(1).map { it.node.id }

    private val root = node("root", null, 0, kind = BudgetNodeKind.GROUP)

    // ---------------- budget ----------------

    @Test
    fun `a budget is sorted inside every group by name, ignoring case`() {
        val nodes = listOf(
            root,
            node("g", "root", 0, kind = BudgetNodeKind.GROUP, title = "Zoo"),
            node("a", "root", 1, title = "apple"),
            node("b", "root", 2, title = "Banana"),
            node("in2", "g", 0, title = "yak"),
            node("in1", "g", 1, title = "Ant"),
        )
        // Root: apple, Banana, Zoo (the group, with its own rows sorted: Ant, yak).
        assertEquals(listOf("a", "b", "g", "in1", "in2"), rows(nodes, BudgetSortKey.NAME))
        assertEquals(listOf("g", "in2", "in1", "b", "a"), rows(nodes, BudgetSortKey.NAME, desc = true))
    }

    @Test
    fun `by sum, goal, remainder and date`() {
        val nodes = listOf(
            root,
            node("x", "root", 0, sum = 300, goal = 400, created = 30, updated = 5),
            node("y", "root", 1, sum = 100, goal = 900, created = 10, updated = 9),
            node("z", "root", 2, sum = 200, goal = 250, created = 20, updated = 1),
        )
        assertEquals(listOf("y", "z", "x"), rows(nodes, BudgetSortKey.SUM))
        assertEquals(listOf("x", "z", "y"), rows(nodes, BudgetSortKey.SUM, desc = true))
        assertEquals(listOf("z", "x", "y"), rows(nodes, BudgetSortKey.GOAL))
        assertEquals(listOf("z", "x", "y"), rows(nodes, BudgetSortKey.REMAINDER)) // 50, 100, 800
        assertEquals(listOf("y", "z", "x"), rows(nodes, BudgetSortKey.CREATED))
        assertEquals(listOf("x", "z", "y"), rows(nodes, BudgetSortKey.CREATED, desc = true))
        assertEquals(listOf("z", "x", "y"), rows(nodes, BudgetSortKey.CHANGED))
    }

    @Test
    fun `progress puts the rows without a goal last, whichever way it goes`() {
        val nodes = listOf(
            root,
            node("none", "root", 0, sum = 500, goal = 0),
            node("half", "root", 1, sum = 50, goal = 100),
            node("full", "root", 2, sum = 100, goal = 100),
            node("over", "root", 3, sum = 300, goal = 100),
        )
        assertEquals(listOf("half", "full", "over", "none"), rows(nodes, BudgetSortKey.PROGRESS))
        assertEquals(listOf("over", "full", "half", "none"), rows(nodes, BudgetSortKey.PROGRESS, desc = true))
    }

    @Test
    fun `equal values keep a steady order, and the own order comes back with MANUAL`() {
        val nodes = listOf(
            root,
            node("b", "root", 0, sum = 100, title = "same"),
            node("a", "root", 1, sum = 100, title = "same"),
            node("c", "root", 2, sum = 50, title = "other"),
        )
        // Equal sums: by name, then by their own order.
        assertEquals(listOf("c", "b", "a"), rows(nodes, BudgetSortKey.SUM))
        // MANUAL is the own order (and "newest first" reads it backwards, only there).
        assertEquals(listOf("b", "a", "c"), rows(nodes, BudgetSortKey.MANUAL))
        assertEquals(listOf("c", "a", "b"), rows(nodes, BudgetSortKey.MANUAL, newestFirst = true))
        // With a sort by value "newest first" has no say.
        assertEquals(rows(nodes, BudgetSortKey.SUM), rows(nodes, BudgetSortKey.SUM, newestFirst = true))
    }

    @Test
    fun `the sort of a budget lives in its layout, unknown names fall back to the own order`() {
        val layout = BudgetLayout(sort = BudgetSortKey.SUM.name, sortDescending = true)
        assertEquals(layout, BudgetLayout.parse(layout.toExtras()))
        assertEquals(BudgetSortKey.MANUAL, BudgetLayout.parse("""{"totalAtBottom":true}""").sortKey)
        assertEquals(BudgetSortKey.MANUAL, BudgetLayout(sort = "FROM_THE_FUTURE").sortKey)
    }

    // ---------------- list ----------------

    private fun item(id: String, text: String, q: Long? = null, unit: String? = null, price: Long? = null, done: Boolean = false, at: Long = 0) =
        ListEntry(id = id, text = text, quantityMilli = q, unit = unit, priceMilli = price, done = done, createdAt = at)

    private fun doc(key: ListSortKey, desc: Boolean = false, price: Boolean = false, vararg entries: ListEntry) = ListDocument(
        entries = entries.toList(), sort = key.name, sortDescending = desc,
        mode = if (price) ListDocument.MODE_PRICE else ListDocument.MODE_QUANTITY,
    )

    private val milk = item("milk", "Milk", q = 2_000, unit = "l", price = 50_000, at = 30)
    private val bread = item("bread", "bread", q = 1_000, unit = "pcs", price = 40_000, done = true, at = 10)
    private val eggs = item("eggs", "Eggs", q = 10_000, unit = "pcs", at = 20)
    private val plain = item("plain", "Cheese")
    private val total = ListEntry.newTotal("pcs", now = 1).copy(id = "total")

    private fun ListDocument.ids() = displayEntries { if (it == "pcs") "pieces" else "litres" }.map { it.id }

    @Test
    fun `a list is sorted by name, ignoring case, with the totals after the items`() {
        assertEquals(listOf("bread", "plain", "eggs", "milk", "total"), doc(ListSortKey.NAME, entries = arrayOf(milk, total, bread, eggs, plain)).ids())
        assertEquals(listOf("milk", "eggs", "plain", "bread", "total"), doc(ListSortKey.NAME, true, entries = arrayOf(milk, total, bread, eggs, plain)).ids())
    }

    @Test
    fun `by quantity, and rows without one come last both ways`() {
        assertEquals(listOf("bread", "milk", "eggs", "plain"), doc(ListSortKey.QUANTITY, entries = arrayOf(milk, plain, bread, eggs)).ids())
        assertEquals(listOf("eggs", "milk", "bread", "plain"), doc(ListSortKey.QUANTITY, true, entries = arrayOf(milk, plain, bread, eggs)).ids())
    }

    @Test
    fun `by price and by cost in a price list`() {
        // bread costs 1 x 40 = 40, milk 2 x 50 = 100; eggs and cheese have no price.
        assertEquals(listOf("bread", "milk", "eggs", "plain"), doc(ListSortKey.PRICE, price = true, entries = arrayOf(milk, eggs, plain, bread)).ids())
        assertEquals(listOf("milk", "bread", "eggs", "plain"), doc(ListSortKey.COST, true, price = true, entries = arrayOf(bread, eggs, plain, milk)).ids())
        assertTrue(ListSortKey.PRICE.needsPrices && ListSortKey.COST.needsPrices)
        assertFalse(ListSortKey.NAME.needsPrices)
    }

    @Test
    fun `by date added, rows of older lists have none and come last`() {
        assertEquals(listOf("bread", "eggs", "milk", "plain"), doc(ListSortKey.CREATED, entries = arrayOf(milk, plain, bread, eggs)).ids())
        assertEquals(listOf("milk", "eggs", "bread", "plain"), doc(ListSortKey.CREATED, true, entries = arrayOf(milk, plain, bread, eggs)).ids())
    }

    @Test
    fun `by unit name and by done`() {
        // pieces (bread, eggs), litres (milk): "litres" < "pieces".
        assertEquals(listOf("milk", "bread", "eggs", "plain"), doc(ListSortKey.UNIT, entries = arrayOf(bread, eggs, milk, plain)).ids())
        // Not done first; equal ones keep their own order.
        assertEquals(listOf("milk", "eggs", "bread"), doc(ListSortKey.DONE, entries = arrayOf(bread, milk, eggs)).ids())
        assertEquals(listOf("bread", "milk", "eggs"), doc(ListSortKey.DONE, true, entries = arrayOf(bread, milk, eggs)).ids())
    }

    @Test
    fun `the own order is what a list shows until a sort is chosen, and the rows keep it underneath`() {
        val entries = arrayOf(milk, bread, eggs)
        val manual = doc(ListSortKey.MANUAL, entries = entries)
        assertFalse(manual.isSorted)
        assertEquals(listOf("milk", "bread", "eggs"), manual.ids())
        val sorted = manual.copy(sort = ListSortKey.NAME.name)
        assertTrue(sorted.isSorted)
        // Choosing a sort does not touch the stored order.
        assertEquals(listOf("milk", "bread", "eggs"), sorted.entries.map { it.id })
        assertEquals(ListSortKey.MANUAL, ListSortKey.of("nonsense"))
    }

    @Test
    fun `the sort and the date of a row are stored with the list`() {
        val original = doc(ListSortKey.COST, true, price = true, entries = arrayOf(milk, bread))
        val encoded = original.encode({ it }, emptyMap())
        val back = ListDocument.fromNote(encoded.contentText, encoded.extras)

        assertEquals(ListSortKey.COST, back.sortKey)
        assertTrue(back.sortDescending)
        assertEquals(30L, back.entries.first { it.id == "milk" }.createdAt)
        // A list stored before sorting existed shows the own order.
        assertEquals(ListSortKey.MANUAL, ListDocument.fromNote("- [ ] a", null).sortKey)
    }
}
