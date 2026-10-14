package com.notebudget.app.core.database

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/** What [DatabaseEncryptor] did. [Failed.message] is a fixed, non-localized diagnostic (logs/tests only; the UI shows its own string). */
sealed interface DatabaseEncryptionResult {
    data object Success : DatabaseEncryptionResult
    data class Failed(val message: String) : DatabaseEncryptionResult
}

/**
 * Converts the app's live database file between plain SQLite and SQLCipher, in place, without going through backup/restore
 * (D-encryption). Uses SQLCipher's own `sqlcipher_export()` recipe: attach the target file with the new key (or an empty
 * one, for plain), copy every table into it, then swap files only after the copy opens and checks out — so a failure
 * midway leaves the original file untouched.
 *
 * The caller must close Room's connection to the database (`AppDatabase.close()`) before calling either method, and only
 * reopen it afterwards: this class talks to the file directly and would otherwise race Room's own connection.
 */
class DatabaseEncryptor(private val context: Context) {

    /** Plaintext -> SQLCipher, keyed with [passphrase]. The database must currently be un-encrypted. */
    suspend fun encrypt(passphrase: String): DatabaseEncryptionResult = withContext(Dispatchers.IO) {
        convert(openPlain = true, key = passphrase)
    }

    /** SQLCipher -> plaintext. [passphrase] is the key the database is currently encrypted with. */
    suspend fun decrypt(passphrase: String): DatabaseEncryptionResult = withContext(Dispatchers.IO) {
        convert(openPlain = false, key = passphrase, sourcePassphrase = passphrase)
    }

    private fun convert(openPlain: Boolean, key: String, sourcePassphrase: String? = null): DatabaseEncryptionResult {
        val live = context.getDatabasePath(AppDatabase.NAME)
        if (!live.isFile) return DatabaseEncryptionResult.Failed("no database file at ${live.absolutePath}")

        // The caller just closed Room's own connection to this file; on some devices the OS does not release its lock
        // synchronously with that call, so the first attempt to open a second connection here can see it as still busy.
        // A few short retries ride that out instead of failing the whole conversion over a race that clears itself.
        var lastError: Throwable? = null
        repeat(ATTEMPTS) { attemptIndex ->
            if (attemptIndex > 0) Thread.sleep(RETRY_DELAY_MS)
            val result = tryConvert(openPlain, key, sourcePassphrase, live)
            if (result is DatabaseEncryptionResult.Success) return result
            lastError = (result as? DatabaseEncryptionResult.Failed)?.let { RuntimeException(it.message) }
        }
        return DatabaseEncryptionResult.Failed(lastError?.message ?: "unknown failure after $ATTEMPTS attempts")
    }

    private fun tryConvert(openPlain: Boolean, key: String, sourcePassphrase: String?, live: File): DatabaseEncryptionResult {
        val target = File(live.parentFile, "${AppDatabase.NAME}.converting")
        val safety = File(live.parentFile, "${AppDatabase.NAME}.pre-convert.bak")
        target.delete()
        safety.delete()
        target.parentFile?.mkdirs() // defensive; this is the same directory the live file is already in

        var stage = "loading the SQLCipher library"
        return try {
            SqlCipherNativeLibrary.ensureLoaded()

            // D189/D190: ATTACH creates a brand new file at the target path — SQLCipher's own docs note that an
            // operation which creates a database (their example is PRAGMA cipher_migrate) needs the connection opened
            // with SQLITE_OPEN_CREATE, not just SQLITE_OPEN_READWRITE, even though the *source* file already exists.
            // Without it, ATTACH on this source connection could not create the .converting file: SQLITE_CANTOPEN.
            val openFlags = CipherDatabase.OPEN_READWRITE or CipherDatabase.CREATE_IF_NECESSARY
            stage = "opening the source database (${live.absolutePath})"
            val source = if (openPlain) {
                CipherDatabase.openDatabase(live.absolutePath, "", null, openFlags, null)
            } else {
                CipherDatabase.openDatabase(live.absolutePath, sourcePassphrase, null, openFlags, null)
            }
            source.use { db ->
                stage = "checkpointing the WAL"
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                stage = "attaching the target database (${target.absolutePath})"
                val exportKey = if (openPlain) key.sqlLiteral() else "''"
                db.execSQL("ATTACH DATABASE '${target.absolutePath.sqlEscaped()}' AS converted KEY $exportKey")
                stage = "running sqlcipher_export"
                db.rawQuery("SELECT sqlcipher_export('converted')", null).use { it.moveToFirst() }
                stage = "detaching the target database"
                db.execSQL("DETACH DATABASE converted")
            }

            // Verify the new file actually opens with the new key before touching the original.
            stage = "reopening the converted file to verify it"
            val opens = runCatching {
                val check = if (openPlain) {
                    CipherDatabase.openDatabase(target.absolutePath, key, null, CipherDatabase.OPEN_READONLY, null)
                } else {
                    CipherDatabase.openDatabase(target.absolutePath, "", null, CipherDatabase.OPEN_READONLY, null)
                }
                check.use { it.rawQuery("SELECT count(*) FROM sqlite_master", null).use { c -> c.moveToFirst() } }
            }
            if (opens.isFailure) {
                target.delete()
                val cause = opens.exceptionOrNull()
                return DatabaseEncryptionResult.Failed("$stage: ${cause?.message ?: cause?.javaClass?.simpleName ?: "did not open"}")
            }

            // Swap: keep the original as a safety copy only until the new file is confirmed live, then remove it.
            stage = "swapping the files"
            check(live.renameTo(safety)) { "could not move aside ${live.absolutePath}" }
            check(target.renameTo(live)) { "could not move ${target.absolutePath} into place" }
            deleteSidecars(safety)
            safety.delete()
            DatabaseEncryptionResult.Success
        } catch (t: Throwable) {
            target.delete()
            DatabaseEncryptionResult.Failed("$stage: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun deleteSidecars(db: File) {
        File(db.parentFile, "${db.name}-wal").delete()
        File(db.parentFile, "${db.name}-shm").delete()
        File(db.parentFile, "${db.name}-journal").delete()
    }

    private fun String.sqlEscaped() = replace("'", "''")
    private fun String.sqlLiteral() = "'${sqlEscaped()}'"

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 300L
    }
}
