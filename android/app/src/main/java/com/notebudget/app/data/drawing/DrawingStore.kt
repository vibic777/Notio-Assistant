package com.notebudget.app.data.drawing

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One binary file per note in app-private storage (`<dir>/<noteId>.<extension>`), for note
 * kinds whose content is a file, not text: a large blob in the database would break the 2 MB
 * cursor window of Room. The DB row of such a note carries no file data.
 */
open class NoteFileStore(private val dir: File, private val extension: String) {

    /** `null` for an id that could escape the folder (ids come from files, so they are checked). */
    private fun fileFor(noteId: String): File? =
        if (SAFE_ID.matches(noteId)) File(dir, "$noteId.$extension") else null

    /** The stored file, or `null` when the note has none yet. */
    fun fileIfExists(noteId: String): File? = fileFor(noteId)?.takeIf { it.isFile }

    suspend fun read(noteId: String): ByteArray? = withContext(Dispatchers.IO) {
        fileFor(noteId)?.takeIf { it.isFile }?.readBytes()
    }

    /** Writes atomically: a crash mid-write never leaves a half-written drawing. */
    suspend fun write(noteId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = fileFor(noteId) ?: return@withContext
        dir.mkdirs()
        val temp = File(dir, "$noteId.$extension.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot replace $target" }
        }
    }

    /**
     * Copies [input] to the file of [noteId] without holding it in memory (D174): the first bytes are shown to [accept] (a check of the
     * file signature), the rest is written to a temp file and moved in when whole. `false` when the id is unsafe or [accept] says no.
     */
    suspend fun writeStream(noteId: String, input: java.io.InputStream, accept: (ByteArray) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val target = fileFor(noteId) ?: return@withContext false
        val head = ByteArray(HEAD_BYTES)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        val start = head.copyOf(read)
        if (!accept(start)) return@withContext false
        dir.mkdirs()
        val temp = File(dir, "$noteId.$extension.tmp")
        try {
            temp.outputStream().use { out ->
                out.write(start)
                input.copyTo(out, 64 * 1024)
            }
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Cannot replace $target" }
            }
            true
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** The stored file, as a [File] even when it is not there; `null` for an unsafe id. */
    fun fileOf(noteId: String): File? = fileFor(noteId)

    suspend fun delete(noteId: String) = withContext(Dispatchers.IO) {
        fileFor(noteId)?.delete()
        Unit
    }

    /** Deletes every file whose name starts with [prefix] (a note's own files, D112), except those named in [keep] (the trash, D156). */
    suspend fun deleteWithPrefix(prefix: String, keep: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        if (SAFE_ID.matches(prefix)) dir.listFiles { f -> f.name.startsWith(prefix) && f.nameWithoutExtension !in keep }?.forEach { it.delete() }
        Unit
    }

    suspend fun deleteAll(keep: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.nameWithoutExtension !in keep }?.forEach { it.delete() }
        Unit
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        const val HEAD_BYTES = 16
    }
}

/** Drawings are raster images (D39): one PNG per note. */
class DrawingStore(dir: File) : NoteFileStore(dir, "png")

/**
 * Audio notes: AAC / MPEG-4 (.m4a) recordings. A note has several (D112): a clip's file key is
 * `AudioDocument.fileKey(noteId, clipId)`; the single recording of an older note is stored under the note id.
 */
class AudioStore(dir: File) : NoteFileStore(dir, "m4a")

/** The vector layer of a drawing note: one JSON file per note (`VectorDocument`). */
class VectorStore(dir: File) : NoteFileStore(dir, "json")
