package com.notio.shared.data

import com.notio.shared.domain.FolderKind
import java.io.File
import java.nio.file.Files
import com.notio.shared.transfer.toDomain
import com.notio.shared.transfer.toRecord
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A database of an older desktop opens in the current schema: schema 11 gets the folder kind (Android D239's MIGRATION_11_12), schema 12
 * the item of a budget log line (Android D282's MIGRATION_12_13).
 */
class SchemaMigrationTest {
    private val dir = Files.createTempDirectory("notio-migrate").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aSchema11FileGetsTheFolderKind() {
        val file = File(dir, "notio.db")
        DriverManager.getConnection("jdbc:sqlite:" + file.path).use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE `folder` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color_hex` TEXT NOT NULL, `style_id` TEXT NOT NULL, `icon` TEXT, " +
                        "`sort_order` INTEGER NOT NULL, `featured_note_id` TEXT, `featured_metrics_mask` INTEGER NOT NULL, `featured_layout` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `is_pinned` INTEGER NOT NULL, " +
                        "`pinned_at` INTEGER NOT NULL, `comment` TEXT, PRIMARY KEY(`id`))",
                )
                st.execute("INSERT INTO folder VALUES ('f1', 'Дом', '#3366FF', 'SOFT', NULL, 0, NULL, 0, 'COMPACT', 1, 1, NULL, 0, 0, NULL)")
                st.execute("PRAGMA user_version = 11")
            }
        }
        NotioDatabase.open(file).use { db ->
            assertEquals(listOf(FolderKind.NOTES), db.folders().map { it.kind })
            // The rest of the tables are there too, and a folder of tasks is kept.
            db.upsertFolder(db.folders().first().copy(id = "f2", name = "Задачи", kind = FolderKind.TASKS))
            assertEquals(FolderKind.TASKS, db.folders().first { it.id == "f2" }.kind)
            assertEquals(0, db.notes().size)
        }
        assertEquals(NotioDatabase.SCHEMA_VERSION, versionOf(file))
    }

    @Test
    fun aSchema12LogGetsTheItemOfItsLines() {
        val file = File(dir, "notio.db")
        // A schema 12 file made by the current code, then its log table put back as it was (no `node_id`).
        val noteId = NotioDatabase.open(file).use { db ->
            val editor = NotioEditor(db, AttachmentStore(File(dir, "files")))
            editor.createNote(editor.createFolder("Дом", "#3366FF", "SOFT").id, "B", com.notio.shared.domain.NoteType.BUDGET).id
        }
        DriverManager.getConnection("jdbc:sqlite:" + file.path).use { c ->
            c.createStatement().use { st ->
                st.execute("DROP TABLE budget_log")
                st.execute(
                    "CREATE TABLE `budget_log` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `logged_at` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `old_value` INTEGER, `new_value` INTEGER, `detail` TEXT, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`note_id`) REFERENCES `note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                st.execute("INSERT INTO budget_log VALUES ('old', '$noteId', 1, 'SUM', 'Bread', 0, 100, NULL)")
                st.execute("PRAGMA user_version = 12")
            }
        }
        NotioDatabase.open(file).use { db ->
            // The old line is kept without an item; a new one keeps its item.
            db.addBudgetLog(listOf(com.notio.shared.transfer.BudgetLogRecord("new", noteId, 2, "SUM", "Bread", 100, 200, nodeId = "b1")))
            assertEquals(listOf("old" to null, "new" to "b1"), db.budgetLogOf(noteId).map { it.id to it.nodeId })
        }
        assertEquals(NotioDatabase.SCHEMA_VERSION, versionOf(file))
    }

    @Test
    fun aSchema13NoteIsNotHiddenAndCanBeHidden() {
        // D144 (Android D363): schema 14 adds `hidden` to a note; a file of schema 13 gets the column when it is opened.
        val file = File(dir, "notio.db")
        val noteId = NotioDatabase.open(file).use { db ->
            val editor = NotioEditor(db, AttachmentStore(File(dir, "files")))
            editor.createNote(editor.createFolder("Дом", "#3366FF", "SOFT").id, "Письмо", com.notio.shared.domain.NoteType.TEXT, "секрет").id
        }
        DriverManager.getConnection("jdbc:sqlite:" + file.path).use { c ->
            c.createStatement().use { st ->
                st.execute("ALTER TABLE note DROP COLUMN hidden")
                st.execute("PRAGMA user_version = 13")
            }
        }
        NotioDatabase.open(file).use { db ->
            // The note is kept as it was, not hidden; the option is then kept, and an edit of the text does not drop it.
            val note = db.note(noteId)!!
            assertEquals("секрет", note.contentText)
            assertEquals(false, note.hidden)
            val editor = NotioEditor(db, AttachmentStore(File(dir, "files")))
            assertEquals(true, editor.setNoteHidden(noteId, true).hidden)
            editor.saveNote(db.note(noteId)!!.copy(contentText = "другое", hidden = false))
            assertEquals(true, db.note(noteId)!!.hidden)
            assertEquals("другое", db.note(noteId)!!.contentText)
            // It travels with the note in a backup or a sync; a file without the field is read as not hidden.
            assertEquals(true, db.note(noteId)!!.toRecord().hidden)
            assertEquals(true, db.note(noteId)!!.toRecord().toDomain().hidden)
            val old = com.notio.shared.transfer.TransferFormat.json.decodeFromString(com.notio.shared.transfer.NoteRecord.serializer(), """{"id":"n","title":"T"}""")
            assertEquals(false, old.toDomain().hidden)
        }
        assertEquals(NotioDatabase.SCHEMA_VERSION, versionOf(file))
    }

    private fun versionOf(file: File): Int = DriverManager.getConnection("jdbc:sqlite:" + file.path).use { c ->
        c.createStatement().use { it.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }
    }
}
