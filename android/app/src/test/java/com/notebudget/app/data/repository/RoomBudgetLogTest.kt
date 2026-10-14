package com.notebudget.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.core.database.AppDatabase
import com.notebudget.app.domain.model.BudgetLogEntry
import com.notebudget.app.domain.model.BudgetLogKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.repository.NoteDataCleaner
import com.notebudget.app.domain.usecase.FakeScheduler
import com.notebudget.app.domain.usecase.FolderUseCases
import com.notebudget.app.domain.usecase.NoteUseCases
import com.notebudget.app.domain.usecase.TestClock
import com.notebudget.app.domain.usecase.TrashUseCases
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The log of a budget note against the REAL Room database: order, trimming and the cascade are decided by SQLite (D166). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomBudgetLogTest {
    private lateinit var db: AppDatabase
    private lateinit var log: RoomBudgetLogRepository
    private lateinit var notes: NoteUseCases
    private lateinit var trash: TrashUseCases
    private lateinit var folders: FolderUseCases

    @Before
    fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val folderRepo = RoomFolderRepository(db)
        val noteRepo = RoomNoteRepository(db)
        val reminderRepo = RoomReminderRepository(db)
        val scheduler = FakeScheduler()
        val clock = TestClock(1_000_000L)
        log = RoomBudgetLogRepository(db)
        folders = FolderUseCases(folderRepo, noteRepo, reminderRepo, scheduler, clock)
        notes = NoteUseCases(noteRepo, folderRepo, reminderRepo, scheduler, clock)
        trash = TrashUseCases(folderRepo, noteRepo, reminderRepo, scheduler, NoteDataCleaner.None, clock)
    }

    @After
    fun close() = db.close()

    private suspend fun note(title: String): Note {
        val folder = RoomFolderRepository(db).getAll().firstOrNull()
            ?: (folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (notes.create(folder.id, title, NoteType.BUDGET) as DomainResult.Success).value
    }

    private fun line(id: String, note: String, at: Long, title: String = "T") =
        BudgetLogEntry(id, note, at, BudgetLogKind.ITEM_ADDED, title, detail = "")

    @Test
    fun `lines come back newest first, and lines of the same moment in the order they were written reversed`() = runBlocking {
        val n = note("n")
        log.add(listOf(line("a", n.id, 10), line("b", n.id, 20), line("c", n.id, 20)))

        assertEquals(listOf("c", "b", "a"), log.getByNote(n.id).map { it.id })
    }

    @Test
    fun `a line keeps its numbers and its detail`() = runBlocking {
        val n = note("n")
        log.add(listOf(BudgetLogEntry("x", n.id, 5, BudgetLogKind.SUM, "Bread", 1_000, null, "SET")))

        val back = log.getByNote(n.id).single()
        assertEquals(1_000L, back.oldValue)
        assertEquals(null, back.newValue)
        assertEquals("SET", back.detail)
    }

    @Test
    fun `only the newest lines of a note are kept`() = runBlocking {
        val n = note("n")
        log.add((1..5).map { line("l$it", n.id, it.toLong()) })

        db.budgetLogDao().trim(n.id, 3)

        assertEquals(listOf("l5", "l4", "l3"), log.getByNote(n.id).map { it.id })
    }

    @Test
    fun `clearing one note leaves the log of another`() = runBlocking {
        val one = note("one"); val two = note("two")
        log.add(listOf(line("a", one.id, 1), line("b", two.id, 2)))

        log.clear(one.id)

        assertTrue(log.getByNote(one.id).isEmpty())
        assertEquals(listOf("b"), log.getByNote(two.id).map { it.id })
    }

    @Test
    fun `a note deleted for good takes its log with it`() = runBlocking {
        val n = note("n")
        val keep = note("keep")
        log.add(listOf(line("a", n.id, 1), line("b", keep.id, 2)))

        notes.delete(n.id)
        trash.purgeNote(n.id)

        assertEquals(listOf("b"), log.getAll().map { it.id })
    }

    @Test
    fun `replacing the whole log drops what was there`() = runBlocking {
        val n = note("n")
        log.add(listOf(line("old", n.id, 1)))

        log.replaceAll(listOf(line("new", n.id, 2)))

        assertEquals(listOf("new"), log.getAll().map { it.id })
    }
}
