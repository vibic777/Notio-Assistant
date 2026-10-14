package com.notio.shared.data

import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.Clock
import com.notio.shared.domain.Comment
import com.notio.shared.domain.FeaturedLayout
import com.notio.shared.domain.Folder
import com.notio.shared.domain.FolderStyleId
import com.notio.shared.domain.IdGenerator
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.Tag

/** Why an edit was refused; the UI maps each to a message (the Android `DomainError`). */
enum class EditError {
    BLANK_NAME, BLANK_TITLE, DUPLICATE_FOLDER_NAME, INVALID_COLOR, INVALID_STYLE, FOLDER_NOT_FOUND, NOTE_NOT_FOUND,
    LAST_FOLDER_CANNOT_BE_DELETED, TARGET_FOLDER_REQUIRED, NOT_IN_TRASH, FOLDER_NOT_EMPTY, INVALID_TAG_NAME, DUPLICATE_TAG_NAME, TAG_NOT_FOUND,
}

class EditException(val error: EditError) : Exception(error.name)

/** What to do with the notes of a folder being deleted (Android D20, D48, D74). */
enum class FolderDeleteMode { MOVE_NOTES, DELETE_NOTES }

/**
 * The editing rules of the Android app (`FolderUseCases`, `NoteUseCases`, `TrashUseCases` and the Room repositories), on the
 * desktop database: flat folders with unique names (D52), the last folder is never deleted (INV-2), a note always has a folder
 * (INV-1), the featured link points into its folder (INV-3), deletion is soft (D29) and only trashed things can be purged.
 * Pins and the bold title are marks, not edits: they do not move "modified" (D136). Confirmations are the UI's job (§1.3).
 */
class NotioEditor(
    private val db: NotioDatabase,
    private val files: AttachmentStore,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
) {
    private fun fail(e: EditError): Nothing = throw EditException(e)

    private fun folder(id: String) = db.folders().firstOrNull { it.id == id }
    private fun activeFolder(id: String) = folder(id)?.takeIf { it.deletedAt == null }
    private fun note(id: String) = db.note(id)

    // ---- folders ----

    fun createFolder(
        name: String, colorHex: String, styleId: String, icon: String? = null,
        /** Notes or tasks (Android D239); chosen when the folder is made. */
        kind: com.notio.shared.domain.FolderKind = com.notio.shared.domain.FolderKind.NOTES,
    ): Folder {
        validate(name, colorHex, styleId, excludeId = null)
        val now = clock.now()
        val folder = Folder(
            id = ids.newId(), name = name.trim(), colorHex = colorHex, styleId = styleId, icon = icon?.takeIf { it.isNotBlank() },
            sortOrder = (db.folders().maxOfOrNull { it.sortOrder } ?: -1) + 1, featuredNoteId = null, featuredMetricsMask = 0,
            featuredLayout = FeaturedLayout.COMPACT, createdAt = now, updatedAt = now, kind = kind,
        )
        db.upsertFolder(folder)
        return folder
    }

    fun updateFolder(id: String, name: String, colorHex: String, styleId: String, icon: String?): Folder {
        val existing = activeFolder(id) ?: fail(EditError.FOLDER_NOT_FOUND)
        validate(name, colorHex, styleId, excludeId = id)
        val updated = existing.copy(
            name = name.trim(), colorHex = colorHex, styleId = styleId, icon = icon?.takeIf { it.isNotBlank() }, updatedAt = clock.now(),
        )
        db.upsertFolder(updated)
        return updated
    }

    fun setFolderComment(id: String, text: String?): Folder {
        val existing = activeFolder(id) ?: fail(EditError.FOLDER_NOT_FOUND)
        val comment = Comment.clean(text)
        if (existing.comment == comment) return existing
        return existing.copy(comment = comment, updatedAt = clock.now()).also(db::upsertFolder)
    }

    fun setFolderPinned(id: String, pinned: Boolean): Folder {
        val existing = activeFolder(id) ?: fail(EditError.FOLDER_NOT_FOUND)
        if (existing.isPinned == pinned) return existing
        return existing.copy(isPinned = pinned, pinnedAt = if (pinned) clock.now() else 0).also(db::upsertFolder)
    }

    /** Moves a folder to the trash; its notes go with it, or first move to [targetFolderId]. */
    fun deleteFolder(id: String, mode: FolderDeleteMode, targetFolderId: String? = null) {
        val existing = activeFolder(id) ?: fail(EditError.FOLDER_NOT_FOUND)
        if (db.folders().count { it.deletedAt == null } <= 1) fail(EditError.LAST_FOLDER_CANNOT_BE_DELETED)
        val now = clock.now()
        val inside = db.notesOf(id).filter { it.deletedAt == null }
        db.transaction {
            if (mode == FolderDeleteMode.MOVE_NOTES && inside.isNotEmpty()) {
                if (targetFolderId == null || targetFolderId == id) fail(EditError.TARGET_FOLDER_REQUIRED)
                activeFolder(targetFolderId) ?: fail(EditError.FOLDER_NOT_FOUND)
                inside.forEach { db.upsertNote(it.copy(folderId = targetFolderId, updatedAt = now)) }
            } else {
                // Trashed with the folder's own time stamp, so a restore of the folder brings exactly them back.
                inside.forEach { db.upsertNote(it.copy(deletedAt = now, updatedAt = now)) }
            }
            db.upsertFolder(existing.copy(deletedAt = now, updatedAt = now, featuredNoteId = null, featuredMetricsMask = 0))
        }
    }

    /** Restores a trashed folder with the notes trashed together with it; its name must still be free (D52). */
    fun restoreFolder(id: String) {
        val existing = folder(id) ?: fail(EditError.FOLDER_NOT_FOUND)
        val deletedAt = existing.deletedAt ?: fail(EditError.NOT_IN_TRASH)
        if (db.folders().any { it.deletedAt == null && it.name.equals(existing.name, ignoreCase = true) }) fail(EditError.DUPLICATE_FOLDER_NAME)
        val now = clock.now()
        db.transaction {
            db.notesOf(id).filter { it.deletedAt == deletedAt }.forEach { db.upsertNote(it.copy(deletedAt = null, updatedAt = now)) }
            db.upsertFolder(existing.copy(deletedAt = null, updatedAt = now))
        }
    }

    /** Deletes a trashed folder for good, with the notes trashed inside it and their files. */
    fun purgeFolder(id: String) {
        val existing = folder(id) ?: fail(EditError.FOLDER_NOT_FOUND)
        if (existing.deletedAt == null) fail(EditError.NOT_IN_TRASH)
        val inside = db.notesOf(id)
        if (inside.any { it.deletedAt == null }) fail(EditError.FOLDER_NOT_EMPTY)
        db.transaction {
            inside.forEach { db.deleteNote(it.id) }
            db.deleteFolder(id)
        }
        inside.forEach { files.deleteNote(it.id) }
    }

    /**
     * Sets or clears the featured note of a folder (D07, D40, §6.4): it must be in the folder (INV-3); the totals only make
     * sense for a budget, so for another kind the mask is reset.
     */
    fun setFeatured(folderId: String, noteId: String?, metricsMask: Int, layout: FeaturedLayout): Folder {
        val folder = activeFolder(folderId) ?: fail(EditError.FOLDER_NOT_FOUND)
        val mask = metricsMask and com.notio.shared.domain.FeaturedMetric.ALL_MASK
        val updated = if (noteId == null) {
            folder.copy(featuredNoteId = null, featuredMetricsMask = 0, featuredLayout = layout)
        } else {
            val note = note(noteId)?.takeIf { it.deletedAt == null } ?: fail(EditError.NOTE_NOT_FOUND)
            if (note.folderId != folderId) fail(EditError.FOLDER_NOT_FOUND)
            folder.copy(featuredNoteId = noteId, featuredMetricsMask = if (note.type == NoteType.BUDGET) mask else 0, featuredLayout = layout)
        }.copy(updatedAt = clock.now())
        db.upsertFolder(updated)
        return updated
    }

    private fun validate(name: String, colorHex: String, styleId: String, excludeId: String?) {
        if (name.isBlank()) fail(EditError.BLANK_NAME)
        if (!COLOR.matches(colorHex)) fail(EditError.INVALID_COLOR)
        if (FolderStyleId.entries.none { it.name == styleId }) fail(EditError.INVALID_STYLE)
        // Kotlin's ignoreCase folds Cyrillic too; SQLite NOCASE would not.
        if (db.folders().any { it.deletedAt == null && it.id != excludeId && it.name.equals(name.trim(), ignoreCase = true) }) {
            fail(EditError.DUPLICATE_FOLDER_NAME)
        }
    }

    // ---- notes ----

    fun createNote(folderId: String, title: String, type: NoteType, contentText: String? = null, colorHex: String? = null): Note {
        if (title.isBlank()) fail(EditError.BLANK_TITLE)
        if (colorHex != null && !COLOR.matches(colorHex)) fail(EditError.INVALID_COLOR)
        activeFolder(folderId) ?: fail(EditError.FOLDER_NOT_FOUND)
        val now = clock.now()
        val note = Note(
            id = ids.newId(), folderId = folderId, title = title.trim(), type = type, colorHex = colorHex, isPinned = false,
            isFavorite = false, isTitleBold = false, contentText = contentText, createdAt = now, updatedAt = now,
            // A new note is the first of its folder in "My order" too (Android D257): before the one that was first.
            sortOrder = db.notesOf(folderId).minOfOrNull { it.sortOrder }?.let { it - 1 } ?: 0,
            // A new task is Created (Android D337).
            extras = if (type == NoteType.TASK) com.notio.shared.domain.TaskDocument.NEW.encode() else null,
        )
        db.transaction {
            db.upsertNote(note)
            // A budget always has exactly one root group (INV-4).
            if (type == NoteType.BUDGET) {
                db.upsertNode(
                    BudgetNode(
                        id = ids.newId(), noteId = note.id, parentId = null, title = note.title, kind = BudgetNodeKind.GROUP,
                        manualSumMinor = 0, manualGoalMinor = 0, comment = null, cachedSumMinor = 0, cachedGoalMinor = 0,
                        cachedRemainderMinor = 0, isExpanded = true, sortOrder = 0, createdAt = now, updatedAt = now,
                    ),
                )
            }
        }
        return note
    }

    /**
     * Saves title, text, colour and the marks; `folderId`, `type` and `createdAt` are never changed here. The type's own JSON
     * (`extras`) is replaced only when [withExtras] (a formatted note's styles), as in the Android `NoteUseCases.save`.
     */
    fun saveNote(edited: Note, withExtras: Boolean = false): Note {
        if (edited.title.isBlank()) fail(EditError.BLANK_TITLE)
        if (edited.colorHex != null && !COLOR.matches(edited.colorHex)) fail(EditError.INVALID_COLOR)
        val existing = note(edited.id) ?: fail(EditError.NOTE_NOT_FOUND)
        val now = clock.now()
        val title = edited.title.trim()
        val extras = if (withExtras) edited.extras else existing.extras
        val edit = title != existing.title || edited.contentText != existing.contentText || extras != existing.extras ||
            edited.colorHex != existing.colorHex
        val updated = existing.copy(
            title = title, colorHex = edited.colorHex, isPinned = edited.isPinned,
            // The note pinned last is the first of the pinned ones.
            pinnedAt = when {
                !edited.isPinned -> 0
                existing.isPinned -> existing.pinnedAt
                else -> now
            },
            isFavorite = false, isTitleBold = edited.isTitleBold, contentText = edited.contentText, extras = extras,
            updatedAt = if (edit) now else existing.updatedAt,
        )
        db.upsertNote(updated)
        return updated
    }

    /** Hides the content of a note until the eye is pressed, or stops hiding it (Android D363). */
    fun setNoteHidden(id: String, hidden: Boolean): Note {
        val existing = note(id) ?: fail(EditError.NOTE_NOT_FOUND)
        if (existing.hidden == hidden) return existing
        return existing.copy(hidden = hidden, updatedAt = clock.now()).also(db::upsertNote)
    }

    fun setNoteComment(id: String, text: String?): Note {
        val existing = note(id) ?: fail(EditError.NOTE_NOT_FOUND)
        val comment = Comment.clean(text)
        if (existing.comment == comment) return existing
        return existing.copy(comment = comment, updatedAt = clock.now()).also(db::upsertNote)
    }

    /** Moving clears the featured link of the old folder (INV-3); reminders stay. */
    fun moveNote(id: String, targetFolderId: String) {
        val existing = note(id) ?: fail(EditError.NOTE_NOT_FOUND)
        activeFolder(targetFolderId) ?: fail(EditError.FOLDER_NOT_FOUND)
        if (existing.folderId == targetFolderId) return
        db.transaction {
            clearFeatured(id)
            db.upsertNote(existing.copy(folderId = targetFolderId, updatedAt = clock.now()))
        }
    }

    /** Moves a note to the trash (D29): the data is kept. */
    fun trashNote(id: String) {
        val existing = note(id) ?: fail(EditError.NOTE_NOT_FOUND)
        if (existing.deletedAt != null) return
        val now = clock.now()
        db.transaction {
            clearFeatured(id)
            db.upsertNote(existing.copy(deletedAt = now, updatedAt = now))
        }
    }

    /** Restores a trashed note; into [targetFolderId] when its own folder is in the trash too. */
    fun restoreNote(id: String, targetFolderId: String? = null) {
        val existing = note(id) ?: fail(EditError.NOTE_NOT_FOUND)
        if (existing.deletedAt == null) fail(EditError.NOT_IN_TRASH)
        val folderId = targetFolderId ?: existing.folderId
        activeFolder(folderId) ?: fail(EditError.FOLDER_NOT_FOUND)
        db.upsertNote(existing.copy(folderId = folderId, deletedAt = null, updatedAt = clock.now()))
    }

    /** Deletes a trashed note for good: its budget, reminders, tags (cascade) and its files. */
    fun purgeNote(id: String) {
        val existing = note(id) ?: fail(EditError.NOTE_NOT_FOUND)
        if (existing.deletedAt == null) fail(EditError.NOT_IN_TRASH)
        db.deleteNote(id)
        files.deleteNote(id)
    }

    /** Everything in the trash, for good. Returns how many folders and notes were removed. */
    fun emptyTrash(): Int {
        var removed = 0
        for (f in db.folders().filter { it.deletedAt != null }) {
            val inside = db.notesOf(f.id)
            if (inside.any { it.deletedAt == null }) continue
            purgeFolder(f.id)
            removed += 1 + inside.size
        }
        for (n in db.notes().filter { it.deletedAt != null }) {
            purgeNote(n.id)
            removed++
        }
        return removed
    }

    /**
     * Removes what has been in the trash longer than [retentionDays] (Android D65, `TrashUseCases.purgeExpired`). A folder goes with
     * the notes trashed with it; a note trashed on its own expires on its own date.
     */
    fun purgeExpired(retentionDays: Int): Int {
        val cutoff = com.notio.shared.domain.TrashRetention.cutoff(clock.now(), retentionDays) ?: return 0
        var removed = 0
        for (n in db.notes().filter { it.deletedAt != null && it.deletedAt <= cutoff }) {
            purgeNote(n.id)
            removed++
        }
        for (f in db.folders().filter { it.deletedAt != null && it.deletedAt <= cutoff }) {
            val inside = db.notesOf(f.id)
            if (inside.any { it.deletedAt == null }) continue
            purgeFolder(f.id)
            removed += 1 + inside.size
        }
        return removed
    }

    /** The person's own order of folders (Android D141, `FolderUseCases.reorder`): the position is the sort order; nothing else changes. */
    fun reorderFolders(orderedIds: List<String>) = db.transaction {
        val byId = db.folders().associateBy { it.id }
        orderedIds.forEachIndexed { index, id -> byId[id]?.takeIf { it.sortOrder != index }?.let { db.upsertFolder(it.copy(sortOrder = index)) } }
    }

    /** The same for notes (`NoteUseCases.reorder`). */
    fun reorderNotes(orderedIds: List<String>) = db.transaction {
        orderedIds.forEachIndexed { index, id -> db.note(id)?.takeIf { it.sortOrder != index }?.let { db.upsertNote(it.copy(sortOrder = index)) } }
    }

    private fun clearFeatured(noteId: String) {
        db.folders().filter { it.featuredNoteId == noteId }.forEach {
            db.upsertFolder(it.copy(featuredNoteId = null, featuredMetricsMask = 0))
        }
    }

    // ---- tags (the Android TagUseCases, D109) ----

    /** A tag name is 1 to 32 characters, unique ignoring case (also Cyrillic); a leading `#` and extra spaces are dropped. */
    fun createTag(name: String): Tag {
        val clean = cleanTag(name)
        if (clean.isEmpty() || clean.length > MAX_TAG) fail(EditError.INVALID_TAG_NAME)
        if (db.tags().any { it.name.equals(clean, ignoreCase = true) }) fail(EditError.DUPLICATE_TAG_NAME)
        return Tag(ids.newId(), clean).also { db.insertTag(it, clock.now()) }
    }

    /** The tag with this name, made when it does not exist yet. */
    fun getOrCreateTag(name: String): Tag {
        val clean = cleanTag(name)
        return db.tags().firstOrNull { it.name.equals(clean, ignoreCase = true) } ?: createTag(clean)
    }

    fun renameTag(id: String, name: String): Tag {
        val clean = cleanTag(name)
        if (clean.isEmpty() || clean.length > MAX_TAG) fail(EditError.INVALID_TAG_NAME)
        val all = db.tags()
        all.firstOrNull { it.id == id } ?: fail(EditError.TAG_NOT_FOUND)
        if (all.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) fail(EditError.DUPLICATE_TAG_NAME)
        db.renameTag(id, clean)
        return Tag(id, clean)
    }

    fun deleteTag(id: String) {
        if (db.tags().none { it.id == id }) fail(EditError.TAG_NOT_FOUND)
        db.deleteTag(id)
    }

    fun setNoteTags(noteId: String, tagIds: Set<String>) {
        note(noteId) ?: fail(EditError.NOTE_NOT_FOUND)
        if (!db.tags().map { it.id }.toSet().containsAll(tagIds)) fail(EditError.TAG_NOT_FOUND)
        db.setNoteTags(noteId, tagIds)
    }

    companion object {
        const val MAX_TAG = 32

        /** What a person typed, made into a tag name: `  #Work  trip ` becomes `Work trip`. */
        fun cleanTag(input: String): String = input.trim().removePrefix("#").trim().replace(Regex("\\s+"), " ")

        val COLOR = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    }
}
