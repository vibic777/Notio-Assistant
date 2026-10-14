package com.notebudget.app.domain.repository

/** Platform alarm scheduling behind an interface, so the reminder logic is unit-testable. */
interface ReminderScheduler {
    /** Schedules (or replaces) the alarm of [reminderId] at [triggerAtMillis] (UTC epoch millis). */
    fun schedule(reminderId: String, triggerAtMillis: Long)

    fun cancel(reminderId: String)

    /** `false` when Android refuses exact alarms (`canScheduleExactAlarms()`), see §10.2. */
    fun canScheduleExact(): Boolean
}
