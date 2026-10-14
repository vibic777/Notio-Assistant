package com.notebudget.app.domain.usecase

import com.notebudget.app.data.transfer.NoteRecord
import com.notebudget.app.data.transfer.TransferFormat
import com.notebudget.app.data.transfer.toDomain
import com.notebudget.app.data.transfer.toRecord
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A note that hides its content until the eye is pressed (D363): the option is the note's own and travels with it. */
class NoteHiddenTest {
    private val env = TestEnv()

    @Test
    fun `the option is kept, an edit of the text does not drop it, and it moves the modified time`() = runTest {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "Letter", NoteType.TEXT, contentText = "a secret") as DomainResult.Success).value
        assertFalse(note.hidden)

        env.clock.current += 1_000
        val hidden = (env.notes.setHidden(note.id, true) as DomainResult.Success).value
        assertTrue(hidden.hidden)
        assertTrue(hidden.updatedAt > note.updatedAt)
        assertTrue(env.noteRepo.getById(note.id)!!.hidden)
        // Set again: nothing changes, the time stays.
        assertEquals(hidden.updatedAt, (env.notes.setHidden(note.id, true) as DomainResult.Success).value.updatedAt)

        // The editor saves the note it opened with (not hidden then): the stored option stays.
        env.notes.save(note.copy(contentText = "another"))
        val stored = env.noteRepo.getById(note.id)!!
        assertEquals("another", stored.contentText)
        assertTrue(stored.hidden)

        assertFalse((env.notes.setHidden(note.id, false) as DomainResult.Success).value.hidden)
        assertTrue(env.notes.setHidden("no such note", true) is DomainResult.Failure)
    }

    @Test
    fun `the option travels in a backup and a sync, and a file without it is not hidden`() {
        val record = TransferFormat.json.decodeFromString(NoteRecord.serializer(), """{"id":"n","title":"T","hidden":true}""")
        assertTrue(record.toDomain().hidden)
        assertTrue(record.toDomain().toRecord().hidden)
        // A file of an older Notio.
        assertFalse(TransferFormat.json.decodeFromString(NoteRecord.serializer(), """{"id":"n","title":"T"}""").toDomain().hidden)
        // What a newer Notio might add later is skipped the same way, so an older Notio reads this file too.
        assertTrue(TransferFormat.json.decodeFromString(NoteRecord.serializer(), """{"id":"n","title":"T","hidden":true,"later":1}""").hidden)
    }
}
