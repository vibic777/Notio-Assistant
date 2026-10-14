package com.notio.desktop

import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.NotioEditor
import com.notio.shared.domain.DomainResult
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.ReminderDraft
import com.notio.shared.domain.ReminderStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A reminder set a moment ahead rings in this process, then Snooze and Done follow the phone's rules. */
class ReminderServiceTest {
    @Test
    fun aReminderRingsAndIsSnoozedAndDone() = runBlocking {
        val dir = Files.createTempDirectory("notio-rem").toFile()
        val db = NotioDatabase.inMemory()
        val editor = NotioEditor(db, AttachmentStore(dir))
        val note = editor.createNote(editor.createFolder("F", "#3366FF", "SOFT").id, "Встреча", NoteType.TEXT)
        val service = ReminderService(db)
        try {
            val created = service.useCases.create(note.id, ReminderDraft(triggerAt = System.currentTimeMillis() + 1_500, soundEnabled = false))
            assertTrue(created is DomainResult.Success, created.toString())
            withTimeout(10_000) { while (service.ringing.value.isEmpty()) delay(50) }
            val ringing = service.ringing.value.single()
            assertEquals("Встреча", ringing.noteTitle)
            assertEquals(ReminderStatus.FIRED, db.reminders().single().status)
            service.snooze(ringing, 1)
            withTimeout(5_000) { while (db.reminders().single().snoozedUntil == 0L) delay(20) }
            assertTrue(service.ringing.value.isEmpty())
            assertTrue(service.hasActive())
        } finally {
            service.close()
            db.close()
            dir.deleteRecursively()
        }
    }

    /** Android D293: Stop ends the melody and leaves the reminder ringing; a melody nobody stops ends by itself. A silent WAV: nothing is heard. */
    @Test
    fun aMelodyStopsByStopAndByItself() = runBlocking {
        val dir = Files.createTempDirectory("notio-rem").toFile()
        val silent = java.io.File(dir, "silent.wav").apply {
            val format = javax.sound.sampled.AudioFormat(8_000f, 16, 1, true, false)
            val samples = ByteArray(8_000 * 2)
            javax.sound.sampled.AudioSystem.write(
                javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(samples), format, 8_000L),
                javax.sound.sampled.AudioFileFormat.Type.WAVE, this,
            )
        }
        // No sound device (a build machine): nothing to check.
        if (runCatching { javax.sound.sampled.AudioSystem.getClip().close() }.isFailure) return@runBlocking
        val db = NotioDatabase.inMemory()
        val editor = NotioEditor(db, AttachmentStore(dir))
        val folder = editor.createFolder("F", "#3366FF", "SOFT").id
        val service = ReminderService(db, ringMillis = 1_500)
        try {
            val first = editor.createNote(folder, "Звонок", NoteType.TEXT)
            service.useCases.create(first.id, ReminderDraft(triggerAt = System.currentTimeMillis() + 500, soundUri = silent.absolutePath))
            withTimeout(10_000) { while (service.sounding.value == null) delay(20) }
            service.silence()
            assertEquals(null, service.sounding.value)
            assertEquals(1, service.ringing.value.size, "the reminder stays to be answered")

            val second = editor.createNote(folder, "Второй", NoteType.TEXT)
            service.useCases.create(second.id, ReminderDraft(triggerAt = System.currentTimeMillis() + 300, soundUri = silent.absolutePath))
            withTimeout(10_000) { while (service.sounding.value == null) delay(20) }
            withTimeout(10_000) { while (service.sounding.value != null) delay(20) }
            assertEquals(2, service.ringing.value.size, "ended by itself, both still to be answered")
        } finally {
            service.close()
            db.close()
            dir.deleteRecursively()
        }
    }
}
