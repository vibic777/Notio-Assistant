package com.notebudget.app.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The month grid and the reminders on it (D236). */
class CalendarPlanTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 9) = LocalDateTime.of(y, m, d, h, 0).toInstant(zone).toEpochMilli()

    private fun reminder(
        id: String,
        triggerAt: Long,
        noteId: String = "n1",
        status: ReminderStatus = ReminderStatus.SCHEDULED,
        recurrence: Recurrence = Recurrence.NONE,
        weekdays: Int = 0,
        monthDay: Int = 0,
    ) = Reminder(
        id = id, noteId = noteId, type = ReminderType.ONE_TIME, triggerAt = triggerAt, isEnabled = true, status = status,
        soundEnabled = true, soundUri = null, vibrationEnabled = true, popupEnabled = true, requiresConfirmation = false,
        repeatUntilConfirmed = false, repeatIntervalMinutes = null, createdAt = 0, updatedAt = 0,
        recurrence = recurrence, weekdaysMask = weekdays, monthDay = monthDay,
    )

    @Test
    fun `the grid starts on the chosen weekday and every row has seven cells`() {
        // September 2026 begins on a Tuesday and has 30 days.
        val monday = CalendarPlan.cells(YearMonth.of(2026, 9), DayOfWeek.MONDAY)
        assertNull(monday[0])
        assertEquals(LocalDate.of(2026, 9, 1), monday[1])
        assertEquals(0, monday.size % 7)
        assertEquals(30, monday.count { it != null })
        val sunday = CalendarPlan.cells(YearMonth.of(2026, 9), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 9, 1), sunday[2])
        // February 2027 starts on a Monday: no empty cell before it.
        assertEquals(LocalDate.of(2027, 2, 1), CalendarPlan.cells(YearMonth.of(2027, 2), DayOfWeek.MONDAY)[0])
    }

    @Test
    fun `a one-time reminder is on its day, a finished one too but marked done`() {
        val days = CalendarPlan.reminders(
            listOf(
                reminder("a", at(2026, 9, 28, 10)),
                reminder("b", at(2026, 9, 3), status = ReminderStatus.COMPLETED),
                reminder("c", at(2026, 10, 1)),
            ),
            mapOf("n1" to "Shopping"), YearMonth.of(2026, 9), zone,
        )
        assertEquals(setOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 3)), days.keys)
        val a = days.getValue(LocalDate.of(2026, 9, 28)).single()
        assertEquals("Shopping", a.noteTitle)
        assertFalse(a.done)
        assertTrue(days.getValue(LocalDate.of(2026, 9, 3)).single().done)
    }

    @Test
    fun `a weekly reminder is on each of its weekdays from its next time on`() {
        val mondaysAndThursdays = RecurrenceRules.maskOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        // Next time: Thursday 17 September 2026.
        val r = reminder("w", at(2026, 9, 17), recurrence = Recurrence.WEEKLY, weekdays = mondaysAndThursdays)
        val sept = CalendarPlan.reminders(listOf(r), mapOf("n1" to "Gym"), YearMonth.of(2026, 9), zone)
        assertEquals(listOf(17, 21, 24, 28), sept.keys.map { it.dayOfMonth }.sorted())
        // A month far ahead is found without walking through all the weeks between.
        val later = CalendarPlan.reminders(listOf(r), mapOf("n1" to "Gym"), YearMonth.of(2030, 1), zone)
        assertEquals(9, later.size) // Mondays and Thursdays of January 2030
        assertTrue(later.values.flatten().all { java.time.Instant.ofEpochMilli(it.at).atZone(zone).hour == 9 })
    }

    @Test
    fun `a monthly reminder skips a month without its day, and nothing is before its next time`() {
        val r = reminder("m", at(2026, 10, 31), recurrence = Recurrence.MONTHLY, monthDay = 31)
        assertTrue(CalendarPlan.reminders(listOf(r), mapOf("n1" to "Rent"), YearMonth.of(2026, 9), zone).isEmpty())
        assertEquals(listOf(31), CalendarPlan.reminders(listOf(r), mapOf("n1" to "Rent"), YearMonth.of(2026, 10), zone).keys.map { it.dayOfMonth })
        assertTrue(CalendarPlan.reminders(listOf(r), mapOf("n1" to "Rent"), YearMonth.of(2026, 11), zone).isEmpty())
        assertEquals(1, CalendarPlan.reminders(listOf(r), mapOf("n1" to "Rent"), YearMonth.of(2026, 12), zone).size)
    }

    @Test
    fun `reminders of notes that are gone are left out, and a day is in time order`() {
        val days = CalendarPlan.reminders(
            listOf(reminder("late", at(2026, 9, 5, 18)), reminder("early", at(2026, 9, 5, 7)), reminder("x", at(2026, 9, 5), noteId = "gone")),
            mapOf("n1" to "N"), YearMonth.of(2026, 9), zone,
        )
        assertEquals(listOf("early", "late"), days.getValue(LocalDate.of(2026, 9, 5)).map { it.sourceId })
    }

    @Test
    fun `budget merges join the reminders of their day in time order (D237)`() {
        val merges = CalendarPlan.merges(
            listOf(ScheduledMerge("b1", "Salary", at(2026, 9, 5, 12)), ScheduledMerge("b2", "Next month", at(2026, 10, 1))),
            YearMonth.of(2026, 9), zone,
        )
        val days = CalendarPlan.join(CalendarPlan.reminders(listOf(reminder("r", at(2026, 9, 5, 8))), mapOf("n1" to "N"), YearMonth.of(2026, 9), zone), merges)
        assertEquals(listOf(CalendarEventKind.REMINDER, CalendarEventKind.BUDGET_MERGE), days.getValue(LocalDate.of(2026, 9, 5)).map { it.kind })
        assertEquals(1, days.size)
    }

    @Test
    fun `the search sees everything once, in time order, across months (D252)`() {
        val all = CalendarPlan.all(
            listOf(reminder("late", at(2027, 1, 5)), reminder("early", at(2026, 3, 1), status = ReminderStatus.COMPLETED), reminder("x", at(2026, 5, 5), noteId = "gone")),
            mapOf("n1" to "Dentist"),
            listOf(ScheduledMerge("b", "Salary", at(2026, 10, 1))),
        )
        assertEquals(listOf("early", "b", "late"), all.map { it.sourceId })
        assertTrue(all.first().done)
        assertEquals(CalendarEventKind.BUDGET_MERGE, all[1].kind)
    }
}
