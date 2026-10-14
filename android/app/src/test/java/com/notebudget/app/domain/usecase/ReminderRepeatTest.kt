package com.notebudget.app.domain.usecase

import com.notebudget.app.data.mapper.toDomain
import com.notebudget.app.data.mapper.toEntity
import com.notebudget.app.data.transfer.toDomain
import com.notebudget.app.data.transfer.toRecord
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.Recurrence
import com.notebudget.app.domain.model.RecurrenceRules
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderDraft
import com.notebudget.app.domain.model.ReminderStatus
import com.notebudget.app.domain.model.RingRamp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extra rings like an alarm clock, days of the week, a day of the month, volume and fade-in (D130). */
class ReminderRepeatTest {
    private val zone = ZoneId.systemDefault()
    private val minute = 60_000L

    private fun millis(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(y, m, d, h, min), zone).toInstant().toEpochMilli()

    // ---------------- the calendar rules ----------------

    @Test
    fun `every day means the next day at the same time, and a weekly rule picks the next chosen weekday`() {
        val monday9 = millis(2026, 9, 21, 9) // 2026-09-21 is a Monday
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2026, 9, 21).dayOfWeek)
        assertEquals(millis(2026, 9, 22, 9), RecurrenceRules.next(Recurrence.WEEKLY, RecurrenceRules.ALL_DAYS, 0, monday9, monday9, zone))

        val monAndThu = RecurrenceRules.maskOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        assertEquals(millis(2026, 9, 24, 9), RecurrenceRules.next(Recurrence.WEEKLY, monAndThu, 0, monday9, monday9, zone))
        assertEquals("from Thursday the next is Monday", millis(2026, 9, 28, 9), RecurrenceRules.next(Recurrence.WEEKLY, monAndThu, 0, monday9, millis(2026, 9, 24, 9), zone))
        val mondayOnly = RecurrenceRules.maskOf(DayOfWeek.MONDAY)
        assertEquals("a weekly Monday reminder is a week later", millis(2026, 9, 28, 9), RecurrenceRules.next(Recurrence.WEEKLY, mondayOnly, 0, monday9, monday9, zone))
    }

    @Test
    fun `later the same day counts, earlier the same day does not`() {
        val mondayOnly = RecurrenceRules.maskOf(DayOfWeek.MONDAY)
        val at9 = millis(2026, 9, 21, 9)
        assertEquals("it is Monday 07:00: today at 09:00 is next", at9, RecurrenceRules.next(Recurrence.WEEKLY, mondayOnly, 0, at9, millis(2026, 9, 21, 7), zone))
        assertEquals("it is Monday 09:00 sharp: the next Monday", millis(2026, 9, 28, 9), RecurrenceRules.next(Recurrence.WEEKLY, mondayOnly, 0, at9, at9, zone))
        assertEquals(millis(2026, 9, 28, 9), RecurrenceRules.next(Recurrence.WEEKLY, mondayOnly, 0, at9, millis(2026, 9, 21, 12), zone))
    }

    @Test
    fun `a day of the month comes back every month and a month without that day is skipped`() {
        val first = millis(2026, 9, 1, 8, 30)
        assertEquals(millis(2026, 10, 1, 8, 30), RecurrenceRules.next(Recurrence.MONTHLY, 0, 1, first, first, zone))
        // the 31st: September has 30 days -> October 31; then November has 30 -> December 31
        val thirtyFirst = millis(2026, 8, 31, 8, 30)
        assertEquals(millis(2026, 10, 31, 8, 30), RecurrenceRules.next(Recurrence.MONTHLY, 0, 31, thirtyFirst, thirtyFirst, zone))
        assertEquals(millis(2026, 12, 31, 8, 30), RecurrenceRules.next(Recurrence.MONTHLY, 0, 31, thirtyFirst, millis(2026, 10, 31, 8, 30), zone))
        // the 30th skips February; the 29th only exists in leap years (2028)
        assertEquals(millis(2026, 3, 30, 8, 30), RecurrenceRules.next(Recurrence.MONTHLY, 0, 30, millis(2026, 1, 30, 8, 30), millis(2026, 1, 30, 8, 30), zone))
        assertEquals(millis(2027, 3, 29, 8, 30), RecurrenceRules.next(Recurrence.MONTHLY, 0, 29, millis(2027, 1, 29, 8, 30), millis(2027, 1, 29, 8, 30), zone))
        assertEquals(millis(2028, 2, 29, 8, 30), RecurrenceRules.next(Recurrence.MONTHLY, 0, 29, millis(2028, 1, 29, 8, 30), millis(2028, 1, 29, 8, 30), zone))
    }

    @Test
    fun `an impossible rule has no next time and a reminder that does not recur has none either`() {
        val t = millis(2026, 9, 21, 9)
        assertNull(RecurrenceRules.next(Recurrence.NONE, 0, 0, t, t, zone))
        assertNull(RecurrenceRules.next(Recurrence.WEEKLY, 0, 0, t, t, zone))
        assertNull(RecurrenceRules.next(Recurrence.MONTHLY, 0, 0, t, t, zone))
        assertNull(RecurrenceRules.next(Recurrence.MONTHLY, 0, 32, t, t, zone))
        assertTrue(RecurrenceRules.isValid(Recurrence.NONE, 0, 0) && RecurrenceRules.isValid(Recurrence.WEEKLY, 1, 0) && RecurrenceRules.isValid(Recurrence.MONTHLY, 0, 31))
        assertTrue(!RecurrenceRules.isValid(Recurrence.WEEKLY, 128, 0))
    }

    @Test
    fun `the time of day is kept across a change of the clocks`() {
        val paris = ZoneId.of("Europe/Paris")
        // the clocks go forward on 2026-03-29: 08:00 on the 28th and 08:00 on the 29th are 23 hours apart
        val saturday = ZonedDateTime.of(LocalDateTime.of(2026, 3, 28, 8, 0), paris).toInstant().toEpochMilli()
        val next = RecurrenceRules.next(Recurrence.WEEKLY, RecurrenceRules.ALL_DAYS, 0, saturday, saturday, paris)!!
        assertEquals(LocalTime.of(8, 0), java.time.Instant.ofEpochMilli(next).atZone(paris).toLocalTime())
        assertEquals(23 * 60 * minute, next - saturday)
    }

    // ---------------- the volume curve ----------------

    @Test
    fun `the melody climbs in a straight line from a quiet start to the chosen volume`() {
        assertEquals(0.6f, RingRamp.gain(0, 0, 60), 0.0001f)         // no fade-in: the chosen volume at once
        assertEquals(1.0f, RingRamp.gain(99_999, 0, 100), 0.0001f)
        assertEquals(0.05f, RingRamp.gain(0, 30, 100), 0.0001f)      // starts quiet
        assertEquals(0.525f, RingRamp.gain(15_000, 30, 100), 0.0001f) // halfway
        assertEquals(0.6f, RingRamp.gain(30_000, 30, 60), 0.0001f)   // reaches the chosen volume
        assertEquals(0.6f, RingRamp.gain(300_000, 30, 60), 0.0001f)  // and stays
        assertEquals("the volume is kept in 10..100", 0.1f, RingRamp.gain(0, 0, 0), 0.0001f)
        assertEquals(1.0f, RingRamp.gain(0, 0, 500), 0.0001f)
    }

    // ---------------- the reminder engine ----------------

    private val env = TestEnv()

    private suspend fun noteId(): String {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (env.notes.create(folder.id, "N", NoteType.TEXT) as DomainResult.Success).value.id
    }

    private suspend fun create(draft: ReminderDraft): Reminder =
        (env.reminders.create(noteId(), draft) as DomainResult.Success).value

    private suspend fun stored(id: String) = env.reminderRepo.getById(id)!!

    @Test
    fun `extra rings come every interval, a set number of times, then the reminder is finished`() = runBlocking {
        val now = env.clock.current
        val r = create(ReminderDraft(triggerAt = now + 10 * minute, repeatCount = 2, repeatIntervalMinutes = 5))
        env.clock.current = now + 10 * minute

        assertNotNull(env.reminders.onFired(r.id))
        assertEquals(now + 15 * minute, env.scheduler.alarms[r.id])
        assertEquals(ReminderStatus.SCHEDULED, stored(r.id).status)
        assertEquals(1, stored(r.id).repeatsDone)

        env.clock.current = now + 15 * minute
        env.reminders.onFired(r.id)
        assertEquals(now + 20 * minute, env.scheduler.alarms[r.id])
        assertEquals(2, stored(r.id).repeatsDone)

        env.clock.current = now + 20 * minute
        env.reminders.onFired(r.id) // the third and last ring
        assertEquals(ReminderStatus.FIRED, stored(r.id).status)
        assertEquals("no fourth ring is armed", now + 20 * minute, env.scheduler.alarms[r.id])
    }

    @Test
    fun `extra rings keep the confirmation waiting and stop when the person confirms`() = runBlocking {
        val now = env.clock.current
        val r = create(ReminderDraft(triggerAt = now + minute, requiresConfirmation = true, repeatCount = 3, repeatIntervalMinutes = 2))
        env.clock.current = now + minute
        env.reminders.onFired(r.id)
        assertEquals(ReminderStatus.AWAITING_CONFIRMATION, stored(r.id).status)
        assertEquals(now + 3 * minute, env.scheduler.alarms[r.id])

        env.reminders.onConfirmed(r.id)
        assertEquals(ReminderStatus.COMPLETED, stored(r.id).status)
        assertNull("the next ring is cancelled", env.scheduler.alarms[r.id])
    }

    @Test
    fun `a daily reminder moves on to tomorrow at the same time and starts its extra rings again`() = runBlocking {
        val first = env.clock.current + 60 * minute
        val r = create(ReminderDraft(triggerAt = first, recurrence = Recurrence.WEEKLY, weekdaysMask = RecurrenceRules.ALL_DAYS, repeatCount = 1, repeatIntervalMinutes = 5))
        env.clock.current = first
        env.reminders.onFired(r.id)              // first ring of today
        assertEquals(1, stored(r.id).repeatsDone)
        env.clock.current = first + 5 * minute
        env.reminders.onFired(r.id)              // the extra ring: today is over
        val next = RecurrenceRules.next(Recurrence.WEEKLY, RecurrenceRules.ALL_DAYS, 0, first, first)!!
        assertEquals(next, stored(r.id).triggerAt)
        assertEquals(next, env.scheduler.alarms[r.id])
        assertEquals(ReminderStatus.SCHEDULED, stored(r.id).status)
        assertEquals(0, stored(r.id).repeatsDone)
    }

    @Test
    fun `a monthly reminder that asks for confirmation waits for it and then moves to the next month`() = runBlocking {
        val first = millis(2026, 9, 30, 9)
        env.clock.current = first - 60 * minute
        val r = create(ReminderDraft(triggerAt = first, recurrence = Recurrence.MONTHLY, monthDay = 30, requiresConfirmation = true, repeatUntilConfirmed = true, repeatIntervalMinutes = 10))
        env.clock.current = first
        env.reminders.onFired(r.id)
        assertEquals("keeps ringing until confirmed", ReminderStatus.AWAITING_CONFIRMATION, stored(r.id).status)
        assertEquals(first, stored(r.id).triggerAt)

        env.reminders.onConfirmed(r.id)
        assertEquals("October has a 30th", millis(2026, 10, 30, 9), stored(r.id).triggerAt)
        assertEquals(ReminderStatus.SCHEDULED, stored(r.id).status)
        assertEquals(millis(2026, 10, 30, 9), env.scheduler.alarms[r.id])
    }

    @Test
    fun `after a restart the next extra ring is armed a whole number of intervals after the first`() = runBlocking {
        val now = env.clock.current
        val r = create(ReminderDraft(triggerAt = now + 10 * minute, repeatCount = 3, repeatIntervalMinutes = 5))
        env.clock.current = now + 10 * minute
        env.reminders.onFired(r.id) // one extra ring done, the next at +15
        env.scheduler.alarms.clear() // the phone restarted
        env.clock.current = now + 12 * minute

        val due = env.reminders.restoreAll()

        assertTrue(due.isEmpty())
        assertEquals(now + 15 * minute, env.scheduler.alarms[r.id])
    }

    @Test
    fun `wrong settings are refused with the reason`() = runBlocking {
        val id = noteId()
        val t = env.clock.current + 60 * minute
        suspend fun error(draft: ReminderDraft) = (env.reminders.create(id, draft) as DomainResult.Failure).error
        assertEquals(DomainError.REMINDER_INVALID_RECURRENCE, error(ReminderDraft(t, recurrence = Recurrence.WEEKLY, weekdaysMask = 0)))
        assertEquals(DomainError.REMINDER_INVALID_RECURRENCE, error(ReminderDraft(t, recurrence = Recurrence.MONTHLY, monthDay = 32)))
        assertEquals(DomainError.REMINDER_INVALID_SOUND, error(ReminderDraft(t, volumePercent = 5)))
        assertEquals(DomainError.REMINDER_INVALID_SOUND, error(ReminderDraft(t, rampSeconds = 301)))
        assertEquals("extra rings need an interval", DomainError.REMINDER_INVALID_REPEAT, error(ReminderDraft(t, repeatCount = 2)))
        assertEquals(DomainError.REMINDER_INVALID_REPEAT, error(ReminderDraft(t, repeatCount = 51, repeatIntervalMinutes = 5)))
        assertTrue(env.reminders.create(id, ReminderDraft(t, volumePercent = 10, rampSeconds = 300, repeatCount = 50, repeatIntervalMinutes = 1440)) is DomainResult.Success)
    }

    @Test
    fun `every new setting survives the database mapping and the backup record, and old records read as before`() {
        val r = Reminder(
            "r", "n", com.notebudget.app.domain.model.ReminderType.ONE_TIME, 1_000, true, ReminderStatus.SCHEDULED, true, "content://x",
            true, true, false, false, 5, 1, 2, volumePercent = 40, rampSeconds = 30, repeatCount = 3, repeatsDone = 1,
            recurrence = Recurrence.WEEKLY, weekdaysMask = 5, monthDay = 0,
        )
        assertEquals(r, r.toEntity().toDomain())
        assertEquals(r, r.toRecord().toDomain())
        val old = com.notebudget.app.data.transfer.ReminderRecord(id = "o", noteId = "n", triggerAt = 5)
        val read = old.toDomain()
        assertEquals(100, read.volumePercent)
        assertEquals(0, read.rampSeconds + read.repeatCount + read.repeatsDone + read.weekdaysMask + read.monthDay)
        assertEquals(Recurrence.NONE, read.recurrence)
        val wild = old.copy(volumePercent = 9999, rampSeconds = -4, repeatCount = 900, recurrence = "NEVER", weekdaysMask = 999, monthDay = 77).toDomain()
        assertEquals(listOf(100, 0, 50, 0, 999 and 127, 31), listOf(wild.volumePercent, wild.rampSeconds, wild.repeatCount, wild.recurrence.ordinal, wild.weekdaysMask, wild.monthDay))
    }
}
