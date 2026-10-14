package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.Clock
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.asFailure
import com.notebudget.app.domain.model.asSuccess
import com.notebudget.app.domain.repository.FolderRepository
import com.notebudget.app.domain.repository.NoteDataCleaner
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.ReminderRepository
import com.notebudget.app.domain.repository.ReminderScheduler

/** How long the trash keeps things (D65). */
object TrashRetention {
    /** 0 means "never remove automatically". */
    const val NEVER = 0

    /**
     * The trash empties itself only when the person chose so (D140): a default of 30 days would silently delete things of
     * someone who never saw the setting.
     */
    const val DEFAULT_DAYS = NEVER

    const val DAYS_30 = 30

    /** The choices offered in settings. */
    val OPTIONS = listOf(7, 14, DAYS_30, 60, 90, NEVER)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Whole days left before an item trashed at [deletedAt] is removed; `null` when nothing expires. */
    fun daysLeft(deletedAt: Long, now: Long, retentionDays: Int): Int? {
        if (retentionDays <= NEVER) return null
        val leftMs = deletedAt + retentionDays * DAY_MS - now
        return if (leftMs <= 0) 0 else ((leftMs + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** Items trashed at or before this moment are expired; `null` when nothing expires. */
    fun cutoff(now: Long, retentionDays: Int): Long? =
        if (retentionDays <= NEVER) null else now - retentionDays * DAY_MS
}

/**
 * Permanent deletion (Phase 10). Only trashed things can be purged; the confirmation dialog
 * required by INV-6 is a UI concern. A purged note loses its reminders and budget nodes (cascade in
 * the database), its alarms and its files (through [NoteDataCleaner]).
 */
class TrashUseCases(
    private val folders: FolderRepository,
    private val notes: NoteRepository,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val cleaner: NoteDataCleaner = NoteDataCleaner.None,
    private val clock: Clock = Clock.System,
) {

    suspend fun purgeNote(noteId: String): DomainResult<Unit> {
        val note = notes.getByIdIncludingDeleted(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        if (note.deletedAt == null) return DomainError.NOT_IN_TRASH.asFailure()
        cancelAlarms(listOf(note))
        notes.purge(noteId)
        cleaner.deleteData(note)
        return Unit.asSuccess()
    }

    suspend fun purgeFolder(folderId: String): DomainResult<Unit> {
        val folder = folders.getByIdIncludingDeleted(folderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        if (folder.deletedAt == null) return DomainError.NOT_IN_TRASH.asFailure()
        // Invariant: a trashed folder holds no active notes. Never delete what is still in use.
        if (notes.getByFolder(folderId).isNotEmpty()) return DomainError.FOLDER_NOT_EMPTY.asFailure()
        val inside = notes.getTrashedByFolder(folderId)
        cancelAlarms(inside)
        folders.purge(folderId) // the folder and its trashed notes, in one transaction
        inside.forEach { cleaner.deleteData(it) }
        return Unit.asSuccess()
    }

    /** Deletes everything in the trash for good. Returns how many folders and notes were removed. */
    suspend fun emptyTrash(): Int {
        var removed = 0
        for (folder in folders.getTrashed()) {
            val inside = notes.getTrashedByFolder(folder.id).size
            if (purgeFolder(folder.id) is DomainResult.Success) removed += 1 + inside
        }
        for (note in notes.getTrashed()) {
            if (purgeNote(note.id) is DomainResult.Success) removed++
        }
        return removed
    }

    /**
     * Removes what has been in the trash longer than [retentionDays] (D65). A folder goes with the
     * notes that were trashed with it; a note trashed on its own expires on its own date.
     */
    suspend fun purgeExpired(retentionDays: Int): Int {
        val cutoff = TrashRetention.cutoff(clock.now(), retentionDays) ?: return 0
        var removed = 0
        for (note in notes.getTrashed()) {
            if (note.deletedAt != null && note.deletedAt <= cutoff && purgeNote(note.id) is DomainResult.Success) removed++
        }
        for (folder in folders.getTrashed()) {
            if (folder.deletedAt != null && folder.deletedAt <= cutoff) {
                val inside = notes.getTrashedByFolder(folder.id).size
                if (purgeFolder(folder.id) is DomainResult.Success) removed += 1 + inside
            }
        }
        return removed
    }

    private suspend fun cancelAlarms(list: List<Note>) {
        reminders.getByNoteIds(list.map { it.id }).forEach { scheduler.cancel(it.id) }
    }
}
