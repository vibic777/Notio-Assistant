package com.notebudget.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.core.database.AppDatabase
import com.notebudget.app.core.database.entity.TagEntity
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.repository.NoteDataCleaner
import com.notebudget.app.domain.usecase.FakeScheduler
import com.notebudget.app.domain.usecase.FolderUseCases
import com.notebudget.app.domain.usecase.NoteUseCases
import com.notebudget.app.domain.usecase.TagUseCases
import com.notebudget.app.domain.usecase.TestClock
import com.notebudget.app.domain.usecase.TrashUseCases
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tags against the REAL Room database: cascades and the unique index are decided by SQLite (D109). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomTagTest {
    private lateinit var db: AppDatabase
    private val clock = TestClock(1_000_000L)
    private lateinit var tags: TagUseCases
    private lateinit var tagRepo: RoomTagRepository
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
        tagRepo = RoomTagRepository(db)
        tags = TagUseCases(tagRepo, noteRepo, clock = clock)
        folders = FolderUseCases(folderRepo, noteRepo, reminderRepo, scheduler, clock)
        notes = NoteUseCases(noteRepo, folderRepo, reminderRepo, scheduler, clock)
        trash = TrashUseCases(folderRepo, noteRepo, reminderRepo, scheduler, NoteDataCleaner.None, clock)
    }

    @After
    fun close() = db.close()

    private suspend fun note(title: String): Note {
        val folder = RoomFolderRepository(db).getAll().firstOrNull()
            ?: (folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (notes.create(folder.id, title, NoteType.TEXT) as DomainResult.Success).value
    }

    private suspend fun tag(name: String) = (tags.create(name) as DomainResult.Success).value

    @Test
    fun `tags are stored, listed by name and can be given to several notes`() = runBlocking {
        val n1 = note("one"); val n2 = note("two")
        val work = tag("work"); val home = tag("Home")
        tags.setNoteTags(n1.id, setOf(work.id, home.id))
        tags.setNoteTags(n2.id, setOf(work.id))

        assertEquals(listOf("Home", "work"), tagRepo.getAll().map { it.name }) // by name, ignoring case
        val assigned = tagRepo.getAssignments()
        assertEquals(listOf("Home", "work"), assigned.getValue(n1.id).map { it.name })
        assertEquals(listOf("work"), assigned.getValue(n2.id).map { it.name })
    }

    @Test
    fun `setting the tags of a note replaces them and unknown ids are refused before anything is written`() = runBlocking {
        val n = note("n")
        val a = tag("a"); val b = tag("b")
        tags.setNoteTags(n.id, setOf(a.id, b.id))
        tags.setNoteTags(n.id, setOf(b.id))
        assertEquals(listOf("b"), tagRepo.getAssignments().getValue(n.id).map { it.name })
        assertEquals(DomainError.TAG_NOT_FOUND, (tags.setNoteTags(n.id, setOf("ghost")) as DomainResult.Failure).error)
        assertEquals(listOf("b"), tagRepo.getAssignments().getValue(n.id).map { it.name })
    }

    @Test
    fun `deleting a tag removes its rows and leaves the notes`() = runBlocking {
        val n = note("n")
        val a = tag("a"); val b = tag("b")
        tags.setNoteTags(n.id, setOf(a.id, b.id))

        tags.delete(a.id)

        assertEquals(listOf("b"), tagRepo.getAssignments().getValue(n.id).map { it.name })
        assertEquals(1, db.noteDao().getEvery().size)
    }

    @Test
    fun `a note deleted for good takes its tag rows with it, the tag stays`() = runBlocking {
        val n = note("n")
        val a = tag("a")
        tags.setNoteTags(n.id, setOf(a.id))
        notes.delete(n.id)
        trash.purgeNote(n.id)

        assertTrue(db.tagDao().getAssignments().isEmpty())
        assertEquals(1, db.tagDao().getAll().size)
    }

    @Test
    fun `the database itself refuses a second tag with the same ASCII name in another case`() = runBlocking {
        tag("Work")
        try {
            db.tagDao().insert(TagEntity("x", "WORK", 1L))
            fail("the unique index should have refused this")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // fine: the use case checks first, the index is the safety net
        }
    }

    @Test
    fun `unused tags are swept and used ones stay`() = runBlocking {
        val n = note("n")
        val used = tag("used"); tag("unused")
        tags.setNoteTags(n.id, setOf(used.id))
        tagRepo.deleteUnused()
        assertEquals(listOf("used"), tagRepo.getAll().map { it.name })
    }
}
