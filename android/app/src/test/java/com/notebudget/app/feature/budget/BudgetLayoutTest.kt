package com.notebudget.app.feature.budget

import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetLayoutTest {
    private val env = TestEnv()

    /** Budget -> [Food -> [Bread, Milk], Rent]; returns the rows for [layout] as "Title/PART". */
    private fun rows(layout: BudgetLayout, collapseFood: Boolean = false): List<String> = runBlocking {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "Budget", NoteType.BUDGET) as DomainResult.Success).value
        val root = env.budgetRepo.getNodes(note.id).single()
        val food = (env.budget.addNode(note.id, root.id, "Food", BudgetNodeKind.GROUP) as DomainResult.Success).value
        env.budget.addNode(note.id, food.id, "Bread", BudgetNodeKind.LEAF)
        env.budget.addNode(note.id, food.id, "Milk", BudgetNodeKind.LEAF)
        env.budget.addNode(note.id, root.id, "Rent", BudgetNodeKind.LEAF)
        if (collapseFood) env.budget.setExpanded(note.id, food.id, false)
        visibleBudgetRows(env.budgetRepo.getNodes(note.id), layout).map { "${it.node.title}/${it.part}" }
    }

    @Test
    fun `default layout is the classic one`() {
        assertEquals(
            listOf("Budget/FULL", "Food/FULL", "Bread/FULL", "Milk/FULL", "Rent/FULL"),
            rows(BudgetLayout.Default),
        )
    }

    @Test
    fun `overall total can go to the bottom`() {
        assertEquals(
            listOf("Food/FULL", "Bread/FULL", "Milk/FULL", "Rent/FULL", "Budget/TOTAL"),
            rows(BudgetLayout(totalAtBottom = true)),
        )
    }

    @Test
    fun `group totals can go below their items`() {
        assertEquals(
            listOf("Budget/FULL", "Food/HEADER", "Bread/FULL", "Milk/FULL", "Food/TOTAL", "Rent/FULL"),
            rows(BudgetLayout(groupTotalsAtBottom = true)),
        )
    }

    @Test
    fun `everything at the bottom, as for a ledger`() {
        assertEquals(
            listOf("Food/HEADER", "Bread/FULL", "Milk/FULL", "Food/TOTAL", "Rent/FULL", "Budget/TOTAL"),
            rows(BudgetLayout(totalAtBottom = true, groupTotalsAtBottom = true)),
        )
    }

    @Test
    fun `newest first reverses every level`() {
        assertEquals(
            listOf("Budget/FULL", "Rent/FULL", "Food/FULL", "Milk/FULL", "Bread/FULL"),
            rows(BudgetLayout(newestFirst = true)),
        )
    }

    @Test
    fun `a collapsed group keeps its aggregates in the header row`() {
        assertEquals(
            listOf("Budget/FULL", "Food/FULL", "Rent/FULL"),
            rows(BudgetLayout(groupTotalsAtBottom = true), collapseFood = true),
        )
    }

    @Test
    fun `the layout is stored as json in the note and tolerates junk`() {
        val layout = BudgetLayout(totalAtBottom = true, newestFirst = true)
        assertEquals(layout, BudgetLayout.parse(layout.toExtras()))
        assertEquals(BudgetLayout.Default, BudgetLayout.parse(null))
        assertEquals(BudgetLayout.Default, BudgetLayout.parse(""))
        assertEquals(BudgetLayout.Default, BudgetLayout.parse("not json"))
        assertEquals(BudgetLayout(totalAtBottom = true), BudgetLayout.parse("""{"totalAtBottom":true,"fromTheFuture":42}"""))
    }

    @Test
    fun `each note keeps its own layout`() = runBlocking {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val a = (env.notes.create(folder.id, "A", NoteType.BUDGET) as DomainResult.Success).value
        val b = (env.notes.create(folder.id, "B", NoteType.BUDGET) as DomainResult.Success).value
        env.notes.updateExtras(a.id, BudgetLayout(totalAtBottom = true).toExtras())
        assertEquals(true, BudgetLayout.parse(env.noteRepo.getById(a.id)?.extras).totalAtBottom)
        assertEquals(false, BudgetLayout.parse(env.noteRepo.getById(b.id)?.extras).totalAtBottom)
    }
}
