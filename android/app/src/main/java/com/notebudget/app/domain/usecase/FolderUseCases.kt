package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.Clock
import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.FeaturedLayout
import com.notebudget.app.domain.model.FeaturedMetric
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.FolderStyleId
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.asFailure
import com.notebudget.app.domain.model.asSuccess
import com.notebudget.app.domain.repository.FolderRepository
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.ReminderRepository
import com.notebudget.app.domain.repository.ReminderScheduler

/** What to do with the notes of a folder being deleted (D20, D48, D74). */
enum class FolderDeleteMode { MOVE_NOTES, DELETE_NOTES }

/**
 * Folder rules: flat folders (D17), unique names (D52), the last folder is never deleted
 * (INV-2), notes never become orphans (INV-7), featured note belongs to the folder (INV-3).
 * The confirmation dialog required by INV-6 is a UI concern and happens before these calls.
 */
class FolderUseCases(
    private val folders: FolderRepository,
    private val notes: NoteRepository,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
) {

    /** Also used for the very first folder of onboarding (D03, D47). */
    suspend fun create(
        name: String,
        colorHex: String,
        styleId: String,
        icon: String? = null,
        kind: com.notebudget.app.domain.model.FolderKind = com.notebudget.app.domain.model.FolderKind.NOTES,
    ): DomainResult<Folder> {
        validate(name, colorHex, styleId, excludeId = null)?.let { return it.asFailure() }
        val now = clock.now()
        val nextOrder = (folders.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val folder = Folder(
            id = ids.newId(),
            name = name.trim(),
            colorHex = colorHex,
            styleId = styleId,
            icon = icon?.takeIf { it.isNotBlank() },
            sortOrder = nextOrder,
            featuredNoteId = null,
            featuredMetricsMask = 0,
            featuredLayout = FeaturedLayout.COMPACT,
            createdAt = now,
            updatedAt = now,
            kind = kind,
        )
        folders.insert(folder)
        return folder.asSuccess()
    }

    suspend fun update(
        folderId: String,
        name: String,
        colorHex: String,
        styleId: String,
        icon: String?,
    ): DomainResult<Folder> {
        val existing = folders.getById(folderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        validate(name, colorHex, styleId, excludeId = folderId)?.let { return it.asFailure() }
        val updated = existing.copy(
            name = name.trim(),
            colorHex = colorHex,
            styleId = styleId,
            icon = icon?.takeIf { it.isNotBlank() },
            updatedAt = clock.now(),
        )
        folders.update(updated)
        return updated.asSuccess()
    }

    /** Sets or clears the comment of a folder (D177); blank clears it. */
    suspend fun setComment(folderId: String, text: String?): DomainResult<Folder> {
        val existing = folders.getById(folderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        val comment = com.notebudget.app.domain.model.Comment.clean(text)
        if (existing.comment == comment) return existing.asSuccess()
        val updated = existing.copy(comment = comment, updatedAt = clock.now())
        folders.update(updated)
        return updated.asSuccess()
    }

    /** Pins or unpins a folder (D136); the folder pinned last comes first. A mark, so "modified" stays. */
    suspend fun setPinned(folderId: String, pinned: Boolean): DomainResult<Folder> {
        val existing = folders.getById(folderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        if (existing.isPinned == pinned) return existing.asSuccess()
        val updated = existing.copy(isPinned = pinned, pinnedAt = if (pinned) clock.now() else 0)
        folders.update(updated)
        return updated.asSuccess()
    }

    /**
     * Moves a folder to the trash (soft delete, D29). [targetFolderId] is required for
     * [FolderDeleteMode.MOVE_NOTES] when the folder is not empty.
     */
    suspend fun delete(
        folderId: String,
        mode: FolderDeleteMode,
        targetFolderId: String? = null,
    ): DomainResult<Unit> {
        folders.getById(folderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        if (folders.count() <= 1) return DomainError.LAST_FOLDER_CANNOT_BE_DELETED.asFailure()

        val folderNotes = notes.getByFolder(folderId)
        when (mode) {
            FolderDeleteMode.MOVE_NOTES -> {
                if (folderNotes.isNotEmpty()) {
                    if (targetFolderId == null || targetFolderId == folderId) {
                        return DomainError.TARGET_FOLDER_REQUIRED.asFailure()
                    }
                    folders.getById(targetFolderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
                    folders.trashMovingNotes(folderId, targetFolderId, clock.now())
                } else {
                    folders.trashWithNotes(folderId, clock.now())
                }
            }

            FolderDeleteMode.DELETE_NOTES -> {
                // Trashed notes must not ring: cancel their alarms.
                reminders.getByNoteIds(folderNotes.map { it.id }).forEach { scheduler.cancel(it.id) }
                folders.trashWithNotes(folderId, clock.now())
            }
        }
        return Unit.asSuccess()
    }

    /**
     * Restores a trashed folder together with the notes trashed with it. The name must still
     * be free (D52), otherwise [DomainError.DUPLICATE_FOLDER_NAME].
     */
    suspend fun restore(folderId: String): DomainResult<Unit> {
        val folder = folders.getByIdIncludingDeleted(folderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        if (folder.deletedAt == null) return DomainError.NOT_IN_TRASH.asFailure()
        if (folders.getAll().any { it.name.equals(folder.name, ignoreCase = true) }) {
            return DomainError.DUPLICATE_FOLDER_NAME.asFailure()
        }
        folders.restore(folderId, clock.now())
        rearmReminders(reminders, scheduler, clock, notes.getByFolder(folderId).map { it.id })
        return Unit.asSuccess()
    }

    /**
     * Sets or clears the featured note (D07, D40). INV-3: the note must be in this folder.
     * Metrics only make sense for BUDGET notes (§6.4), so for other notes the mask is reset.
     */
    suspend fun setFeatured(
        folderId: String,
        noteId: String?,
        metricsMask: Int,
        layout: FeaturedLayout,
    ): DomainResult<Folder> {
        val folder = folders.getById(folderId) ?: return DomainError.FOLDER_NOT_FOUND.asFailure()
        val mask = metricsMask and FeaturedMetric.ALL_MASK
        val updated = if (noteId == null) {
            folder.copy(featuredNoteId = null, featuredMetricsMask = 0, featuredLayout = layout)
        } else {
            val note = notes.getById(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
            if (note.folderId != folderId) return DomainError.FEATURED_NOTE_NOT_IN_FOLDER.asFailure()
            folder.copy(
                featuredNoteId = noteId,
                featuredMetricsMask = if (note.type == NoteType.BUDGET) mask else 0,
                featuredLayout = layout,
            )
        }.copy(updatedAt = clock.now())
        folders.update(updated)
        return updated.asSuccess()
    }

    /** Manual drag ordering (D54): [orderedIds] become sortOrder 0, 1, 2, ... */
    suspend fun reorder(orderedIds: List<String>) {
        folders.updateSortOrders(orderedIds.withIndex().associate { (index, id) -> id to index })
    }

    private suspend fun validate(
        name: String,
        colorHex: String,
        styleId: String,
        excludeId: String?,
    ): DomainError? {
        if (name.isBlank()) return DomainError.BLANK_NAME
        if (!COLOR_REGEX.matches(colorHex)) return DomainError.INVALID_COLOR
        if (FolderStyleId.entries.none { it.name == styleId }) return DomainError.INVALID_STYLE
        val trimmed = name.trim()
        // Kotlin ignoreCase folds Cyrillic too; SQLite NOCASE would not.
        if (folders.getAll().any { it.id != excludeId && it.name.equals(trimmed, ignoreCase = true) }) {
            return DomainError.DUPLICATE_FOLDER_NAME
        }
        return null
    }

    companion object {
        val COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    }
}
