package com.notebudget.app.core.database

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

sealed interface RawDatabaseImportResult {
    data object Success : RawDatabaseImportResult
    data object WrongPassword : RawDatabaseImportResult
    data class Failed(val message: String) : RawDatabaseImportResult
}

/** What to remember about the database file that was just imported: whether it is encrypted, and with which passphrase. */
data class DatabaseEncryptionCandidate(val enabled: Boolean, val passphrase: String?)

/**
 * Exports or replaces the live database file as one unit (D-encryption): a second way to move data between phones or
 * keep an offline copy, next to the existing ZIP+JSON backup (§6.13), which stays the one guaranteed to open on any
 * app version because it does not touch the database file at all. A raw file is exactly what SQLite/SQLCipher wrote,
 * plaintext or encrypted, matching whatever the live file currently is.
 */
class RawDatabaseTransfer(private val context: Context) {

    fun isEncryptedFile(file: File): Boolean {
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }
        if (read < 16) return true // too short to be a plain SQLite header; treat as opaque/encrypted
        return !header.contentEquals(SQLITE_HEADER)
    }

    /**
     * Checkpoints the live database so the file on disk is complete, then writes it to [out] as an archive together with the
     * files the notes keep beside it (D347, [DatabaseArchive]): drawings, blueprints, audio, photos, videos. Returns how many
     * files went in.
     */
    fun export(database: AppDatabase, out: OutputStream): Int {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        return DatabaseArchive.write(out, context.getDatabasePath(AppDatabase.NAME), context.filesDir)
    }

    /**
     * Imports what [export] wrote: the database is checked and put in place by [importFile], then the files of this phone are
     * replaced by those of the archive. A bare database file of an earlier version is imported as it was, without files.
     */
    fun import(input: InputStream, password: String?): Pair<RawDatabaseImportResult, DatabaseEncryptionCandidate?> {
        val buffered = java.io.BufferedInputStream(input)
        val head = ByteArray(4)
        buffered.mark(head.size)
        val length = buffered.read(head)
        buffered.reset()
        if (!DatabaseArchive.isArchive(head, length)) return importFile(buffered, password)
        var outcome: Pair<RawDatabaseImportResult, DatabaseEncryptionCandidate?>? = null
        return try {
            val read = DatabaseArchive.read(buffered, context.filesDir) { database ->
                importFile(database, password).also { outcome = it }.first is RawDatabaseImportResult.Success
            }
            if (read.databaseTaken) {
                com.notebudget.app.core.common.AppLog.i("database", "the database archive was imported: " + read.files + " files, " + read.skipped + " left out")
            }
            outcome ?: (RawDatabaseImportResult.Failed("not a Notio database") to null)
        } catch (t: Throwable) {
            // The archive ended badly after the database took its place: the notes are there, some files may be missing.
            com.notebudget.app.core.common.AppLog.critical("database", "the database archive could not be read to its end", t)
            outcome ?: (RawDatabaseImportResult.Failed(t.message ?: t.javaClass.simpleName) to null)
        }
    }

    /**
     * Validates [input] as a real Notio database (opens it with [password], `null`/empty for a plaintext file) and, only
     * if that succeeds, replaces the live file with it. The caller must have already closed Room's connection to the
     * live database ([AppDatabase.close]) and must reopen it (or restart) afterwards, since the passphrase it needs may
     * have changed.
     */
    fun importFile(input: InputStream, password: String?): Pair<RawDatabaseImportResult, DatabaseEncryptionCandidate?> {
        val live = context.getDatabasePath(AppDatabase.NAME)
        val staging = File(live.parentFile, "${AppDatabase.NAME}.importing")
        val safety = File(live.parentFile, "${AppDatabase.NAME}.pre-import.bak")
        staging.delete()
        safety.delete()

        return try {
            staging.outputStream().use { input.copyTo(it) }
            val encrypted = isEncryptedFile(staging)
            if (encrypted && password.isNullOrEmpty()) {
                staging.delete()
                return RawDatabaseImportResult.Failed("password required") to null
            }

            val hasNoteTable = runCatching {
                SqlCipherNativeLibrary.ensureLoaded()
                val opened = if (encrypted) {
                    CipherDatabase.openDatabase(staging.absolutePath, password, null, CipherDatabase.OPEN_READONLY, null)
                } else {
                    CipherDatabase.openDatabase(staging.absolutePath, "", null, CipherDatabase.OPEN_READONLY, null)
                }
                opened.use { db ->
                    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='note'", null).use { it.moveToFirst() }
                }
            }
            if (hasNoteTable.isFailure) {
                staging.delete()
                val failure = if (encrypted) RawDatabaseImportResult.WrongPassword
                else RawDatabaseImportResult.Failed(hasNoteTable.exceptionOrNull()?.message ?: "cannot open")
                return failure to null
            }
            if (hasNoteTable.getOrDefault(false) != true) {
                staging.delete()
                return RawDatabaseImportResult.Failed("not a Notio database") to null
            }

            if (live.isFile) check(live.renameTo(safety)) { "could not move aside ${live.absolutePath}" }
            check(staging.renameTo(live)) { "could not move the imported file into place" }
            deleteSidecars(safety)
            safety.delete()
            RawDatabaseImportResult.Success to DatabaseEncryptionCandidate(enabled = encrypted, passphrase = if (encrypted) password else null)
        } catch (t: Throwable) {
            staging.delete()
            RawDatabaseImportResult.Failed(t.message ?: t.javaClass.simpleName) to null
        }
    }

    private fun deleteSidecars(db: File) {
        File(db.parentFile, "${db.name}-wal").delete()
        File(db.parentFile, "${db.name}-shm").delete()
        File(db.parentFile, "${db.name}-journal").delete()
    }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
