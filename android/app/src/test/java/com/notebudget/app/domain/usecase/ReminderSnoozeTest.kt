package com.notebudget.app.domain.usecase

import com.notebudget.app.data.mapper.toDomain
import com.notebudget.app.data.mapper.toEntity
import com.notebudget.app.data.transfer.toDomain
import com.notebudget.app.data.transfer.toRecord
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.Recurrence
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderDraft
import com.notebudget.app.domain.model.ReminderStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snooze (D150): "ring again in N minutes", with an alarm of its own that survives a reboot. */
class ReminderSnoozeTest {
    private val env = TestEnv()
    private val minute = 60_000L
    private var folders = 0

    private suspend fun create(draft: (Long) -> ReminderDraft): Reminder {
        val folder = (env.folders.create("F${folders++}", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "N", NoteType.TEXT) as DomainResult.Success).value
        return (env.reminders.create(note.id, draft(env.clock.now() + 10 * minute)) as DomainResult.Success).value
    }

    private suspend fun stored(id: String) = env.reminderRepo.getById(id)!!

    @Test
    fun `snoozing sets its own alarm and keeps the moment in the reminder`() = runBlocking {
        val r = create { ReminderDraft(triggerAt = it) }
        env.clock.current += 10 * minute
        env.reminders.onFired(r.id)
        env.clock.current += 1 * minute

        val result = env.reminders.snooze(r.id, 15)
        assertTrue(result is DomainResult.Success)
        val until = env.clock.now() + 15 * minute
        assertEquals(until, stored(r.id).snoozedUntil)
        assertEquals(until, env.scheduler.alarms[ReminderUseCases.snoozeAlarmId(r.id)])
    }

    @Test
    fun `the snooze alarm rings the reminder again once and clears the snooze`() = runBlocking {
        val r = create { ReminderDraft(triggerAt = it) }
        env.clock.current += 10 * minute
        env.reminders.onFired(r.id)
        env.reminders.snooze(r.id, 5)
        env.clock.current += 5 * minute

        val again = env.reminders.onSnoozeFired(r.id)
        assertNotNull(again)
        assertEquals(0L, stored(r.id).snoozedUntil)
        assertNull("no second ring for the same snooze", env.reminders.onSnoozeFired(r.id))
    }

    @Test
    fun `a bad length is refused and an unknown reminder too`() = runBlocking {
        val r = create { ReminderDraft(triggerAt = it) }
        assertTrue((env.reminders.snooze(r.id, 0) as DomainResult.Failure).error == DomainError.REMINDER_INVALID_REPEAT)
        assertTrue((env.reminders.snooze(r.id, 5000) as DomainResult.Failure).error == DomainError.REMINDER_INVALID_REPEAT)
        assertTrue((env.reminders.snooze("nope", 5) as DomainResult.Failure).error == DomainError.REMINDER_NOT_FOUND)
    }

    @Test
    fun `a one-time reminder that repeats stops repeating while snoozed and starts again after it`() = runBlocking {
        val r = create { ReminderDraft(triggerAt = it, requiresConfirmation = true, repeatUntilConfirmed = true, repeatIntervalMinutes = 3) }
        env.clock.current += 10 * minute
        env.reminders.onFired(r.id)
        assertNotNull("the repeat is set", env.scheduler.alarms[r.id])

        env.reminders.snooze(r.id, 20)
        assertNull("no repeat during the snooze", env.scheduler.alarms[r.id])
        env.clock.current += 20 * minute
        env.reminders.onSnoozeFired(r.id)
        assertEquals(env.clock.now() + 3 * minute, env.scheduler.alarms[r.id])
    }

    @Test
    fun `a recurring reminder keeps its next day when snoozed`() = runBlocking {
        val r = create { ReminderDraft(triggerAt = it, recurrence = Recurrence.WEEKLY, weekdaysMask = 127) }
        env.clock.current += 10 * minute
        env.reminders.onFired(r.id)
        val next = env.scheduler.alarms[r.id]
        assertNotNull(next)

        env.reminders.snooze(r.id, 10)
        assertEquals("the next day stays armed", next, env.scheduler.alarms[r.id])
        assertNotNull(env.scheduler.alarms[ReminderUseCases.snoozeAlarmId(r.id)])
    }

    @Test
    fun `after a reboot a snooze that is still to come is armed again and one that ended rings now`() = runBlocking {
        val r = create { ReminderDraft(triggerAt = it) }
        env.clock.current += 10 * minute
        env.reminders.onFired(r.id)
        env.reminders.snooze(r.id, 30)
        env.scheduler.alarms.clear()

        assertTrue(env.reminders.restoreAll().isEmpty())
        assertEquals(stored(r.id).snoozedUntil, env.scheduler.alarms[ReminderUseCases.snoozeAlarmId(r.id)])

        env.clock.current += 31 * minute
        env.scheduler.alarms.clear()
        val due = env.reminders.restoreAll()
        assertEquals(listOf(r.id), due.map { it.id })
        assertEquals(0L, stored(r.id).snoozedUntil)
    }

    @Test
    fun `confirming, cancelling, deleting and editing end a snooze`() = runBlocking {
        val a = create { ReminderDraft(triggerAt = it, requiresConfirmation = true) }
        env.clock.current += 10 * minute
        env.reminders.onFired(a.id)
        env.reminders.snooze(a.id, 5)
        env.reminders.onConfirmed(a.id)
        assertEquals(0L, stored(a.id).snoozedUntil)
        assertEquals(ReminderStatus.COMPLETED, stored(a.id).status)
        assertNull(env.scheduler.alarms[ReminderUseCases.snoozeAlarmId(a.id)])

        val b = create { ReminderDraft(triggerAt = env.clock.now() + 10 * minute) }
        env.reminders.snooze(b.id, 5)
        env.reminders.cancel(b.id)
        assertNull(env.scheduler.alarms[ReminderUseCases.snoozeAlarmId(b.id)])
        env.reminders.snooze(b.id, 5)
        env.reminders.delete(b.id)
        assertNull(env.scheduler.alarms[ReminderUseCases.snoozeAlarmId(b.id)])

        val c = create { ReminderDraft(triggerAt = env.clock.now() + 10 * minute) }
        env.reminders.snooze(c.id, 5)
        env.reminders.update(c.id, ReminderDraft(triggerAt = env.clock.now() + 60 * minute))
        assertEquals(0L, stored(c.id).snoozedUntil)
        assertNull(env.scheduler.alarms[ReminderUseCases.snoozeAlarmId(c.id)])
    }

    @Test
    fun `the snooze travels in the database and in a backup`() {
        val r = Reminder(
            id = "r", noteId = "n", type = com.notebudget.app.domain.model.ReminderType.ONE_TIME, triggerAt = 5, isEnabled = true,
            status = ReminderStatus.FIRED, soundEnabled = true, soundUri = null, vibrationEnabled = true, popupEnabled = true,
            requiresConfirmation = false, repeatUntilConfirmed = false, repeatIntervalMinutes = null, createdAt = 1, updatedAt = 1,
            snoozedUntil = 777,
        )
        assertEquals(777L, r.toEntity().toDomain().snoozedUntil)
        assertEquals(777L, r.toRecord().toDomain().snoozedUntil)
    }
}

/** The numbers behind the notification that stays in the shade while a reminder is set (D154). */
class ReminderSummaryTest {
    private fun r(id: String, at: Long, status: ReminderStatus = ReminderStatus.SCHEDULED, snoozed: Long = 0, enabled: Boolean = true) = Reminder(
        id = id, noteId = "n", type = com.notebudget.app.domain.model.ReminderType.ONE_TIME, triggerAt = at, isEnabled = enabled,
        status = status, soundEnabled = true, soundUri = null, vibrationEnabled = true, popupEnabled = true, requiresConfirmation = false,
        repeatUntilConfirmed = false, repeatIntervalMinutes = null, createdAt = 1, updatedAt = 1, snoozedUntil = snoozed,
    )

    @Test
    fun `no active reminder means no notification`() {
        assertNull(com.notebudget.app.domain.model.ReminderSummary.of(emptyList()))
        assertNull(com.notebudget.app.domain.model.ReminderSummary.of(listOf(r("a", 5, ReminderStatus.COMPLETED), r("b", 6, ReminderStatus.CANCELLED), r("c", 7, enabled = false))))
    }

    @Test
    fun `the count and the nearest moment, a snooze counting with its own end`() {
        val s = com.notebudget.app.domain.model.ReminderSummary.of(listOf(r("a", 500), r("b", 300, ReminderStatus.AWAITING_CONFIRMATION), r("c", 100, ReminderStatus.FIRED, snoozed = 900)))!!
        assertEquals(3, s.count)
        assertEquals(300L, s.nextAt)
        assertEquals(900L, com.notebudget.app.domain.model.ReminderSummary.of(listOf(r("c", 100, ReminderStatus.FIRED, snoozed = 900)))!!.nextAt)
    }
}
