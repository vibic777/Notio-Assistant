package com.notio.shared.domain

/** Platform alarm scheduling behind an interface, so the reminder logic is unit-testable. */
interface ReminderScheduler {
    /** Schedules (or replaces) the alarm of [reminderId] at [triggerAtMillis] (UTC epoch millis). */
    fun schedule(reminderId: String, triggerAtMillis: Long)

    fun cancel(reminderId: String)

    /** `false` when Android refuses exact alarms (`canScheduleExactAlarms()`), see §10.2. */
    fun canScheduleExact(): Boolean
}

/** The reminders, as the use cases need them (the desktop database). */
interface ReminderRepository {
    suspend fun getById(id: String): Reminder?
    suspend fun getByNoteIds(noteIds: List<String>): List<Reminder>
    suspend fun getActive(): List<Reminder>
    suspend fun upsert(reminder: Reminder)
    suspend fun delete(id: String)
}

/** The notes, as the reminder rules need them. */
interface NoteRepository {
    suspend fun getById(id: String): Note?
}
