package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.Clock
import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.CustomUnit
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.Tag
import com.notebudget.app.domain.repository.TagRepository
import com.notebudget.app.domain.repository.BudgetRepository
import com.notebudget.app.domain.repository.FolderRepository
import com.notebudget.app.domain.repository.NoteDataCleaner
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.ReminderRepository
import com.notebudget.app.domain.repository.ReminderScheduler
import com.notebudget.app.domain.repository.UnitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory database shared by the fake repositories; mimics the FK rules of the Room schema. */
class FakeStore {
    val folders = MutableStateFlow<List<Folder>>(emptyList())
    val notes = MutableStateFlow<List<Note>>(emptyList())
    val nodes = MutableStateFlow<List<BudgetNode>>(emptyList())
    val reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val tags = MutableStateFlow<List<Tag>>(emptyList())

    /** (noteId, tagId) pairs: what the `note_tag` table holds. */
    val noteTags = MutableStateFlow<Set<Pair<String, String>>>(emptySet())

    fun noteIsActive(noteId: String): Boolean = notes.value.any { it.id == noteId && it.deletedAt == null }

    /** What ON DELETE CASCADE does when a note row is deleted. */
    fun deleteNotes(ids: Set<String>) {
        notes.value = notes.value.filterNot { it.id in ids }
        nodes.value = nodes.value.filterNot { it.noteId in ids }
        reminders.value = reminders.value.filterNot { it.noteId in ids }
        noteTags.value = noteTags.value.filterNot { it.first in ids }.toSet()
    }

    /** Clears folder links to the note (what the Room repository does in the same transaction). */
    fun clearFeatured(noteId: String) {
        folders.value = folders.value.map {
            if (it.featuredNoteId == noteId) it.copy(featuredNoteId = null, featuredMetricsMask = 0) else it
        }
    }
}

class FakeFolderRepository(private val store: FakeStore) : FolderRepository {
    private fun active() = store.folders.value.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<Folder>> = store.folders.map { l -> l.filter { it.deletedAt == null } }
    override suspend fun getAll(): List<Folder> = active().sortedBy { it.sortOrder }
    override suspend fun getById(id: String): Folder? = active().find { it.id == id }
    override suspend fun getByIdIncludingDeleted(id: String): Folder? = store.folders.value.find { it.id == id }
    override suspend fun count(): Int = active().size
    override suspend fun insert(folder: Folder) {
        store.folders.value = store.folders.value + folder
    }

    override suspend fun update(folder: Folder) {
        store.folders.value = store.folders.value.map { if (it.id == folder.id) folder else it }
    }

    override suspend fun updateSortOrders(orders: Map<String, Int>) {
        store.folders.value = store.folders.value.map { f -> orders[f.id]?.let { f.copy(sortOrder = it) } ?: f }
    }

    override suspend fun trashMovingNotes(folderId: String, targetFolderId: String, now: Long) {
        store.notes.value = store.notes.value.map {
            if (it.folderId == folderId) it.copy(folderId = targetFolderId, updatedAt = now) else it
        }
        store.folders.value = store.folders.value.map {
            if (it.id == folderId) it.copy(deletedAt = now, updatedAt = now) else it
        }
    }

    override suspend fun trashWithNotes(folderId: String, now: Long) {
        store.notes.value = store.notes.value.map {
            if (it.folderId == folderId && it.deletedAt == null) it.copy(deletedAt = now, updatedAt = now) else it
        }
        store.folders.value = store.folders.value.map {
            if (it.id == folderId) it.copy(deletedAt = now, updatedAt = now) else it
        }
    }

    override suspend fun restore(folderId: String, now: Long) {
        val deletedAt = store.folders.value.find { it.id == folderId }?.deletedAt ?: return
        store.notes.value = store.notes.value.map {
            if (it.folderId == folderId && it.deletedAt == deletedAt) it.copy(deletedAt = null, updatedAt = now) else it
        }
        store.folders.value = store.folders.value.map {
            if (it.id == folderId) it.copy(deletedAt = null, updatedAt = now) else it
        }
    }

    override fun observeTrashed(): Flow<List<Folder>> =
        store.folders.map { l -> l.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt } }

    override suspend fun getTrashed(): List<Folder> =
        store.folders.value.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }

    override suspend fun purge(folderId: String) {
        if (store.folders.value.none { it.id == folderId && it.deletedAt != null }) return
        store.deleteNotes(store.notes.value.filter { it.folderId == folderId && it.deletedAt != null }.map { it.id }.toSet())
        // Like the RESTRICT foreign key: a folder that still has notes cannot be deleted.
        check(store.notes.value.none { it.folderId == folderId }) { "folder still has notes" }
        store.folders.value = store.folders.value.filterNot { it.id == folderId }
    }
}

class FakeNoteRepository(private val store: FakeStore) : NoteRepository {
    private fun active() = store.notes.value.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<Note>> = store.notes.map { l -> l.filter { it.deletedAt == null } }
    override fun observeByFolder(folderId: String): Flow<List<Note>> =
        store.notes.map { list -> list.filter { it.folderId == folderId && it.deletedAt == null } }

    override fun observeById(id: String): Flow<Note?> =
        store.notes.map { list -> list.find { it.id == id && it.deletedAt == null } }

    override fun observeCountsByFolder(): Flow<Map<String, Int>> =
        store.notes.map { list -> list.filter { it.deletedAt == null }.groupingBy { it.folderId }.eachCount() }

    override suspend fun updateSortOrders(orders: Map<String, Int>) {
        store.notes.value = store.notes.value.map { n -> orders[n.id]?.let { n.copy(sortOrder = it) } ?: n }
    }

    override suspend fun getById(id: String): Note? = active().find { it.id == id }
    override suspend fun getByIdIncludingDeleted(id: String): Note? = store.notes.value.find { it.id == id }
    override suspend fun getByFolder(folderId: String): List<Note> = active().filter { it.folderId == folderId }
    override suspend fun getAllIncludingDeleted(): List<Note> = store.notes.value

    override suspend fun insert(note: Note, budgetRoot: BudgetNode?) {
        store.notes.value = store.notes.value + note
        budgetRoot?.let { store.nodes.value = store.nodes.value + it }
    }

    override suspend fun update(note: Note) {
        store.notes.value = store.notes.value.map { if (it.id == note.id) note else it }
    }

    override suspend fun move(noteId: String, targetFolderId: String, now: Long) {
        store.clearFeatured(noteId)
        store.notes.value = store.notes.value.map {
            if (it.id == noteId) it.copy(folderId = targetFolderId, updatedAt = now) else it
        }
    }

    override suspend fun trash(noteId: String, now: Long) {
        store.clearFeatured(noteId)
        store.notes.value = store.notes.value.map {
            if (it.id == noteId && it.deletedAt == null) it.copy(deletedAt = now, updatedAt = now) else it
        }
    }

    override suspend fun restore(noteId: String, now: Long) {
        store.notes.value = store.notes.value.map {
            if (it.id == noteId) it.copy(deletedAt = null, updatedAt = now) else it
        }
    }

    override fun observeTrashed(): Flow<List<Note>> =
        store.notes.map { l -> l.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt } }

    override suspend fun getTrashed(): List<Note> =
        store.notes.value.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }

    override suspend fun getTrashedByFolder(folderId: String): List<Note> =
        store.notes.value.filter { it.folderId == folderId && it.deletedAt != null }

    override suspend fun purge(noteId: String) {
        if (store.notes.value.any { it.id == noteId && it.deletedAt != null }) store.deleteNotes(setOf(noteId))
    }
}

class FakeBudgetRepository(private val store: FakeStore) : BudgetRepository {
    override fun observeNodes(noteId: String): Flow<List<BudgetNode>> =
        store.nodes.map { list -> list.filter { it.noteId == noteId } }

    override fun observeRoot(noteId: String): Flow<BudgetNode?> =
        store.nodes.map { list -> list.find { it.noteId == noteId && it.parentId == null } }

    override suspend fun getNodes(noteId: String): List<BudgetNode> = store.nodes.value.filter { it.noteId == noteId }

    override suspend fun applyChanges(upserts: List<BudgetNode>, deletedIds: List<String>) {
        val byId = store.nodes.value.filterNot { it.id in deletedIds }.associateBy { it.id }.toMutableMap()
        upserts.forEach { byId[it.id] = it }
        store.nodes.value = byId.values.toList()
    }
}

class FakeReminderRepository(private val store: FakeStore) : ReminderRepository {
    override fun observeByNote(noteId: String): Flow<List<Reminder>> =
        store.reminders.map { list -> list.filter { it.noteId == noteId } }

    override fun observeAll(): Flow<List<Reminder>> = store.reminders

    override suspend fun getById(id: String): Reminder? = store.reminders.value.find { it.id == id }
    override suspend fun getByNoteIds(noteIds: List<String>): List<Reminder> =
        store.reminders.value.filter { it.noteId in noteIds }

    override suspend fun getActive(): List<Reminder> = store.reminders.value.filter {
        it.isEnabled && store.noteIsActive(it.noteId) &&
            (it.status.name == "SCHEDULED" || it.status.name == "AWAITING_CONFIRMATION" || it.snoozedUntil > 0)
    }

    override suspend fun upsert(reminder: Reminder) {
        store.reminders.value = store.reminders.value.filterNot { it.id == reminder.id } + reminder
    }

    override suspend fun delete(id: String) {
        store.reminders.value = store.reminders.value.filterNot { it.id == id }
    }
}

class FakeUnitRepository(initial: List<CustomUnit> = emptyList()) : UnitRepository {
    val state = MutableStateFlow(initial)
    override fun observeCustom(): Flow<List<CustomUnit>> = state
    override suspend fun getCustom(): List<CustomUnit> = state.value
    override suspend fun setCustom(units: List<CustomUnit>) {
        state.value = units
    }
}

class FakeTagRepository(private val store: FakeStore) : TagRepository {
    private fun sorted() = store.tags.value.sortedBy { it.name.lowercase() }

    override fun observeAll(): Flow<List<Tag>> = store.tags.map { list -> list.sortedBy { it.name.lowercase() } }
    override suspend fun getAll(): List<Tag> = sorted()

    private fun group(): Map<String, List<Tag>> {
        val order = sorted().withIndex().associate { it.value.id to it.index }
        val byId = store.tags.value.associateBy { it.id }
        return store.noteTags.value.groupBy({ it.first }, { it.second }).mapValues { (_, ids) ->
            ids.mapNotNull { byId[it] }.sortedBy { order.getValue(it.id) }
        }
    }

    override fun observeAssignments(): Flow<Map<String, List<Tag>>> =
        kotlinx.coroutines.flow.combine(store.tags, store.noteTags) { _, _ -> group() }

    override suspend fun getAssignments(): Map<String, List<Tag>> = group()

    override suspend fun insert(tag: Tag, createdAt: Long) {
        store.tags.value = store.tags.value + tag
    }

    override suspend fun rename(tagId: String, name: String) {
        store.tags.value = store.tags.value.map { if (it.id == tagId) it.copy(name = name) else it }
    }

    override suspend fun delete(tagId: String) {
        store.tags.value = store.tags.value.filterNot { it.id == tagId }
        store.noteTags.value = store.noteTags.value.filterNot { it.second == tagId }.toSet()
    }

    override suspend fun deleteUnused() {
        val used = store.noteTags.value.map { it.second }.toSet()
        store.tags.value = store.tags.value.filter { it.id in used }
    }

    override suspend fun setNoteTags(noteId: String, tagIds: Set<String>) {
        store.noteTags.value = store.noteTags.value.filterNot { it.first == noteId }.toSet() + tagIds.map { noteId to it }
    }
}

class FakeScheduler : ReminderScheduler {
    /** reminderId -> scheduled trigger time. */
    val alarms = mutableMapOf<String, Long>()
    override fun schedule(reminderId: String, triggerAtMillis: Long) {
        alarms[reminderId] = triggerAtMillis
    }

    override fun cancel(reminderId: String) {
        alarms.remove(reminderId)
    }

    override fun canScheduleExact(): Boolean = true
}

class TestClock(var current: Long = 1_000_000L) : Clock {
    override fun now(): Long = current
}

class SequentialIds : IdGenerator {
    private var next = 1
    override fun newId(): String = "id-${next++}"
}

/** Wires everything against one [FakeStore]. */
class TestEnv {
    val store = FakeStore()
    val clock = TestClock()
    val ids = SequentialIds()
    val scheduler = FakeScheduler()
    val folderRepo = FakeFolderRepository(store)
    val noteRepo = FakeNoteRepository(store)
    val budgetRepo = FakeBudgetRepository(store)
    val reminderRepo = FakeReminderRepository(store)

    val folders = FolderUseCases(folderRepo, noteRepo, reminderRepo, scheduler, clock, ids)
    val notes = NoteUseCases(noteRepo, folderRepo, reminderRepo, scheduler, clock, ids)
    val logRepo = FakeBudgetLogRepository()
    val budget = BudgetEditor(budgetRepo, clock, ids, logRepo)
    val reminders = ReminderUseCases(reminderRepo, noteRepo, scheduler, clock, ids)

    val tagRepo = FakeTagRepository(store)
    val tags = TagUseCases(tagRepo, noteRepo, ids, clock)

    val unitRepo = FakeUnitRepository()
    val units = UnitUseCases(unitRepo, noteRepo, ids)

    /** Notes whose files the trash asked to delete. */
    val cleaned = mutableListOf<Note>()
    val trash = TrashUseCases(folderRepo, noteRepo, reminderRepo, scheduler, NoteDataCleaner { cleaned += it }, clock)
}

/** The logs of budget notes in memory (D166); `add` keeps the order the lines came in, newest last. */
class FakeBudgetLogRepository : com.notebudget.app.domain.model.BudgetLogRepository {
    val rows = kotlinx.coroutines.flow.MutableStateFlow<List<com.notebudget.app.domain.model.BudgetLogEntry>>(emptyList())

    override fun observe(noteId: String) = rows.map { list -> list.filter { it.noteId == noteId }.asReversed() }
    override suspend fun getByNote(noteId: String) = rows.value.filter { it.noteId == noteId }.asReversed()
    override suspend fun getAll() = rows.value
    override suspend fun add(entries: List<com.notebudget.app.domain.model.BudgetLogEntry>) {
        rows.value = rows.value.filterNot { r -> entries.any { it.id == r.id } } + entries
    }
    override suspend fun clear(noteId: String) { rows.value = rows.value.filterNot { it.noteId == noteId } }
    override suspend fun replaceAll(entries: List<com.notebudget.app.domain.model.BudgetLogEntry>) { rows.value = entries }
}
