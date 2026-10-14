package com.notio.shared.data

import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.Comment
import com.notio.shared.domain.FeaturedLayout
import com.notio.shared.domain.Folder
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.Recurrence
import com.notio.shared.domain.Reminder
import com.notio.shared.domain.ReminderStatus
import com.notio.shared.domain.ReminderType
import com.notio.shared.domain.Tag
import com.notio.shared.transfer.BackupData
import com.notio.shared.transfer.BudgetLogRecord
import com.notio.shared.transfer.ImportPlan
import com.notio.shared.transfer.MediaTrashRecord
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * The desktop database: the same SQLite schema as the Android app's Room database version 11 (AGENT.md D07), so the
 * data model, its invariants and the backup code stay one and the same. Plain JDBC, one connection, every write that
 * touches several rows in a transaction.
 */
class NotioDatabase private constructor(private val connection: Connection, private val file: File? = null) : AutoCloseable {

    companion object {
        /** The Android Room schema version this layout matches. */
        const val SCHEMA_VERSION = 14

        /** [key]: the SQLCipher passphrase of an encrypted file (D46), `null` for a plain one. */
        fun open(file: File, key: String? = null): NotioDatabase {
            file.parentFile?.mkdirs()
            return create(DatabaseCipher.connect(file, key), file)
        }

        /** A database that lives in memory only (tests). */
        fun inMemory(): NotioDatabase = create(DriverManager.getConnection("jdbc:sqlite::memory:"), null)

        private fun create(connection: Connection, file: File?): NotioDatabase {
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            return NotioDatabase(connection, file).also { it.migrate() }
        }

        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `folder` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color_hex` TEXT NOT NULL, `style_id` TEXT NOT NULL, `icon` TEXT, `sort_order` INTEGER NOT NULL, `featured_note_id` TEXT, `featured_metrics_mask` INTEGER NOT NULL, `featured_layout` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `is_pinned` INTEGER NOT NULL, `pinned_at` INTEGER NOT NULL, `comment` TEXT, `kind` TEXT NOT NULL DEFAULT 'NOTES', PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `note` (`id` TEXT NOT NULL, `folder_id` TEXT NOT NULL, `title` TEXT NOT NULL, `type` TEXT NOT NULL, `color_hex` TEXT, `is_pinned` INTEGER NOT NULL, `is_favorite` INTEGER NOT NULL, `is_title_bold` INTEGER NOT NULL, `content_text` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `extras` TEXT, `pinned_at` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `comment` TEXT, `hidden` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`folder_id`) REFERENCES `folder`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
            "CREATE INDEX IF NOT EXISTS `index_note_folder_id` ON `note` (`folder_id`)",
            "CREATE TABLE IF NOT EXISTS `budget_node` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `parent_id` TEXT, `title` TEXT NOT NULL, `kind` TEXT NOT NULL, `manual_sum_minor` INTEGER NOT NULL, `manual_goal_minor` INTEGER NOT NULL, `comment` TEXT, `cached_sum_minor` INTEGER NOT NULL, `cached_goal_minor` INTEGER NOT NULL, `cached_remainder_minor` INTEGER NOT NULL, `is_expanded` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `quantity` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`), FOREIGN KEY(`note_id`) REFERENCES `note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`parent_id`) REFERENCES `budget_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_budget_node_note_id` ON `budget_node` (`note_id`)",
            "CREATE INDEX IF NOT EXISTS `index_budget_node_parent_id` ON `budget_node` (`parent_id`)",
            "CREATE TABLE IF NOT EXISTS `reminder` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `type` TEXT NOT NULL, `trigger_at` INTEGER NOT NULL, `is_enabled` INTEGER NOT NULL, `status` TEXT NOT NULL, `sound_enabled` INTEGER NOT NULL, `sound_uri` TEXT, `vibration_enabled` INTEGER NOT NULL, `popup_enabled` INTEGER NOT NULL, `requires_confirmation` INTEGER NOT NULL, `repeat_until_confirmed` INTEGER NOT NULL, `repeat_interval_minutes` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `volume_percent` INTEGER NOT NULL DEFAULT 100, `ramp_seconds` INTEGER NOT NULL DEFAULT 0, `repeat_count` INTEGER NOT NULL DEFAULT 0, `repeats_done` INTEGER NOT NULL DEFAULT 0, `recurrence` TEXT NOT NULL DEFAULT 'NONE', `weekdays_mask` INTEGER NOT NULL DEFAULT 0, `month_day` INTEGER NOT NULL DEFAULT 0, `snoozed_until` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`note_id`) REFERENCES `note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_reminder_note_id` ON `reminder` (`note_id`)",
            "CREATE INDEX IF NOT EXISTS `index_reminder_status` ON `reminder` (`status`)",
            "CREATE TABLE IF NOT EXISTS `tag` (`id` TEXT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_name` ON `tag` (`name`)",
            "CREATE TABLE IF NOT EXISTS `note_tag` (`note_id` TEXT NOT NULL, `tag_id` TEXT NOT NULL, PRIMARY KEY(`note_id`, `tag_id`), FOREIGN KEY(`note_id`) REFERENCES `note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`tag_id`) REFERENCES `tag`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_note_tag_tag_id` ON `note_tag` (`tag_id`)",
            "CREATE TABLE IF NOT EXISTS `media_trash` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `item_json` TEXT NOT NULL, `position` INTEGER NOT NULL, `note_title` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_media_trash_note_id` ON `media_trash` (`note_id`)",
            "CREATE TABLE IF NOT EXISTS `budget_log` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `logged_at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `old_value` INTEGER, `new_value` INTEGER, `detail` TEXT, `node_id` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`note_id`) REFERENCES `note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_budget_log_note_id` ON `budget_log` (`note_id`)",
        )
    }

    private fun migrate() {
        val version = query("PRAGMA user_version") { it.getInt(1) }.firstOrNull() ?: 0
        if (version > SCHEMA_VERSION) error("The database was written by a newer Notio (schema $version)")
        if (version == SCHEMA_VERSION) return
        transaction {
            if (version == 0) {
                // A fresh file: the tables of the current version at once.
                connection.createStatement().use { st -> SCHEMA.forEach(st::execute) }
            } else {
                // A file of an older schema (the phone's Room migrations, as far as the desktop was ever behind).
                // v12: a folder has a kind, notes or tasks (Android D239); every folder so far is a folder of notes.
                if (version < 12 && columnsOf("folder").none { it == "kind" }) {
                    connection.createStatement().use { it.execute("ALTER TABLE `folder` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'NOTES'") }
                }
                // v13: a line of a budget log knows its item or group (Android D282); older lines have none.
                if (version < 13 && columnsOf("budget_log").let { it.isNotEmpty() && "node_id" !in it }) {
                    connection.createStatement().use { it.execute("ALTER TABLE `budget_log` ADD COLUMN `node_id` TEXT") }
                }
                // v14: a note can hide its content until the eye is pressed (Android D363); no note hides it so far.
                if (version < 14 && columnsOf("note").let { it.isNotEmpty() && "hidden" !in it }) {
                    connection.createStatement().use { it.execute("ALTER TABLE `note` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0") }
                }
                // Any table a file might lack is made (all are CREATE ... IF NOT EXISTS).
                connection.createStatement().use { st -> SCHEMA.forEach(st::execute) }
            }
            connection.createStatement().use { it.execute("PRAGMA user_version = $SCHEMA_VERSION") }
        }
    }

    private fun columnsOf(table: String): List<String> = query("PRAGMA table_info(`$table`)") { it.getString("name") }

    /**
     * The database file as it is, plain or encrypted (the phone's "Export database file", D46): nothing is written meanwhile (every query
     * of this class waits for the lock), and the file has no journal left over (no WAL; autocommit between the calls).
     */
    @Synchronized
    fun copyFileTo(out: java.io.OutputStream) {
        val f = checkNotNull(file) { "a database in memory has no file" }
        f.inputStream().use { it.copyTo(out) }
    }

    /** Waits for a query in progress (another thread) before the connection goes. */
    @Synchronized
    override fun close() = connection.close()

    @Synchronized
    fun <T> transaction(block: () -> T): T {
        val auto = connection.autoCommit
        if (!auto) return block()
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    // ---- reading ----

    fun folders(): List<Folder> = query("SELECT * FROM folder") { it.toFolder() }

    fun notes(): List<Note> = query("SELECT * FROM note") { it.toNote() }

    fun notesOf(folderId: String): List<Note> = query("SELECT * FROM note WHERE folder_id = ?", folderId) { it.toNote() }

    fun note(id: String): Note? = query("SELECT * FROM note WHERE id = ?", id) { it.toNote() }.firstOrNull()

    fun budgetNodes(): List<BudgetNode> = query("SELECT * FROM budget_node") { it.toNode() }

    fun budgetNodesOf(noteId: String): List<BudgetNode> =
        query("SELECT * FROM budget_node WHERE note_id = ?", noteId) { it.toNode() }

    fun reminders(): List<Reminder> = query("SELECT * FROM reminder") { it.toReminder() }

    fun remindersOf(noteId: String): List<Reminder> = query("SELECT * FROM reminder WHERE note_id = ?", noteId) { it.toReminder() }

    /** Everything, trashed rows included (the Android `BackupStore.readAll`). */
    fun readAll(): BackupData = transaction { BackupData(folders(), notes(), budgetNodes(), reminders()) }

    /** Note id -> its tags. */
    fun tagAssignments(): Map<String, List<Tag>> = query(
        "SELECT nt.note_id, t.id, t.name FROM note_tag nt JOIN tag t ON t.id = nt.tag_id ORDER BY t.name COLLATE NOCASE",
    ) { it.getString(1) to Tag(it.getString(2), it.getString(3)) }.groupBy({ it.first }, { it.second })

    fun tagsOf(noteId: String): List<Tag> = query(
        "SELECT t.id, t.name FROM note_tag nt JOIN tag t ON t.id = nt.tag_id WHERE nt.note_id = ? ORDER BY t.name COLLATE NOCASE",
        noteId,
    ) { Tag(it.getString(1), it.getString(2)) }

    fun mediaTrash(): List<MediaTrashRecord> = query("SELECT * FROM media_trash") {
        MediaTrashRecord(
            it.getString("id"), it.getString("note_id"), it.getString("kind"), it.getString("title"), it.getString("item_json"),
            it.getInt("position"), it.getString("note_title"), it.getLong("deleted_at"), it.getLong("size_bytes"),
        )
    }

    fun budgetLog(): List<BudgetLogRecord> = query("SELECT * FROM budget_log") { it.toLog() }

    fun budgetLogOf(noteId: String): List<BudgetLogRecord> =
        query("SELECT * FROM budget_log WHERE note_id = ? ORDER BY logged_at", noteId) { it.toLog() }

    // ---- the assistant (D145, the branch `assistant`; docs/ASSISTANT_PLAN_RU.md) ----
    // Its two tables stand beside the notes and are made when first used. The schema version does not change: a Notio without
    // the assistant opens the same file and leaves them alone, and they are encrypted with the file. They are in no backup or sync.

    private var assistantTables = false

    private fun assistant() {
        if (assistantTables) return
        connection.createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS `assistant_access` (`id` TEXT NOT NULL, `flags` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            st.execute(
                "CREATE TABLE IF NOT EXISTS `assistant_journal` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                    "`request` TEXT NOT NULL, `body` TEXT NOT NULL, `rolled_back_at` INTEGER, PRIMARY KEY(`id`))",
            )
            // The lines of the chat, when the person keeps it (D150).
            st.execute("CREATE TABLE IF NOT EXISTS `assistant_chat` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `body` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
        assistantTables = true
    }

    /** The kept lines of the chat, the oldest first: the id and the line as the window wrote it. */
    @Synchronized
    fun assistantChat(): List<Pair<String, String>> {
        assistant()
        return query("SELECT id, body FROM assistant_chat ORDER BY at, rowid") { it.getString(1) to it.getString(2) }
    }

    /** Keeps a line of the chat (a new one, or one that changed), and only the [keep] newest lines. */
    @Synchronized
    fun putAssistantChat(id: String, at: Long, body: String, keep: Int) = transaction {
        assistant()
        upsert("assistant_chat", linkedMapOf("id" to id, "at" to at, "body" to body))
        update("DELETE FROM assistant_chat WHERE id NOT IN (SELECT id FROM assistant_chat ORDER BY at DESC, rowid DESC LIMIT ?)", keep)
    }

    /** Deletes a line of the chat; with `null`, all of them. */
    @Synchronized
    fun deleteAssistantChat(id: String?) {
        assistant()
        if (id == null) update("DELETE FROM assistant_chat") else update("DELETE FROM assistant_chat WHERE id = ?", id)
    }

    /** The id of a note or a folder -> what the assistant may do with it (`AssistantAccess`); an id without a row is closed to it. */
    @Synchronized
    fun assistantAccess(): Map<String, Int> {
        assistant()
        return query("SELECT id, flags FROM assistant_access") { it.getString(1) to it.getInt(2) }.toMap()
    }

    /** Sets the access of the notes and folders [ids]; 0 closes them (the row goes). */
    @Synchronized
    fun setAssistantAccess(ids: Collection<String>, flags: Int) = transaction {
        assistant()
        ids.forEach { id ->
            if (flags == 0) update("DELETE FROM assistant_access WHERE id = ?", id)
            else update("INSERT INTO assistant_access (id, flags) VALUES (?, ?) ON CONFLICT(`id`) DO UPDATE SET flags = excluded.flags", id, flags)
        }
    }

    /** The records of the assistant's journal, the newest first. */
    @Synchronized
    fun assistantJournal(): List<com.notio.shared.assistant.JournalRow> {
        assistant()
        return query("SELECT * FROM assistant_journal ORDER BY at DESC, rowid DESC") {
            com.notio.shared.assistant.JournalRow(
                it.getString("id"), it.getLong("at"), it.getString("source"), it.getString("request"), it.getString("body"), it.longOrNull("rolled_back_at"),
            )
        }
    }

    @Synchronized
    fun putAssistantJournal(row: com.notio.shared.assistant.JournalRow) {
        assistant()
        upsert(
            "assistant_journal",
            linkedMapOf("id" to row.id, "at" to row.at, "source" to row.source, "request" to row.request, "body" to row.body, "rolled_back_at" to row.rolledBackAt),
        )
    }

    /** Keeps the [keep] newest records of the journal; with 0 the journal is emptied. */
    @Synchronized
    fun trimAssistantJournal(keep: Int) {
        assistant()
        update("DELETE FROM assistant_journal WHERE id NOT IN (SELECT id FROM assistant_journal ORDER BY at DESC, rowid DESC LIMIT ?)", keep)
    }

    // ---- writing ----

    /** Applies an import plan in one transaction, all of it or nothing (the Android `RoomBackupStore.apply`). */
    fun apply(plan: ImportPlan) = transaction {
        if (plan.clearAll) {
            // Notes first: the note -> folder foreign key is RESTRICT. Deleting notes cascades to nodes, reminders, tags, logs.
            update("DELETE FROM note")
            update("DELETE FROM folder")
        }
        plan.folders.forEach(::upsertFolder)
        plan.notes.forEach(::upsertNote)
        plan.replaceNodesOf.forEach { update("DELETE FROM budget_node WHERE note_id = ?", it) }
        // Parents before children so the parent foreign key is always satisfied.
        val byId = plan.nodes.associateBy { it.id }
        plan.nodes.sortedBy { depthOf(it, byId) }.forEach(::upsertNode)
        plan.reminders.forEach(::upsertReminder)
    }

    private fun depthOf(node: BudgetNode, byId: Map<String, BudgetNode>): Int {
        var depth = 0
        var current = node
        while (true) {
            current = current.parentId?.let { byId[it] } ?: return depth
            if (++depth > byId.size) return depth
        }
    }

    fun upsertFolder(f: Folder) = upsert(
        "folder",
        linkedMapOf(
            "id" to f.id, "name" to f.name, "color_hex" to f.colorHex, "style_id" to f.styleId, "icon" to f.icon,
            "sort_order" to f.sortOrder, "featured_note_id" to f.featuredNoteId, "featured_metrics_mask" to f.featuredMetricsMask,
            "featured_layout" to f.featuredLayout.name, "created_at" to f.createdAt, "updated_at" to f.updatedAt,
            "deleted_at" to f.deletedAt, "is_pinned" to f.isPinned, "pinned_at" to f.pinnedAt, "comment" to f.comment,
            "kind" to f.kind.name,
        ),
    )

    fun upsertNote(n: Note) = upsert(
        "note",
        linkedMapOf(
            "id" to n.id, "folder_id" to n.folderId, "title" to n.title, "type" to n.type.id, "color_hex" to n.colorHex,
            "is_pinned" to n.isPinned, "is_favorite" to n.isFavorite, "is_title_bold" to n.isTitleBold,
            "content_text" to n.contentText, "created_at" to n.createdAt, "updated_at" to n.updatedAt,
            "deleted_at" to n.deletedAt, "extras" to n.extras, "pinned_at" to n.pinnedAt, "sort_order" to n.sortOrder,
            "comment" to n.comment, "hidden" to n.hidden,
        ),
    )

    fun upsertNode(b: BudgetNode) = upsert(
        "budget_node",
        linkedMapOf(
            "id" to b.id, "note_id" to b.noteId, "parent_id" to b.parentId, "title" to b.title, "kind" to b.kind.name,
            "manual_sum_minor" to b.manualSumMinor, "manual_goal_minor" to b.manualGoalMinor, "comment" to b.comment,
            "cached_sum_minor" to b.cachedSumMinor, "cached_goal_minor" to b.cachedGoalMinor,
            "cached_remainder_minor" to b.cachedRemainderMinor, "is_expanded" to b.isExpanded, "sort_order" to b.sortOrder,
            "created_at" to b.createdAt, "updated_at" to b.updatedAt, "quantity" to b.quantity,
        ),
    )

    fun upsertReminder(r: Reminder) = upsert(
        "reminder",
        linkedMapOf(
            "id" to r.id, "note_id" to r.noteId, "type" to r.type.name, "trigger_at" to r.triggerAt, "is_enabled" to r.isEnabled,
            "status" to r.status.name, "sound_enabled" to r.soundEnabled, "sound_uri" to r.soundUri,
            "vibration_enabled" to r.vibrationEnabled, "popup_enabled" to r.popupEnabled,
            "requires_confirmation" to r.requiresConfirmation, "repeat_until_confirmed" to r.repeatUntilConfirmed,
            "repeat_interval_minutes" to r.repeatIntervalMinutes, "created_at" to r.createdAt, "updated_at" to r.updatedAt,
            "volume_percent" to r.volumePercent, "ramp_seconds" to r.rampSeconds, "repeat_count" to r.repeatCount,
            "repeats_done" to r.repeatsDone, "recurrence" to r.recurrence.name, "weekdays_mask" to r.weekdaysMask,
            "month_day" to r.monthDay, "snoozed_until" to r.snoozedUntil,
        ),
    )

    /** Puts tags on a note by name (the Android `TagUseCases.assignByNames`): a missing tag is created, case does not matter. */
    fun assignTags(noteId: String, names: List<String>, now: Long = System.currentTimeMillis()) = transaction {
        for (raw in names) {
            val name = raw.trim().takeIf { it.isNotEmpty() } ?: continue
            val id = query("SELECT id FROM tag WHERE name = ?", name) { it.getString(1) }.firstOrNull()
                ?: UUID.randomUUID().toString().also { update("INSERT INTO tag (id, name, created_at) VALUES (?, ?, ?)", it, name, now) }
            update("INSERT OR IGNORE INTO note_tag (note_id, tag_id) VALUES (?, ?)", noteId, id)
        }
    }

    /** Removes a note for good; its budget nodes, reminders, tags and log go with it (foreign keys, cascade). */
    fun deleteNote(id: String) = update("DELETE FROM note WHERE id = ?", id)

    /** Removes a folder that holds no notes any more (the note -> folder key is RESTRICT). */
    fun deleteFolder(id: String) = update("DELETE FROM folder WHERE id = ?", id)

    fun deleteReminder(id: String) = update("DELETE FROM reminder WHERE id = ?", id)

    fun clearBudgetLog(noteId: String) = update("DELETE FROM budget_log WHERE note_id = ?", noteId)

    fun deleteNode(id: String) = update("DELETE FROM budget_node WHERE id = ?", id)

    /** Keeps the newest [keep] lines of a note's budget log (Android `BudgetLogDao.trim`). */
    fun trimBudgetLog(noteId: String, keep: Int) = update(
        "DELETE FROM budget_log WHERE note_id = ? AND id NOT IN (SELECT id FROM budget_log WHERE note_id = ? ORDER BY logged_at DESC, rowid DESC LIMIT ?)",
        noteId, noteId, keep,
    )

    fun tags(): List<Tag> = query("SELECT id, name FROM tag ORDER BY name COLLATE NOCASE") { Tag(it.getString(1), it.getString(2)) }

    fun insertTag(tag: Tag, now: Long) = update("INSERT INTO tag (id, name, created_at) VALUES (?, ?, ?)", tag.id, tag.name, now)

    fun renameTag(id: String, name: String) = update("UPDATE tag SET name = ? WHERE id = ?", name, id)

    /** Removes a tag from every note too (note_tag cascades). */
    fun deleteTag(id: String) = update("DELETE FROM tag WHERE id = ?", id)

    /** Gives the note exactly these tags. */
    fun setNoteTags(noteId: String, tagIds: Set<String>) = transaction {
        update("DELETE FROM note_tag WHERE note_id = ?", noteId)
        tagIds.forEach { update("INSERT OR IGNORE INTO note_tag (note_id, tag_id) VALUES (?, ?)", noteId, it) }
    }

    fun deleteUnusedTags() = update("DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM note_tag)")

    fun replaceMediaTrash(rows: List<MediaTrashRecord>) = transaction {
        update("DELETE FROM media_trash")
        addMediaTrash(rows)
    }

    fun addMediaTrash(rows: List<MediaTrashRecord>) = transaction {
        rows.forEach { m ->
            update(
                "INSERT OR IGNORE INTO media_trash (id, note_id, kind, title, item_json, position, note_title, deleted_at, size_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                m.id, m.noteId, m.kind, m.title, m.itemJson, m.position, m.noteTitle, m.deletedAt, m.sizeBytes,
            )
        }
    }

    fun replaceBudgetLog(rows: List<BudgetLogRecord>) = transaction {
        update("DELETE FROM budget_log")
        addBudgetLog(rows)
    }

    fun addBudgetLog(rows: List<BudgetLogRecord>) = transaction {
        rows.forEach { l ->
            update(
                "INSERT OR IGNORE INTO budget_log (id, note_id, logged_at, kind, title, old_value, new_value, detail, node_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                l.id, l.noteId, l.at, l.kind, l.title, l.oldValue, l.newValue, l.detail, l.nodeId,
            )
        }
    }

    // ---- JDBC helpers ----

    private fun upsert(table: String, values: LinkedHashMap<String, Any?>) {
        val columns = values.keys.joinToString(", ") { "`$it`" }
        val marks = values.keys.joinToString(", ") { "?" }
        // An upsert, not INSERT OR REPLACE: a replace deletes the row first and would cascade to its children.
        val set = values.keys.filter { it != "id" }.joinToString(", ") { "`$it` = excluded.`$it`" }
        update("INSERT INTO `$table` ($columns) VALUES ($marks) ON CONFLICT(`id`) DO UPDATE SET $set", *values.values.toTypedArray())
    }

    @Synchronized
    private fun update(sql: String, vararg args: Any?): Int = connection.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeUpdate()
    }

    @Synchronized
    private fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out += map(rs)
                out
            }
        }

    private fun bind(st: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, v ->
            val at = i + 1
            when (v) {
                null -> st.setNull(at, Types.NULL)
                is String -> st.setString(at, v)
                is Int -> st.setInt(at, v)
                is Long -> st.setLong(at, v)
                is Boolean -> st.setInt(at, if (v) 1 else 0)
                else -> error("Unsupported parameter ${v::class}")
            }
        }
    }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private fun ResultSet.bool(column: String): Boolean = getInt(column) != 0

    private fun ResultSet.toFolder() = Folder(
        id = getString("id"),
        name = getString("name"),
        colorHex = getString("color_hex"),
        styleId = getString("style_id"),
        icon = getString("icon"),
        sortOrder = getInt("sort_order"),
        featuredNoteId = getString("featured_note_id"),
        featuredMetricsMask = getInt("featured_metrics_mask"),
        featuredLayout = runCatching { FeaturedLayout.valueOf(getString("featured_layout")) }.getOrDefault(FeaturedLayout.COMPACT),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        deletedAt = longOrNull("deleted_at"),
        isPinned = bool("is_pinned"),
        pinnedAt = getLong("pinned_at"),
        comment = Comment.clean(getString("comment")),
        kind = com.notio.shared.domain.FolderKind.of(getString("kind")),
    )

    private fun ResultSet.toNote() = Note(
        id = getString("id"),
        folderId = getString("folder_id"),
        title = getString("title"),
        type = NoteType(getString("type")),
        colorHex = getString("color_hex"),
        isPinned = bool("is_pinned"),
        isFavorite = bool("is_favorite"),
        isTitleBold = bool("is_title_bold"),
        contentText = getString("content_text"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        deletedAt = longOrNull("deleted_at"),
        extras = getString("extras"),
        pinnedAt = getLong("pinned_at"),
        sortOrder = getInt("sort_order"),
        comment = getString("comment"),
        hidden = bool("hidden"),
    )

    private fun ResultSet.toNode() = BudgetNode(
        id = getString("id"),
        noteId = getString("note_id"),
        parentId = getString("parent_id"),
        title = getString("title"),
        kind = runCatching { BudgetNodeKind.valueOf(getString("kind")) }.getOrDefault(BudgetNodeKind.LEAF),
        manualSumMinor = getLong("manual_sum_minor"),
        manualGoalMinor = getLong("manual_goal_minor"),
        comment = getString("comment"),
        cachedSumMinor = getLong("cached_sum_minor"),
        cachedGoalMinor = getLong("cached_goal_minor"),
        cachedRemainderMinor = getLong("cached_remainder_minor"),
        isExpanded = bool("is_expanded"),
        sortOrder = getInt("sort_order"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        quantity = getInt("quantity"),
    )

    private fun ResultSet.toReminder() = Reminder(
        id = getString("id"),
        noteId = getString("note_id"),
        type = runCatching { ReminderType.valueOf(getString("type")) }.getOrDefault(ReminderType.ONE_TIME),
        triggerAt = getLong("trigger_at"),
        isEnabled = bool("is_enabled"),
        status = runCatching { ReminderStatus.valueOf(getString("status")) }.getOrDefault(ReminderStatus.SCHEDULED),
        soundEnabled = bool("sound_enabled"),
        soundUri = getString("sound_uri"),
        vibrationEnabled = bool("vibration_enabled"),
        popupEnabled = bool("popup_enabled"),
        requiresConfirmation = bool("requires_confirmation"),
        repeatUntilConfirmed = bool("repeat_until_confirmed"),
        repeatIntervalMinutes = intOrNull("repeat_interval_minutes"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        volumePercent = getInt("volume_percent"),
        rampSeconds = getInt("ramp_seconds"),
        repeatCount = getInt("repeat_count"),
        repeatsDone = getInt("repeats_done"),
        recurrence = runCatching { Recurrence.valueOf(getString("recurrence")) }.getOrDefault(Recurrence.NONE),
        weekdaysMask = getInt("weekdays_mask"),
        monthDay = getInt("month_day"),
        snoozedUntil = getLong("snoozed_until"),
    )

    private fun ResultSet.toLog() = BudgetLogRecord(
        id = getString("id"),
        noteId = getString("note_id"),
        at = getLong("logged_at"),
        kind = getString("kind"),
        title = getString("title"),
        oldValue = longOrNull("old_value"),
        newValue = longOrNull("new_value"),
        detail = getString("detail"),
        nodeId = getString("node_id"),
    )
}
