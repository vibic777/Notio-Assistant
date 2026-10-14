package com.notebudget.app.domain.usecase

import com.notebudget.app.data.media.MediaNoteWriter
import com.notebudget.app.data.media.MediaStore
import com.notebudget.app.data.transfer.toDomain
import com.notebudget.app.data.transfer.toRecord
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.MediaDocument
import com.notebudget.app.domain.model.MediaFiles
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.MediaRestoreResult
import com.notebudget.app.domain.model.MediaTrashRepository
import com.notebudget.app.domain.model.StampDisplay
import com.notebudget.app.domain.model.TrashedMedia
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeMediaTrashRepository : MediaTrashRepository {
    val rows = MutableStateFlow<List<TrashedMedia>>(emptyList())
    override fun observeAll() = rows
    override suspend fun getAll() = rows.value
    override suspend fun get(id: String) = rows.value.firstOrNull { it.id == id }
    override suspend fun upsert(items: List<TrashedMedia>) { rows.value = rows.value.filter { r -> items.none { it.id == r.id } } + items }
    override suspend fun remove(ids: Collection<String>) { rows.value = rows.value.filter { it.id !in ids } }
    override suspend fun replaceAll(items: List<TrashedMedia>) { rows.value = items }
}

/** Files by "kind:key" that exist; the real stores are not needed for the rules. */
class FakeMediaFiles : MediaFiles {
    val present = mutableMapOf<String, Long>()
    override fun sizeOf(kind: String, key: String) = present["$kind:$key"] ?: 0L
    override fun exists(kind: String, key: String) = present.containsKey("$kind:$key")
    override suspend fun delete(kind: String, key: String) { present.remove("$kind:$key") }
}

/** The trash of photos, videos and recordings (D156) and the display switches of the time stamps (D157). */
class MediaTrashTest {
    private val dir = Files.createTempDirectory("media-trash").toFile()
    private val store = MediaStore(dir)
    private val env = TestEnv()
    private val repo = FakeMediaTrashRepository()
    private val files = FakeMediaFiles()
    private val trash = MediaTrashUseCases(repo, env.noteRepo, env.notes, files, env.clock)
    private val writer = MediaNoteWriter(env.noteRepo, env.notes, store, trash)
    private var n = 0

    @After
    fun cleanUp() { dir.deleteRecursively() }

    private suspend fun photoNote(vararg ids: String): String {
        val f = (env.folders.create("F${n++}", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(f.id, "Trip", MediaKind.PHOTO.type) as DomainResult.Success).value
        ids.forEach { id ->
            writer.add(note.id, MediaKind.PHOTO, MediaItem(id, name = "Photo $id"), null, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4), null)
            files.present["PHOTO:" + MediaDocument.fileKey(note.id, id)] = 100
        }
        return note.id
    }

    private suspend fun ids(noteId: String) = MediaDocument.parse(env.noteRepo.getById(noteId)!!.extras).items.map { it.id }

    @Test
    fun `deleting takes the item out of the note but keeps its file and lists it in the trash`() = runBlocking {
        val note = photoNote("a", "b", "c")
        writer.delete(note, MediaKind.PHOTO, setOf("b"))
        assertEquals(listOf("a", "c"), ids(note))
        assertNotNull("the file stays", store.existing(MediaDocument.fileKey(note, "b"), MediaKind.PHOTO))
        val row = repo.rows.value.single()
        assertEquals(MediaDocument.fileKey(note, "b"), row.id)
        assertEquals("Trip", row.noteTitle)
        assertEquals(1, row.position)
        assertEquals(100L, row.sizeBytes)
    }

    @Test
    fun `restoring puts the item back at its old place and empties its row`() = runBlocking {
        val note = photoNote("a", "b", "c")
        writer.delete(note, MediaKind.PHOTO, setOf("b"))
        val id = repo.rows.value.single().id
        assertEquals(MediaRestoreResult.RESTORED, trash.restore(id))
        assertEquals(listOf("a", "b", "c"), ids(note))
        assertTrue(repo.rows.value.isEmpty())
        assertEquals("Photo b", MediaDocument.parse(env.noteRepo.getById(note)!!.extras).items[1].name)
    }

    @Test
    fun `restoring is refused while the note is in the trash, when it is gone and when the file is missing`() = runBlocking {
        val note = photoNote("a", "b")
        writer.delete(note, MediaKind.PHOTO, setOf("a"))
        val id = repo.rows.value.single().id

        env.notes.delete(note)
        assertEquals(MediaRestoreResult.NOTE_IN_TRASH, trash.restore(id))
        env.notes.restore(note)

        files.present.remove("PHOTO:$id")
        assertEquals(MediaRestoreResult.FILE_MISSING, trash.restore(id))
        files.present["PHOTO:$id"] = 100

        env.notes.delete(note)
        env.trash.purgeNote(note)
        assertEquals(MediaRestoreResult.NOTE_GONE, trash.restore(id))
        assertEquals("the item is still listed, so it can be exported", 1, repo.rows.value.size)
        assertEquals(MediaRestoreResult.NOT_FOUND, trash.restore("nothing"))
    }

    @Test
    fun `deleting for good removes the file and the row, and the expiry follows the retention`() = runBlocking {
        val note = photoNote("a", "b")
        writer.delete(note, MediaKind.PHOTO, setOf("a", "b"))
        val first = repo.rows.value.first()
        trash.purge(first.id)
        assertEquals(1, repo.rows.value.size)
        assertTrue(!files.exists("PHOTO", first.id))

        assertEquals("never removes nothing", 0, trash.purgeExpired(TrashRetention.NEVER))
        env.clock.current += 31L * 24 * 60 * 60 * 1000
        assertEquals(1, trash.purgeExpired(30))
        assertTrue(repo.rows.value.isEmpty())
    }

    @Test
    fun `the files of trashed items are protected from a purge of the note`() = runBlocking {
        val note = photoNote("a", "b")
        writer.delete(note, MediaKind.PHOTO, setOf("a"))
        val keep = trash.keysOf(note)
        assertEquals(setOf(MediaDocument.fileKey(note, "a")), keep)
        store.deleteForNote(note, keep)
        assertNotNull("the trashed one stays", store.existing(MediaDocument.fileKey(note, "a"), MediaKind.PHOTO))
        assertNull("the live one goes", store.existing(MediaDocument.fileKey(note, "b"), MediaKind.PHOTO))
        store.deleteAll(trash.allKeys())
        assertNotNull(store.existing(MediaDocument.fileKey(note, "a"), MediaKind.PHOTO))
    }

    @Test
    fun `a backup carries the list of the trash, a merge only adds what is missing and a restore replaces it`() = runBlocking {
        val note = photoNote("a", "b")
        writer.delete(note, MediaKind.PHOTO, setOf("a"))
        val rows = repo.rows.value
        assertEquals(rows, rows.map { it.toRecord().toDomain() })

        val other = TrashedMedia("x_y", "x", "VIDEO", "Clip", "{}", 0, "Old", 5, 10)
        trash.mergeFromBackup(rows + other)
        assertEquals(2, repo.rows.value.size)
        files.present["VIDEO:x_y"] = 10
        trash.replaceFromBackup(rows)
        assertEquals(rows, repo.rows.value)
        assertTrue("the file of an item that is no longer listed goes", !files.exists("VIDEO", "x_y"))
    }

    @Test
    fun `emptying the trash removes every file and row`() = runBlocking {
        val note = photoNote("a", "b")
        writer.delete(note, MediaKind.PHOTO, setOf("a", "b"))
        assertEquals(2, trash.emptyAll())
        assertTrue(repo.rows.first().isEmpty())
        assertTrue(files.present.isEmpty())
    }

    // ---- time stamps shown (D157) ----

    @Test
    fun `the four stamp switches are off by default and survive storage`() {
        val d = StampDisplay()
        assertTrue(!d.notesCreated && !d.notesModified && !d.foldersCreated && !d.foldersModified)
        val c = StampDisplay(notesCreated = false, foldersModified = true)
        assertEquals(c, StampDisplay.decode(c.encode()))
        assertEquals(StampDisplay(), StampDisplay.decode("junk"))
        assertEquals(StampDisplay(foldersCreated = true), StampDisplay.decode("""{"foldersCreated":true,"x":1}"""))
    }
}
