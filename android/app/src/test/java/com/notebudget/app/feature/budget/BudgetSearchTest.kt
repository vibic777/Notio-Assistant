package com.notebudget.app.feature.budget

import com.notebudget.app.core.ui.SearchHit
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.ListEntry
import com.notebudget.app.domain.model.TextPattern
import com.notebudget.app.feature.notes.list.ListHitKey
import com.notebudget.app.feature.notes.list.listHits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The search inside a budget and a list (D238). */
class BudgetSearchTest {
    private fun node(id: String, parent: String?, title: String, group: Boolean = false, open: Boolean = false, comment: String? = null, order: Int = 0) =
        BudgetNode(
            id = id, noteId = "n", parentId = parent, title = title,
            kind = if (group) BudgetNodeKind.GROUP else BudgetNodeKind.LEAF,
            manualSumMinor = 0, manualGoalMinor = 0, comment = comment, cachedSumMinor = 0, cachedGoalMinor = 0,
            cachedRemainderMinor = 0, isExpanded = open, sortOrder = order, createdAt = 0, updatedAt = 0,
        )

    // Total > Food (closed) > Dairy (closed) > Milk; Total > Rent (comment "milk money")
    private val nodes = listOf(
        node("root", null, "", group = true, open = true),
        node("food", "root", "Food", group = true, open = false, order = 0),
        node("dairy", "food", "Dairy", group = true, open = false, order = 0),
        node("milk", "dairy", "Milk", order = 0),
        node("rent", "root", "Rent", comment = "milk money", order = 1),
    )

    @Test
    fun `the groups above a match are opened for the search, without the others`() {
        assertEquals(setOf("root", "food", "dairy"), budgetSearchOpen(nodes, TextPattern.of("milk"), showComments = false))
        // The comment counts only while the rows show comments.
        assertEquals(setOf("root", "food", "dairy"), budgetSearchOpen(nodes, TextPattern.of("MILK"), showComments = true))
        assertTrue(budgetSearchOpen(nodes, TextPattern.of("  "), showComments = true).isEmpty())
    }

    @Test
    fun `opened for the search, the hidden item is shown and found in order`() {
        val closed = visibleBudgetRows(nodes)
        assertEquals(listOf("root", "food", "rent"), closed.map { it.node.id })
        val open = visibleBudgetRows(nodes, open = budgetSearchOpen(nodes, TextPattern.of("milk"), showComments = true))
        assertEquals(listOf("root", "food", "dairy", "milk", "rent"), open.map { it.node.id })
        assertEquals(
            listOf(SearchHit(BudgetHitKey("milk", 0), 0), SearchHit(BudgetHitKey("rent", 1), 0)),
            budgetHits(open, TextPattern.of("milk"), showComments = true),
        )
        // Nothing was saved: the groups themselves are still closed.
        assertTrue(nodes.none { it.id == "food" && it.isExpanded })
    }

    @Test
    fun `a list is searched in the text and the comment of each entry, as shown`() {
        val entries = listOf(
            ListEntry(id = "a", text = "**Milk** 2%", comment = "for tea"),
            ListEntry(id = "b", text = "Tea", comment = null),
            ListEntry(id = "t", kind = ListEntry.TOTAL, text = "Tea total"),
        )
        val hits = listHits(entries, TextPattern.of("tea"))
        assertEquals(listOf("a:1", "b:0", "t:0"), hits.map { (it.key as ListHitKey).let { k -> k.id + ":" + k.field } })
        // The Markdown marks are not searched: "**" is not in the shown text.
        assertTrue(listHits(entries, TextPattern.of("**")).isEmpty())
    }
}
