package com.notio.shared.data

import java.io.File
import java.io.InputStream

/**
 * The files of the notes (drawings, photos, recordings ...), kept next to the database, never as BLOBs (Android D222).
 * Stored under the very names a backup gives them (`<noteId>/<name>`, e.g. `drawing.png`, `item-<id>.jpg`,
 * `clip-<id>.m4a`), so an import and a later export carry every file through unchanged, whatever its kind.
 */
class AttachmentStore(private val root: File) {

    private fun dirOf(noteId: String): File {
        require(SAFE.matches(noteId)) { "unsafe note id" }
        return File(root, noteId)
    }

    private fun checkName(name: String) {
        require(name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains(':') && !name.contains("..")) { "unsafe name" }
    }

    fun list(noteId: String): List<File> =
        dirOf(noteId).listFiles()?.filter { it.isFile && !it.name.endsWith(TEMP) }?.sortedBy { it.name }.orEmpty()

    fun file(noteId: String, name: String): File? {
        checkName(name)
        return File(dirOf(noteId), name).takeIf { it.isFile }
    }

    /** Copies [input] into the store; written to a temporary file first, so a broken copy never replaces a good file. */
    fun write(noteId: String, name: String, input: InputStream): Long {
        checkName(name)
        val dir = dirOf(noteId).apply { mkdirs() }
        val temp = File(dir, name + TEMP)
        val size = temp.outputStream().use { input.copyTo(it, 64 * 1024) }
        val target = File(dir, name)
        SafeFile.replace(temp, target)
        return size
    }

    fun deleteNote(noteId: String) {
        dirOf(noteId).deleteRecursively()
    }

    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    private companion object {
        val SAFE = Regex("[A-Za-z0-9_-]{1,64}")
        const val TEMP = ".part"
    }
}
