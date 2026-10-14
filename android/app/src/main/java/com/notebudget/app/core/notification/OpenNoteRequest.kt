package com.notebudget.app.core.notification

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Open this note" as it arrives from a notification tap (D126). The activity puts the id here; the navigation takes it and
 * clears it, so a turn of the screen does not open the note again. It stays here while the lock screen is up.
 */
class OpenNoteRequest {
    val noteId = MutableStateFlow<String?>(null)

    /** "Show this reminder in the calendar" (D267): the day of [CalendarTarget.at], the reminder marked there. */
    val calendar = MutableStateFlow<CalendarTarget?>(null)

    data class CalendarTarget(val at: Long, val reminderId: String)

    /** Takes the target out of an intent that came from a reminder notification; anything else is ignored. */
    fun take(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_NOTE -> intent.getStringExtra(EXTRA_NOTE_ID)?.takeIf { it.isNotBlank() }?.let { noteId.value = it }
            ACTION_OPEN_CALENDAR -> {
                val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID).orEmpty()
                val at = intent.getLongExtra(EXTRA_AT, 0L)
                if (at > 0L) calendar.value = CalendarTarget(at, reminderId)
            }
            else -> return
        }
        // Handled: a later "recreate" of the same intent must not open the note again.
        intent.action = null
    }

    fun consumed() {
        noteId.value = null
    }

    fun calendarShown() {
        calendar.value = null
    }

    companion object {
        const val ACTION_OPEN_NOTE = "com.notebudget.app.action.OPEN_NOTE"
        const val ACTION_OPEN_CALENDAR = "com.notebudget.app.action.OPEN_CALENDAR"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_AT = "at"
    }
}
