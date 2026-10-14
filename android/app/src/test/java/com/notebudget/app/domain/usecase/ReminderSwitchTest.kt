package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.Recurrence
import com.notebudget.app.domain.model.RecurrenceRules
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on / off switch of a reminder (D268). */
class ReminderSwitchTest {
    private val env = TestEnv()
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private suspend fun create(draft: ReminderDraft): Reminder {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "N", NoteType.TEXT) as DomainResult.Success).value
        return (env.reminders.create(note.id, draft) as DomainResult.Success).value
    }

    @Test
    fun `off removes the alarm and keeps the time, on sets it back`() = runTest {
        val r = create(ReminderDraft(triggerAt = env.clock.current + 10 * minute))
        val off = (env.reminders.setEnabled(r.id, false) as DomainResult.Success).value
        assertFalse(off.isEnabled)
        assertEquals(r.triggerAt, off.triggerAt)
        assertNull(env.scheduler.alarms[r.id])
        assertTrue(env.reminderRepo.getActive().none { it.id == r.id })
        // Switched off, a late alarm does nothing.
        assertNull(env.reminders.onFired(r.id))

        val on = (env.reminders.setEnabled(r.id, true) as DomainResult.Success).value
        assertTrue(on.isEnabled)
        assertEquals(r.triggerAt, env.scheduler.alarms[r.id])
    }

    @Test
    fun `a one-time reminder whose time went by cannot be switched on`() = runTest {
        val r = create(ReminderDraft(triggerAt = env.clock.current + 10 * minute))
        env.reminders.setEnabled(r.id, false)
        env.clock.current += 20 * minute
        assertEquals(DomainResult.Failure(DomainError.REMINDER_IN_PAST), env.reminders.setEnabled(r.id, true))
        assertNull(env.scheduler.alarms[r.id])
    }

    @Test
    fun `a daily reminder switched on after its time comes back at its next day`() = runTest {
        val first = env.clock.current + 10 * minute
        val r = create(ReminderDraft(triggerAt = first, recurrence = Recurrence.WEEKLY, weekdaysMask = RecurrenceRules.ALL_DAYS))
        env.reminders.setEnabled(r.id, false)
        env.clock.current += 3 * day
        val on = (env.reminders.setEnabled(r.id, true) as DomainResult.Success).value
        assertTrue(on.triggerAt > env.clock.current)
        assertTrue(on.triggerAt - env.clock.current <= day)
        assertEquals(on.triggerAt, env.scheduler.alarms[r.id])
    }
}
