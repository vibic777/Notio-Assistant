package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.FeaturedLayout
import com.notebudget.app.domain.model.FeaturedMetric
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.ReminderDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderAndNoteUseCasesTest {
    private val env = TestEnv()

    private suspend fun folder(name: String = "My Notes"): Folder =
        (env.folders.create(name, "#3366FF", "SOFT") as DomainResult.Success).value

    private suspend fun note(folderId: String, type: NoteType = NoteType.TEXT, title: String = "Note"): Note =
        (env.notes.create(folderId, title, type) as DomainResult.Success).value

    private fun failure(error: DomainError) = DomainResult.Failure(error)

    // ---- folders ----

    @Test
    fun `first folder is created with trimmed name and sort order 0`() = runTest {
        val folder = folder("  My Notes  ")
        assertEquals("My Notes", folder.name)
        assertEquals(0, folder.sortOrder)
        assertEquals(1, env.folderRepo.count())
    }

    @Test
    fun `folder validation rejects blank name, bad colour, unknown style`() = runTest {
        assertEquals(failure(DomainError.BLANK_NAME), env.folders.create("  ", "#3366FF", "SOFT"))
        assertEquals(failure(DomainError.INVALID_COLOR), env.folders.create("A", "blue", "SOFT"))
        assertEquals(failure(DomainError.INVALID_STYLE), env.folders.create("A", "#3366FF", "NOPE"))
        assertEquals(0, env.folderRepo.count())
    }

    @Test
    fun `folder names are unique ignoring case, also for Cyrillic`() = runTest {
        folder("Семья")
        assertEquals(failure(DomainError.DUPLICATE_FOLDER_NAME), env.folders.create("семья", "#3366FF", "SOFT"))
    }

    @Test
    fun `renaming a folder to its own name is allowed`() = runTest {
        val f = folder("Work")
        val result = env.folders.update(f.id, "work", "#112233", "MINIMAL", null)
        assertTrue(result is DomainResult.Success)
    }

    @Test
    fun `the last folder cannot be deleted`() = runTest {
        val f = folder()
        val result = env.folders.delete(f.id, FolderDeleteMode.DELETE_NOTES)
        assertEquals(failure(DomainError.LAST_FOLDER_CANNOT_BE_DELETED), result)
        assertEquals(1, env.folderRepo.count())
    }

    @Test
    fun `deleting a folder with move keeps all notes`() = runTest {
        val a = folder("A")
        val b = folder("B")
        val n1 = note(a.id, title = "n1")
        val n2 = note(a.id, title = "n2")

        val result = env.folders.delete(a.id, FolderDeleteMode.MOVE_NOTES, b.id)

        assertTrue(result is DomainResult.Success)
        assertNull(env.folderRepo.getById(a.id))
        assertEquals(b.id, env.noteRepo.getById(n1.id)?.folderId)
        assertEquals(b.id, env.noteRepo.getById(n2.id)?.folderId)
    }

    @Test
    fun `moving notes requires an existing different target`() = runTest {
        val a = folder("A")
        folder("B")
        note(a.id)
        assertEquals(failure(DomainError.TARGET_FOLDER_REQUIRED), env.folders.delete(a.id, FolderDeleteMode.MOVE_NOTES, null))
        assertEquals(failure(DomainError.TARGET_FOLDER_REQUIRED), env.folders.delete(a.id, FolderDeleteMode.MOVE_NOTES, a.id))
        assertEquals(failure(DomainError.FOLDER_NOT_FOUND), env.folders.delete(a.id, FolderDeleteMode.MOVE_NOTES, "missing"))
        assertEquals(2, env.folderRepo.count())
    }

    @Test
    fun `deleting a folder with its notes cancels their alarms`() = runTest {
        val a = folder("A")
        folder("B")
        val n = note(a.id)
        env.clock.current = 1_000L
        val reminder = (env.reminders.create(n.id, ReminderDraft(triggerAt = 5_000L)) as DomainResult.Success).value
        assertTrue(env.scheduler.alarms.containsKey(reminder.id))

        env.folders.delete(a.id, FolderDeleteMode.DELETE_NOTES)

        assertTrue(env.scheduler.alarms.isEmpty())
        assertNull(env.folderRepo.getById(a.id))
        // Soft delete: the note and its reminder are kept in the trash.
        assertTrue(env.noteRepo.getByFolder(a.id).isEmpty())
        assertNotNull(env.noteRepo.getByIdIncludingDeleted(n.id)?.deletedAt)
        assertEquals(1, env.store.reminders.value.size)
    }

    @Test
    fun `reorder assigns sequential sort orders`() = runTest {
        val a = folder("A")
        val b = folder("B")
        val c = folder("C")
        env.folders.reorder(listOf(c.id, a.id, b.id))
        assertEquals(listOf(c.id, a.id, b.id), env.folderRepo.getAll().map { it.id })
    }

    // ---- notes ----

    @Test
    fun `a note cannot be created without an existing folder or a title`() = runTest {
        assertEquals(failure(DomainError.FOLDER_NOT_FOUND), env.notes.create("missing", "T", NoteType.TEXT))
        val f = folder()
        assertEquals(failure(DomainError.BLANK_TITLE), env.notes.create(f.id, " ", NoteType.TEXT))
        assertTrue(env.store.notes.value.isEmpty())
    }

    @Test
    fun `a budget note gets a root group node`() = runTest {
        val f = folder()
        val n = note(f.id, NoteType.BUDGET, "Family")
        val nodes = env.budgetRepo.getNodes(n.id)
        assertEquals(1, nodes.size)
        assertNull(nodes.single().parentId)
        assertEquals("Family", nodes.single().title)
    }

    @Test
    fun `saving a note never changes its folder or type`() = runTest {
        val f = folder()
        val n = note(f.id)
        env.notes.save(n.copy(title = "New", isPinned = true, folderId = "other", type = NoteType.LIST))
        val saved = env.noteRepo.getById(n.id)!!
        assertEquals("New", saved.title)
        assertTrue(saved.isPinned)
        assertEquals(f.id, saved.folderId)
        assertEquals(NoteType.TEXT, saved.type)
    }

    @Test
    fun `moving a note keeps its reminders and clears the featured link`() = runTest {
        val a = folder("A")
        val b = folder("B")
        val n = note(a.id)
        env.clock.current = 1_000L
        env.reminders.create(n.id, ReminderDraft(triggerAt = 5_000L))
        env.folders.setFeatured(a.id, n.id, 0, FeaturedLayout.COMPACT)
        assertEquals(n.id, env.folderRepo.getById(a.id)?.featuredNoteId)

        env.notes.move(n.id, b.id)

        assertEquals(b.id, env.noteRepo.getById(n.id)?.folderId)
        assertNull(env.folderRepo.getById(a.id)?.featuredNoteId)
        assertEquals(1, env.store.reminders.value.size)
    }

    @Test
    fun `deleting a note trashes it, clears the featured link and cancels alarms`() = runTest {
        val f = folder()
        val n = note(f.id, NoteType.BUDGET)
        env.clock.current = 1_000L
        env.reminders.create(n.id, ReminderDraft(triggerAt = 5_000L))
        env.folders.setFeatured(f.id, n.id, FeatureAll, FeaturedLayout.FULL)

        env.notes.delete(n.id)

        assertNull(env.folderRepo.getById(f.id)?.featuredNoteId)
        assertEquals(0, env.folderRepo.getById(f.id)?.featuredMetricsMask)
        assertNull(env.noteRepo.getById(n.id))
        assertNotNull(env.noteRepo.getByIdIncludingDeleted(n.id)?.deletedAt)
        // Nothing is destroyed: reminder and budget tree are kept for a possible restore.
        assertEquals(1, env.store.reminders.value.size)
        assertEquals(1, env.store.nodes.value.size)
        assertTrue(env.scheduler.alarms.isEmpty())
    }

    // ---- trash / restore ----

    @Test
    fun `restoring a note brings it back and re-arms its future reminder`() = runTest {
        val f = folder()
        val n = note(f.id)
        env.clock.current = 1_000L
        val r = (env.reminders.create(n.id, ReminderDraft(triggerAt = 50_000L)) as DomainResult.Success).value
        env.notes.delete(n.id)
        assertTrue(env.scheduler.alarms.isEmpty())

        env.clock.current = 2_000L
        assertTrue(env.notes.restore(n.id) is DomainResult.Success)

        assertNotNull(env.noteRepo.getById(n.id))
        assertEquals(50_000L, env.scheduler.alarms[r.id])
    }

    @Test
    fun `a note cannot be restored into a trashed folder or when it is not in the trash`() = runTest {
        val a = folder("A")
        folder("B")
        val n = note(a.id)
        assertEquals(failure(DomainError.NOT_IN_TRASH), env.notes.restore(n.id))

        env.notes.delete(n.id)
        env.folders.delete(a.id, FolderDeleteMode.DELETE_NOTES)
        assertEquals(failure(DomainError.FOLDER_NOT_FOUND), env.notes.restore(n.id))
    }

    @Test
    fun `restoring a folder restores only the notes trashed together with it`() = runTest {
        val a = folder("A")
        folder("B")
        val early = note(a.id, title = "trashed earlier")
        val withFolder = note(a.id, title = "trashed with folder")
        env.clock.current += 10
        env.notes.delete(early.id)
        env.clock.current += 10
        env.folders.delete(a.id, FolderDeleteMode.DELETE_NOTES)

        env.clock.current += 10
        assertTrue(env.folders.restore(a.id) is DomainResult.Success)

        assertNotNull(env.folderRepo.getById(a.id))
        assertNotNull(env.noteRepo.getById(withFolder.id))
        assertNull(env.noteRepo.getById(early.id))
    }

    @Test
    fun `a folder cannot be restored if its name was taken meanwhile`() = runTest {
        val a = folder("Work")
        folder("Home")
        env.folders.delete(a.id, FolderDeleteMode.MOVE_NOTES, null)
        folder("work")
        assertEquals(failure(DomainError.DUPLICATE_FOLDER_NAME), env.folders.restore(a.id))
    }

    @Test
    fun `reminders of trashed notes are not restored after a reboot`() = runTest {
        val f = folder()
        val n = note(f.id)
        env.clock.current = 1_000L
        env.reminders.create(n.id, ReminderDraft(triggerAt = 50_000L))
        env.notes.delete(n.id)

        env.reminders.restoreAll()

        assertTrue(env.scheduler.alarms.isEmpty())
    }

    // ---- featured note ----

    @Test
    fun `featured note must belong to the folder (INV-3)`() = runTest {
        val a = folder("A")
        val b = folder("B")
        val n = note(b.id)
        assertEquals(
            failure(DomainError.FEATURED_NOTE_NOT_IN_FOLDER),
            env.folders.setFeatured(a.id, n.id, 0, FeaturedLayout.COMPACT),
        )
    }

    @Test
    fun `metrics apply only to budget notes`() = runTest {
        val f = folder()
        val text = note(f.id, NoteType.TEXT)
        val budget = note(f.id, NoteType.BUDGET)

        val forText = env.folders.setFeatured(f.id, text.id, FeatureAll, FeaturedLayout.COMPACT) as DomainResult.Success
        assertEquals(0, forText.value.featuredMetricsMask)

        val forBudget = env.folders.setFeatured(f.id, budget.id, FeatureAll, FeaturedLayout.FULL) as DomainResult.Success
        assertEquals(FeaturedMetric.ALL_MASK, forBudget.value.featuredMetricsMask)
        assertTrue(forBudget.value.shows(FeaturedMetric.REMAINDER))
    }

    private companion object {
        val FeatureAll = FeaturedMetric.ALL_MASK
    }
}
