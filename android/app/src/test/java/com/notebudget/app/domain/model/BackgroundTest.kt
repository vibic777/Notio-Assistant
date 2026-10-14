package com.notebudget.app.domain.model

import com.notebudget.app.core.ui.effectiveBackgrounds
import com.notebudget.app.data.BackgroundImages
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Backgrounds (D110): the stored form, the shrinking of pictures, and which slot a note kind uses. */
class BackgroundTest {
    @Test
    fun `a background is stored as text and read back`() {
        assertEquals("NONE", BackgroundSpec.None.encode())
        assertEquals("COLOR:#0A1B2C", BackgroundSpec.Color(0xFF0A1B2C.toInt()).encode())
        assertEquals("IMAGE:1234", BackgroundSpec.Image(1234).encode())
        for (spec in listOf(BackgroundSpec.None, BackgroundSpec.Color(0xFFFF8800.toInt()), BackgroundSpec.Image(99))) {
            assertEquals(spec, BackgroundSpec.decode(spec.encode()))
        }
    }

    @Test
    fun `unreadable stored values are the default background`() {
        for (bad in listOf(null, "", "COLOR:", "COLOR:#12", "COLOR:#GGGGGG", "IMAGE:x", "PICTURE:1", "  ")) {
            assertEquals("'$bad'", BackgroundSpec.None, BackgroundSpec.decode(bad))
        }
        assertEquals(BackgroundSpec.Image(0), BackgroundSpec.decode("IMAGE"))
    }

    @Test
    fun `a big picture is shrunk by powers of two to about 1600 pixels and a small one is left alone`() {
        assertEquals(1, BackgroundImageMath.sampleSize(800, 600))
        assertEquals(1, BackgroundImageMath.sampleSize(1600, 1200))
        assertEquals(2, BackgroundImageMath.sampleSize(3200, 2400))
        assertEquals(4, BackgroundImageMath.sampleSize(6400, 3000))
        assertEquals(4, BackgroundImageMath.sampleSize(4000, 8000)) // the long side counts
        assertEquals(8, BackgroundImageMath.sampleSize(12_800, 9_000))
    }

    @Test
    fun `every kind of note has its own slot and an unknown kind has none`() {
        assertEquals(BackgroundSlot.NOTE_TEXT, BackgroundSlot.forNote(NoteType.TEXT))
        assertEquals(BackgroundSlot.NOTE_LIST, BackgroundSlot.forNote(NoteType.LIST))
        assertEquals(BackgroundSlot.NOTE_BUDGET, BackgroundSlot.forNote(NoteType.BUDGET))
        assertEquals(BackgroundSlot.NOTE_DRAWING, BackgroundSlot.forNote(NoteType.DRAWING))
        assertEquals(BackgroundSlot.NOTE_BLUEPRINT, BackgroundSlot.forNote(NoteType("BLUEPRINT")))
        assertEquals(BackgroundSlot.NOTE_AUDIO, BackgroundSlot.forNote(NoteType.AUDIO))
        assertEquals(BackgroundSlot.NOTE_SCHEDULE, BackgroundSlot.forNote(NoteType.SCHEDULE))
        assertEquals(BackgroundSlot.NOTE_PHOTO, BackgroundSlot.forNote(MediaKind.PHOTO.type))
        assertEquals(BackgroundSlot.NOTE_VIDEO, BackgroundSlot.forNote(MediaKind.VIDEO.type))
        assertEquals(BackgroundSlot.NOTE_SCREEN, BackgroundSlot.forNote(MediaKind.SCREEN.type))
        assertNull(BackgroundSlot.forNote(NoteType("MINDMAP")))
    }

    @Test
    fun `no kind of note that the app can create is left without a background slot`() {
        // Every kind the app offers in the new-note dialog, one by one: a new kind must be added to this list AND get a slot.
        val kinds = listOf(
            NoteType.TEXT, NoteType.WORD, NoteType.LIST, NoteType.BUDGET, NoteType.DRAWING, NoteType.BLUEPRINT, NoteType.AUDIO, NoteType.SCHEDULE,
            // The task (D239) is made in a folder of tasks, not in the new-note list, but has its slot too.
            NoteType.TASK,
        ) + MediaKind.entries.map { it.type }
        val slots = kinds.map { BackgroundSlot.forNote(it) }
        assertEquals(emptyList<NoteType>(), kinds.filterIndexed { i, _ -> slots[i] == null })
        assertEquals("a slot per kind, none shared", kinds.size, slots.toSet().size)
        // Twelve kinds, plus the whole app and the folder cards.
        assertEquals(kinds.size + 2, BackgroundSlot.entries.size)
    }

    @Test
    fun `a picture whose file is gone counts as the default, a colour always stays`() {
        val dir: File = Files.createTempDirectory("bg-test").toFile().also { it.deleteOnExit() }
        val images = BackgroundImages(dir)
        images.file(BackgroundSlot.NOTE_TEXT).also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1))
        val specs = mapOf(
            BackgroundSlot.APP to BackgroundSpec.Image(1),
            BackgroundSlot.NOTE_TEXT to BackgroundSpec.Image(2),
            BackgroundSlot.FOLDERS to BackgroundSpec.Color(0xFF112233.toInt()),
        )
        val effective = effectiveBackgrounds(specs, images)
        assertEquals(BackgroundSpec.None, effective[BackgroundSlot.APP])
        assertEquals(BackgroundSpec.Image(2), effective[BackgroundSlot.NOTE_TEXT])
        assertEquals(BackgroundSpec.Color(0xFF112233.toInt()), effective[BackgroundSlot.FOLDERS])
        images.delete(BackgroundSlot.NOTE_TEXT)
        assertEquals(false, images.exists(BackgroundSlot.NOTE_TEXT))
    }
}
