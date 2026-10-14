package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderDraft
import com.notebudget.app.domain.model.ReminderStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderUseCasesTest {
    private val env = TestEnv()
    private val minute = 60_000L

    private suspend fun noteId(): String {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (env.notes.create(folder.id, "N", NoteType.TEXT) as DomainResult.Success).value.id
    }

    private suspend fun create(noteId: String, draft: ReminderDraft): Reminder =
        (env.reminders.create(noteId, draft) as DomainResult.Success).value

    @Test
    fun `creating schedules an alarm and keeps status SCHEDULED`() = runTest {
        val r = create(noteId(), ReminderDraft(triggerAt = env.clock.current + 5 * minute))
        assertEquals(ReminderStatus.SCHEDULED, r.status)
        assertEquals(r.triggerAt, env.scheduler.alarms[r.id])
    }

    @Test
    fun `a reminder in the past or with an invalid repeat is rejected`() = runTest {
        val n = noteId()
        val past = ReminderDraft(triggerAt = env.clock.current - 1)
        assertEquals(DomainResult.Failure(DomainError.REMINDER_IN_PAST), env.reminders.create(n, past))

        val future = env.clock.current + minute
        val repeatWithoutConfirm = ReminderDraft(future, repeatUntilConfirmed = true, repeatIntervalMinutes = 5)
        val repeatWithoutInterval = ReminderDraft(future, requiresConfirmation = true, repeatUntilConfirmed = true)
        assertEquals(DomainResult.Failure(DomainError.REMINDER_INVALID_REPEAT), env.reminders.create(n, repeatWithoutConfirm))
        assertEquals(DomainResult.Failure(DomainError.REMINDER_INVALID_REPEAT), env.reminders.create(n, repeatWithoutInterval))
        assertTrue(env.scheduler.alarms.isEmpty())
    }

    @Test
    fun `firing without confirmation ends in FIRED`() = runTest {
        val r = create(noteId(), ReminderDraft(triggerAt = env.clock.current + minute))
        env.clock.current += 2 * minute
        val fired = env.reminders.onFired(r.id)
        assertEquals(ReminderStatus.FIRED, fired?.status)
        assertEquals(ReminderStatus.FIRED, env.reminderRepo.getById(r.id)?.status)
    }

    @Test
    fun `firing with confirmation waits, repeats until confirmed, then stops`() = runTest {
        val start = env.clock.current
        val r = create(
            noteId(),
            ReminderDraft(
                triggerAt = start + minute,
                requiresConfirmation = true,
                repeatUntilConfirmed = true,
                repeatIntervalMinutes = 10,
            ),
        )
        env.clock.current = start + minute
        val fired = env.reminders.onFired(r.id)
        assertEquals(ReminderStatus.AWAITING_CONFIRMATION, fired?.status)
        assertEquals(start + minute + 10 * minute, env.scheduler.alarms[r.id])

        // It fires again while unconfirmed and re-arms itself.
        env.clock.current = start + 11 * minute
        assertEquals(ReminderStatus.AWAITING_CONFIRMATION, env.reminders.onFired(r.id)?.status)
        assertEquals(start + 21 * minute, env.scheduler.alarms[r.id])

        env.reminders.onConfirmed(r.id)
        assertEquals(ReminderStatus.COMPLETED, env.reminderRepo.getById(r.id)?.status)
        assertTrue(env.scheduler.alarms.isEmpty())
        assertNull(env.reminders.onFired(r.id))
    }

    @Test
    fun `cancelled and deleted reminders are ignored when the alarm fires`() = runTest {
        val n = noteId()
        val cancelled = create(n, ReminderDraft(triggerAt = env.clock.current + minute))
        val deleted = create(n, ReminderDraft(triggerAt = env.clock.current + minute))

        env.reminders.cancel(cancelled.id)
        env.reminders.delete(deleted.id)

        assertNull(env.reminders.onFired(cancelled.id))
        assertNull(env.reminders.onFired(deleted.id))
        assertTrue(env.scheduler.alarms.isEmpty())
    }

    @Test
    fun `editing re-arms the reminder with the new time`() = runTest {
        val r = create(noteId(), ReminderDraft(triggerAt = env.clock.current + minute))
        env.clock.current += 2 * minute
        env.reminders.onFired(r.id)

        val newTime = env.clock.current + 30 * minute
        val updated = (env.reminders.update(r.id, ReminderDraft(triggerAt = newTime)) as DomainResult.Success).value

        assertEquals(ReminderStatus.SCHEDULED, updated.status)
        assertEquals(newTime, env.scheduler.alarms[r.id])
    }

    @Test
    fun `restoreAll re-arms future reminders after a reboot (INV-8)`() = runTest {
        val r = create(noteId(), ReminderDraft(triggerAt = env.clock.current + 30 * minute))
        env.scheduler.alarms.clear() // reboot wipes alarms

        val due = env.reminders.restoreAll()

        assertTrue(due.isEmpty())
        assertEquals(r.triggerAt, env.scheduler.alarms[r.id])
    }

    @Test
    fun `restoreAll fires overdue reminders immediately (D78)`() = runTest {
        val r = create(noteId(), ReminderDraft(triggerAt = env.clock.current + minute))
        env.scheduler.alarms.clear()
        env.clock.current += 10 * minute // device was off past the trigger time

        val due = env.reminders.restoreAll()

        assertEquals(listOf(r.id), due.map { it.id })
        assertEquals(ReminderStatus.FIRED, env.reminderRepo.getById(r.id)?.status)
        assertTrue(env.scheduler.alarms.isEmpty())
    }

    @Test
    fun `restoreAll keeps repeating unconfirmed reminders`() = runTest {
        val start = env.clock.current
        val r = create(
            noteId(),
            ReminderDraft(start + minute, requiresConfirmation = true, repeatUntilConfirmed = true, repeatIntervalMinutes = 5),
        )
        env.clock.current = start + minute
        env.reminders.onFired(r.id)
        env.scheduler.alarms.clear()

        env.clock.current = start + 3 * minute
        env.reminders.restoreAll()

        assertEquals(start + 3 * minute + 5 * minute, env.scheduler.alarms[r.id])
    }

    @Test
    fun `restoreAll skips cancelled and completed reminders`() = runTest {
        val n = noteId()
        val a = create(n, ReminderDraft(triggerAt = env.clock.current + minute))
        val b = create(n, ReminderDraft(triggerAt = env.clock.current + minute))
        env.reminders.cancel(a.id)
        env.reminders.onConfirmed(b.id)

        env.reminders.restoreAll()

        assertTrue(env.scheduler.alarms.isEmpty())
    }
}
