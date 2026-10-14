package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.Clock
import com.notebudget.app.domain.model.ReminderStatus
import com.notebudget.app.domain.repository.ReminderRepository
import com.notebudget.app.domain.repository.ReminderScheduler

/**
 * Re-arms the alarms of reminders whose note was just restored from the trash. Alarms were
 * cancelled on delete; only future SCHEDULED reminders are armed again (missed ones stay as is).
 */
internal suspend fun rearmReminders(
    reminders: ReminderRepository,
    scheduler: ReminderScheduler,
    clock: Clock,
    noteIds: List<String>,
) {
    val now = clock.now()
    reminders.getByNoteIds(noteIds)
        .filter { it.isEnabled && it.status == ReminderStatus.SCHEDULED && it.triggerAt > now }
        .forEach { scheduler.schedule(it.id, it.triggerAt) }
}
