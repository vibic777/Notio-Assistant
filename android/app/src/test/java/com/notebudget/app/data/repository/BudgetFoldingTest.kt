package com.notebudget.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.core.database.AppDatabase
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.BudgetEditor
import com.notebudget.app.domain.usecase.FakeScheduler
import com.notebudget.app.domain.usecase.FolderUseCases
import com.notebudget.app.domain.usecase.NoteUseCases
import com.notebudget.app.domain.usecase.TestClock
import com.notebudget.app.feature.budget.visibleBudgetRows
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The open / closed groups of a budget are kept in the database, so a budget opens as it was left (D279). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BudgetFoldingTest {
    private lateinit var db: AppDatabase
    private val clock = TestClock(1_000_000L)

    @Before
    fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    @Test
    fun `collapsed groups stay collapsed when the budget is read again`() = runBlocking {
        val folders = RoomFolderRepository(db)
        val notes = RoomNoteRepository(db)
        val reminders = RoomReminderRepository(db)
        val budgets = RoomBudgetRepository(db)
        val folder = (FolderUseCases(folders, notes, reminders, FakeScheduler(), clock).create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (NoteUseCases(notes, folders, reminders, FakeScheduler(), clock).create(folder.id, "B", NoteType.BUDGET) as DomainResult.Success).value
        val editor = BudgetEditor(budgets, clock)
        val root = budgets.getNodes(note.id).single { it.parentId == null }
        val food = (editor.addNode(note.id, root.id, "Food", BudgetNodeKind.GROUP) as DomainResult.Success).value
        editor.addNode(note.id, food.id, "Bread", BudgetNodeKind.LEAF, sumMinor = 100)

        editor.setAllExpanded(note.id, false)

        // "Opening the note again": a fresh read of the tree, as a new screen does.
        val again = budgets.observeNodes(note.id).first()
        assertFalse(again.single { it.id == food.id }.isExpanded)
        assertEquals(listOf("Food"), visibleBudgetRows(again).drop(1).map { it.node.title })
    }
}
