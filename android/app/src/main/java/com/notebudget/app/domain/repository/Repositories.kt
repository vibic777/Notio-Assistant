package com.notebudget.app.domain.repository

import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository contracts. Every method that touches several tables is a single transaction
 * in the implementation (AGENT_EN.md §1.2).
 */
interface FolderRepository {
    /** Active (not trashed) folders only. */
    fun observeAll(): Flow<List<Folder>>
    suspend fun getAll(): List<Folder>
    suspend fun getById(id: String): Folder?

    /** Also returns a trashed folder. */
    suspend fun getByIdIncludingDeleted(id: String): Folder?
    suspend fun count(): Int
    suspend fun insert(folder: Folder)
    suspend fun update(folder: Folder)

    /** Sets `sortOrder` of the given folders (id -> order) atomically. */
    suspend fun updateSortOrders(orders: Map<String, Int>)

    /** Atomically moves every note of [folderId] to [targetFolderId] and trashes the folder. */
    suspend fun trashMovingNotes(folderId: String, targetFolderId: String, now: Long)

    /** Atomically trashes the folder together with its active notes (same timestamp). */
    suspend fun trashWithNotes(folderId: String, now: Long)

    /** Restores a trashed folder and the notes that were trashed together with it. */
    suspend fun restore(folderId: String, now: Long)

    /** Trashed folders, most recently deleted first. */
    fun observeTrashed(): Flow<List<Folder>>
    suspend fun getTrashed(): List<Folder>

    /**
     * Permanently deletes a TRASHED folder and every trashed note that still points at it, in
     * one transaction. Does nothing for an active folder.
     */
    suspend fun purge(folderId: String)
}

interface NoteRepository {
    /** Active (not trashed) notes only. */
    fun observeAll(): Flow<List<Note>>
    fun observeByFolder(folderId: String): Flow<List<Note>>

    /** Emits the active note, or `null` if it is missing or trashed. */
    fun observeById(id: String): Flow<Note?>

    /** Note count per folder id (folders without notes are absent). */
    fun observeCountsByFolder(): Flow<Map<String, Int>>
    suspend fun getById(id: String): Note?

    /** Also returns a trashed note. */
    suspend fun getByIdIncludingDeleted(id: String): Note?
    suspend fun getByFolder(folderId: String): List<Note>

    /** Every note, trashed ones included (to find what still uses a unit, for example). */
    suspend fun getAllIncludingDeleted(): List<Note>

    /**
     * The active notes of one kind (D331). The database answers it with those rows alone; this default, for a repository that
     * has no such query, goes through all of them.
     */
    suspend fun getActiveByType(type: com.notebudget.app.domain.model.NoteType): List<Note> =
        getAllIncludingDeleted().filter { it.deletedAt == null && it.type == type }

    /** The settings (`extras`) of the active notes of one kind, and nothing else of them (D331). */
    fun observeExtrasByType(type: com.notebudget.app.domain.model.NoteType): Flow<List<String?>> =
        observeAll().map { notes -> notes.filter { it.type == type }.map { it.extras } }

    suspend fun getExtrasByType(type: com.notebudget.app.domain.model.NoteType): List<String?> =
        getActiveByType(type).map { it.extras }

    /** Inserts the note and, for BUDGET notes, its root node in one transaction. */
    suspend fun insert(note: Note, budgetRoot: BudgetNode? = null)
    suspend fun update(note: Note)

    /** Sets `sortOrder` of the given notes (id -> order) atomically (manual order, D136). */
    suspend fun updateSortOrders(orders: Map<String, Int>)

    /** Moves the note and clears any folder link to it as featured note (INV-3). */
    suspend fun move(noteId: String, targetFolderId: String, now: Long)

    /** Trashes the note (soft delete) and clears `featuredNoteId` links to it. Data is kept. */
    suspend fun trash(noteId: String, now: Long)

    suspend fun restore(noteId: String, now: Long)

    /** Trashed notes, most recently deleted first. */
    fun observeTrashed(): Flow<List<Note>>
    suspend fun getTrashed(): List<Note>
    suspend fun getTrashedByFolder(folderId: String): List<Note>

    /**
     * Permanently deletes a TRASHED note; its budget nodes and reminders go with it (cascade).
     * Does nothing for an active note.
     */
    suspend fun purge(noteId: String)
}

interface BudgetRepository {
    fun observeNodes(noteId: String): Flow<List<BudgetNode>>
    fun observeRoot(noteId: String): Flow<BudgetNode?>
    suspend fun getNodes(noteId: String): List<BudgetNode>

    /** Upserts and deletes nodes in one transaction (the deleted subtree included). */
    suspend fun applyChanges(upserts: List<BudgetNode>, deletedIds: List<String>)
}

interface ReminderRepository {
    fun observeByNote(noteId: String): Flow<List<Reminder>>

    /** Every reminder, for the reminder screen (D122). */
    fun observeAll(): Flow<List<Reminder>>
    suspend fun getById(id: String): Reminder?
    suspend fun getByNoteIds(noteIds: List<String>): List<Reminder>

    /** Enabled reminders in status SCHEDULED or AWAITING_CONFIRMATION. */
    suspend fun getActive(): List<Reminder>
    suspend fun upsert(reminder: Reminder)
    suspend fun delete(id: String)
}

/** Tags and which notes have them (D109). */
interface TagRepository {
    /** Every tag, by name. */
    fun observeAll(): Flow<List<Tag>>
    suspend fun getAll(): List<Tag>

    /** noteId -> the tags of that note, by name; notes without tags are absent. */
    fun observeAssignments(): Flow<Map<String, List<Tag>>>
    suspend fun getAssignments(): Map<String, List<Tag>>

    suspend fun insert(tag: Tag, createdAt: Long)
    suspend fun rename(tagId: String, name: String)

    /** Removes the tag from every note. */
    suspend fun delete(tagId: String)

    /** Removes the tags that no note has (after a full restore). */
    suspend fun deleteUnused()

    /** Replaces the tags of the note by exactly [tagIds], in one transaction. */
    suspend fun setNoteTags(noteId: String, tagIds: Set<String>)
}
