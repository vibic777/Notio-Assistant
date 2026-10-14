package com.notio.shared.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** What a day of the calendar can show (D236). The budget merges (D237) and the tasks (D239) join this list later. */
enum class CalendarEventKind { REMINDER, BUDGET_MERGE, TASK }

/** A budget merge to show (D237): the income / expense note [noteId] named [title] merges at [at]. */
data class ScheduledMerge(val noteId: String, val title: String, val at: Long)

/**
 * One mark on the calendar: a reminder of the note [noteId] at [at]. [done] is a reminder that has already rung and is over
 * (or was cancelled): still shown, but paler.
 */
data class CalendarEvent(
    val kind: CalendarEventKind,
    val noteId: String,
    val noteTitle: String,
    val at: Long,
    val done: Boolean,
    val sourceId: String,
    /** A task (D239): its end, and the colour of its note for the bar. */
    val endAt: Long? = null,
    val colorArgb: Int? = null,
)

/** The month grid and what goes on each day (D236). Pure, so the calendar rules are tested. */
object CalendarPlan {

    /**
     * The days of the grid of [month], week by week, starting on [firstDay]: `null` for the cells before the 1st and after the
     * last day, so that every row has seven cells.
     */
    fun cells(month: YearMonth, firstDay: DayOfWeek): List<LocalDate?> {
        val first = month.atDay(1)
        val lead = (first.dayOfWeek.value - firstDay.value + 7) % 7
        val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
        val trail = (7 - (lead + days.size) % 7) % 7
        return List(lead) { null } + days + List(trail) { null }
    }

    /**
     * The reminders of [month] by day, each day's in time order. A reminder still to come that repeats (weekly, monthly) is
     * shown on every day of the month it will ring from its next time on; any other reminder once, at its time. Reminders of
     * notes not in [titles] (in the trash, gone) are left out.
     */
    fun reminders(
        reminders: List<Reminder>,
        titles: Map<String, String>,
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDate, List<CalendarEvent>> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val out = ArrayList<CalendarEvent>()
        for (r in reminders) {
            val title = titles[r.noteId] ?: continue
            val active = r.isEnabled && (r.status == ReminderStatus.SCHEDULED || r.status == ReminderStatus.AWAITING_CONFIRMATION)
            fun add(at: Long) { out += CalendarEvent(CalendarEventKind.REMINDER, r.noteId, title, at, done = !active, sourceId = r.id) }
            if (!active || r.recurrence == Recurrence.NONE) {
                if (r.triggerAt in start until end) add(r.triggerAt)
                continue
            }
            // The first time in this month at or after the reminder's own next time; then each next one, at most one a day.
            var at: Long? = if (r.triggerAt >= start) r.triggerAt
            else RecurrenceRules.next(r.recurrence, r.weekdaysMask, r.monthDay, r.triggerAt, start - 1, zone)
            var guard = 0
            while (at != null && at < end && guard++ < 32) {
                add(at)
                at = RecurrenceRules.next(r.recurrence, r.weekdaysMask, r.monthDay, r.triggerAt, at, zone)
            }
        }
        return out.sortedBy { it.at }.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
    }

    /** The budget merges of [month] by day (D237). */
    fun merges(merges: List<ScheduledMerge>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<CalendarEvent>> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return merges.filter { it.at in start until end }
            .map { CalendarEvent(CalendarEventKind.BUDGET_MERGE, it.noteId, it.title, it.at, done = false, sourceId = it.noteId) }
            .sortedBy { it.at }.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
    }

    /**
     * Everything the calendar can show, in time order, for its search (D252): each reminder once at its (next) time, each budget
     * merge at its time. Reminders of notes not in [titles] are left out.
     */
    fun all(
        reminders: List<Reminder>,
        titles: Map<String, String>,
        merges: List<ScheduledMerge>,
        tasks: List<ScheduledTask> = emptyList(),
    ): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>(reminders.size + merges.size)
        for (r in reminders) {
            val title = titles[r.noteId] ?: continue
            val active = r.isEnabled && (r.status == ReminderStatus.SCHEDULED || r.status == ReminderStatus.AWAITING_CONFIRMATION)
            out += CalendarEvent(CalendarEventKind.REMINDER, r.noteId, title, r.triggerAt, done = !active, sourceId = r.id)
        }
        merges.forEach { out += CalendarEvent(CalendarEventKind.BUDGET_MERGE, it.noteId, it.title, it.at, done = false, sourceId = it.noteId) }
        tasks.forEach { out += CalendarEvent(CalendarEventKind.TASK, it.noteId, it.title, it.start, it.done, it.noteId, it.end, it.colorArgb) }
        return out.sortedBy { it.at }
    }

    /**
     * The tasks of [month] by day (D239): a task is on every day from its start to its end (only one of them set: that day),
     * at most 62 days of it looked at in a month.
     */
    fun tasks(tasks: List<ScheduledTask>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<CalendarEvent>> {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val out = HashMap<LocalDate, MutableList<CalendarEvent>>()
        for (t in tasks) {
            val startDay = Instant.ofEpochMilli(t.start).atZone(zone).toLocalDate()
            val endDay = Instant.ofEpochMilli(maxOf(t.end, t.start)).atZone(zone).toLocalDate()
            var day = if (startDay.isBefore(first)) first else startDay
            val stop = if (endDay.isAfter(last)) last else endDay
            var guard = 0
            while (!day.isAfter(stop) && guard++ < 62) {
                val at = if (day == startDay) t.start else day.atStartOfDay(zone).toInstant().toEpochMilli()
                out.getOrPut(day) { ArrayList() } += CalendarEvent(CalendarEventKind.TASK, t.noteId, t.title, at, t.done, t.noteId, t.end, t.colorArgb)
                day = day.plusDays(1)
            }
        }
        return out.mapValues { (_, list) -> list.sortedBy { it.at } }
    }

    /** Two maps of days into one, each day in time order. */
    fun join(a: Map<LocalDate, List<CalendarEvent>>, b: Map<LocalDate, List<CalendarEvent>>): Map<LocalDate, List<CalendarEvent>> =
        (a.keys + b.keys).associateWith { day -> (a[day].orEmpty() + b[day].orEmpty()).sortedBy { it.at } }
}
