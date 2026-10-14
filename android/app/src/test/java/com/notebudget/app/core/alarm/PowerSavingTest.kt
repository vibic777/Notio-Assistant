package com.notebudget.app.core.alarm

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.core.common.PowerSaving
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** D308: the battery saver is kept, the screens follow it, and reminders are still set while it is on (as alarms the system may batch). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PowerSavingTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val alarms = app.getSystemService(AlarmManager::class.java)

    @After
    fun off() = PowerSaving.set(app, false)

    @Test
    fun theSwitchIsKeptAndFollowedByTheScreens() {
        assertFalse(PowerSaving.isOn(app))
        PowerSaving.set(app, true)
        assertTrue(PowerSaving.isOn(app))
        assertTrue(PowerSaving.enabled.value)
    }

    @Test
    fun aReminderIsStillSetWithTheSaverOn() {
        // A new installation has the reminders off (D328): the person turned them on.
        RemindersSwitch.set(app, true)
        PowerSaving.set(app, true)
        val before = shadowOf(alarms).scheduledAlarms.size
        AndroidReminderScheduler(app).schedule("a", System.currentTimeMillis() + 3_600_000)
        assertEquals(before + 1, shadowOf(alarms).scheduledAlarms.size)
    }
}
