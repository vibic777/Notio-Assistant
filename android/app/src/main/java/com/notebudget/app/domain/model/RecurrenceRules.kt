package com.notebudget.app.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The days and times a recurring reminder comes on (D130); pure, so the calendar rules are tested. */
object RecurrenceRules {
    const val ALL_DAYS = 127

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun maskOf(vararg days: DayOfWeek): Int = days.fold(0) { acc, d -> acc or bit(d) }

    fun includes(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    /** Is the recurrence itself well formed (at least one weekday, a real day of the month)? */
    fun isValid(recurrence: Recurrence, weekdaysMask: Int, monthDay: Int): Boolean = when (recurrence) {
        Recurrence.NONE -> true
        Recurrence.WEEKLY -> weekdaysMask and ALL_DAYS != 0
        Recurrence.MONTHLY -> monthDay in 1..31
    }

    /**
     * The first time after [afterMillis] that the rule allows, at the time of day of [timeOfDayFrom] (the reminder's own time),
     * in [zone]; `null` for a reminder that does not recur or a rule that never matches. Weekly: the next chosen weekday.
     * Monthly: the next month that HAS the chosen day (the 31st skips April, the 30th skips February).
     */
    fun next(
        recurrence: Recurrence,
        weekdaysMask: Int,
        monthDay: Int,
        timeOfDayFrom: Long,
        afterMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (!isValid(recurrence, weekdaysMask, monthDay) || recurrence == Recurrence.NONE) return null
        val time = java.time.Instant.ofEpochMilli(timeOfDayFrom).atZone(zone).toLocalTime()
        val after = java.time.Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDateTime()
        var day: LocalDate = after.toLocalDate()
        // A daily or weekly rule repeats within 7 days, a monthly one within a few years (leap-day style rules are not offered).
        repeat(if (recurrence == Recurrence.WEEKLY) 8 else 366 * 2) {
            val matches = when (recurrence) {
                Recurrence.WEEKLY -> includes(weekdaysMask, day.dayOfWeek)
                else -> day.dayOfMonth == monthDay
            }
            if (matches) {
                val candidate = LocalDateTime.of(day, time)
                if (candidate.atZone(zone).toInstant().toEpochMilli() > afterMillis) return candidate.atZone(zone).toInstant().toEpochMilli()
            }
            day = day.plusDays(1)
        }
        return null
    }
}
