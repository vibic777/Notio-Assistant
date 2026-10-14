package com.notio.shared.data

import com.notio.shared.AppLog
import com.notio.shared.sync.SecretBox
import java.io.File
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Database encryption as on the phone (Android D184, D186 - D190; AGENT.md D46): the file is SQLCipher 4 — the phone's format, so a
 * raw database file moves between them — through the SQLite3 Multiple Ciphers build of sqlite-jdbc. The passphrase is made by the app
 * (256 random bits, base64, like the phone's) and kept sealed with Windows DPAPI ([SecretBox]) instead of the Android Keystore.
 */
object DatabaseCipher {
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** A new passphrase: 32 random bytes in base64 (the phone's `generatePassphrase`). */
    fun newPassphrase(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getEncoder().encodeToString(it) }

    /** Not a plain SQLite file (or too short to be one): encrypted, or not a database at all. */
    fun isEncrypted(file: File): Boolean {
        val header = ByteArray(16)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (read < 16) return true
        return !header.contentEquals(SQLITE_HEADER)
    }

    /** A connection to [file]: plain with no [key], else SQLCipher 4 with it. */
    fun connect(file: File, key: String?): Connection {
        val url = "jdbc:sqlite:" + file.absolutePath
        if (key == null) return DriverManager.getConnection(url)
        return org.sqlite.mc.SQLiteMCSqlCipherConfig.getV4Defaults().withKey(key).build().createConnection(url)
    }

    /**
     * Converts [file] in place from [fromKey] to [toKey] (`null` = plain), the phone's DatabaseEncryptor done the desktop way: a copy is
     * re-keyed and checked (it opens with the new key and has the `note` table) before it replaces the file; any failure leaves the file
     * as it was. Returns `null` on success, else the exact step that failed (shown with Copy error text, D189). The file must not be open.
     */
    fun convert(file: File, fromKey: String?, toKey: String?): String? {
        val work = File(file.parentFile, file.name + ".converting")
        val safety = File(file.parentFile, file.name + ".pre-convert.bak")
        work.delete(); safety.delete()
        var stage = "opening the database"
        return try {
            // A crash or a power cut in the middle of a write leaves a journal next to the file: opening it once rolls that back, so the
            // copy is whole and no journal of the old file is left to be played into the converted one (the code check of 2026-10-01).
            stage = "opening the database"
            check(hasNotes(file, fromKey)) { "the database does not open" }
            check(sidecars(file).none(File::exists)) { "the database still has a journal" }
            stage = "copying the database"
            file.copyTo(work, overwrite = true)
            stage = "opening the copy"
            connect(work, fromKey).use { c ->
                c.createStatement().use { st ->
                    stage = "reading the copy"
                    st.executeQuery("SELECT count(*) FROM sqlite_master").use { it.next() }
                    stage = "changing the key"
                    st.execute("PRAGMA cipher = 'sqlcipher'")
                    st.execute("PRAGMA legacy = 4")
                    st.executeQuery("PRAGMA rekey = " + literal(toKey.orEmpty())).close()
                }
            }
            stage = "checking the converted copy"
            check(isEncrypted(work) == (toKey != null)) { if (toKey != null) "the copy is still plain" else "the copy is still encrypted" }
            check(hasNotes(work, toKey)) { "the converted copy has no notes table" }
            stage = "swapping the files"
            check(file.renameTo(safety)) { "could not move aside ${file.absolutePath}" }
            if (!work.renameTo(file)) {
                safety.renameTo(file)
                error("could not move the converted file into place")
            }
            sidecars(safety).forEach(File::delete)
            safety.delete()
            null
        } catch (t: Throwable) {
            work.delete()
            if (!file.exists() && safety.exists()) safety.renameTo(file)
            AppLog.critical("database", "the database could not be converted: $stage", t)
            "$stage: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** Whether [file] opens with [key] (`null` = plain) and is a Notio database. */
    fun hasNotes(file: File, key: String?): Boolean = runCatching {
        connect(file, key).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='note'").use { it.next() }
            }
        }
    }.getOrDefault(false)

    /** The schema version written in [file] (`PRAGMA user_version`), or `null` when it does not open. */
    fun schemaVersion(file: File, key: String?): Int? = runCatching {
        connect(file, key).use { c -> c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } } }
    }.getOrNull()

    fun sidecars(db: File) = listOf("-wal", "-shm", "-journal").map { File(db.parentFile, db.name + it) }

    private fun literal(s: String) = "'" + s.replace("'", "''") + "'"
}

/** What waits for the next start (the database is converted or replaced before it is opened, D46). */
@Serializable
data class PendingDatabaseChange(
    /** "encrypt", "decrypt" or "import". */
    val action: String,
    /** The new passphrase (sealed) for "encrypt", or the imported file's (sealed, `null` for a plain one). */
    val sealedKey: String? = null,
    /** "import": the checked file that replaces the database. */
    val file: String? = null,
)

@Serializable
private data class KeyRecord(
    val sealedKey: String? = null,
    val pending: PendingDatabaseChange? = null,
    val lastError: String? = null,
    val lastErrorAction: String? = null,
)

/**
 * The database passphrase of this computer, sealed with [box], and what is to happen to the file at the next start (`database-key.json`
 * of the data folder). A failed change is remembered once ([takeLastError]) and never tried again by itself — no crash loop (D187).
 */
class DatabaseKeyStore(private val file: File, private val box: SecretBox) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun read(): KeyRecord = runCatching {
        // A key written by an older Notio may have been cut off between its two steps: its new copy is taken then.
        val legacy = File(file.parentFile, file.name + ".tmp")
        if (!file.isFile && legacy.isFile) SafeFile.replace(legacy, file)
        SafeFile.readText(file)?.let { json.decodeFromString(KeyRecord.serializer(), it) }
    }.getOrNull() ?: KeyRecord()

    private fun write(r: KeyRecord) {
        // Never "delete, then rename" (the whole-code pass, round 2): without this file an encrypted database cannot be opened.
        SafeFile.writeText(file, json.encodeToString(KeyRecord.serializer(), r))
    }

    /** The passphrase the database is encrypted with now, or `null` (plain). */
    fun passphrase(): String? = read().sealedKey?.let(box::open)

    val enabled: Boolean get() = read().sealedKey != null

    fun pending(): PendingDatabaseChange? = read().pending

    fun planEncrypt(passphrase: String) = write(read().copy(pending = PendingDatabaseChange("encrypt", box.seal(passphrase))))

    fun planDecrypt() = write(read().copy(pending = PendingDatabaseChange("decrypt")))

    fun planImport(checked: File, passphrase: String?) =
        write(read().copy(pending = PendingDatabaseChange("import", passphrase?.let(box::seal), checked.absolutePath)))

    fun cancelPending() = write(read().copy(pending = null))

    fun open(sealed: String?): String? = sealed?.let(box::open)

    internal fun done(passphrase: String?) = write(KeyRecord(sealedKey = passphrase?.let(box::seal)))

    internal fun failed(action: String, message: String) = write(read().copy(pending = null, lastError = message, lastErrorAction = action))

    /** The failure of the last change and its action ("encrypt", "decrypt", "import"), once (shown with Copy error text), then forgotten. */
    fun takeLastError(): Pair<String, String>? {
        val r = read()
        val message = r.lastError ?: return null
        write(r.copy(lastError = null, lastErrorAction = null))
        return (r.lastErrorAction ?: "encrypt") to message
    }
}

/**
 * Opens the database of [paths] at the start: first what was planned ([DatabaseKeyStore.pending]), while nothing has the file open,
 * then the file itself with the passphrase it has now.
 */
object DatabaseStartup {
    fun open(paths: AppPaths, keys: DatabaseKeyStore): NotioDatabase {
        val file = paths.database
        keys.pending()?.let { p -> apply(file, keys, p) }
        val key = keys.passphrase()
        // The stored state and the file disagree (a file put back by hand, a lost key): the file says what it is.
        val usable = if (file.isFile && DatabaseCipher.isEncrypted(file) != (key != null)) {
            AppLog.e("database", "the database file does not match its key record")
            if (key == null) null else key.takeIf { DatabaseCipher.isEncrypted(file) }
        } else key
        return NotioDatabase.open(file, usable)
    }

    private fun apply(file: File, keys: DatabaseKeyStore, p: PendingDatabaseChange) {
        when (p.action) {
            "encrypt" -> {
                val key = keys.open(p.sealedKey) ?: return keys.failed("encrypt", "the new passphrase could not be read")
                if (!file.isFile) return keys.done(null)
                val error = DatabaseCipher.convert(file, null, key)
                if (error == null) { keys.done(key); AppLog.i("database", "the database is encrypted") } else keys.failed("encrypt", error)
            }
            "decrypt" -> {
                val key = keys.passphrase() ?: return keys.failed("decrypt", "no stored passphrase")
                val error = DatabaseCipher.convert(file, key, null)
                if (error == null) { keys.done(null); AppLog.i("database", "the database is no longer encrypted") } else keys.failed("decrypt", error)
            }
            "import" -> {
                val staged = p.file?.let(::File)
                val key = keys.open(p.sealedKey)
                if (staged == null || !staged.isFile || !DatabaseCipher.hasNotes(staged, key)) return keys.failed("import", "the imported file is gone or does not open")
                val safety = File(file.parentFile, file.name + ".pre-import.bak")
                safety.delete()
                try {
                    if (file.isFile) check(file.renameTo(safety)) { "could not move aside ${file.absolutePath}" }
                    DatabaseCipher.sidecars(file).forEach(File::delete)
                    if (!staged.renameTo(file)) { staged.copyTo(file, overwrite = true); staged.delete() }
                    DatabaseCipher.sidecars(safety).forEach(File::delete)
                    safety.delete()
                    keys.done(key)
                    AppLog.i("database", "a database file was imported")
                } catch (t: Throwable) {
                    if (!file.exists() && safety.exists()) safety.renameTo(file)
                    AppLog.critical("database", "the database file could not be imported", t)
                    keys.failed("import", "swapping the files: ${t.message ?: t.javaClass.simpleName}")
                }
            }
            else -> keys.cancelPending()
        }
    }

    /**
     * Checks a chosen database file for import and copies it next to the database (`notio.db.importing`): `null` when it is fine,
     * else why not — "password" when it is encrypted and [password] is missing or wrong (the dialog asks), "newer" when a newer Notio
     * wrote it, "invalid" when it is not a Notio database.
     */
    fun stageImport(paths: AppPaths, chosen: File, password: String?): String? {
        val staged = File(paths.database.parentFile, paths.database.name + ".importing")
        staged.delete()
        return try {
            chosen.copyTo(staged, overwrite = true)
            val encrypted = DatabaseCipher.isEncrypted(staged)
            val key = if (encrypted) password?.takeIf { it.isNotEmpty() } else null
            if (encrypted && key == null) return "password".also { staged.delete() }
            if (!DatabaseCipher.hasNotes(staged, key)) return (if (encrypted) "password" else "invalid").also { staged.delete() }
            val version = DatabaseCipher.schemaVersion(staged, key) ?: 0
            if (version > NotioDatabase.SCHEMA_VERSION) return "newer".also { staged.delete() }
            null
        } catch (t: Throwable) {
            staged.delete()
            AppLog.e("database", "a database file could not be read for import", t)
            "invalid"
        }
    }

    fun stagedImport(paths: AppPaths) = File(paths.database.parentFile, paths.database.name + ".importing")
}
