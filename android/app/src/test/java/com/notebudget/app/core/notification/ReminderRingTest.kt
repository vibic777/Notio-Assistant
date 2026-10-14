package com.notebudget.app.core.notification

import android.app.Notification
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.core.alarm.ReminderReceiver
import com.notebudget.app.domain.model.NotificationPrivacy
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderStatus
import com.notebudget.app.domain.model.ReminderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** D293: a reminder whose melody plays has Stop in its notification, and Stop silences it without ending the reminder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderRingTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun reminder(volume: Int) = Reminder(
        id = "r1", noteId = "n1", type = ReminderType.entries.first(), triggerAt = 0, isEnabled = true,
        status = ReminderStatus.SCHEDULED, soundEnabled = true, soundUri = null, vibrationEnabled = false, popupEnabled = false,
        requiresConfirmation = true, repeatUntilConfirmed = false, repeatIntervalMinutes = null, createdAt = 0, updatedAt = 0,
        volumePercent = volume,
    )

    @Test
    fun `a reminder rung by the melody service has Stop sound in its notification`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ReminderNotifier(app).show(reminder(volume = 50), null, NotificationPrivacy.GENERIC)
        val started: Intent = shadowOf(app).nextStartedService
        assertEquals(ReminderRingService::class.java.name, started.component?.className)
        assertEquals("r1", started.getStringExtra(ReminderRingService.EXTRA_REMINDER_ID))
        @Suppress("DEPRECATION")
        val notification = started.getParcelableExtra<Notification>(ReminderRingService.EXTRA_NOTIFICATION)
        assertNotNull(notification)
        val stop = notification!!.actions.firstOrNull { it.title.toString() == app.getString(com.notebudget.app.R.string.reminder_stop_sound) }
        assertNotNull("the notification has Stop sound", stop)
        val sent = shadowOf(stop!!.actionIntent).savedIntent
        assertEquals(ReminderReceiver.ACTION_SILENCE, sent.action)
        // Snooze and Done are still there: Stop only ends the sound.
        assertTrue(notification.actions.size >= 3)
    }

    @Test
    fun `a reminder with the default volume is rung by the melody service too`() {
        // D296: the channel played it before, with nothing to stop it and nothing shown in the app.
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ReminderNotifier(app).show(reminder(volume = 100), null, NotificationPrivacy.GENERIC)
        val started: Intent = shadowOf(app).nextStartedService
        assertEquals(ReminderRingService::class.java.name, started.component?.className)
        @Suppress("DEPRECATION")
        val notification = started.getParcelableExtra<Notification>(ReminderRingService.EXTRA_NOTIFICATION)!!
        assertTrue(notification.actions.any { it.title.toString() == app.getString(com.notebudget.app.R.string.reminder_stop_sound) })
        // Swiping the notification away ends the ring as Turn off does.
        assertEquals(ReminderReceiver.ACTION_DISMISS, shadowOf(notification.deleteIntent).savedIntent.action)
    }

    @Test
    fun `a silent reminder is shown by its channel and the page knows it calls now until it is reset`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifier = ReminderNotifier(app)
        val before = ReminderNotifier.changes.value
        notifier.show(reminder(volume = 100).copy(soundEnabled = false), null, NotificationPrivacy.GENERIC)
        assertEquals(setOf("r1"), notifier.shownNow(listOf("r1", "r2")))
        assertTrue(ReminderNotifier.changes.value > before)
        notifier.dismiss("r1")
        assertTrue(notifier.shownNow(listOf("r1", "r2")).isEmpty())
    }
}
