package com.notebudget.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notebudget.app.core.database.dao.BudgetNodeDao
import com.notebudget.app.core.database.dao.FolderDao
import com.notebudget.app.core.database.dao.MediaTrashDao
import com.notebudget.app.core.database.dao.NoteDao
import com.notebudget.app.core.database.dao.ReminderDao
import com.notebudget.app.core.database.dao.TagDao
import com.notebudget.app.core.database.entity.BudgetLogEntity
import com.notebudget.app.core.database.entity.BudgetNodeEntity
import com.notebudget.app.core.database.entity.FolderEntity
import com.notebudget.app.core.database.entity.MediaTrashEntity
import com.notebudget.app.core.database.entity.NoteEntity
import com.notebudget.app.core.database.entity.NoteTagEntity
import com.notebudget.app.core.database.entity.ReminderEntity
import com.notebudget.app.core.database.entity.TagEntity

/**
 * Room database, schema per AGENT_EN.md §8: `folder`, `note`, `budget_node`, `reminder`.
 * Tags, trash, list items, units, budget log/periods, drawings and accounts are not designed
 * yet (gap G01) and are added, with migrations, in the phase that needs them.
 *
 * Version 1 was installed on a real phone (2026-09-20), so from now on every schema change
 * needs a migration (kept in [MIGRATIONS], covered by `MigrationTest`) and destructive
 * migrations are forbidden (§1.2): never call `fallbackToDestructiveMigration()`.
 */
@Database(
    entities = [
        FolderEntity::class,
        NoteEntity::class,
        BudgetNodeEntity::class,
        ReminderEntity::class,
        TagEntity::class,
        NoteTagEntity::class,
        MediaTrashEntity::class,
        BudgetLogEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun noteDao(): NoteDao
    abstract fun budgetNodeDao(): BudgetNodeDao
    abstract fun reminderDao(): ReminderDao
    abstract fun tagDao(): TagDao
    abstract fun mediaTrashDao(): MediaTrashDao
    abstract fun budgetLogDao(): com.notebudget.app.core.database.dao.BudgetLogDao

    companion object {
        const val NAME = "notebudget.db"

        /** The current schema version: shown in Settings -> About, and checked against the migrations by a test. */
        const val VERSION = 14

        /** v2: type-specific note settings (`note.extras`). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE note ADD COLUMN extras TEXT")
            }
        }

        /** v3: how many times a budget item repeats (`budget_node.quantity`, D94); existing rows count once. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE budget_node ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v4: tags (D109): the tables `tag` and `note_tag`; existing notes simply have no tags. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `tag` (`id` TEXT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_name` ON `tag` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_tag` (`note_id` TEXT NOT NULL, `tag_id` TEXT NOT NULL, PRIMARY KEY(`note_id`, `tag_id`), " +
                        "FOREIGN KEY(`note_id`) REFERENCES `note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`tag_id`) REFERENCES `tag`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_tag_tag_id` ON `note_tag` (`tag_id`)")
            }
        }

        /** v5: reminders get a volume, a fade-in, extra rings and recurrence (D130); every old reminder keeps its behaviour. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reminder` ADD COLUMN `volume_percent` INTEGER NOT NULL DEFAULT 100")
                db.execSQL("ALTER TABLE `reminder` ADD COLUMN `ramp_seconds` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `reminder` ADD COLUMN `repeat_count` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `reminder` ADD COLUMN `repeats_done` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `reminder` ADD COLUMN `recurrence` TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE `reminder` ADD COLUMN `weekdays_mask` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `reminder` ADD COLUMN `month_day` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6: pinning by time and the manual order of notes, pinned folders (D136); notes pinned before keep their place. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `note` ADD COLUMN `pinned_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `note` ADD COLUMN `sort_order` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `note` SET `pinned_at` = `updated_at` WHERE `is_pinned` = 1")
                db.execSQL("ALTER TABLE `folder` ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `folder` ADD COLUMN `pinned_at` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7: reminder snooze (D150); nothing changes for what exists. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reminder` ADD COLUMN `snoozed_until` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v8: the trash of photos, videos, screen and audio recordings (D156); a new table, nothing else changes. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_trash` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `item_json` TEXT NOT NULL, `position` INTEGER NOT NULL, `note_title` TEXT NOT NULL, " +
                        "`deleted_at` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_trash_note_id` ON `media_trash` (`note_id`)")
            }
        }

        /** v9: the log of a budget note (D166); a new table, nothing else changes. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `budget_log` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `logged_at` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `old_value` INTEGER, `new_value` INTEGER, `detail` TEXT, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`note_id`) REFERENCES `note`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_budget_log_note_id` ON `budget_log` (`note_id`)")
            }
        }

        /** v10: a comment on a note and on a folder (D177); a nullable column each, nothing else changes. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `note` ADD COLUMN `comment` TEXT")
                db.execSQL("ALTER TABLE `folder` ADD COLUMN `comment` TEXT")
            }
        }

        /** v11: the star is gone, a favourite note becomes a pinned one and keeps its place near the top (D180); no column changes. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE `note` SET `is_pinned` = 1, `pinned_at` = `updated_at` WHERE `is_favorite` = 1 AND `is_pinned` = 0")
                db.execSQL("UPDATE `note` SET `is_favorite` = 0 WHERE `is_favorite` = 1")
            }
        }

        /** v12: a folder has a kind, notes or tasks (D239); every folder so far is a folder of notes. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `folder` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'NOTES'")
            }
        }

        /** v13: a line of a budget log knows its item or group (D282), so it can be undone; older lines have none. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `budget_log` ADD COLUMN `node_id` TEXT")
            }
        }

        /** v14: a note can hide its content until the eye is pressed (D363); no note hides it so far. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `note` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)

        /**
         * [passphrase] is `null` for the plain (default) database, exactly as before; when it is set (D-encryption,
         * the person turned the Settings switch on), Room opens the very same file and schema through SQLCipher's
         * `SupportOpenHelperFactory` instead of the framework's own SQLite — the schema, entities and [MIGRATIONS] do
         * not change, only what opens the file. Converting the file itself between the two is [DatabaseEncryptor]'s job,
         * done once, before this is called with the new passphrase.
         */
        fun create(context: Context, passphrase: String? = null): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .apply {
                    if (passphrase != null) {
                        SqlCipherNativeLibrary.ensureLoaded()
                        openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))
                    }
                }
                .build()
    }
}
