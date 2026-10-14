package com.notebudget.app.data.media

import com.notebudget.app.data.transfer.MediaTransferHandler
import com.notebudget.app.data.transfer.NoteExtras
import com.notebudget.app.domain.model.GeoPoint
import com.notebudget.app.domain.model.MediaDocument
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.OverlayPosition
import com.notebudget.app.domain.model.OverlaySettings
import com.notebudget.app.domain.model.StampLayer
import com.notebudget.app.domain.model.TextLayer
import com.notebudget.app.domain.usecase.TestEnv
import com.notebudget.app.feature.media.AudioCapture
import com.notebudget.app.feature.media.WavMixer
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photo, camera-video and screen-recording notes (D147, D148): the data side that needs no Android. */
class MediaNoteTest {
    private val dir: File = Files.createTempDirectory("media-test").toFile()
    private val store = MediaStore(dir)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), n.toByte(), 1, 2, 3, 4)
    private fun mp4(n: Int) = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), n.toByte(), 0, 0, 0, 1, 2, 3)

    private suspend fun TestEnv.mediaNote(kind: MediaKind, title: String = "Trip"): Note {
        val f = folders.create("F", "#3366FF", "SOFT").let { (it as com.notebudget.app.domain.model.DomainResult.Success).value }
        return (notes.create(f.id, title, kind.type) as com.notebudget.app.domain.model.DomainResult.Success).value
    }

    // ---- the list of items ----

    @Test
    fun `the list of items survives storage, unknown fields are ignored and damage gives an empty list`() {
        val doc = MediaDocument(items = listOf(MediaItem("a", "One", "c", 5, 6, 1200, 1920, 1080, GeoPoint(1.5, 2.5), "MIC")))
        assertEquals(doc, MediaDocument.parse(doc.encode()))
        assertEquals(1, MediaDocument.parse("""{"v":9,"items":[{"id":"x","future":true}],"other":1}""").items.size)
        assertEquals(0, MediaDocument.parse("not json").items.size)
        assertEquals(0, MediaDocument.parse(null).items.size)
        assertEquals("One: c", doc.searchText())
        assertEquals("1.50000, 2.50000", GeoPoint(1.5, 2.5).text())
    }

    // ---- adding, changing, deleting ----

    @Test
    fun `an added photo is stored with its picture, listed in the note and found by search text`() = runBlocking {
        val env = TestEnv()
        val note = env.mediaNote(MediaKind.PHOTO)
        val writer = MediaNoteWriter(env.noteRepo, env.notes, store)
        val item = MediaItem("p1", name = "Gate", comment = "morning", createdAt = 10)

        assertTrue(writer.add(note.id, MediaKind.PHOTO, item, source = null, bytes = jpeg(1), thumbnail = byteArrayOf(9)))

        val stored = env.noteRepo.getById(note.id)!!
        assertEquals(listOf("p1"), MediaDocument.parse(stored.extras).items.map { it.id })
        assertEquals("Gate: morning", stored.contentText)
        val key = MediaDocument.fileKey(note.id, "p1")
        assertArrayEquals(jpeg(1), store.read(key, MediaKind.PHOTO))
        assertArrayEquals(byteArrayOf(9), store.thumbFile(key)!!.readBytes())
    }

    @Test
    fun `a recording file is moved in and an unknown note adds nothing`() = runBlocking {
        val env = TestEnv()
        val note = env.mediaNote(MediaKind.SCREEN)
        val writer = MediaNoteWriter(env.noteRepo, env.notes, store)
        val temp = store.newTemp(".mp4").also { it.writeBytes(mp4(1)) }

        assertTrue(writer.add(note.id, MediaKind.SCREEN, MediaItem("v1"), source = temp, bytes = null, thumbnail = null))
        assertFalse(temp.exists())
        assertNotNull(store.existing(MediaDocument.fileKey(note.id, "v1"), MediaKind.SCREEN))

        assertFalse(writer.add("no-such-note", MediaKind.SCREEN, MediaItem("v2"), null, mp4(2), null))
        assertNull(store.existing(MediaDocument.fileKey("no-such-note", "v2"), MediaKind.SCREEN))
    }

    @Test
    fun `changing an item stamps it as changed, deleting removes the files and leaves the others`() = runBlocking {
        val env = TestEnv()
        val note = env.mediaNote(MediaKind.PHOTO)
        val writer = MediaNoteWriter(env.noteRepo, env.notes, store)
        writer.add(note.id, MediaKind.PHOTO, MediaItem("a", createdAt = 1), null, jpeg(1), byteArrayOf(1))
        writer.add(note.id, MediaKind.PHOTO, MediaItem("b", createdAt = 2), null, jpeg(2), byteArrayOf(2))

        writer.change(note.id, "a", now = 500) { it.copy(name = "Renamed") }
        val changed = writer.document(note.id).items.first { it.id == "a" }
        assertEquals("Renamed", changed.name)
        assertEquals(500L, changed.updatedAt)
        assertEquals(1L, changed.createdAt)

        writer.delete(note.id, MediaKind.PHOTO, setOf("a"))
        assertEquals(listOf("b"), writer.document(note.id).items.map { it.id })
        assertNull(store.existing(MediaDocument.fileKey(note.id, "a"), MediaKind.PHOTO))
        assertFalse(store.thumbFile(MediaDocument.fileKey(note.id, "a"))!!.exists())
        assertNotNull(store.existing(MediaDocument.fileKey(note.id, "b"), MediaKind.PHOTO))
    }

    @Test
    fun `a file name that could leave the folder is refused`() {
        assertNull(store.file("../evil", MediaKind.PHOTO))
        assertNull(store.file("a/b", MediaKind.PHOTO))
        assertNotNull(store.file("note_item", MediaKind.PHOTO))
    }

    // ---- export and backup ----

    @Test
    fun `one item exports as the file itself and several as a zip`() = runBlocking {
        val env = TestEnv()
        val note = env.mediaNote(MediaKind.PHOTO)
        val writer = MediaNoteWriter(env.noteRepo, env.notes, store)
        val handler = MediaTransferHandler(MediaKind.PHOTO, store)
        writer.add(note.id, MediaKind.PHOTO, MediaItem("a", name = "First"), null, jpeg(1), null)
        var current = env.noteRepo.getById(note.id)!!
        assertEquals("jpg", handler.exportFormatsFor(current).single().extension)
        assertArrayEquals(jpeg(1), handler.export(current, handler.exportFormatsFor(current).single()))

        writer.add(note.id, MediaKind.PHOTO, MediaItem("b", name = "Second"), null, jpeg(2), null)
        current = env.noteRepo.getById(note.id)!!
        val format = handler.exportFormatsFor(current).single()
        assertEquals("zip", format.extension)
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(handler.export(current, format))).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(listOf("001-First.jpg", "002-Second.jpg"), names)
    }

    @Test
    fun `a backup carries the files, restores them under the new note id and ignores wrong files`() = runBlocking {
        val env = TestEnv()
        val note = env.mediaNote(MediaKind.VIDEO)
        val writer = MediaNoteWriter(env.noteRepo, env.notes, store)
        val handler = MediaTransferHandler(MediaKind.VIDEO, store)
        writer.add(note.id, MediaKind.VIDEO, MediaItem("v1"), null, mp4(1), null)
        val current = env.noteRepo.getById(note.id)!!

        val files = handler.backupAttachments(current)
        assertEquals(setOf("item-v1.mp4"), files.keys)

        val other = MediaStore(Files.createTempDirectory("media-restore").toFile())
        val restored = MediaTransferHandler(MediaKind.VIDEO, other)
        val copy = current.copy(id = "new-id")
        restored.restoreBackupAttachments(copy, files + ("item-bad.mp4" to byteArrayOf(1, 2, 3)) + ("../x.mp4" to mp4(3)))
        assertArrayEquals(mp4(1), other.read(MediaDocument.fileKey("new-id", "v1"), MediaKind.VIDEO))
        assertNull(other.existing(MediaDocument.fileKey("new-id", "bad"), MediaKind.VIDEO))
        assertEquals(0, restored.collect(copy.copy(extras = null)).attachments.size)
        assertNull(restored.importFile("x.mp4", mp4(1)))
        val unused = NoteExtras()
        assertTrue(unused.attachments.isEmpty())
        other.deleteAll()
    }

    @Test
    fun `deleting a note removes every file of it`() = runBlocking {
        val env = TestEnv()
        val note = env.mediaNote(MediaKind.PHOTO)
        val writer = MediaNoteWriter(env.noteRepo, env.notes, store)
        writer.add(note.id, MediaKind.PHOTO, MediaItem("a"), null, jpeg(1), byteArrayOf(1))
        MediaTransferHandler(MediaKind.PHOTO, store).deleteAttachments(env.noteRepo.getById(note.id)!!)
        assertEquals(0, dir.listFiles { f -> f.isFile }!!.size)
    }

    // ---- overlays ----

    @Test
    fun `an overlay sits at its place of the grid with a margin and never leaves the frame`() {
        val (x, y) = OverlayPosition.BOTTOM_RIGHT.place(1000f, 500f, 200f, 50f, 20f)
        assertEquals(780f, x, 0.01f)
        assertEquals(430f, y, 0.01f)
        val (cx, cy) = OverlayPosition.CENTER.place(1000f, 500f, 200f, 50f, 20f)
        assertEquals(400f, cx, 0.01f)
        assertEquals(225f, cy, 0.01f)
        assertEquals(20f to 20f, OverlayPosition.TOP_LEFT.place(1000f, 500f, 200f, 50f, 20f))
        assertEquals(0f to 0f, OverlayPosition.BOTTOM_RIGHT.place(100f, 100f, 300f, 300f, 20f))
    }

    @Test
    fun `overlay settings know what is to be drawn and survive storage`() {
        assertFalse(OverlaySettings().any)
        assertTrue(OverlaySettings(stamp = StampLayer(enabled = true)).any)
        assertFalse(OverlaySettings(stamp = StampLayer(enabled = true, showDate = false, showTime = false)).any)
        assertFalse(OverlaySettings(text = TextLayer(enabled = true, text = "  ")).any)
        assertTrue(OverlaySettings(text = TextLayer(enabled = true, text = "Katerina")).any)
        assertTrue(OverlaySettings(stamp = StampLayer(enabled = true)).changesWithTime)
        val s = OverlaySettings(text = TextLayer(enabled = true, text = "Hi", backgroundArgb = 0x80112233.toInt()))
        assertEquals(s, OverlaySettings.decode(s.encode()))
        assertEquals(OverlaySettings(), OverlaySettings.decode("garbage"))
    }

    // ---- sound ----

    @Test
    fun `two sounds are mixed by adding the samples and clipping, and the file is a wav`() {
        fun pcm(vararg samples: Int): ByteArray = samples.flatMap { listOf((it and 0xFF).toByte(), (it shr 8).toByte()) }.toByteArray()
        val a = File(dir, "a.pcm").also { it.writeBytes(pcm(100, 20000, -20000, 5)) }
        val b = File(dir, "b.pcm").also { it.writeBytes(pcm(50, 20000, -20000)) }
        val out = File(dir, "mix.wav")
        WavMixer.mix(listOf(a, b), out)
        val bytes = out.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(44 + 8, bytes.size)
        fun sample(i: Int) = (bytes[44 + 2 * i + 1].toInt() shl 8) or (bytes[44 + 2 * i].toInt() and 0xFF)
        assertEquals(150, sample(0))
        assertEquals(32767, sample(1))
        assertEquals(-32768, sample(2))
        assertEquals(5, sample(3))
        assertEquals(44_100, AudioCapture.RATE)
    }
}
