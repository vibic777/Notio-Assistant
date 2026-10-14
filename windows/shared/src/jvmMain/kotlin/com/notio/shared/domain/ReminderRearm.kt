package com.notio.shared.domain

import com.notio.shared.domain.Clock
import com.notio.shared.domain.ReminderStatus
import com.notio.shared.domain.ReminderRepository
import com.notio.shared.domain.ReminderScheduler

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
