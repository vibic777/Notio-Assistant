package com.notebudget.app.core.database

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The file of the database as an archive (D347): the database file as it is on the disk, and beside it every file that the
 * notes keep outside the database — drawings and blueprints (the picture and the vector layer), audio, photos, videos, the
 * pictures laid over them, the backgrounds, the melodies of reminders. Before, the export was the database file alone, and a
 * drawing restored from it was an empty sheet.
 *
 * Plain files and streams only, so it is tried without a phone. The files are copied one by one: an archive of any size needs
 * no more memory than a small one.
 */
object DatabaseArchive {
    const val FORMAT = "notio-database"
    const val VERSION = 1
    const val MARKER_ENTRY = "notio-database.json"
    const val DATABASE_ENTRY = "database/notebudget.db"
    private const val FILES = "files/"

    /** The folders of the app's storage that belong to the notes. The journal and the caches are not data. */
    val FILE_DIRS = listOf("drawings", "drawings-vector", "audio", "media", "overlay_images", "backgrounds", "reminder_sounds")

    /** The first bytes of [head] are those of a ZIP archive: an export of this version. A bare database file starts otherwise. */
    fun isArchive(head: ByteArray, length: Int): Boolean =
        length >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    /** Writes the archive to [out] (not closed here). Returns how many files of the notes went in. */
    fun write(out: OutputStream, database: File, filesRoot: File): Int {
        var written = 0
        val zip = ZipOutputStream(out)
        // Pictures, sound and video are compressed already: the fastest level costs little and saves minutes.
        zip.setLevel(Deflater.BEST_SPEED)
        zip.putNextEntry(ZipEntry(MARKER_ENTRY))
        zip.write("""{"format":"$FORMAT","version":$VERSION}""".toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
        database.inputStream().use { it.copyTo(zip, BUFFER) }
        zip.closeEntry()
        for (dir in FILE_DIRS) {
            val base = File(filesRoot, dir)
            if (!base.isDirectory) continue
            for (file in base.walkTopDown().filter { it.isFile }) {
                // A file that went away meanwhile is left out; the rest goes on.
                val input = try { file.inputStream() } catch (e: java.io.IOException) { continue }
                input.use {
                    zip.putNextEntry(ZipEntry(FILES + dir + "/" + file.relativeTo(base).invariantSeparatorsPath))
                    it.copyTo(zip, BUFFER)
                    zip.closeEntry()
                }
                written++
            }
        }
        zip.finish()
        zip.flush()
        return written
    }

    /** What [read] did: was a database found and taken, how many files came back, how many were left out. */
    data class ReadResult(val databaseTaken: Boolean, val databaseFound: Boolean, val files: Int, val skipped: Int)

    /**
     * Reads the archive from [input]. [takeDatabase] is given the database file and says whether it took its place (it checks
     * the file and the password). Only then are the files of [filesRoot] replaced by those of the archive: every folder of
     * [FILE_DIRS] is emptied first, so nothing of the data that was replaced stays. A name that would lead out of its folder is
     * skipped. When the database is not taken nothing on the disk is changed.
     *
     * An archive is a file somebody gave: a few kilobytes of it can unpack into more than the phone holds (D356). So the
     * database is read up to [MAX_DATABASE_BYTES] and a file up to [maxFileBytes] (a bigger file is left out), at most
     * [MAX_FILES] files are taken, and the reading ends when the free storage ([freeBytes]) falls under [SPACE_RESERVE]:
     * what came by then stays, the rest is counted as left out.
     */
    fun read(
        input: InputStream,
        filesRoot: File,
        freeBytes: () -> Long = { filesRoot.apply { mkdirs() }.usableSpace },
        maxFileBytes: Long = MAX_FILE_BYTES,
        takeDatabase: (InputStream) -> Boolean,
    ): ReadResult {
        var found = false
        var taken = false
        var files = 0
        var skipped = 0
        val zip = ZipInputStream(input)
        val space = Space(freeBytes)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name
            if (name == DATABASE_ENTRY && !found) {
                found = true
                taken = takeDatabase(Limited(zip, MAX_DATABASE_BYTES, space))
                if (!taken) return ReadResult(databaseTaken = false, databaseFound = true, files = 0, skipped = 0)
                FILE_DIRS.forEach { File(filesRoot, it).deleteRecursively() }
                continue
            }
            // The files follow the database in an archive of ours; any before it belong to no database that was taken.
            if (!taken || !name.startsWith(FILES)) continue
            val target = target(filesRoot, name.removePrefix(FILES))
            if (target == null || files >= MAX_FILES) { skipped++; continue }
            try {
                target.parentFile?.mkdirs()
                target.outputStream().use { Limited(zip, maxFileBytes, space).copyTo(it, BUFFER) }
                files++
            } catch (e: NoSpace) {
                // The storage is nearly full: nothing more is unpacked.
                target.delete()
                skipped++
                break
            } catch (e: java.io.IOException) {
                target.delete()
                skipped++
            }
        }
        return ReadResult(taken, found, files, skipped)
    }

    /** Where [path] (`<folder>/<name>`) of the archive is put, or `null` when it is not a file of one of [FILE_DIRS]. */
    internal fun target(filesRoot: File, path: String): File? {
        if ('\\' in path) return null
        val parts = path.split('/')
        if (parts.size < 2 || parts[0] !in FILE_DIRS) return null
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        val base = File(filesRoot, parts[0])
        val file = File(base, parts.drop(1).joinToString(File.separator))
        // The last word: wherever the name leads, it stays inside its folder.
        return file.takeIf { it.canonicalPath.startsWith(base.canonicalPath + File.separator) }
    }

    private class NoSpace : java.io.IOException("too little free storage")

    /** The free storage, looked at once for every [SPACE_CHECK_EVERY] bytes unpacked, whatever files they went into. */
    private class Space(private val freeBytes: () -> Long) {
        private var sinceCheck = SPACE_CHECK_EVERY // the first bytes are checked too

        fun took(bytes: Int) {
            sinceCheck += bytes
            if (sinceCheck < SPACE_CHECK_EVERY) return
            sinceCheck = 0
            if (freeBytes() < SPACE_RESERVE) throw NoSpace()
        }
    }

    /**
     * One entry of the archive, read no further than [max] bytes and only while there is free storage. For a reader that
     * closes what it is given: the archive under it stays open.
     */
    private class Limited(private val inner: InputStream, private val max: Long, private val space: Space) : InputStream() {
        private var total = 0L

        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it) }

        private fun count(bytes: Int) {
            total += bytes
            if (total > max) throw java.io.IOException("an entry of the archive is bigger than $max bytes")
            space.took(bytes)
        }

        override fun close() = Unit
    }

    private const val BUFFER = 64 * 1024

    /** A file of a note: up to 2 GB, as in a backup. */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

    /** The database file itself: far above any database of notes. */
    const val MAX_DATABASE_BYTES = 4L * 1024 * 1024 * 1024

    /** More files than any person's notes keep; a made-up archive of empty files ends here. */
    const val MAX_FILES = 200_000

    /** Free storage the phone always keeps: a full phone misbehaves everywhere. */
    const val SPACE_RESERVE = 100L * 1024 * 1024
    private const val SPACE_CHECK_EVERY = 8L * 1024 * 1024
}
