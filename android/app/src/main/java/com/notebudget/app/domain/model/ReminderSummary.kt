package com.notebudget.app.domain.model

/** How many reminders are on and when the next one rings (D154), for the notification that stays in the shade. */
class ReminderSummary(val count: Int, val nextAt: Long) {
    companion object {
        /** `null` when no reminder is on. A snoozed reminder counts with the moment its snooze ends. */
        fun of(active: List<Reminder>): ReminderSummary? {
            val on = active.filter {
                it.isEnabled && (it.snoozedUntil > 0 || it.status == ReminderStatus.SCHEDULED || it.status == ReminderStatus.AWAITING_CONFIRMATION)
            }
            if (on.isEmpty()) return null
            val next = on.minOf { if (it.snoozedUntil > 0) it.snoozedUntil else it.triggerAt }
            return ReminderSummary(on.size, next)
        }
    }
}
