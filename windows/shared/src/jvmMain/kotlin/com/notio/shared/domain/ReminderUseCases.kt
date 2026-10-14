package com.notio.shared.domain

import com.notio.shared.domain.Clock
import com.notio.shared.domain.IdGenerator
import com.notio.shared.domain.DomainError
import com.notio.shared.domain.DomainResult
import com.notio.shared.domain.Reminder
import com.notio.shared.domain.ReminderDraft
import com.notio.shared.domain.Recurrence
import com.notio.shared.domain.RecurrenceRules
import com.notio.shared.domain.ReminderStatus
import com.notio.shared.domain.ReminderType
import com.notio.shared.domain.asFailure
import com.notio.shared.domain.asSuccess
import com.notio.shared.domain.NoteRepository
import com.notio.shared.domain.ReminderRepository
import com.notio.shared.domain.ReminderScheduler

/**
 * Reminder lifecycle (AGENT_EN.md §6.10): SCHEDULED -> FIRED, or with confirmation
 * SCHEDULED -> AWAITING_CONFIRMATION -> COMPLETED; CANCELLED at any time.
 *
 * Overdue reminders found after a reboot fire immediately (decision D78). Android delivers
 * the alarm; showing the notification is the caller's job, using the reminder returned by
 * [onFired] / [restoreAll].
 */
class ReminderUseCases(
    private val reminders: ReminderRepository,
    private val notes: NoteRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
    /**
     * All reminders on or off at once (D318). While it says `false` nothing fires and nothing is armed again; the reminders
     * stay as they are.
     */
    private val enabled: () -> Boolean = { true },
) {

    /** All reminders off (D318): every alarm is taken away; nothing is marked, the reminders stay as they are. */
    suspend fun disarmAll() {
        for (reminder in reminders.getActive()) {
            scheduler.cancel(reminder.id)
            scheduler.cancel(snoozeAlarmId(reminder.id))
        }
    }

    suspend fun create(noteId: String, draft: ReminderDraft): DomainResult<Reminder> {
        notes.getById(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        validate(draft)?.let { return it.asFailure() }
        val now = clock.now()
        val reminder = Reminder(
            id = ids.newId(),
            noteId = noteId,
            type = ReminderType.ONE_TIME,
            triggerAt = draft.triggerAt,
            isEnabled = true,
            status = ReminderStatus.SCHEDULED,
            soundEnabled = draft.soundEnabled,
            soundUri = draft.soundUri,
            vibrationEnabled = draft.vibrationEnabled,
            popupEnabled = draft.popupEnabled,
            requiresConfirmation = draft.requiresConfirmation,
            repeatUntilConfirmed = draft.repeatUntilConfirmed,
            repeatIntervalMinutes = draft.repeatIntervalMinutes,
            createdAt = now,
            updatedAt = now,
            volumePercent = draft.volumePercent,
            rampSeconds = draft.rampSeconds,
            repeatCount = draft.repeatCount,
            repeatsDone = 0,
            recurrence = draft.recurrence,
            weekdaysMask = draft.weekdaysMask,
            monthDay = draft.monthDay,
        )
        reminders.upsert(reminder)
        scheduler.schedule(reminder.id, reminder.triggerAt)
        return reminder.asSuccess()
    }

    /** Editing re-arms the reminder: it becomes SCHEDULED again and the alarm is replaced. */
    suspend fun update(id: String, draft: ReminderDraft): DomainResult<Reminder> {
        val existing = reminders.getById(id) ?: return DomainError.REMINDER_NOT_FOUND.asFailure()
        validate(draft)?.let { return it.asFailure() }
        val updated = existing.copy(
            triggerAt = draft.triggerAt,
            isEnabled = true,
            status = ReminderStatus.SCHEDULED,
            soundEnabled = draft.soundEnabled,
            soundUri = draft.soundUri,
            vibrationEnabled = draft.vibrationEnabled,
            popupEnabled = draft.popupEnabled,
            requiresConfirmation = draft.requiresConfirmation,
            repeatUntilConfirmed = draft.repeatUntilConfirmed,
            repeatIntervalMinutes = draft.repeatIntervalMinutes,
            updatedAt = clock.now(),
            volumePercent = draft.volumePercent,
            rampSeconds = draft.rampSeconds,
            repeatCount = draft.repeatCount,
            repeatsDone = 0,
            recurrence = draft.recurrence,
            weekdaysMask = draft.weekdaysMask,
            monthDay = draft.monthDay,
            snoozedUntil = 0,
        )
        scheduler.cancel(snoozeAlarmId(id))
        reminders.upsert(updated)
        scheduler.schedule(updated.id, updated.triggerAt)
        return updated.asSuccess()
    }

    /**
     * Snooze (D150): stops this ring and rings once more in [minutes] minutes, whatever the reminder does otherwise. The
     * snooze has its own alarm ([snoozeAlarmId]) and its moment is kept in [Reminder.snoozedUntil], so it survives a reboot.
     * A one-time reminder that is still repeating stops repeating until the snooze ends; a recurring one keeps its next day.
     */
    suspend fun snooze(id: String, minutes: Int): DomainResult<Reminder> {
        val reminder = reminders.getById(id) ?: return DomainError.REMINDER_NOT_FOUND.asFailure()
        if (minutes !in 1..MAX_SNOOZE_MINUTES) return DomainError.REMINDER_INVALID_REPEAT.asFailure()
        val now = clock.now()
        val until = now + minutes * MILLIS_PER_MINUTE
        if (reminder.recurrence == Recurrence.NONE) scheduler.cancel(id)
        scheduler.schedule(snoozeAlarmId(id), until)
        val updated = reminder.copy(snoozedUntil = until, updatedAt = now)
        reminders.upsert(updated)
        return updated.asSuccess()
    }

    /**
     * The snooze alarm went off. Returns the reminder to ring again, or `null` when it must be ignored (deleted, cancelled,
     * completed, or the snooze was replaced). A reminder that asks for confirmation and repeats starts repeating again.
     */
    suspend fun onSnoozeFired(id: String): Reminder? {
        if (!enabled()) return null
        val reminder = reminders.getById(id) ?: return null
        if (!reminder.isEnabled || reminder.snoozedUntil == 0L) return null
        if (reminder.status == ReminderStatus.CANCELLED || reminder.status == ReminderStatus.COMPLETED) return null
        val now = clock.now()
        val interval = reminder.repeatIntervalMinutes?.takeIf { it > 0 }
        if (reminder.recurrence == Recurrence.NONE && reminder.status == ReminderStatus.AWAITING_CONFIRMATION && reminder.repeatUntilConfirmed && interval != null) {
            scheduler.schedule(id, now + interval * MILLIS_PER_MINUTE)
        }
        val updated = reminder.copy(snoozedUntil = 0, updatedAt = now)
        reminders.upsert(updated)
        return updated
    }

    suspend fun cancel(id: String): DomainResult<Unit> {
        val existing = reminders.getById(id) ?: return DomainError.REMINDER_NOT_FOUND.asFailure()
        scheduler.cancel(id)
        scheduler.cancel(snoozeAlarmId(id))
        reminders.upsert(existing.copy(status = ReminderStatus.CANCELLED, updatedAt = clock.now()))
        return Unit.asSuccess()
    }

    /**
     * Switches a reminder off or back on (Android D268), like an alarm clock's switch: off, it keeps everything (time, days, sound) but
     * has no alarm and does not ring, count or show a bell; on again, its alarm is set back. A recurring reminder whose time
     * went by while it was off comes back at its next day; a one-time one whose time is past cannot be switched on
     * ([DomainError.REMINDER_IN_PAST]: change its time instead). Only a reminder still to ring (or awaiting its Done) has the
     * switch; a cancelled or finished one does not.
     */
    suspend fun setEnabled(id: String, enabled: Boolean): DomainResult<Reminder> {
        val existing = reminders.getById(id) ?: return DomainError.REMINDER_NOT_FOUND.asFailure()
        if (existing.isEnabled == enabled) return existing.asSuccess()
        val now = clock.now()
        if (!enabled) {
            scheduler.cancel(id)
            scheduler.cancel(snoozeAlarmId(id))
            val off = existing.copy(isEnabled = false, snoozedUntil = 0, updatedAt = now)
            reminders.upsert(off)
            return off.asSuccess()
        }
        val on = when (existing.status) {
            ReminderStatus.SCHEDULED -> {
                val at = when {
                    existing.triggerAt > now -> existing.triggerAt
                    existing.recurrence != Recurrence.NONE ->
                        RecurrenceRules.next(existing.recurrence, existing.weekdaysMask, existing.monthDay, existing.triggerAt, now)
                            ?: return DomainError.REMINDER_IN_PAST.asFailure()
                    else -> return DomainError.REMINDER_IN_PAST.asFailure()
                }
                scheduler.schedule(id, at)
                existing.copy(isEnabled = true, triggerAt = at, repeatsDone = 0, updatedAt = now)
            }
            ReminderStatus.AWAITING_CONFIRMATION -> {
                val interval = existing.repeatIntervalMinutes?.takeIf { it > 0 }
                if (existing.repeatUntilConfirmed && interval != null) scheduler.schedule(id, now + interval * MILLIS_PER_MINUTE)
                existing.copy(isEnabled = true, updatedAt = now)
            }
            else -> return DomainError.REMINDER_NOT_FOUND.asFailure()
        }
        reminders.upsert(on)
        return on.asSuccess()
    }

    suspend fun delete(id: String): DomainResult<Unit> {
        reminders.getById(id) ?: return DomainError.REMINDER_NOT_FOUND.asFailure()
        scheduler.cancel(id)
        scheduler.cancel(snoozeAlarmId(id))
        reminders.delete(id)
        return Unit.asSuccess()
    }

    /**
     * The alarm went off. Returns the updated reminder to notify about, or `null` when it
     * must be ignored (deleted, disabled, cancelled or already completed).
     */
    suspend fun onFired(id: String): Reminder? {
        if (!enabled()) return null
        val reminder = reminders.getById(id) ?: return null
        if (!reminder.isEnabled) return null
        if (reminder.status == ReminderStatus.CANCELLED || reminder.status == ReminderStatus.COMPLETED) return null

        val now = clock.now()
        val interval = reminder.repeatIntervalMinutes?.takeIf { it > 0 }
        // Rings again until confirmed (needs a confirmation), or a set number of extra rings: an alarm clock's "repeat".
        val untilConfirmed = reminder.requiresConfirmation && reminder.repeatUntilConfirmed && interval != null
        val extraRing = !untilConfirmed && interval != null && reminder.repeatsDone < reminder.repeatCount
        val ringsAgain = untilConfirmed || extraRing
        if (ringsAgain) scheduler.schedule(id, now + interval!! * MILLIS_PER_MINUTE)
        val done = if (extraRing) reminder.repeatsDone + 1 else reminder.repeatsDone

        val base = reminder.copy(repeatsDone = done, updatedAt = now)
        val updated = when {
            ringsAgain && reminder.requiresConfirmation -> base.copy(status = ReminderStatus.AWAITING_CONFIRMATION)
            ringsAgain -> base.copy(status = ReminderStatus.SCHEDULED)
            // A reminder that comes back moves on to its next day now, unless it keeps ringing until confirmed.
            reminder.recurrence != Recurrence.NONE && !untilConfirmed -> advance(base, now) ?: base.copy(status = ReminderStatus.FIRED)
            reminder.requiresConfirmation -> base.copy(status = ReminderStatus.AWAITING_CONFIRMATION)
            else -> base.copy(status = ReminderStatus.FIRED)
        }
        reminders.upsert(updated)
        return updated
    }

    /** The user confirmed: stops any repeat; a reminder that comes back is armed for its next day. */
    suspend fun onConfirmed(id: String): DomainResult<Unit> {
        val reminder = reminders.getById(id) ?: return DomainError.REMINDER_NOT_FOUND.asFailure()
        scheduler.cancel(id)
        scheduler.cancel(snoozeAlarmId(id))
        val now = clock.now()
        val plain = reminder.copy(snoozedUntil = 0, updatedAt = now)
        val next = if (reminder.recurrence != Recurrence.NONE) advance(plain, now) else null
        reminders.upsert(next ?: plain.copy(status = ReminderStatus.COMPLETED))
        return Unit.asSuccess()
    }

    /**
     * The reminder moved to its next occurrence (a chosen weekday or the day of the month, at the same time of day): the
     * alarm is set for it and the count of extra rings starts again. `null` when the rule has no next time.
     */
    private fun advance(reminder: Reminder, now: Long): Reminder? {
        val next = RecurrenceRules.next(reminder.recurrence, reminder.weekdaysMask, reminder.monthDay, reminder.triggerAt, maxOf(now, reminder.triggerAt))
            ?: return null
        scheduler.schedule(reminder.id, next)
        return reminder.copy(triggerAt = next, status = ReminderStatus.SCHEDULED, repeatsDone = 0, isEnabled = true)
    }

    /**
     * Re-arms all active reminders after a reboot or a time / timezone change (INV-8, §10.3,
     * §10.4). Returns overdue reminders that fired right now and need a notification.
     */
    suspend fun restoreAll(): List<Reminder> {
        // All reminders are off (D318): nothing is armed, nothing that was due rings.
        if (!enabled()) return emptyList()
        val now = clock.now()
        val dueNow = mutableListOf<Reminder>()
        for (reminder in reminders.getActive()) {
            if (reminder.snoozedUntil > 0) {
                // A snoozed reminder waits for its snooze; one that ended while the phone was off rings now.
                if (reminder.snoozedUntil > now) scheduler.schedule(snoozeAlarmId(reminder.id), reminder.snoozedUntil)
                else onSnoozeFired(reminder.id)?.let { dueNow += it }
                if (reminder.recurrence == Recurrence.NONE) continue
            }
            when (reminder.status) {
                ReminderStatus.SCHEDULED -> {
                    // After some extra rings the next one is due a whole number of intervals after the first.
                    val interval = reminder.repeatIntervalMinutes?.takeIf { it > 0 }
                    val next = if (reminder.repeatsDone > 0 && interval != null) reminder.triggerAt + reminder.repeatsDone * interval * MILLIS_PER_MINUTE else reminder.triggerAt
                    if (next > now) {
                        scheduler.schedule(reminder.id, next)
                    } else {
                        onFired(reminder.id)?.let { dueNow += it }
                    }
                }

                ReminderStatus.AWAITING_CONFIRMATION -> {
                    val interval = reminder.repeatIntervalMinutes
                    if (reminder.repeatUntilConfirmed && interval != null && interval > 0) {
                        scheduler.schedule(reminder.id, now + interval * MILLIS_PER_MINUTE)
                    }
                }

                else -> Unit
            }
        }
        return dueNow
    }

    private fun validate(draft: ReminderDraft): DomainError? {
        if (draft.triggerAt <= clock.now()) return DomainError.REMINDER_IN_PAST
        if (!RecurrenceRules.isValid(draft.recurrence, draft.weekdaysMask, draft.monthDay)) return DomainError.REMINDER_INVALID_RECURRENCE
        if (draft.volumePercent !in 10..100 || draft.rampSeconds !in 0..300) return DomainError.REMINDER_INVALID_SOUND
        if (draft.repeatCount !in 0..MAX_EXTRA_RINGS) return DomainError.REMINDER_INVALID_REPEAT
        if (draft.repeatCount > 0 && (draft.repeatIntervalMinutes ?: 0) !in 1..1440) return DomainError.REMINDER_INVALID_REPEAT
        if (draft.repeatUntilConfirmed) {
            val interval = draft.repeatIntervalMinutes
            if (!draft.requiresConfirmation || interval == null || interval < 1) {
                return DomainError.REMINDER_INVALID_REPEAT
            }
        }
        return null
    }

    companion object {
        /** At most this many extra rings after the first. */
        const val MAX_EXTRA_RINGS = 50

        /** The longest snooze, in minutes (a day). */
        const val MAX_SNOOZE_MINUTES = 1440

        /** The alarm of a snooze is kept apart from the reminder's own alarm, so neither disturbs the other. */
        fun snoozeAlarmId(reminderId: String) = "snooze-$reminderId"
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
