package com.notebudget.app.data.media

import com.notebudget.app.domain.model.MediaKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The files of media notes in app-private storage (D147): `<dir>/<noteId>_<itemId>.<jpg|mp4>` and, for a small picture in
 * lists, `<noteId>_<itemId>.thumb.jpg`. Videos are big, so they are handled as files, never as one array in memory
 * (except when a backup or an export needs the bytes). A recording is first made under a temp name and moved in when it
 * is whole, so a failed recording never leaves a broken item.
 */
class MediaStore(private val dir: File) {
    private val tempDir = File(dir, "tmp")

    private fun safe(key: String) = SAFE_KEY.matches(key)

    /** The stored file of an item, or `null` when the key is not a safe name. It may not exist yet. */
    fun file(key: String, kind: MediaKind): File? = if (safe(key)) File(dir, "$key.${kind.extension}") else null

    fun existing(key: String, kind: MediaKind): File? = file(key, kind)?.takeIf { it.isFile && it.length() > 0 }

    fun thumbFile(key: String): File? = if (safe(key)) File(dir, "$key.thumb.jpg") else null

    /** A new empty temp file for a recording that is being made. */
    fun newTemp(suffix: String): File {
        tempDir.mkdirs()
        return File.createTempFile("rec-", suffix, tempDir)
    }

    /** Moves a finished [temp] file in as the file of [key]. */
    suspend fun commit(temp: File, key: String, kind: MediaKind): Boolean = withContext(Dispatchers.IO) {
        val target = file(key, kind) ?: return@withContext false
        dir.mkdirs()
        target.delete()
        temp.renameTo(target) || runCatching { temp.copyTo(target, overwrite = true); temp.delete() }.isSuccess
    }

    suspend fun write(key: String, kind: MediaKind, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val target = file(key, kind) ?: return@withContext false
        dir.mkdirs()
        val temp = File(dir, "$key.${kind.extension}.tmp")
        temp.writeBytes(bytes)
        target.delete()
        temp.renameTo(target)
    }

    /**
     * Copies [input] in as the file of [key] without holding it in memory (D174): the first bytes go to [accept] (a check of the file
     * signature), the rest to a temp file that is moved in when whole. `false` when the key is unsafe or [accept] says no.
     */
    suspend fun writeStream(key: String, kind: MediaKind, input: java.io.InputStream, accept: (ByteArray) -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val target = file(key, kind) ?: return@withContext false
            val head = ByteArray(16)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            val start = head.copyOf(read)
            if (!accept(start)) return@withContext false
            dir.mkdirs()
            val temp = File(dir, "$key.${kind.extension}.tmp")
            try {
                temp.outputStream().use { out ->
                    out.write(start)
                    input.copyTo(out, 64 * 1024)
                }
                target.delete()
                if (!temp.renameTo(target)) error("Cannot move $temp to $target")
                true
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
        }

    suspend fun read(key: String, kind: MediaKind): ByteArray? = withContext(Dispatchers.IO) { existing(key, kind)?.readBytes() }

    suspend fun writeThumb(key: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = thumbFile(key) ?: return@withContext
        dir.mkdirs()
        target.writeBytes(bytes)
    }

    suspend fun delete(key: String, kind: MediaKind) = withContext(Dispatchers.IO) {
        file(key, kind)?.delete()
        thumbFile(key)?.delete()
        Unit
    }

    /** Every file of one note, except those of the keys in [keep] (items in the trash, D156). */
    suspend fun deleteForNote(noteId: String, keep: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        if (safe(noteId)) dir.listFiles { f -> f.name.startsWith(noteId + "_") && !isKept(f, keep) }?.forEach { it.delete() }
        Unit
    }

    suspend fun deleteAll(keep: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { if (it.isFile && !isKept(it, keep)) it.delete() }
        tempDir.listFiles()?.forEach { it.delete() }
        Unit
    }

    /** `<key>.<ext>` and `<key>.thumb.jpg` both belong to [key]. */
    private fun isKept(file: File, keep: Set<String>) = keep.any { file.name.startsWith("$it.") }

    private companion object {
        val SAFE_KEY = Regex("[A-Za-z0-9_-]{1,80}")
    }
}

/** The pictures the person put on top of photos and videos (logos, signatures), one file per kind (D148). */
class OverlayImages(private val dir: File) {
    fun file(name: String): File? = if (SAFE.matches(name)) File(dir, name) else null

    suspend fun save(name: String, png: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val target = file(name) ?: return@withContext false
        dir.mkdirs()
        target.writeBytes(png)
        true
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        file(name)?.delete()
        Unit
    }

    private companion object {
        val SAFE = Regex("[A-Za-z0-9_.-]{1,80}")
    }
}
