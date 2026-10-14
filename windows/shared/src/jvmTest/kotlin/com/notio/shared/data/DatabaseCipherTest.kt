package com.notio.shared.data

import com.notio.shared.sync.SecretBox
import com.notio.shared.transfer.BackupService
import com.notio.shared.transfer.DemoBackup
import com.notio.shared.transfer.ImportMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Database encryption as on the phone (Android D184, D186 - D190; AGENT.md D46): conversions at the start, the key record, raw import. */
class DatabaseCipherTest {
    private val dirs = ArrayList<File>()

    @AfterTest
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun home(): AppPaths = AppPaths(Files.createTempDirectory("notio-test").toFile().also { dirs += it })

    private fun keys(paths: AppPaths) = DatabaseKeyStore(File(paths.root, "database-key.json"), SecretBox.Plain)

    /** A database with the demo data (8 notes), closed. */
    private fun withDemo(paths: AppPaths, key: String? = null) {
        NotioDatabase.open(paths.database, key).use { db ->
            val bytes = ByteArrayOutputStream().also { DemoBackup.write(it, 1_700_000_000_000) }.toByteArray()
            BackupService(db, AttachmentStore(File(paths.root, "attachments")), SettingsStore(File(paths.root, "settings.json")))
                .importFrom({ ByteArrayInputStream(bytes) }, ImportMode.MERGE)
        }
    }

    private fun notes(paths: AppPaths, keys: DatabaseKeyStore) = DatabaseStartup.open(paths, keys).use { it.notes().size }

    @Test
    fun turnedOnTheFileIsEncryptedAtTheNextStartAndOffPlainAgainWithEveryNote() {
        val paths = home()
        withDemo(paths)
        val keys = keys(paths)
        assertFalse(DatabaseCipher.isEncrypted(paths.database))
        val pass = DatabaseCipher.newPassphrase()
        assertEquals(44, pass.length) // 32 bytes in base64, as the phone's
        keys.planEncrypt(pass)
        assertEquals(8, notes(paths, keys))
        assertTrue(DatabaseCipher.isEncrypted(paths.database))
        assertTrue(keys.enabled)
        assertEquals(pass, keys.passphrase())
        assertNull(keys.pending())
        assertFalse(DatabaseCipher.hasNotes(paths.database, null), "a plain open fails")
        assertFalse(DatabaseCipher.hasNotes(paths.database, "wrong"), "a wrong key fails")
        // Nothing is left beside the file.
        assertTrue(paths.root.list()!!.none { it.endsWith(".converting") || it.endsWith(".bak") }, paths.root.list()!!.toList().toString())

        keys.planDecrypt()
        assertEquals(8, notes(paths, keys))
        assertFalse(DatabaseCipher.isEncrypted(paths.database))
        assertFalse(keys.enabled)
        assertNull(keys.takeLastError())
    }

    @Test
    fun aFailedConversionLeavesTheFileAsItWasTellsOnceAndIsNotTriedAgain() {
        val paths = home()
        withDemo(paths)
        val keys = keys(paths)
        val before = paths.database.readBytes()
        // A passphrase that cannot be opened (sealed on another computer) makes the change fail before the file is touched.
        File(paths.root, "database-key.json").writeText("""{"pending":{"action":"encrypt","sealedKey":"dpapi:AAAA"}}""")
        assertEquals(8, notes(paths, keys))
        assertTrue(before.contentEquals(paths.database.readBytes()))
        assertNull(keys.pending(), "not tried again at the next start")
        assertEquals("encrypt", keys.takeLastError()?.first)
        assertNull(keys.takeLastError(), "told once")

        // Damaged database: the conversion stops at its step, the file is untouched.
        val broken = File(paths.root, "broken.db").apply { writeBytes(ByteArray(4096) { 7 }) }
        val copy = broken.readBytes()
        val error = DatabaseCipher.convert(broken, null, "k")
        assertNotNull(error)
        assertTrue(copy.contentEquals(broken.readBytes()))
        assertFalse(File(paths.root, "broken.db.converting").exists())
    }

    @Test
    fun aDatabaseFileIsCheckedBeforeItIsImportedAndReplacesTheDataAtTheNextStart() {
        val paths = home()
        NotioDatabase.open(paths.database).close()
        val keys = keys(paths)
        assertEquals(0, notes(paths, keys))

        // An encrypted file from elsewhere (another computer or the phone: the same SQLCipher 4 format).
        val other = home()
        withDemo(other, key = "their-pass")
        assertEquals("password", DatabaseStartup.stageImport(paths, other.database, null))
        assertEquals("password", DatabaseStartup.stageImport(paths, other.database, "nope"))
        assertNull(DatabaseStartup.stageImport(paths, other.database, "their-pass"))
        keys.planImport(DatabaseStartup.stagedImport(paths), "their-pass")
        assertEquals(8, notes(paths, keys))
        assertTrue(keys.enabled, "the imported file stays encrypted with its password")
        assertFalse(DatabaseStartup.stagedImport(paths).exists())

        // Not a database, and a file of a newer Notio.
        val junk = File(other.root, "junk.db").apply { writeText("hello") }
        assertEquals("password", DatabaseStartup.stageImport(paths, junk, null)) // opaque bytes look encrypted
        val plainJunk = File(other.root, "plain.db").also { f -> java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.path).use { it.createStatement().execute("CREATE TABLE x(a)") } }
        assertEquals("invalid", DatabaseStartup.stageImport(paths, plainJunk, null))
        val newer = File(other.root, "newer.db").also { f ->
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.path).use { c ->
                c.createStatement().use { it.execute("CREATE TABLE note(id TEXT)"); it.execute("PRAGMA user_version = 99") }
            }
        }
        assertEquals("newer", DatabaseStartup.stageImport(paths, newer, null))
    }

    @Test
    fun anEncryptedFileExportedWhileOpenComesInElsewhereWithItsPasswordAndAnOlderSchemaIsBroughtUp() {
        // The whole-code pass (row 19; user: "check the encryption and export / import with a password again").
        val mine = home()
        withDemo(mine)
        val myKeys = keys(mine)
        val pass = DatabaseCipher.newPassphrase()
        myKeys.planEncrypt(pass)
        val exported = File(home().root, "export.db")
        DatabaseStartup.open(mine, myKeys).use { db -> exported.outputStream().use(db::copyFileTo) }
        assertTrue(DatabaseCipher.isEncrypted(exported))

        // Another computer: the password shown (or mailed) on the first one opens it; a wrong one is told, nothing changes.
        val other = home()
        NotioDatabase.open(other.database).close()
        val otherKeys = keys(other)
        assertEquals("password", DatabaseStartup.stageImport(other, exported, pass.dropLast(1)))
        assertEquals(0, notes(other, otherKeys))
        assertNull(DatabaseStartup.stageImport(other, exported, pass))
        otherKeys.planImport(DatabaseStartup.stagedImport(other), pass)
        assertEquals(8, notes(other, otherKeys))
        assertEquals(pass, otherKeys.passphrase())

        // An encrypted file of schema 11 (a phone before task folders): imported, then brought up to 12 with the folder kind.
        val old = File(home().root, "old.db")
        exported.copyTo(old)
        DatabaseCipher.connect(old, pass).use { c ->
            c.createStatement().use { it.execute("ALTER TABLE folder DROP COLUMN kind"); it.execute("PRAGMA user_version = 11") }
        }
        assertEquals(11, DatabaseCipher.schemaVersion(old, pass))
        assertNull(DatabaseStartup.stageImport(other, old, pass))
        otherKeys.planImport(DatabaseStartup.stagedImport(other), pass)
        DatabaseStartup.open(other, otherKeys).use { db ->
            assertEquals(8, db.notes().size)
            assertTrue(db.folders().all { it.kind == com.notio.shared.domain.FolderKind.NOTES })
        }
        assertEquals(NotioDatabase.SCHEMA_VERSION, DatabaseCipher.schemaVersion(other.database, pass))
    }
}
