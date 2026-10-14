package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.ReminderDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashUseCasesTest {
    private val env = TestEnv()
    private val day = 24L * 60 * 60 * 1000

    private suspend fun folder(name: String): Folder =
        (env.folders.create(name, "#3366FF", "SOFT") as DomainResult.Success).value

    private suspend fun note(folderId: String, title: String, type: NoteType = NoteType.TEXT): Note =
        (env.notes.create(folderId, title, type) as DomainResult.Success).value

    private suspend fun remind(noteId: String) =
        (env.reminders.create(noteId, ReminderDraft(triggerAt = env.clock.current + 10 * day)) as DomainResult.Success).value

    private fun failure(result: DomainResult<*>) = (result as DomainResult.Failure).error

    // ---- one note ----

    @Test
    fun `only a trashed note can be purged`() = runBlocking {
        val f = folder("F")
        val n = note(f.id, "keep")
        assertEquals(DomainError.NOT_IN_TRASH, failure(env.trash.purgeNote(n.id)))
        assertEquals(DomainError.NOTE_NOT_FOUND, failure(env.trash.purgeNote("missing")))
        assertNotNull(env.noteRepo.getById(n.id))
        assertTrue(env.cleaned.isEmpty())
    }

    @Test
    fun `purging a note removes its budget nodes and reminders, cancels alarms and cleans its files`() = runBlocking {
        val f = folder("F")
        val budget = note(f.id, "Trip", NoteType.BUDGET)
        val other = note(f.id, "Other")
        val reminder = remind(budget.id)
        val otherReminder = remind(other.id)
        assertTrue(env.store.nodes.value.any { it.noteId == budget.id })
        env.notes.delete(budget.id) // to the trash: the alarm is cancelled
        env.scheduler.alarms[reminder.id] = 1L // pretend an alarm survived

        assertTrue(env.trash.purgeNote(budget.id) is DomainResult.Success)

        assertNull(env.noteRepo.getByIdIncludingDeleted(budget.id))
        assertTrue(env.store.nodes.value.none { it.noteId == budget.id })
        assertTrue(env.store.reminders.value.none { it.noteId == budget.id })
        assertNull(env.scheduler.alarms[reminder.id])
        assertEquals(listOf(budget.id), env.cleaned.map { it.id })
        // Another note is untouched.
        assertNotNull(env.noteRepo.getById(other.id))
        assertNotNull(env.store.reminders.value.find { it.id == otherReminder.id })
    }

    // ---- restore into another folder ----

    @Test
    fun `a note whose folder is in the trash cannot be restored as it is but can go to another folder`() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val n = note(a.id, "orphan")
        val reminder = remind(n.id)
        env.notes.delete(n.id)
        env.folders.delete(a.id, FolderDeleteMode.DELETE_NOTES) // the folder is trashed too

        assertEquals(DomainError.FOLDER_NOT_FOUND, failure(env.notes.restore(n.id)))

        assertTrue(env.notes.restoreInto(n.id, b.id) is DomainResult.Success)
        val restored = env.noteRepo.getById(n.id)
        assertNotNull(restored)
        assertEquals(b.id, restored!!.folderId)
        assertNull(restored.deletedAt)
        // The reminder is armed again.
        assertEquals(env.store.reminders.value.single { it.id == reminder.id }.triggerAt, env.scheduler.alarms[reminder.id])
    }

    @Test
    fun `restoreInto refuses an active note, a missing note and a trashed target`() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val active = note(a.id, "active")
        assertEquals(DomainError.NOT_IN_TRASH, failure(env.notes.restoreInto(active.id, b.id)))
        assertEquals(DomainError.NOTE_NOT_FOUND, failure(env.notes.restoreInto("missing", b.id)))

        val gone = note(a.id, "gone")
        env.notes.delete(gone.id)
        env.folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)
        assertEquals(DomainError.FOLDER_NOT_FOUND, failure(env.notes.restoreInto(gone.id, b.id)))
    }

    // ---- one folder ----

    @Test
    fun `purging a trashed folder removes it with its notes, alarms and files`() = runBlocking {
        val keep = folder("Keep")
        val doomed = folder("Doomed")
        val n1 = note(doomed.id, "one")
        val n2 = note(doomed.id, "two", NoteType.BUDGET)
        val reminder = remind(n1.id)
        val safe = note(keep.id, "safe")
        env.folders.delete(doomed.id, FolderDeleteMode.DELETE_NOTES)

        assertTrue(env.trash.purgeFolder(doomed.id) is DomainResult.Success)

        assertNull(env.folderRepo.getByIdIncludingDeleted(doomed.id))
        assertNull(env.noteRepo.getByIdIncludingDeleted(n1.id))
        assertNull(env.noteRepo.getByIdIncludingDeleted(n2.id))
        assertTrue(env.store.nodes.value.none { it.noteId == n2.id })
        assertNull(env.scheduler.alarms[reminder.id])
        assertEquals(setOf(n1.id, n2.id), env.cleaned.map { it.id }.toSet())
        assertNotNull(env.noteRepo.getById(safe.id))
    }

    @Test
    fun `only a trashed folder can be purged, and never one that still has active notes`() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val n = note(a.id, "n")
        assertEquals(DomainError.NOT_IN_TRASH, failure(env.trash.purgeFolder(a.id)))
        assertEquals(DomainError.FOLDER_NOT_FOUND, failure(env.trash.purgeFolder("missing")))

        // A broken state that the app cannot create by itself: a trashed folder with an active note.
        env.store.folders.value = env.store.folders.value.map { if (it.id == a.id) it.copy(deletedAt = 5L) else it }
        assertEquals(DomainError.FOLDER_NOT_EMPTY, failure(env.trash.purgeFolder(a.id)))
        assertNotNull(env.noteRepo.getById(n.id))
        assertNotNull(env.folderRepo.getByIdIncludingDeleted(a.id))
        assertNotNull(env.folderRepo.getById(b.id))
    }

    // ---- empty the trash ----

    @Test
    fun `emptying the trash removes everything trashed and nothing else`() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val stays = note(a.id, "stays")
        val lone = note(a.id, "lone")
        val inFolder = note(b.id, "inFolder")
        env.notes.delete(lone.id)
        env.folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)

        val removed = env.trash.emptyTrash()

        assertEquals(3, removed) // the folder, its note, the lone note
        assertTrue(env.folderRepo.getTrashed().isEmpty())
        assertTrue(env.noteRepo.getTrashed().isEmpty())
        assertNotNull(env.noteRepo.getById(stays.id))
        assertNotNull(env.folderRepo.getById(a.id))
        assertEquals(setOf(lone.id, inFolder.id), env.cleaned.map { it.id }.toSet())
        assertEquals(0, env.trash.emptyTrash()) // nothing left
    }

    // ---- retention ----

    @Test
    fun `items older than the retention time are removed, newer ones stay`() = runBlocking {
        val f = folder("F")
        val old = note(f.id, "old")
        val fresh = note(f.id, "fresh")
        env.clock.current = 100 * day
        env.notes.delete(old.id)
        env.clock.current = 125 * day
        env.notes.delete(fresh.id)

        env.clock.current = 100 * day + 30 * day // exactly 30 days after `old` was trashed
        assertEquals(1, env.trash.purgeExpired(30))
        assertNull(env.noteRepo.getByIdIncludingDeleted(old.id))
        assertNotNull(env.noteRepo.getByIdIncludingDeleted(fresh.id))

        env.clock.current = 125 * day + 30 * day - 1 // one millisecond too early
        assertEquals(0, env.trash.purgeExpired(30))
        env.clock.current = 125 * day + 30 * day
        assertEquals(1, env.trash.purgeExpired(30))
    }

    @Test
    fun `retention never removes nothing`() = runBlocking {
        val f = folder("F")
        val n = note(f.id, "n")
        env.clock.current = 10 * day
        env.notes.delete(n.id)
        env.clock.current = 10_000 * day
        assertEquals(0, env.trash.purgeExpired(TrashRetention.NEVER))
        assertNotNull(env.noteRepo.getByIdIncludingDeleted(n.id))
    }

    @Test
    fun `an expired folder goes with its notes and a note trashed earlier expires on its own date`() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val early = note(b.id, "early")
        val together = note(b.id, "together")
        env.clock.current = 10 * day
        env.notes.delete(early.id)
        env.clock.current = 20 * day
        env.folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)

        // Day 45: `early` (day 10) has expired, the folder (day 20) has not.
        env.clock.current = 45 * day
        assertEquals(1, env.trash.purgeExpired(30))
        assertNull(env.noteRepo.getByIdIncludingDeleted(early.id))
        assertNotNull(env.folderRepo.getByIdIncludingDeleted(b.id))
        assertNotNull(env.noteRepo.getByIdIncludingDeleted(together.id))

        // Day 51: the folder has expired and takes its note along.
        env.clock.current = 51 * day
        assertEquals(2, env.trash.purgeExpired(30))
        assertNull(env.folderRepo.getByIdIncludingDeleted(b.id))
        assertNull(env.noteRepo.getByIdIncludingDeleted(together.id))
        assertNotNull(env.folderRepo.getById(a.id))
    }

    // ---- TrashRetention helpers ----

    @Test
    fun `days left counts whole days rounded up and reaches zero at expiry`() {
        val deleted = 1_000 * day
        assertEquals(30, TrashRetention.daysLeft(deleted, deleted, 30))
        assertEquals(30, TrashRetention.daysLeft(deleted, deleted + 1, 30)) // just under 30 days left still shows 30
        assertEquals(1, TrashRetention.daysLeft(deleted, deleted + 29 * day + 1, 30))
        assertEquals(0, TrashRetention.daysLeft(deleted, deleted + 30 * day, 30))
        assertEquals(0, TrashRetention.daysLeft(deleted, deleted + 99 * day, 30))
        assertNull(TrashRetention.daysLeft(deleted, deleted, TrashRetention.NEVER))
    }

    @Test
    fun `the settings offer never and the default is one of the options`() {
        assertTrue(TrashRetention.DEFAULT_DAYS in TrashRetention.OPTIONS)
        assertTrue(TrashRetention.NEVER in TrashRetention.OPTIONS)
        assertNull(TrashRetention.cutoff(1_000, TrashRetention.NEVER))
        assertEquals(1_000 - 7 * day, TrashRetention.cutoff(1_000, 7))
    }
}
