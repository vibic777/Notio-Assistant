package com.notebudget.app.core.notification

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.domain.model.DateTimeFormats
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderStatus
import com.notebudget.app.domain.model.ReminderType
import com.notebudget.app.domain.usecase.FakeNoteRepository
import com.notebudget.app.domain.usecase.FakeReminderRepository
import com.notebudget.app.domain.usecase.FakeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D331: the notification in the shade (D154) is posted when its words change, not at every refresh — an alarm that goes off
 * asks for it several times, and a process started for an alarm must not post again what is already there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderStatusNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val store = FakeStore()
    private val formats = DateTimeFormats()

    private fun status() = ReminderStatusNotification(app, FakeReminderRepository(store), FakeNoteRepository(store), now = { 1_000 })

    private fun reminder(id: String, at: Long) = Reminder(
        id = id, noteId = "n1", type = ReminderType.entries.first(), triggerAt = at, isEnabled = true,
        status = ReminderStatus.SCHEDULED, soundEnabled = true, soundUri = null, vibrationEnabled = false, popupEnabled = false,
        requiresConfirmation = false, repeatUntilConfirmed = false, repeatIntervalMinutes = null, createdAt = 0, updatedAt = 0,
    )

    private fun shown() = shadowOf(manager).allNotifications.singleOrNull()

    @Test
    fun `it is posted when its words change and left alone when they do not`() = runBlocking {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        store.notes.value = listOf(Note("n1", "f", "Note", NoteType.TEXT, null, false, false, false, "text", 1, 1, null))
        store.reminders.value = listOf(reminder("r1", 5_000_000))
        val status = status()

        status.refresh(enabled = true, formats = formats)
        val first = shown()
        assertNotNull(first)
        // The same again, as an alarm and the change it made ask for it: nothing is posted.
        status.refresh(enabled = true, formats = formats)
        status.refresh(enabled = true, formats = formats)
        assertSame(first, shown())

        // A process started later (for an alarm) finds the same words in the shade and leaves them.
        status().refresh(enabled = true, formats = formats)
        assertSame(first, shown())

        // Another reminder: other words, posted.
        store.reminders.value = store.reminders.value + reminder("r2", 9_000_000)
        status.refresh(enabled = true, formats = formats)
        assertNotSame(first, shown())
        assertEquals(1, shadowOf(manager).allNotifications.size)

        // Switched off in the settings: taken away; on again: back.
        status.refresh(enabled = false, formats = formats)
        assertNull(shown())
        status.refresh(enabled = true, formats = formats)
        assertNotNull(shown())
    }
}
