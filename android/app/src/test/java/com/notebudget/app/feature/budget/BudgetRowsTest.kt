package com.notebudget.app.feature.budget

import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.TestEnv
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetRowsTest {
    private val env = TestEnv()

    @Test
    fun `rows are depth first in sort order and collapsed groups hide their children`() = runTest {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "Budget", NoteType.BUDGET) as DomainResult.Success).value
        val root = env.budgetRepo.getNodes(note.id).single()
        val food = (env.budget.addNode(note.id, root.id, "Food", BudgetNodeKind.GROUP) as DomainResult.Success).value
        env.budget.addNode(note.id, food.id, "Bread", BudgetNodeKind.LEAF)
        env.budget.addNode(note.id, root.id, "Rent", BudgetNodeKind.LEAF)

        suspend fun rows() = visibleBudgetRows(env.budgetRepo.getNodes(note.id)).map { it.node.title to it.depth }

        assertEquals(listOf("Budget" to 0, "Food" to 1, "Bread" to 2, "Rent" to 1), rows())

        env.budget.setExpanded(note.id, food.id, false)
        assertEquals(listOf("Budget" to 0, "Food" to 1, "Rent" to 1), rows())
    }
}
