package com.notebudget.app.data.repository

import androidx.room.withTransaction
import com.notebudget.app.core.database.AppDatabase
import com.notebudget.app.data.mapper.toDomain
import com.notebudget.app.data.mapper.toEntity
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.Tag
import com.notebudget.app.core.database.entity.NoteTagEntity
import com.notebudget.app.core.database.entity.TagEntity
import com.notebudget.app.domain.repository.TagRepository
import kotlinx.coroutines.flow.combine
import com.notebudget.app.domain.repository.BudgetRepository
import com.notebudget.app.domain.repository.FolderRepository
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.ReminderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomFolderRepository(private val db: AppDatabase) : FolderRepository {
    private val folders = db.folderDao()
    private val notes = db.noteDao()

    override fun observeAll(): Flow<List<Folder>> = folders.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(): List<Folder> = folders.getAll().map { it.toDomain() }

    override suspend fun getById(id: String): Folder? = folders.getById(id)?.toDomain()

    override suspend fun getByIdIncludingDeleted(id: String): Folder? =
        folders.getByIdIncludingDeleted(id)?.toDomain()

    override suspend fun count(): Int = folders.count()

    override suspend fun insert(folder: Folder) = folders.insert(folder.toEntity())

    override suspend fun update(folder: Folder) = folders.update(folder.toEntity())

    override suspend fun updateSortOrders(orders: Map<String, Int>) {
        db.withTransaction { orders.forEach { (id, order) -> folders.updateSortOrder(id, order) } }
    }

    override suspend fun trashMovingNotes(folderId: String, targetFolderId: String, now: Long) {
        db.withTransaction {
            notes.moveAll(folderId, targetFolderId, now)
            folders.markDeleted(folderId, now)
        }
    }

    override suspend fun trashWithNotes(folderId: String, now: Long) {
        db.withTransaction {
            notes.markDeletedByFolder(folderId, now)
            folders.markDeleted(folderId, now)
        }
    }

    override suspend fun restore(folderId: String, now: Long) {
        db.withTransaction {
            val deletedAt = folders.getByIdIncludingDeleted(folderId)?.deletedAt ?: return@withTransaction
            notes.markRestoredByFolder(folderId, deletedAt, now)
            folders.markRestored(folderId, now)
        }
    }

    override fun observeTrashed(): Flow<List<Folder>> = folders.observeTrashed().map { list -> list.map { it.toDomain() } }

    override suspend fun getTrashed(): List<Folder> = folders.getTrashed().map { it.toDomain() }

    override suspend fun purge(folderId: String) {
        db.withTransaction {
            notes.deleteTrashedByFolder(folderId)
            folders.deleteTrashed(folderId)
        }
    }
}

class RoomNoteRepository(private val db: AppDatabase) : NoteRepository {
    private val notes = db.noteDao()
    private val folders = db.folderDao()
    private val budgetNodes = db.budgetNodeDao()

    override fun observeAll(): Flow<List<Note>> = notes.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeByFolder(folderId: String): Flow<List<Note>> =
        notes.observeByFolder(folderId).map { list -> list.map { it.toDomain() } }

    override fun observeById(id: String): Flow<Note?> = notes.observeById(id).map { it?.toDomain() }

    override suspend fun updateSortOrders(orders: Map<String, Int>) {
        db.withTransaction { orders.forEach { (id, order) -> notes.updateSortOrder(id, order) } }
    }

    override fun observeCountsByFolder(): Flow<Map<String, Int>> =
        notes.observeCountsByFolder().map { rows -> rows.associate { it.folderId to it.noteCount } }

    override suspend fun getById(id: String): Note? = notes.getById(id)?.toDomain()

    override suspend fun getByIdIncludingDeleted(id: String): Note? =
        notes.getByIdIncludingDeleted(id)?.toDomain()

    override suspend fun getByFolder(folderId: String): List<Note> =
        notes.getByFolder(folderId).map { it.toDomain() }

    override suspend fun getAllIncludingDeleted(): List<Note> = notes.getEvery().map { it.toDomain() }

    override suspend fun getActiveByType(type: com.notebudget.app.domain.model.NoteType): List<Note> =
        notes.getActiveByType(type.id).map { it.toDomain() }

    override fun observeExtrasByType(type: com.notebudget.app.domain.model.NoteType): Flow<List<String?>> = notes.observeExtrasByType(type.id)

    override suspend fun getExtrasByType(type: com.notebudget.app.domain.model.NoteType): List<String?> = notes.getExtrasByType(type.id)

    override suspend fun insert(note: Note, budgetRoot: BudgetNode?) {
        db.withTransaction {
            notes.insert(note.toEntity())
            budgetRoot?.let { budgetNodes.upsert(it.toEntity()) }
        }
    }

    override suspend fun update(note: Note) = notes.update(note.toEntity())

    override suspend fun move(noteId: String, targetFolderId: String, now: Long) {
        db.withTransaction {
            folders.clearFeaturedNote(noteId)
            notes.updateFolder(noteId, targetFolderId, now)
        }
    }

    override suspend fun trash(noteId: String, now: Long) {
        db.withTransaction {
            folders.clearFeaturedNote(noteId)
            notes.markDeleted(noteId, now)
        }
    }

    override suspend fun restore(noteId: String, now: Long) = notes.markRestored(noteId, now)

    override fun observeTrashed(): Flow<List<Note>> = notes.observeTrashed().map { list -> list.map { it.toDomain() } }

    override suspend fun getTrashed(): List<Note> = notes.getTrashed().map { it.toDomain() }

    override suspend fun getTrashedByFolder(folderId: String): List<Note> =
        notes.getTrashedByFolder(folderId).map { it.toDomain() }

    override suspend fun purge(noteId: String) = notes.deleteTrashed(noteId)
}

class RoomBudgetRepository(private val db: AppDatabase) : BudgetRepository {
    private val nodes = db.budgetNodeDao()

    override fun observeNodes(noteId: String): Flow<List<BudgetNode>> =
        nodes.observeByNote(noteId).map { list -> list.map { it.toDomain() } }

    override fun observeRoot(noteId: String): Flow<BudgetNode?> =
        nodes.observeRoot(noteId).map { it?.toDomain() }

    override suspend fun getNodes(noteId: String): List<BudgetNode> =
        nodes.getByNote(noteId).map { it.toDomain() }

    override suspend fun applyChanges(upserts: List<BudgetNode>, deletedIds: List<String>) {
        db.withTransaction {
            // Delete first: a moved or deleted subtree must not clash with the new state.
            deletedIds.forEach { nodes.delete(it) }
            // A new node's parent is always already stored or earlier in the list.
            upserts.forEach { nodes.upsert(it.toEntity()) }
        }
    }
}

class RoomReminderRepository(db: AppDatabase) : ReminderRepository {
    private val reminders = db.reminderDao()

    override fun observeByNote(noteId: String): Flow<List<Reminder>> =
        reminders.observeByNote(noteId).map { list -> list.map { it.toDomain() } }

    override fun observeAll(): Flow<List<Reminder>> = reminders.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: String): Reminder? = reminders.getById(id)?.toDomain()

    override suspend fun getByNoteIds(noteIds: List<String>): List<Reminder> =
        if (noteIds.isEmpty()) emptyList() else reminders.getByNoteIds(noteIds).map { it.toDomain() }

    override suspend fun getActive(): List<Reminder> = reminders.getActive().map { it.toDomain() }

    override suspend fun upsert(reminder: Reminder) = reminders.upsert(reminder.toEntity())

    override suspend fun delete(id: String) = reminders.delete(id)
}

class RoomTagRepository(private val db: AppDatabase) : TagRepository {
    private val dao = db.tagDao()

    private fun TagEntity.toTag() = Tag(id, name)

    override fun observeAll(): Flow<List<Tag>> = dao.observeAll().map { list -> list.map { it.toTag() } }

    override suspend fun getAll(): List<Tag> = dao.getAll().map { it.toTag() }

    override fun observeAssignments(): Flow<Map<String, List<Tag>>> =
        combine(dao.observeAll(), dao.observeAssignments()) { tags, rows -> group(tags, rows) }

    override suspend fun getAssignments(): Map<String, List<Tag>> = group(dao.getAll(), dao.getAssignments())

    private fun group(tags: List<TagEntity>, rows: List<NoteTagEntity>): Map<String, List<Tag>> {
        val byId = tags.associateBy { it.id }
        // `tags` is already ordered by name, so each note's list is too.
        val order = tags.withIndex().associate { it.value.id to it.index }
        return rows.groupBy { it.noteId }.mapValues { (_, list) ->
            list.mapNotNull { byId[it.tagId] }.sortedBy { order.getValue(it.id) }.map { it.toTag() }
        }
    }

    override suspend fun insert(tag: Tag, createdAt: Long) = dao.insert(TagEntity(tag.id, tag.name, createdAt))

    override suspend fun rename(tagId: String, name: String) = dao.rename(tagId, name)

    override suspend fun delete(tagId: String) = dao.delete(tagId)

    override suspend fun deleteUnused() = dao.deleteUnused()

    override suspend fun setNoteTags(noteId: String, tagIds: Set<String>) {
        db.withTransaction {
            dao.clearNote(noteId)
            dao.insertAssignments(tagIds.map { NoteTagEntity(noteId, it) })
        }
    }
}

class RoomMediaTrashRepository(private val db: AppDatabase) : com.notebudget.app.domain.model.MediaTrashRepository {
    private val dao = db.mediaTrashDao()

    private fun com.notebudget.app.core.database.entity.MediaTrashEntity.toDomain() = com.notebudget.app.domain.model.TrashedMedia(
        id, noteId, kind, title, itemJson, position, noteTitle, deletedAt, sizeBytes,
    )

    private fun com.notebudget.app.domain.model.TrashedMedia.toEntity() = com.notebudget.app.core.database.entity.MediaTrashEntity(
        id, noteId, kind, title, itemJson, position, noteTitle, deletedAt, sizeBytes,
    )

    override fun observeAll(): Flow<List<com.notebudget.app.domain.model.TrashedMedia>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun getAll() = dao.getAll().map { it.toDomain() }
    override suspend fun get(id: String) = dao.get(id)?.toDomain()
    override suspend fun upsert(items: List<com.notebudget.app.domain.model.TrashedMedia>) = dao.upsert(items.map { it.toEntity() })
    override suspend fun remove(ids: Collection<String>) { if (ids.isNotEmpty()) dao.remove(ids.toList()) }
    override suspend fun replaceAll(items: List<com.notebudget.app.domain.model.TrashedMedia>) {
        db.withTransaction { dao.deleteAll(); dao.upsert(items.map { it.toEntity() }) }
    }
}

class RoomBudgetLogRepository(private val db: AppDatabase) : com.notebudget.app.domain.model.BudgetLogRepository {
    private val dao = db.budgetLogDao()

    private fun com.notebudget.app.core.database.entity.BudgetLogEntity.toDomain() = com.notebudget.app.domain.model.BudgetLogEntry(
        id, noteId, at, kind, title, oldValue, newValue, detail, nodeId,
    )

    private fun com.notebudget.app.domain.model.BudgetLogEntry.toEntity() = com.notebudget.app.core.database.entity.BudgetLogEntity(
        id, noteId, at, kind, title, oldValue, newValue, detail, nodeId,
    )

    override fun observe(noteId: String) = dao.observe(noteId).map { list -> list.map { it.toDomain() } }
    override suspend fun getByNote(noteId: String) = dao.getByNote(noteId).map { it.toDomain() }
    override suspend fun getAll() = dao.getAll().map { it.toDomain() }

    override suspend fun add(entries: List<com.notebudget.app.domain.model.BudgetLogEntry>) {
        if (entries.isEmpty()) return
        db.withTransaction {
            dao.upsert(entries.map { it.toEntity() })
            entries.map { it.noteId }.distinct().forEach { dao.trim(it, com.notebudget.app.domain.model.BudgetLogKind.MAX_PER_NOTE) }
        }
    }

    override suspend fun clear(noteId: String) = dao.clear(noteId)

    override suspend fun replaceAll(entries: List<com.notebudget.app.domain.model.BudgetLogEntry>) {
        db.withTransaction { dao.deleteAll(); dao.upsert(entries.map { it.toEntity() }) }
    }
}
