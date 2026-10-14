package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.Clock
import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.asFailure
import com.notebudget.app.domain.model.asSuccess
import com.notebudget.app.domain.repository.FolderRepository
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.ReminderRepository
import com.notebudget.app.domain.repository.ReminderScheduler

/**
 * Note rules: a note always has an existing folder (INV-1), the title is required (D55),
 * moving a note clears its featured link (§6.5). Deletion is soft (D29, D81): notes go to the
 * trash; restoring and permanent deletion are in [TrashUseCases] and the trash screen.
 */
class NoteUseCases(
    private val notes: NoteRepository,
    private val folders: FolderRepository,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
) {

    suspend fun create(
        folderId: String,
        title: String,
        type: NoteType,
        colorHex: String? = null,
        contentText: String? = null,
    ): DomainResult<Note> {
        if (title.isBlank()) return DomainError.BLANK_TITLE.asFailure()
        if (colorHex != null && !FolderUseCases.COLOR_REGEX.matches(colorHex)) {
            return DomainError.INVALID_COLOR.asFailure()
        }
        folders.getById(folderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()

        val now = clock.now()
        // A new note is the first of its folder in "My order" too (D257): before the one that was first.
        val first = notes.getByFolder(folderId).minOfOrNull { it.sortOrder }
        val note = Note(
            id = ids.newId(),
            folderId = folderId,
            sortOrder = if (first == null) 0 else first - 1,
            title = title.trim(),
            type = type,
            colorHex = colorHex,
            isPinned = false,
            isFavorite = false,
            isTitleBold = false,
            contentText = contentText,
            createdAt = now,
            updatedAt = now,
            // A new task is Created (D337).
            extras = if (type == NoteType.TASK) com.notebudget.app.domain.model.TaskDocument.NEW.encode() else null,
        )
        val root = if (type == NoteType.BUDGET) newBudgetRoot(note, now) else null
        notes.insert(note, root)
        return note.asSuccess()
    }

    /**
     * Saves editable properties. `folderId` (use [move]), `type` and `createdAt` are never
     * changed here.
     */
    suspend fun save(edited: Note, withExtras: Boolean = false): DomainResult<Note> {
        if (edited.title.isBlank()) return DomainError.BLANK_TITLE.asFailure()
        if (edited.colorHex != null && !FolderUseCases.COLOR_REGEX.matches(edited.colorHex)) {
            return DomainError.INVALID_COLOR.asFailure()
        }
        val existing = notes.getById(edited.id) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        val now = clock.now()
        val title = edited.title.trim()
        val extras = if (withExtras) edited.extras else existing.extras
        // Pinning, the favourite star and the bold title are marks, not edits: they do not change "modified" (D136).
        val edit = title != existing.title || edited.contentText != existing.contentText || extras != existing.extras ||
            edited.colorHex != existing.colorHex
        val updated = existing.copy(
            title = title,
            colorHex = edited.colorHex,
            isPinned = edited.isPinned,
            // The note pinned last is the first of the pinned ones.
            pinnedAt = when {
                !edited.isPinned -> 0
                existing.isPinned -> existing.pinnedAt
                else -> now
            },
            isFavorite = false, // the star is gone: a favourite became a pin (D180)
            isTitleBold = edited.isTitleBold,
            contentText = edited.contentText,
            // The type-specific JSON is replaced only when the editor changed it (a LIST keeps its
            // structure there); otherwise the stored one stays, whatever stale copy [edited] carries.
            extras = extras,
            updatedAt = if (edit) now else existing.updatedAt,
        )
        notes.update(updated)
        return updated.asSuccess()
    }

    /** Sets or clears the comment of a note (D177); blank clears it. It changes the note, so "modified" moves. */
    suspend fun setComment(noteId: String, text: String?): DomainResult<Note> {
        val existing = notes.getById(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        val comment = com.notebudget.app.domain.model.Comment.clean(text)
        if (existing.comment == comment) return existing.asSuccess()
        val updated = existing.copy(comment = comment, updatedAt = clock.now())
        notes.update(updated)
        return updated.asSuccess()
    }

    /** Hides the content of a note until the eye is pressed, or stops hiding it (D363). It changes the note, so "modified" moves. */
    suspend fun setHidden(noteId: String, hidden: Boolean): DomainResult<Note> {
        val existing = notes.getById(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        if (existing.hidden == hidden) return existing.asSuccess()
        val updated = existing.copy(hidden = hidden, updatedAt = clock.now())
        notes.update(updated)
        return updated.asSuccess()
    }

    /** The user's own order inside a folder (D136): [orderedIds] become sortOrder 0, 1, 2, ... */
    suspend fun reorder(orderedIds: List<String>) {
        notes.updateSortOrders(orderedIds.withIndex().associate { (index, id) -> id to index })
    }

    /** Stores the type-specific settings JSON of a note (e.g. the budget layout). */
    suspend fun updateExtras(noteId: String, extras: String?): DomainResult<Note> {
        val existing = notes.getById(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        val updated = existing.copy(extras = extras, updatedAt = clock.now())
        notes.update(updated)
        return updated.asSuccess()
    }

    /** Marks the note as changed without touching its fields (e.g. after a drawing was saved). */
    suspend fun touch(noteId: String): DomainResult<Note> {
        val existing = notes.getById(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        val updated = existing.copy(updatedAt = clock.now())
        notes.update(updated)
        return updated.asSuccess()
    }

    /** Reminders are preserved; the featured link is cleared by the repository (INV-3). */
    suspend fun move(noteId: String, targetFolderId: String): DomainResult<Unit> {
        val note = notes.getById(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        folders.getById(targetFolderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        if (note.folderId != targetFolderId) notes.move(noteId, targetFolderId, clock.now())
        return Unit.asSuccess()
    }

    /** Moves the note to the trash (soft delete, D29). Its alarms are cancelled, data is kept. */
    suspend fun delete(noteId: String): DomainResult<Unit> {
        notes.getById(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        reminders.getByNoteIds(listOf(noteId)).forEach { scheduler.cancel(it.id) }
        notes.trash(noteId, clock.now())
        return Unit.asSuccess()
    }

    /** Restores a trashed note; its folder must still exist (not trashed). */
    suspend fun restore(noteId: String): DomainResult<Unit> {
        val note = notes.getByIdIncludingDeleted(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        if (note.deletedAt == null) return DomainError.NOT_IN_TRASH.asFailure()
        folders.getById(note.folderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        notes.restore(noteId, clock.now())
        rearmReminders(reminders, scheduler, clock, listOf(noteId))
        return Unit.asSuccess()
    }

    /**
     * Restores a trashed note into [targetFolderId] instead of its own folder, for a note whose
     * folder is itself in the trash. The featured link was cleared when the note was trashed.
     */
    suspend fun restoreInto(noteId: String, targetFolderId: String): DomainResult<Unit> {
        val note = notes.getByIdIncludingDeleted(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        if (note.deletedAt == null) return DomainError.NOT_IN_TRASH.asFailure()
        folders.getById(targetFolderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        if (note.folderId != targetFolderId) notes.move(noteId, targetFolderId, clock.now())
        notes.restore(noteId, clock.now())
        rearmReminders(reminders, scheduler, clock, listOf(noteId))
        return Unit.asSuccess()
    }

    private fun newBudgetRoot(note: Note, now: Long) = BudgetNode(
        id = ids.newId(),
        noteId = note.id,
        parentId = null,
        title = note.title,
        kind = BudgetNodeKind.GROUP,
        manualSumMinor = 0,
        manualGoalMinor = 0,
        comment = null,
        cachedSumMinor = 0,
        cachedGoalMinor = 0,
        cachedRemainderMinor = 0,
        isExpanded = true,
        sortOrder = 0,
        createdAt = now,
        updatedAt = now,
    )
}
