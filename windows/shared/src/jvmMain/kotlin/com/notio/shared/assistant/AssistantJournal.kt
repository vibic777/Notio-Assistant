package com.notio.shared.assistant

import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.DbBudgets
import com.notio.shared.data.FolderDeleteMode
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.NotioEditor
import com.notio.shared.domain.BudgetEditor
import com.notio.shared.domain.BudgetLogKind
import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.Clock
import com.notio.shared.domain.DeletedRecord
import com.notio.shared.domain.Folder
import com.notio.shared.domain.IdGenerator
import com.notio.shared.domain.Note
import com.notio.shared.transfer.FolderRecord
import com.notio.shared.transfer.NoteRecord
import com.notio.shared.transfer.TransferFormat
import com.notio.shared.transfer.toDomain
import com.notio.shared.transfer.toRecord
import kotlinx.serialization.Serializable

/** One record of the journal as the database keeps it; [body] is a [JournalBody] as JSON. */
data class JournalRow(val id: String, val at: Long, val source: String, val request: String, val body: String, val rolledBackAt: Long? = null)

/**
 * What one request changed, and how it was before (D145, D147; the plan, section 7).
 * [before] - every note the request touched, as it was before the first touch; [after] - the `updatedAt` each of them had when
 * the request ended, so that a later change by the person is noticed; [created] - the notes the request made;
 * [sorts] - folders whose sort it changed, with the sort they had (`null` - none of their own); [ops] - what was done, in words.
 * [tags] - the names of the tags a note had; [budgets] - the items of a budget as they were (a `DeletedRecord`);
 * [folders] - folders as they were, [createdFolders] - folders the request made; [reminders] - reminders it made;
 * [settings] - settings of the app with the values they had.
 */
@Serializable
data class JournalBody(
    val ops: List<String> = emptyList(),
    val before: List<NoteRecord> = emptyList(),
    val after: Map<String, Long> = emptyMap(),
    val created: List<String> = emptyList(),
    val sorts: Map<String, String?> = emptyMap(),
    val tags: Map<String, List<String>> = emptyMap(),
    val budgets: Map<String, String> = emptyMap(),
    val folders: List<FolderRecord> = emptyList(),
    val createdFolders: List<String> = emptyList(),
    val reminders: List<String> = emptyList(),
    val settings: Map<String, String?> = emptyMap(),
) {
    /** How many things the request changed, for the line under an answer. */
    val changes: Int
        get() = (before.map { it.id } + created + tags.keys + budgets.keys).toSet().size + sorts.size + folders.size + createdFolders.size + reminders.size + settings.size
}

/** A record of the journal, read. */
data class JournalEntry(val id: String, val at: Long, val source: String, val request: String, val body: JournalBody, val rolledBackAt: Long?) {
    val rolledBack: Boolean get() = rolledBackAt != null
}

/**
 * The outcome of a rollback: [done] - everything is as it was; otherwise [conflicts] names the notes the person changed after
 * the assistant (their titles), and nothing was touched. [missing] - the record is not there or was rolled back already.
 */
data class RollbackResult(val done: Boolean, val conflicts: List<String> = emptyList(), val missing: Boolean = false)

/**
 * The journal of the assistant with rollback (D145; the plan, section 7). One request of the person is one [Session] and one
 * record, however many commands it took. A thing is remembered before its first change; reading leaves no trace. A rollback puts
 * everything back as it was and moves what the request made to the trash: it destroys nothing. The assistant has no command
 * that reaches this class - only the person rolls back.
 */
class AssistantJournal(
    private val db: NotioDatabase,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
    private val host: AssistantHost = AssistantHost.None,
    /** How many records are kept; the older ones go. */
    private val keep: Int = KEEP,
) {
    /**
     * One request while it runs. [calls] counts its commands, for the limit of one request. [confirmed] - the person said yes to
     * what needs a yes (many notes at once, the sums of a budget); [needsConfirm] - a command was refused for the lack of it.
     */
    class Session internal constructor(val source: String, val request: String, val confirmed: Boolean) {
        internal val ops = ArrayList<String>()
        internal val before = LinkedHashMap<String, NoteRecord>()
        internal val created = ArrayList<String>()
        internal val sorts = LinkedHashMap<String, String?>()
        internal val tags = LinkedHashMap<String, List<String>>()
        internal val budgets = LinkedHashMap<String, String>()
        internal val folders = LinkedHashMap<String, FolderRecord>()
        internal val createdFolders = ArrayList<String>()
        internal val reminders = ArrayList<String>()
        internal val settings = LinkedHashMap<String, String?>()
        var calls = 0
            internal set
        var needsConfirm = false
            internal set

        val changed: Boolean
            get() = before.isNotEmpty() || created.isNotEmpty() || sorts.isNotEmpty() || tags.isNotEmpty() || budgets.isNotEmpty() ||
                folders.isNotEmpty() || createdFolders.isNotEmpty() || reminders.isNotEmpty() || settings.isNotEmpty()

        /** How many notes the request has changed or made so far: what the limit of one unconfirmed request counts. */
        val changedNotes: Int get() = (before.keys + created + tags.keys + budgets.keys).toSet().size
    }

    fun begin(channel: Channel, request: String, confirmed: Boolean = false): Session = Session(channel.source, request.take(REQUEST_CHARS), confirmed)

    /** Before a note is changed: how it is now, kept once per request. A note the request made itself is not kept. */
    internal fun touch(session: Session, note: Note) {
        if (note.id !in session.created) session.before.getOrPut(note.id) { note.toRecord() }
    }

    internal fun created(session: Session, noteId: String) { session.created += noteId }

    /** Before the sort of a folder is changed: the sort it has, kept once per request. */
    internal fun sortTouched(session: Session, folderId: String) {
        if (folderId !in session.sorts) session.sorts[folderId] = host.folderSort(folderId)
    }

    /** Before the tags of a note are changed: their names. */
    internal fun tagsTouched(session: Session, noteId: String) {
        if (noteId !in session.created) session.tags.getOrPut(noteId) { db.tagsOf(noteId).map { it.name } }
    }

    /** Before a budget is changed: all its items as they are. */
    internal fun budgetTouched(session: Session, noteId: String) {
        if (noteId !in session.created) session.budgets.getOrPut(noteId) { DeletedRecord.of(db.budgetNodesOf(noteId)).encode() }
    }

    internal fun folderTouched(session: Session, folder: Folder) {
        if (folder.id !in session.createdFolders) session.folders.getOrPut(folder.id) { folder.toRecord() }
    }

    internal fun createdFolder(session: Session, folderId: String) { session.createdFolders += folderId }

    internal fun createdReminder(session: Session, reminderId: String) { session.reminders += reminderId }

    /** Before a setting of the app is changed: the value it has. */
    internal fun settingTouched(session: Session, name: String) {
        if (name !in session.settings) session.settings[name] = host.setting(name)
    }

    internal fun op(session: Session, text: String) { session.ops += text }

    /** The request is over: its record, or `null` when it changed nothing (then nothing is written). */
    fun finish(session: Session): JournalEntry? {
        if (!session.changed) return null
        val after = session.before.keys.mapNotNull { id -> db.note(id)?.let { id to it.updatedAt } }.toMap()
        val body = JournalBody(
            session.ops.toList(), session.before.values.toList(), after, session.created.toList(), session.sorts.toMap(), session.tags.toMap(),
            session.budgets.toMap(), session.folders.values.toList(), session.createdFolders.toList(), session.reminders.toList(), session.settings.toMap(),
        )
        val row = JournalRow(ids.newId(), clock.now(), session.source, session.request, TransferFormat.json.encodeToString(JournalBody.serializer(), body))
        db.putAssistantJournal(row)
        db.trimAssistantJournal(keep)
        return JournalEntry(row.id, row.at, row.source, row.request, body, null)
    }

    fun entries(): List<JournalEntry> = db.assistantJournal().mapNotNull(::read)

    private fun read(row: JournalRow): JournalEntry? =
        runCatching { TransferFormat.json.decodeFromString(JournalBody.serializer(), row.body) }.getOrNull()
            ?.let { JournalEntry(row.id, row.at, row.source, row.request, it, row.rolledBackAt) }

    /**
     * Puts everything the record [entryId] changed back as it was. A note the person changed after the assistant stops the
     * rollback, which then touches nothing and names such notes - unless [force] says to overwrite them.
     */
    fun rollback(entryId: String, force: Boolean = false): RollbackResult {
        val row = db.assistantJournal().firstOrNull { it.id == entryId } ?: return RollbackResult(false, missing = true)
        val entry = read(row)?.takeIf { !it.rolledBack } ?: return RollbackResult(false, missing = true)
        val body = entry.body
        if (!force) {
            val conflicts = body.before.mapNotNull { was ->
                val now = db.note(was.id) ?: return@mapNotNull null
                now.title.takeIf { body.after[was.id] != null && now.updatedAt != body.after[was.id] }
            }
            if (conflicts.isNotEmpty()) return RollbackResult(false, conflicts)
        }
        val at = clock.now()
        val editor = NotioEditor(db, AttachmentStore(java.io.File(System.getProperty("java.io.tmpdir"), "notio-none")), clock, ids)
        db.transaction {
            // Folders first, as they were: a note goes back into its folder.
            body.folders.forEach { was -> if (db.folders().any { it.id == was.id }) db.upsertFolder(was.toDomain().copy(updatedAt = at)) }
            // As it was, with the time of now: for a sync the rollback is the newest change, so it reaches the other devices.
            // A note purged since is not made again; one whose old folder is gone for good stays in the folder it is in.
            val folders = db.folders().map { it.id }.toSet()
            body.before.forEach { was ->
                val now = db.note(was.id) ?: return@forEach
                val back = was.toDomain().copy(updatedAt = at)
                db.upsertNote(if (back.folderId in folders) back else back.copy(folderId = now.folderId))
            }
            // The tags a note had, by their names.
            body.tags.forEach { (noteId, names) ->
                if (db.note(noteId) != null) editor.setNoteTags(noteId, names.map { editor.getOrCreateTag(it).id }.toSet())
            }
            // What the request made goes to the trash, from where the person can still take it.
            body.created.forEach { id -> db.note(id)?.takeIf { it.deletedAt == null }?.let { db.upsertNote(it.copy(deletedAt = at, updatedAt = at)) } }
            db.putAssistantJournal(row.copy(rolledBackAt = at))
        }
        // A budget as it was: its own editor puts the items back, counts the totals and writes a line into the budget's log.
        body.budgets.forEach { (noteId, kept) ->
            val nodes: List<BudgetNode> = runCatching { DeletedRecord.decode("\n" + kept)?.toNodes(noteId) }.getOrNull() ?: return@forEach
            // The kept items carry no totals: they are counted anew.
            if (db.note(noteId) != null) BudgetEditor(DbBudgets(db), clock, ids, DbBudgets(db)).restoreNodes(noteId, com.notio.shared.domain.BudgetCalculator.recalculateAll(nodes), BudgetLogKind.UNDONE)
        }
        // A folder the request made goes to the trash with what is in it; the last folder of all cannot, and stays.
        body.createdFolders.forEach { id -> runCatching { editor.deleteFolder(id, FolderDeleteMode.DELETE_NOTES) } }
        body.reminders.forEach { host.removeReminder(it) }
        body.sorts.forEach { (folderId, sort) -> host.setFolderSort(folderId, sort) }
        body.settings.forEach { (name, value) -> if (value != null) host.setSetting(name, value) }
        return RollbackResult(true)
    }

    /** Empties the journal: the copies of the notes it holds go with it. */
    fun clear() = db.trimAssistantJournal(0)

    companion object {
        const val KEEP = 200
        private const val REQUEST_CHARS = 2_000
    }
}
