package com.notebudget.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.core.database.AppDatabase
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.ReminderDraft
import com.notebudget.app.domain.repository.NoteDataCleaner
import com.notebudget.app.domain.usecase.FakeScheduler
import com.notebudget.app.domain.usecase.FolderDeleteMode
import com.notebudget.app.domain.usecase.FolderUseCases
import com.notebudget.app.domain.usecase.NoteUseCases
import com.notebudget.app.domain.usecase.ReminderUseCases
import com.notebudget.app.domain.usecase.TestClock
import com.notebudget.app.domain.usecase.TrashUseCases
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Permanent deletion against the REAL Room database (in memory): the foreign keys decide what
 * cascades and what is refused, so this cannot be checked with the in-memory fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomTrashTest {
    private lateinit var db: AppDatabase
    private val clock = TestClock(1_000_000L)
    private val scheduler = FakeScheduler()
    private val cleaned = mutableListOf<Note>()

    private lateinit var folderRepo: RoomFolderRepository
    private lateinit var noteRepo: RoomNoteRepository
    private lateinit var folders: FolderUseCases
    private lateinit var notes: NoteUseCases
    private lateinit var reminders: ReminderUseCases
    private lateinit var trash: TrashUseCases

    @Before
    fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        folderRepo = RoomFolderRepository(db)
        noteRepo = RoomNoteRepository(db)
        val reminderRepo = RoomReminderRepository(db)
        folders = FolderUseCases(folderRepo, noteRepo, reminderRepo, scheduler, clock)
        notes = NoteUseCases(noteRepo, folderRepo, reminderRepo, scheduler, clock)
        reminders = ReminderUseCases(reminderRepo, noteRepo, scheduler, clock)
        trash = TrashUseCases(folderRepo, noteRepo, reminderRepo, scheduler, NoteDataCleaner { cleaned += it }, clock)
    }

    @After
    fun close() = db.close()

    private suspend fun folder(name: String): Folder =
        (folders.create(name, "#3366FF", "SOFT") as DomainResult.Success).value

    private suspend fun note(folderId: String, title: String, type: NoteType = NoteType.TEXT): Note =
        (notes.create(folderId, title, type) as DomainResult.Success).value

    private suspend fun remind(noteId: String) =
        (reminders.create(noteId, ReminderDraft(triggerAt = clock.current + 86_400_000L)) as DomainResult.Success).value

    private suspend fun noteIds() = db.noteDao().getEvery().map { it.id }.toSet()

    @Test
    fun `purging a note deletes its budget nodes and reminders through the cascade`() = runBlocking {
        val f = folder("F")
        val budget = note(f.id, "Trip", NoteType.BUDGET)
        val keep = note(f.id, "Keep", NoteType.BUDGET)
        remind(budget.id)
        val keepReminder = remind(keep.id)
        assertEquals(2, db.budgetNodeDao().getEvery().size)
        notes.delete(budget.id)

        assertTrue(trash.purgeNote(budget.id) is DomainResult.Success)

        assertEquals(setOf(keep.id), noteIds())
        assertEquals(listOf(keep.id), db.budgetNodeDao().getEvery().map { it.noteId })
        assertEquals(listOf(keepReminder.id), db.reminderDao().getEvery().map { it.id })
        assertEquals(listOf(budget.id), cleaned.map { it.id })
    }

    @Test
    fun `an active note is never deleted by a purge, even when asked directly`() = runBlocking {
        val f = folder("F")
        val n = note(f.id, "active")
        noteRepo.purge(n.id) // the DAO only deletes trashed rows
        assertNotNull(noteRepo.getById(n.id))
        // The use case refuses too, before anything is touched.
        assertEquals(DomainError.NOT_IN_TRASH, (trash.purgeNote(n.id) as DomainResult.Failure).error)
        assertNotNull(noteRepo.getById(n.id))
    }

    @Test
    fun `purging a folder deletes it with its trashed notes and everything hanging on them`() = runBlocking {
        val keep = folder("Keep")
        val doomed = folder("Doomed")
        val kept = note(keep.id, "kept")
        val budget = note(doomed.id, "budget", NoteType.BUDGET)
        val plain = note(doomed.id, "plain")
        remind(plain.id)
        folders.delete(doomed.id, FolderDeleteMode.DELETE_NOTES)
        assertEquals(setOf(kept.id, budget.id, plain.id), noteIds()) // still stored, in the trash

        assertTrue(trash.purgeFolder(doomed.id) is DomainResult.Success)

        assertEquals(setOf(kept.id), noteIds())
        assertEquals(listOf(keep.id), db.folderDao().getEvery().map { it.id })
        assertTrue(db.budgetNodeDao().getEvery().isEmpty())
        assertTrue(db.reminderDao().getEvery().isEmpty())
        assertEquals(setOf(budget.id, plain.id), cleaned.map { it.id }.toSet())
    }

    @Test
    fun `the database refuses to drop a folder that still has an active note and rolls the whole purge back`() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val early = note(b.id, "trashed earlier")
        val stuck = note(b.id, "stuck")
        notes.delete(early.id)
        folderRepo.trashWithNotes(b.id, clock.current + 10) // trashes `stuck` with the folder
        db.noteDao().markRestored(stuck.id, clock.current + 20) // a state the app never creates: active note in a trashed folder

        try {
            folderRepo.purge(b.id)
            fail("RESTRICT foreign key should have refused this")
        } catch (expected: Exception) {
            // the notes were deleted first inside the transaction, then the folder failed
        }

        // Everything is still there: the transaction was rolled back, the trashed note included.
        assertEquals(setOf(early.id, stuck.id), noteIds())
        assertNotNull(folderRepo.getByIdIncludingDeleted(b.id))
        assertNotNull(folderRepo.getById(a.id))
        // And the use case sees the same thing and refuses before touching anything.
        assertTrue(trash.purgeFolder(b.id) is DomainResult.Failure)
        assertEquals(setOf(early.id, stuck.id), noteIds())
    }

    @Test
    fun `trashed lists are newest first and hold only trashed rows`() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val n1 = note(a.id, "first")
        val n2 = note(a.id, "second")
        note(a.id, "active")
        clock.current = 2_000_000L
        notes.delete(n1.id)
        clock.current = 3_000_000L
        notes.delete(n2.id)
        clock.current = 4_000_000L
        folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)

        assertEquals(listOf("second", "first"), noteRepo.getTrashed().map { it.title })
        assertEquals(listOf("B"), folderRepo.getTrashed().map { it.name })
        assertEquals(setOf(n1.id, n2.id), noteRepo.getTrashedByFolder(a.id).map { it.id }.toSet())
    }

    @Test
    fun `emptying the trash on the real database leaves the active data alone`() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val stays = note(a.id, "stays")
        val lone = note(a.id, "lone", NoteType.BUDGET)
        note(b.id, "inside b")
        notes.delete(lone.id)
        folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)

        assertEquals(3, trash.emptyTrash())

        assertEquals(setOf(stays.id), noteIds())
        assertEquals(listOf(a.id), db.folderDao().getEvery().map { it.id })
        assertTrue(db.budgetNodeDao().getEvery().isEmpty())
    }
}
