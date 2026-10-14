package com.notio.shared.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Files replaced whole or not at all (the whole-code pass, round 2). They used to be written as "delete the old file, then rename the
 * new one": a power cut between the two left no file at all — for `database-key.json` that is an encrypted database nobody can open.
 * Now the new text is flushed to the disk first and then takes the old file's place in one step (Windows MoveFileEx with replace);
 * a reader that finds only the new file (an interrupted older write) takes it.
 */
object SafeFile {
    /** [temp] takes the place of [target] in one step; the old [target] stays whole until then. */
    fun replace(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** [bytes] into [target] safely: next to it first, on the disk, then in its place. */
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = tempOf(target)
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        replace(temp, target)
    }

    fun writeText(target: File, text: String) = write(target, text.toByteArray(Charsets.UTF_8))

    /** The text of [target]; when only its new copy is there (a write cut off between the two steps of the old way), that one. */
    fun readText(target: File): String? {
        if (!target.isFile) {
            val temp = tempOf(target)
            if (temp.isFile) runCatching { replace(temp, target) }
        }
        return if (target.isFile) target.readText() else null
    }

    fun tempOf(target: File) = File(target.path + ".part")
}
