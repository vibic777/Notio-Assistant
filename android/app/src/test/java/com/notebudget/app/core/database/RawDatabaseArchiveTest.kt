package com.notebudget.app.core.database

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file of the database as an archive with the files of the notes (D347). */
class RawDatabaseArchiveTest {
    private val root = File("build/tmp/database-archive-test-" + System.nanoTime()).apply { mkdirs() }

    @After
    fun clean() { root.deleteRecursively() }

    private fun phone(name: String): Pair<File, File> {
        val files = File(root, "$name/files").apply { mkdirs() }
        val database = File(root, "$name/notebudget.db")
        return database to files
    }

    private fun File.put(path: String, text: String) = File(this, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun `the archive carries the database and every file of the notes, and gives them back on another phone`() {
        val (database, files) = phone("old")
        database.writeBytes(ByteArray(5000) { (it % 251).toByte() })
        files.put("drawings/note-1.png", "a drawing")
        files.put("drawings-vector/note-1.json", "its shapes")
        files.put("drawings-vector/note-2.json", "a blueprint")
        files.put("audio/note-3/clip-1.m4a", "a recording")
        files.put("media/note-4/video.mp4", "a video")
        files.put("logs/app.log", "the journal is not data")

        val out = ByteArrayOutputStream()
        assertEquals(5, DatabaseArchive.write(out, database, files))
        val bytes = out.toByteArray()
        assertTrue(DatabaseArchive.isArchive(bytes, bytes.size))

        val (newDatabase, newFiles) = phone("new")
        newFiles.put("drawings/gone.png", "a drawing of the data that is replaced")
        newFiles.put("logs/app.log", "kept")
        val result = DatabaseArchive.read(ByteArrayInputStream(bytes), newFiles) { stream ->
            newDatabase.outputStream().use { stream.copyTo(it) }
            true
        }

        assertEquals(DatabaseArchive.ReadResult(databaseTaken = true, databaseFound = true, files = 5, skipped = 0), result)
        assertArrayEquals(database.readBytes(), newDatabase.readBytes())
        assertEquals("a drawing", File(newFiles, "drawings/note-1.png").readText())
        assertEquals("a blueprint", File(newFiles, "drawings-vector/note-2.json").readText())
        assertEquals("a recording", File(newFiles, "audio/note-3/clip-1.m4a").readText())
        assertEquals("a video", File(newFiles, "media/note-4/video.mp4").readText())
        assertFalse("what belonged to the replaced data is gone", File(newFiles, "drawings/gone.png").exists())
        assertEquals("kept", File(newFiles, "logs/app.log").readText())
    }

    @Test
    fun `a database that is not taken (a wrong password) changes nothing`() {
        val (database, files) = phone("old")
        database.writeText("db")
        files.put("drawings/a.png", "new")
        val out = ByteArrayOutputStream()
        DatabaseArchive.write(out, database, files)

        val (_, mine) = phone("mine")
        mine.put("drawings/mine.png", "mine")
        val result = DatabaseArchive.read(ByteArrayInputStream(out.toByteArray()), mine) { false }

        assertEquals(DatabaseArchive.ReadResult(databaseTaken = false, databaseFound = true, files = 0, skipped = 0), result)
        assertEquals("mine", File(mine, "drawings/mine.png").readText())
        assertFalse(File(mine, "drawings/a.png").exists())
    }

    @Test
    fun `a name that leads out of its folder, or into a folder that is not the notes', is never written`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("files/drawings/early.png", "before the database: belongs to nothing")
            entry(DatabaseArchive.DATABASE_ENTRY, "db")
            entry("files/drawings/../../escaped.txt", "x")
            entry("files/drawings/..\\escaped.txt", "x")
            entry("files/logs/app.log", "x")
            entry("files/shared_prefs/lock.xml", "x")
            entry("files/drawings/ok.png", "ok")
            entry("elsewhere.txt", "x")
        }
        val (_, files) = phone("target")
        val result = DatabaseArchive.read(ByteArrayInputStream(out.toByteArray()), files) { true }

        assertEquals(1, result.files)
        assertEquals(4, result.skipped)
        assertEquals(listOf("ok.png"), File(files, "drawings").list()?.toList())
        assertFalse(File(root, "target/escaped.txt").exists())
        assertFalse(File(files, "logs").exists())
        assertNull(DatabaseArchive.target(files, "drawings"))
        assertNull(DatabaseArchive.target(files, "drawings//a.png"))
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun `a file bigger than allowed is left out and the rest comes back (D356)`() {
        val bytes = archive(
            DatabaseArchive.DATABASE_ENTRY to "db".toByteArray(),
            "files/media/huge.mp4" to ByteArray(5000),
            "files/media/small.jpg" to ByteArray(100),
        )
        val (_, files) = phone("target")
        val result = DatabaseArchive.read(ByteArrayInputStream(bytes), files, maxFileBytes = 1000) { true }

        assertEquals(1, result.files)
        assertEquals(1, result.skipped)
        assertEquals(listOf("small.jpg"), File(files, "media").list()?.toList())
    }

    @Test
    fun `nothing is unpacked into a storage that is nearly full (D356)`() {
        val bytes = archive(
            DatabaseArchive.DATABASE_ENTRY to "db".toByteArray(),
            "files/media/a.jpg" to ByteArray(9 * 1024 * 1024), // more than is unpacked between two looks at the storage
            "files/media/b.jpg" to ByteArray(100),
        )
        val (_, files) = phone("target")
        files.put("drawings/mine.png", "mine")
        // The database does not fit: it is not taken, and nothing of this phone is touched.
        val refused = runCatching { DatabaseArchive.read(ByteArrayInputStream(bytes), files, freeBytes = { 0L }) { it.readBytes(); true } }
        assertTrue(refused.isFailure)
        assertEquals("mine", File(files, "drawings/mine.png").readText())

        // The storage ends while the files come: the reading stops there, no file is left half written.
        var asked = 0
        val result = DatabaseArchive.read(ByteArrayInputStream(bytes), files, freeBytes = { if (asked++ == 0) Long.MAX_VALUE else 0L }) { it.readBytes(); true }
        assertTrue(result.databaseTaken)
        assertEquals(0, result.files)
        assertEquals(1, result.skipped)
        assertTrue(File(files, "media").list().isNullOrEmpty())
    }

    @Test
    fun `a bare database file of an earlier version is told from an archive, and an archive without a database is refused`() {
        val sqlite = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        assertFalse(DatabaseArchive.isArchive(sqlite, sqlite.size))
        assertFalse(DatabaseArchive.isArchive(ByteArray(4), 2))

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("manifest.json")); zip.write("{}".toByteArray()); zip.closeEntry() }
        val (_, files) = phone("target")
        files.put("drawings/mine.png", "mine")
        val result = DatabaseArchive.read(ByteArrayInputStream(out.toByteArray()), files) { true }
        assertFalse(result.databaseFound)
        assertEquals("mine", File(files, "drawings/mine.png").readText())
    }
}
