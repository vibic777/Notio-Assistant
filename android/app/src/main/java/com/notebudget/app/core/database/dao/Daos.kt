package com.notebudget.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.notebudget.app.core.database.entity.BudgetLogEntity
import com.notebudget.app.core.database.entity.BudgetNodeEntity
import com.notebudget.app.core.database.entity.FolderEntity
import com.notebudget.app.core.database.entity.MediaTrashEntity
import com.notebudget.app.core.database.entity.NoteEntity
import com.notebudget.app.core.database.entity.NoteTagEntity
import com.notebudget.app.core.database.entity.TagEntity
import com.notebudget.app.core.database.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

/** Row of the per-folder note count query. */
data class FolderNoteCount(
    val folderId: String,
    val noteCount: Int,
)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folder WHERE deleted_at IS NULL ORDER BY sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folder WHERE deleted_at IS NULL ORDER BY sort_order ASC, created_at ASC")
    suspend fun getAll(): List<FolderEntity>

    /** Active (not trashed) folder only. */
    @Query("SELECT * FROM folder WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): FolderEntity?

    /** Includes trashed folders (used to restore). */
    @Query("SELECT * FROM folder WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): FolderEntity?

    @Query("SELECT COUNT(*) FROM folder WHERE deleted_at IS NULL")
    suspend fun count(): Int

    @Insert
    suspend fun insert(folder: FolderEntity)

    /** Every folder, trashed included (full backup). */
    @Query("SELECT * FROM folder")
    suspend fun getEvery(): List<FolderEntity>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Query("DELETE FROM folder")
    suspend fun deleteAll()

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("UPDATE folder SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("UPDATE folder SET featured_note_id = NULL, featured_metrics_mask = 0 WHERE featured_note_id = :noteId")
    suspend fun clearFeaturedNote(noteId: String)

    @Query("UPDATE folder SET deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long)

    @Query("UPDATE folder SET deleted_at = NULL, updated_at = :now WHERE id = :id")
    suspend fun markRestored(id: String, now: Long)

    @Query("SELECT * FROM folder WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeTrashed(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folder WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    suspend fun getTrashed(): List<FolderEntity>

    /** Only a trashed folder; its notes must be gone first (RESTRICT). */
    @Query("DELETE FROM folder WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun deleteTrashed(id: String)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM note WHERE deleted_at IS NULL")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE folder_id = :folderId AND deleted_at IS NULL")
    fun observeByFolder(folderId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query(
        "SELECT folder_id AS folderId, COUNT(*) AS noteCount FROM note " +
            "WHERE deleted_at IS NULL GROUP BY folder_id",
    )
    fun observeCountsByFolder(): Flow<List<FolderNoteCount>>

    /** Active (not trashed) note only. */
    @Query("SELECT * FROM note WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): NoteEntity?

    /** Includes trashed notes (used to restore). */
    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): NoteEntity?

    @Query("SELECT * FROM note WHERE folder_id = :folderId AND deleted_at IS NULL")
    suspend fun getByFolder(folderId: String): List<NoteEntity>

    @Insert
    suspend fun insert(note: NoteEntity)

    @Query("UPDATE note SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    /** Every note, trashed included (full backup). */
    @Query("SELECT * FROM note")
    suspend fun getEvery(): List<NoteEntity>

    /** The active notes of one kind (D331): what a start for an alarm needs of the budgets, without reading every note. */
    @Query("SELECT * FROM note WHERE deleted_at IS NULL AND type = :type")
    suspend fun getActiveByType(type: String): List<NoteEntity>

    /** Only the settings of the active notes of one kind (D331): the tasks are counted from them, their texts are not read. */
    @Query("SELECT COALESCE(extras, '') FROM note WHERE deleted_at IS NULL AND type = :type")
    fun observeExtrasByType(type: String): Flow<List<String>>

    @Query("SELECT COALESCE(extras, '') FROM note WHERE deleted_at IS NULL AND type = :type")
    suspend fun getExtrasByType(type: String): List<String>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    /** Cascades to the budget nodes and reminders of the notes. */
    @Query("DELETE FROM note")
    suspend fun deleteAll()

    @Update
    suspend fun update(note: NoteEntity)

    @Query("UPDATE note SET folder_id = :targetFolderId, updated_at = :now WHERE id = :noteId")
    suspend fun updateFolder(noteId: String, targetFolderId: String, now: Long)

    /** Moves active AND trashed notes, so nothing keeps pointing at a trashed folder. */
    @Query("UPDATE note SET folder_id = :targetFolderId, updated_at = :now WHERE folder_id = :fromFolderId")
    suspend fun moveAll(fromFolderId: String, targetFolderId: String, now: Long)

    @Query("UPDATE note SET deleted_at = :now, updated_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun markDeleted(id: String, now: Long)

    /** Trashes the still-active notes of a folder with the same timestamp as the folder. */
    @Query("UPDATE note SET deleted_at = :now, updated_at = :now WHERE folder_id = :folderId AND deleted_at IS NULL")
    suspend fun markDeletedByFolder(folderId: String, now: Long)

    @Query("UPDATE note SET deleted_at = NULL, updated_at = :now WHERE id = :id")
    suspend fun markRestored(id: String, now: Long)

    /** Restores the notes that were trashed together with the folder (same timestamp). */
    @Query("UPDATE note SET deleted_at = NULL, updated_at = :now WHERE folder_id = :folderId AND deleted_at = :deletedAt")
    suspend fun markRestoredByFolder(folderId: String, deletedAt: Long, now: Long)

    @Query("SELECT * FROM note WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeTrashed(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    suspend fun getTrashed(): List<NoteEntity>

    @Query("SELECT * FROM note WHERE folder_id = :folderId AND deleted_at IS NOT NULL")
    suspend fun getTrashedByFolder(folderId: String): List<NoteEntity>

    /** Only a trashed note; budget nodes and reminders follow by ON DELETE CASCADE. */
    @Query("DELETE FROM note WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun deleteTrashed(id: String)

    @Query("DELETE FROM note WHERE folder_id = :folderId AND deleted_at IS NOT NULL")
    suspend fun deleteTrashedByFolder(folderId: String)
}

@Dao
interface BudgetNodeDao {
    @Query("SELECT * FROM budget_node WHERE note_id = :noteId ORDER BY sort_order ASC, created_at ASC")
    fun observeByNote(noteId: String): Flow<List<BudgetNodeEntity>>

    @Query("SELECT * FROM budget_node WHERE note_id = :noteId AND parent_id IS NULL LIMIT 1")
    fun observeRoot(noteId: String): Flow<BudgetNodeEntity?>

    @Query("SELECT * FROM budget_node WHERE note_id = :noteId ORDER BY sort_order ASC, created_at ASC")
    suspend fun getByNote(noteId: String): List<BudgetNodeEntity>

    @Upsert
    suspend fun upsert(node: BudgetNodeEntity)

    @Query("SELECT * FROM budget_node")
    suspend fun getEvery(): List<BudgetNodeEntity>

    @Query("DELETE FROM budget_node WHERE note_id = :noteId")
    suspend fun deleteByNote(noteId: String)

    @Query("DELETE FROM budget_node WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder WHERE note_id = :noteId ORDER BY trigger_at ASC")
    fun observeByNote(noteId: String): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminder ORDER BY trigger_at ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun getById(id: String): ReminderEntity?

    @Query("SELECT * FROM reminder WHERE note_id IN (:noteIds)")
    suspend fun getByNoteIds(noteIds: List<String>): List<ReminderEntity>

    /** Reminders of trashed notes are not active. */
    @Query(
        "SELECT reminder.* FROM reminder INNER JOIN note ON note.id = reminder.note_id " +
            "WHERE reminder.is_enabled = 1 AND note.deleted_at IS NULL " +
            "AND (reminder.status IN ('SCHEDULED', 'AWAITING_CONFIRMATION') OR reminder.snoozed_until > 0)",
    )
    suspend fun getActive(): List<ReminderEntity>

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Query("SELECT * FROM reminder")
    suspend fun getEvery(): List<ReminderEntity>

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tag ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM note_tag")
    fun observeAssignments(): Flow<List<NoteTagEntity>>

    @Query("SELECT * FROM note_tag")
    suspend fun getAssignments(): List<NoteTagEntity>

    @Insert
    suspend fun insert(tag: TagEntity)

    @Query("UPDATE tag SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    /** The rows of `note_tag` follow by ON DELETE CASCADE. */
    @Query("DELETE FROM tag WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM note_tag)")
    suspend fun deleteUnused()

    @Query("DELETE FROM note_tag WHERE note_id = :noteId")
    suspend fun clearNote(noteId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssignments(rows: List<NoteTagEntity>)
}

@Dao
interface MediaTrashDao {
    @Query("SELECT * FROM media_trash ORDER BY deleted_at DESC")
    fun observeAll(): Flow<List<MediaTrashEntity>>

    @Query("SELECT * FROM media_trash ORDER BY deleted_at DESC")
    suspend fun getAll(): List<MediaTrashEntity>

    @Query("SELECT * FROM media_trash WHERE id = :id")
    suspend fun get(id: String): MediaTrashEntity?

    @Upsert
    suspend fun upsert(items: List<MediaTrashEntity>)

    @Query("DELETE FROM media_trash WHERE id IN (:ids)")
    suspend fun remove(ids: List<String>)

    @Query("DELETE FROM media_trash")
    suspend fun deleteAll()
}

@Dao
interface BudgetLogDao {
    @Query("SELECT * FROM budget_log WHERE note_id = :noteId ORDER BY logged_at DESC, rowid DESC")
    fun observe(noteId: String): Flow<List<BudgetLogEntity>>

    @Query("SELECT * FROM budget_log WHERE note_id = :noteId ORDER BY logged_at DESC, rowid DESC")
    suspend fun getByNote(noteId: String): List<BudgetLogEntity>

    @Query("SELECT * FROM budget_log ORDER BY logged_at ASC, rowid ASC")
    suspend fun getAll(): List<BudgetLogEntity>

    @Upsert
    suspend fun upsert(items: List<BudgetLogEntity>)

    @Query("DELETE FROM budget_log WHERE note_id = :noteId")
    suspend fun clear(noteId: String)

    @Query("DELETE FROM budget_log")
    suspend fun deleteAll()

    /** Keeps the newest [keep] lines of a note and drops the older ones. */
    @Query(
        "DELETE FROM budget_log WHERE note_id = :noteId AND id NOT IN " +
            "(SELECT id FROM budget_log WHERE note_id = :noteId ORDER BY logged_at DESC, rowid DESC LIMIT :keep)",
    )
    suspend fun trim(noteId: String, keep: Int)
}
