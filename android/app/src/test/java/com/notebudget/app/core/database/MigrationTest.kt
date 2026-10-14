package com.notebudget.app.core.database

import com.notebudget.app.data.mapper.toDomain
import com.notebudget.app.data.mapper.toEntity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Version 1 is installed on real phones, so a database written by it must survive every later
 * schema. The old database is built from the exported schema file of that version
 * (`app/schemas/.../<version>.json`, kept in git), filled with data, then opened by the real
 * [AppDatabase] with the real migrations; Room itself verifies the result against the new
 * schema. Every migration gets a test like this one. Never delete them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Creates a database exactly as [version] of the app created it. */
    private fun createOldDatabase(name: String, version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = File("schemas/${AppDatabase::class.java.canonicalName}/$version.json")
        check(schema.isFile) { "Exported schema of version $version is missing: ${schema.absolutePath}" }
        val database = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in database.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                (e["indices"] as? JsonArray)?.forEach {
                    db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            val hash = database.getValue("identityHash").jsonPrimitive.content
            (database["setupQueries"] as? JsonArray)?.forEach {
                db.execSQL(it.jsonPrimitive.content.replace("\${IDENTITY_HASH}", hash))
            }
            fill(db)
            db.version = version
        }
    }

    @Test
    fun `1 to 2 keeps all rows and adds the empty extras column`() = runBlocking {
        val name = "migration-1-2.db"
        createOldDatabase(name, 1) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, 'n2', 7, 'COMPACT', 1, 2, NULL)",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, " +
                    "content_text, created_at, updated_at, deleted_at) " +
                    "VALUES ('n1', 'f1', 'Buy milk', 'LIST', NULL, 1, 0, 0, '- [ ] milk', 1, 2, NULL)",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, " +
                    "content_text, created_at, updated_at, deleted_at) " +
                    "VALUES ('n2', 'f1', 'Trip', 'BUDGET', '#FF0000', 0, 1, 1, NULL, 1, 3, NULL)",
            )
            db.execSQL(
                "INSERT INTO budget_node (id, note_id, parent_id, title, kind, manual_sum_minor, manual_goal_minor, " +
                    "comment, cached_sum_minor, cached_goal_minor, cached_remainder_minor, is_expanded, sort_order, " +
                    "created_at, updated_at) VALUES ('b1', 'n2', NULL, 'Trip', 'GROUP', 0, 0, NULL, 5000, 9000, 4000, 1, 0, 1, 1)",
            )
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val notes = db.noteDao().getEvery().sortedBy { it.id }
            assertEquals(listOf("Buy milk", "Trip"), notes.map { it.title })
            assertEquals("LIST", notes[0].type)
            assertEquals(true, notes[0].isPinned)
            assertEquals("- [ ] milk", notes[0].contentText)
            assertEquals("#FF0000", notes[1].colorHex)
            assertNull("extras starts empty", notes[0].extras)
            assertEquals(5000L, db.budgetNodeDao().getByNote("n2").single().cachedSumMinor)
            assertEquals("n2", db.folderDao().getById("f1")?.featuredNoteId)

            // The new column works after the migration.
            db.noteDao().update(notes[1].copy(extras = """{"totalAtBottom":true}"""))
            assertEquals("""{"totalAtBottom":true}""", db.noteDao().getById("n2")?.extras)
        } finally {
            db.close()
        }
    }

    @Test
    fun `2 to 3 keeps the budget and counts every existing item once`() = runBlocking {
        val name = "migration-2-3.db"
        createOldDatabase(name, 2) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL)",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, " +
                    "content_text, created_at, updated_at, deleted_at, extras) " +
                    "VALUES ('n1', 'f1', 'Trip', 'BUDGET', NULL, 0, 0, 0, NULL, 1, 1, NULL, '{\"totalAtBottom\":true}')",
            )
            db.execSQL(
                "INSERT INTO budget_node (id, note_id, parent_id, title, kind, manual_sum_minor, manual_goal_minor, " +
                    "comment, cached_sum_minor, cached_goal_minor, cached_remainder_minor, is_expanded, sort_order, " +
                    "created_at, updated_at) VALUES ('root', 'n1', NULL, 'Trip', 'GROUP', 0, 0, NULL, 1200, 2000, 800, 1, 0, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO budget_node (id, note_id, parent_id, title, kind, manual_sum_minor, manual_goal_minor, " +
                    "comment, cached_sum_minor, cached_goal_minor, cached_remainder_minor, is_expanded, sort_order, " +
                    "created_at, updated_at) VALUES ('hotel', 'n1', 'root', 'Hotel', 'LEAF', 1200, 2000, 'two nights', 1200, 2000, 800, 1, 0, 1, 1)",
            )
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val nodes = db.budgetNodeDao().getByNote("n1").associateBy { it.id }
            assertEquals(setOf("root", "hotel"), nodes.keys)
            // Nothing else changed, and every existing item counts once, as before.
            assertEquals(1, nodes.getValue("hotel").quantity)
            assertEquals(1, nodes.getValue("root").quantity)
            assertEquals(1200L, nodes.getValue("hotel").manualSumMinor)
            assertEquals(1200L, nodes.getValue("hotel").cachedSumMinor)
            assertEquals("two nights", nodes.getValue("hotel").comment)
            assertEquals("""{"totalAtBottom":true}""", db.noteDao().getById("n1")?.extras)

            // The new column is usable.
            db.budgetNodeDao().upsert(nodes.getValue("hotel").copy(quantity = 3))
            assertEquals(3, db.budgetNodeDao().getByNote("n1").single { it.id == "hotel" }.quantity)
        } finally {
            db.close()
        }
    }

    @Test
    fun `3 to 4 adds tags and keeps every note, budget row and its data`() = runBlocking {
        val name = "migration-3-4.db"
        createOldDatabase(name, 3) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL)",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, " +
                    "content_text, created_at, updated_at, deleted_at, extras) " +
                    "VALUES ('n1', 'f1', 'Trip', 'BUDGET', NULL, 0, 0, 0, NULL, 1, 1, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO budget_node (id, note_id, parent_id, title, kind, manual_sum_minor, manual_goal_minor, " +
                    "comment, cached_sum_minor, cached_goal_minor, cached_remainder_minor, is_expanded, sort_order, " +
                    "created_at, updated_at, quantity) VALUES ('b1', 'n1', NULL, 'Trip', 'GROUP', 0, 0, NULL, 5, 9, 4, 1, 0, 1, 1, 1)",
            )
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(listOf("Trip"), db.noteDao().getEvery().map { it.title })
            assertEquals(5L, db.budgetNodeDao().getByNote("n1").single().cachedSumMinor)
            assertEquals(true, db.tagDao().getAll().isEmpty()) // nobody has tags yet

            // The new tables work, with their cascades.
            db.tagDao().insert(com.notebudget.app.core.database.entity.TagEntity("t1", "Work", 1L))
            db.tagDao().insertAssignments(listOf(com.notebudget.app.core.database.entity.NoteTagEntity("n1", "t1")))
            assertEquals(1, db.tagDao().getAssignments().size)
            db.tagDao().delete("t1")
            assertEquals(true, db.tagDao().getAssignments().isEmpty())
            assertEquals(1, db.noteDao().getEvery().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `11 to 12 makes every folder a folder of notes and keeps everything else (D239)`() = runBlocking {
        val name = "migration-11-12.db"
        createOldDatabase(name, 11) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at, is_pinned, pinned_at, comment) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL, 1, 5, 'old')",
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val folder = db.folderDao().getEvery().single()
            assertEquals("NOTES", folder.kind)
            assertEquals("old", folder.comment)
            assertEquals(true, folder.isPinned)
        } finally {
            db.close()
        }
    }

    @Test
    fun `12 to 13 gives log lines an empty item and keeps them (D282)`() = runBlocking {
        val name = "migration-12-13.db"
        createOldDatabase(name, 12) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at, is_pinned, pinned_at, comment, kind) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL, 0, 0, NULL, 'NOTES')",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, content_text, " +
                    "created_at, updated_at, deleted_at, extras, pinned_at, sort_order, comment) " +
                    "VALUES ('n1', 'f1', 'Budget', 'BUDGET', NULL, 0, 0, 0, NULL, 1, 2, NULL, NULL, 0, 0, NULL)",
            )
            db.execSQL(
                "INSERT INTO budget_log (id, note_id, logged_at, kind, title, old_value, new_value, detail) " +
                    "VALUES ('l1', 'n1', 5, 'SUM', 'Bread', 0, 100, 'ADD')",
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val line = db.budgetLogDao().getAll().single()
            assertEquals("Bread", line.title)
            assertEquals(100L, line.newValue)
            assertEquals(null, line.nodeId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `13 to 14 leaves every note not hidden and lets a note be hidden (D363)`() = runBlocking {
        val name = "migration-13-14.db"
        createOldDatabase(name, 13) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at, is_pinned, pinned_at, comment, kind) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL, 0, 0, NULL, 'NOTES')",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, content_text, " +
                    "created_at, updated_at, deleted_at, extras, pinned_at, sort_order, comment) " +
                    "VALUES ('n1', 'f1', 'Letter', 'TEXT', '#FF0000', 1, 0, 1, 'a secret', 1, 2, NULL, NULL, 7, 3, 'mine')",
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val note = db.noteDao().getById("n1")!!
            assertEquals(false, note.hidden)
            // Everything else is as it was.
            assertEquals("a secret", note.contentText)
            assertEquals("#FF0000", note.colorHex)
            assertEquals(true, note.isPinned)
            assertEquals(7L, note.pinnedAt)
            assertEquals(3, note.sortOrder)
            assertEquals("mine", note.comment)
            // The new column works after the migration.
            db.noteDao().update(note.copy(hidden = true))
            assertEquals(true, db.noteDao().getById("n1")?.hidden)
        } finally {
            db.close()
        }
    }

    @Test
    fun `10 to 11 turns favourites into pins and keeps everything else`() = runBlocking {
        val name = "migration-10-11.db"
        createOldDatabase(name, 10) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at, is_pinned, pinned_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL, 0, 0)",
            )
            fun note(id: String, favourite: Int, pinned: Int, pinnedAt: Long, updated: Long) = db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, content_text, " +
                    "created_at, updated_at, deleted_at, extras, pinned_at, sort_order) " +
                    "VALUES ('$id', 'f1', '$id', 'TEXT', NULL, $pinned, $favourite, 0, 'body', 1, $updated, NULL, NULL, $pinnedAt, 0)",
            )
            note("star", favourite = 1, pinned = 0, pinnedAt = 0, updated = 500)
            note("both", favourite = 1, pinned = 1, pinnedAt = 900, updated = 100)
            note("pin", favourite = 0, pinned = 1, pinnedAt = 700, updated = 100)
            note("plain", favourite = 0, pinned = 0, pinnedAt = 0, updated = 300)
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val notes = db.noteDao().getEvery().associateBy { it.id }
            // The favourite is pinned now, at the time it was last changed; the star is gone everywhere.
            assertEquals(true, notes.getValue("star").isPinned)
            assertEquals(500L, notes.getValue("star").pinnedAt)
            assertEquals(900L, notes.getValue("both").pinnedAt) // already pinned: its own time stays
            assertEquals(700L, notes.getValue("pin").pinnedAt)
            assertEquals(false, notes.getValue("plain").isPinned)
            assertEquals(true, notes.values.none { it.isFavorite })
            assertEquals("body", notes.getValue("plain").contentText)
        } finally {
            db.close()
        }
    }

    @Test
    fun `9 to 10 gives notes and folders a comment and keeps everything else`() = runBlocking {
        val name = "migration-9-10.db"
        createOldDatabase(name, 9) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at, is_pinned, pinned_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, content_text, " +
                    "created_at, updated_at, deleted_at, extras, pinned_at, sort_order) " +
                    "VALUES ('n1', 'f1', 'Old note', 'TEXT', NULL, 0, 0, 0, 'body', 3, 4, NULL, NULL, 0, 0)",
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val folder = db.folderDao().getById("f1")!!
            assertEquals("My Notes", folder.name)
            assertEquals(null, folder.comment)
            val note = db.noteDao().getById("n1")!!
            assertEquals("body", note.contentText)
            assertEquals(null, note.comment)
            db.noteDao().update(note.copy(comment = "hello"))
            assertEquals("hello", db.noteDao().getById("n1")!!.comment)
        } finally {
            db.close()
        }
    }

    @Test
    fun `8 to 9 adds the log of budget notes and keeps everything else`() = runBlocking {
        val name = "migration-8-9.db"
        createOldDatabase(name, 8) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at, is_pinned, pinned_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL, 0, 0)",
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("My Notes", db.folderDao().getById("f1")!!.name)
            assertEquals(0, db.budgetLogDao().getAll().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `7 to 8 adds the trash of media items and keeps everything else`() = runBlocking {
        val name = "migration-7-8.db"
        createOldDatabase(name, 7) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at, is_pinned, pinned_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL, 0, 0)",
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("My Notes", db.folderDao().getById("f1")!!.name)
            assertEquals(0, db.mediaTrashDao().getAll().size)
            db.mediaTrashDao().upsert(listOf(com.notebudget.app.core.database.entity.MediaTrashEntity("n_i", "n", "PHOTO", "T", "{}", 2, "Note", 5, 99)))
            assertEquals(99L, db.mediaTrashDao().get("n_i")!!.sizeBytes)
            db.mediaTrashDao().remove(listOf("n_i"))
            assertEquals(0, db.mediaTrashDao().getAll().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `6 to 7 leaves every reminder unsnoozed`() = runBlocking {
        val name = "migration-6-7.db"
        createOldDatabase(name, 6) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at, is_pinned, pinned_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, " +
                    "content_text, created_at, updated_at, deleted_at, extras, pinned_at, sort_order) " +
                    "VALUES ('n1', 'f1', 'Trip', 'TEXT', NULL, 0, 0, 0, 'x', 1, 1, NULL, NULL, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO reminder (id, note_id, type, trigger_at, is_enabled, status, sound_enabled, sound_uri, " +
                    "vibration_enabled, popup_enabled, requires_confirmation, repeat_until_confirmed, repeat_interval_minutes, " +
                    "created_at, updated_at, volume_percent, ramp_seconds, repeat_count, repeats_done, recurrence, weekdays_mask, month_day) " +
                    "VALUES ('r1', 'n1', 'ONE_TIME', 5000, 1, 'SCHEDULED', 1, NULL, 1, 1, 0, 0, NULL, 1, 1, 100, 0, 0, 0, 'NONE', 0, 0)",
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val r = db.reminderDao().getById("r1")!!.toDomain()
            assertEquals(5000L, r.triggerAt)
            assertEquals(0L, r.snoozedUntil)
            db.reminderDao().upsert(r.copy(snoozedUntil = 9000).toEntity())
            assertEquals(9000L, db.reminderDao().getById("r1")!!.snoozedUntil)
            // A snoozed reminder counts as active, even when it has already rung.
            db.reminderDao().upsert(r.copy(status = com.notebudget.app.domain.model.ReminderStatus.FIRED, snoozedUntil = 9000).toEntity())
            assertEquals(1, db.reminderDao().getActive().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `5 to 6 keeps pinned notes in place and gives notes and folders room for order and pins`() = runBlocking {
        val name = "migration-5-6.db"
        createOldDatabase(name, 5) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL)",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, " +
                    "content_text, created_at, updated_at, deleted_at, extras) " +
                    "VALUES ('n1', 'f1', 'Pinned', 'TEXT', NULL, 1, 0, 0, 'x', 1, 77, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, " +
                    "content_text, created_at, updated_at, deleted_at, extras) " +
                    "VALUES ('n2', 'f1', 'Plain', 'TEXT', NULL, 0, 0, 0, 'y', 1, 88, NULL, NULL)",
            )
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val pinned = db.noteDao().getById("n1")!!.toDomain()
            assertEquals(true, pinned.isPinned)
            assertEquals(77L, pinned.pinnedAt)
            assertEquals(0L, db.noteDao().getById("n2")!!.toDomain().pinnedAt)
            assertEquals(0, pinned.sortOrder)
            val folder = db.folderDao().getById("f1")!!.toDomain()
            assertEquals(false, folder.isPinned)
            // The new columns take values.
            db.noteDao().updateSortOrder("n2", 5)
            assertEquals(5, db.noteDao().getById("n2")!!.sortOrder)
            db.folderDao().update(db.folderDao().getById("f1")!!.copy(isPinned = true, pinnedAt = 9))
            assertEquals(9L, db.folderDao().getById("f1")!!.pinnedAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun `4 to 5 gives every reminder the same behaviour as before and room for the new settings`() = runBlocking {
        val name = "migration-4-5.db"
        createOldDatabase(name, 4) { db ->
            db.execSQL(
                "INSERT INTO folder (id, name, color_hex, style_id, icon, sort_order, featured_note_id, " +
                    "featured_metrics_mask, featured_layout, created_at, updated_at, deleted_at) " +
                    "VALUES ('f1', 'My Notes', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 2, NULL)",
            )
            db.execSQL(
                "INSERT INTO note (id, folder_id, title, type, color_hex, is_pinned, is_favorite, is_title_bold, " +
                    "content_text, created_at, updated_at, deleted_at, extras) " +
                    "VALUES ('n1', 'f1', 'Trip', 'TEXT', NULL, 0, 0, 0, 'x', 1, 1, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO reminder (id, note_id, type, trigger_at, is_enabled, status, sound_enabled, sound_uri, " +
                    "vibration_enabled, popup_enabled, requires_confirmation, repeat_until_confirmed, repeat_interval_minutes, " +
                    "created_at, updated_at) VALUES ('r1', 'n1', 'ONE_TIME', 5000, 1, 'SCHEDULED', 1, NULL, 1, 1, 1, 1, 10, 1, 1)",
            )
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val r = db.reminderDao().getById("r1")!!.toDomain()
            assertEquals(5000L, r.triggerAt)
            assertEquals(true, r.repeatUntilConfirmed)
            assertEquals(10, r.repeatIntervalMinutes)
            assertEquals(100, r.volumePercent)
            assertEquals(0, r.rampSeconds + r.repeatCount + r.repeatsDone + r.weekdaysMask + r.monthDay)
            assertEquals(com.notebudget.app.domain.model.Recurrence.NONE, r.recurrence)
            // The new columns take values.
            db.reminderDao().upsert(r.copy(volumePercent = 30, recurrence = com.notebudget.app.domain.model.Recurrence.MONTHLY, monthDay = 15).toEntity())
            assertEquals(15, db.reminderDao().getById("r1")!!.monthDay)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the migrations form an unbroken chain from version 1 to the current version`() {
        val steps = AppDatabase.MIGRATIONS.sortedBy { it.startVersion }
        assertEquals((1 until AppDatabase.VERSION).toList(), steps.map { it.startVersion })
        assertEquals((2..AppDatabase.VERSION).toList(), steps.map { it.endVersion })
    }

    @Test
    fun `every released schema version has its exported schema file`() {
        for (version in 1..AppDatabase.VERSION) {
            val file = File("schemas/${AppDatabase::class.java.canonicalName}/$version.json")
            assertEquals("missing exported schema of version $version", true, file.isFile)
        }
    }

    @Test
    fun `a fresh install creates the same schema the migrations lead to`() = runBlocking {
        val fresh = Room.databaseBuilder(context, AppDatabase::class.java, "fresh.db")
            .addMigrations(*AppDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            // Opening validates the schema; a wrong migration would already have failed above.
            assertEquals(0, fresh.noteDao().getEvery().size)
        } finally {
            fresh.close()
        }
    }
}
