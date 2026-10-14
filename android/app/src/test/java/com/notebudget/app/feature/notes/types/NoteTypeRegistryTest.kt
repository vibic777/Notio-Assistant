package com.notebudget.app.feature.notes.types

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.notebudget.app.data.transfer.ExportFormat
import com.notebudget.app.data.transfer.ImportedNote
import com.notebudget.app.data.transfer.NoteTransferHandler
import com.notebudget.app.data.transfer.TextTransferHandler
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** A made-up kind, to prove that adding one needs no change to the core. */
private class StickerNoteType(override val creatable: Boolean = true) : NoteTypeDefinition {
    override val type = NoteType("STICKER")
    override val labelRes = com.notebudget.app.R.string.note_type_text
    override val transfer = object : NoteTransferHandler {
        override val exportFormats = emptyList<ExportFormat>()
        override suspend fun export(note: Note, format: ExportFormat) = ByteArray(0)
        override suspend fun importFile(fileName: String, bytes: ByteArray): ImportedNote? =
            if (fileName.endsWith(".sticker")) ImportedNote("sticker", NoteType("STICKER")) else null
    }

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = Unit
}

class NoteTypeRegistryTest {
    private val text = TextNoteType(TextTransferHandler())

    @Test
    fun `a new kind is found by its id and offered for creation`() {
        val registry = NoteTypeRegistry(listOf(text, StickerNoteType()))
        assertEquals(NoteType("STICKER"), registry.definitionFor(NoteType("STICKER")).type)
        assertEquals(listOf(NoteType.TEXT, NoteType("STICKER")), registry.creatable.map { it.type })
        assertEquals(2, registry.allHandlers.size)
    }

    @Test
    fun `a hidden kind is known but not offered`() {
        val registry = NoteTypeRegistry(listOf(text, StickerNoteType(creatable = false)))
        assertEquals(listOf(NoteType.TEXT), registry.creatable.map { it.type })
        assertTrue(registry.handlerFor(NoteType("STICKER")) != null)
    }

    @Test
    fun `an unknown kind gets a placeholder and no handler, never a crash`() {
        val registry = NoteTypeRegistry(listOf(text))
        val unknown = registry.definitionFor(NoteType("FROM_THE_FUTURE"))
        assertTrue(unknown is UnsupportedNoteType)
        assertFalse(unknown.creatable)
        assertNull(registry.handlerFor(NoteType("FROM_THE_FUTURE")))
    }

    @Test
    fun `two definitions with the same id are a programming error`() {
        try {
            NoteTypeRegistry(listOf(text, TextNoteType(TextTransferHandler())))
            fail("expected duplicate ids to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `file import is offered to every registered kind`() = runBlocking {
        val registry = NoteTypeRegistry(listOf(text, StickerNoteType()))
        val found = registry.allHandlers.firstNotNullOfOrNull { it.importFile("a.sticker", ByteArray(1)) }
        assertEquals(NoteType("STICKER"), found?.type)
        val md = registry.allHandlers.firstNotNullOfOrNull { it.importFile("a.md", "x".toByteArray()) }
        assertEquals(NoteType.TEXT, md?.type)
    }
}
