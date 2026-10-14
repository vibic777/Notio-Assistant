package com.notebudget.app.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notebudget.app.NoteBudgetApplication
import com.notebudget.app.core.common.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs [block] off the main thread while keeping the broadcast alive (`goAsync`). A failure is written to the app log instead of
 * closing the app in the background (D231): a reminder that cannot be shown is then visible there, not a silent crash.
 */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    val receiver = javaClass.simpleName
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.notebudget.app.core.common.AppLog.critical("reminder", "$receiver failed: a reminder may not have been shown", e)
        } finally {
            pending.finish()
        }
    }
}

private fun Context.container(): AppContainer = (applicationContext as NoteBudgetApplication).container

private fun Intent.reminderId(): String? = data?.lastPathSegment

/** Alarm fired, or the user pressed "Done" on a notification. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.reminderId() ?: return
        val action = intent.action
        val container = context.container()
        runAsync {
            try { handle(action, id, intent, context, container) } finally { container.refreshReminderStatus() }
        }
    }

    private suspend fun handle(action: String?, id: String, intent: Intent, context: Context, container: AppContainer) {
        run {
            when (action) {
                ACTION_FIRE -> {
                    // A snooze has an alarm of its own (D150): its id is the reminder's id with a prefix.
                    val snoozed = id.startsWith(SNOOZE_PREFIX)
                    val real = id.removePrefix(SNOOZE_PREFIX)
                    val fired = if (snoozed) container.reminderUseCases.onSnoozeFired(real) else container.reminderUseCases.onFired(real)
                    // What woke the phone, for the journal (D345): never a word of the note.
                    com.notebudget.app.core.common.AppLog.i("reminder", "an alarm went off (" + real.take(8) + (if (snoozed) ", a snooze" else "") + "): " + describe(fired))
                    fired?.let { reminder ->
                        val note = container.noteRepository.getById(reminder.noteId)
                        val privacy = container.settings.notificationPrivacy.first()
                        val snooze = container.settings.snoozeMinutes.first()
                        // A ring nobody answers lights the screen for less each time (D351).
                        val ring = RingSeries.ring(context, reminder.id, reminder.repeatIntervalMinutes)
                        container.reminderNotifier.show(reminder, note, privacy, snooze, ring)
                        container.reminderNotifier.startAlarmScreen(reminder, note, privacy, snooze, ring)
                    }
                }

                ACTION_TEST -> {
                    // "Test the alarm" in Settings: a reminder that is not stored anywhere rings like a real one (D152).
                    val now = System.currentTimeMillis()
                    val test = com.notebudget.app.domain.model.Reminder(
                        id = "test", noteId = "", type = com.notebudget.app.domain.model.ReminderType.ONE_TIME, triggerAt = now,
                        isEnabled = true, status = com.notebudget.app.domain.model.ReminderStatus.FIRED, soundEnabled = true, soundUri = null,
                        vibrationEnabled = true, popupEnabled = true, requiresConfirmation = false, repeatUntilConfirmed = false,
                        repeatIntervalMinutes = null, createdAt = now, updatedAt = now,
                    )
                    val snooze = container.settings.snoozeMinutes.first()
                    val privacy = container.settings.notificationPrivacy.first()
                    container.reminderNotifier.show(test, null, privacy, snooze)
                    container.reminderNotifier.startAlarmScreen(test, null, privacy, snooze)
                }

                ACTION_SNOOZE -> {
                    val minutes = intent.getIntExtra(EXTRA_MINUTES, 10)
                    container.reminderUseCases.snooze(id, minutes)
                    container.reminderNotifier.dismiss(id)
                }

                ACTION_DISMISS -> container.reminderNotifier.dismiss(id)

                ACTION_SILENCE -> com.notebudget.app.core.notification.ReminderRingService.stop(context)

                ACTION_CONFIRM -> {
                    container.reminderUseCases.onConfirmed(id)
                    container.reminderNotifier.dismiss(id)
                }
            }
        }
    }

    /** How the reminder rings and whether it rings again: what its alarm costs (D345). */
    private fun describe(fired: com.notebudget.app.domain.model.Reminder?): String {
        if (fired == null) return "nothing to ring"
        val interval = fired.repeatIntervalMinutes?.takeIf { it > 0 }
        return listOfNotNull(
            "rings",
            "sound".takeIf { fired.soundEnabled },
            "the alarm screen".takeIf { fired.popupEnabled },
            "waits for Done".takeIf { fired.requiresConfirmation },
            when {
                interval == null -> null
                fired.requiresConfirmation && fired.repeatUntilConfirmed -> "again every $interval min until Done"
                fired.repeatCount > 0 -> "extra ring " + fired.repeatsDone + " of " + fired.repeatCount + ", every $interval min"
                else -> null
            },
        ).joinToString(", ")
    }

    companion object {
        const val ACTION_FIRE = "com.notebudget.app.action.REMINDER_FIRE"
        const val ACTION_CONFIRM = "com.notebudget.app.action.REMINDER_CONFIRM"
        const val ACTION_SILENCE = "com.notebudget.app.action.REMINDER_SILENCE"
        const val ACTION_SNOOZE = "com.notebudget.app.action.REMINDER_SNOOZE"
        const val ACTION_DISMISS = "com.notebudget.app.action.REMINDER_DISMISS"
        const val ACTION_TEST = "com.notebudget.app.action.REMINDER_TEST"
        const val EXTRA_MINUTES = "minutes"
        private const val SNOOZE_PREFIX = "snooze-"
    }
}

/**
 * Sets every alarm again and shows the reminders whose time has passed meanwhile: after a reboot, an update, a change of the
 * clock — and when the app is opened (D341): a phone that "force-stops" an app (the person in the system settings, or the
 * maker's battery manager when the app is swiped away) takes all of its alarms and tells it nothing until it is opened again.
 * One read of the active reminders and of the budgets; nothing stays running.
 */
internal suspend fun rearmAlarms(container: AppContainer) {
    // The alarms of budget merges come back too (D237).
    runCatching { container.budgetMerge.rescheduleAll() }
    // A merge whose time passed has no alarm (D345): it is done here.
    runCatching { container.budgetMerge.runDue() }
    val due = container.reminderUseCases.restoreAll()
    if (due.isEmpty()) return
    // The settings are read once, not for every reminder that was due.
    val privacy = container.settings.notificationPrivacy.first()
    val snooze = container.settings.snoozeMinutes.first()
    due.forEach { reminder -> container.reminderNotifier.show(reminder, container.noteRepository.getById(reminder.noteId), privacy, snooze) }
}

/**
 * Re-arms reminders after a reboot (INV-8), an app update, or a time / timezone change
 * (AGENT_EN.md §10.3, §10.4). Alarms do not survive a reboot.
 */
class RescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The receiver is exported so the system can call it; only the system broadcasts it listens for are acted on, so another
        // app cannot make it re-post notifications by sending it an arbitrary intent.
        if (intent.action !in ACTIONS) return
        val container = context.container()
        // Why the phone started Notio, for the journal (D345): a phone that says "the clock was set" often shows here.
        com.notebudget.app.core.common.AppLog.i("reminder", "the alarms are set again: " + intent.action?.substringAfterLast('.'))
        runAsync { rearmAlarms(container) }
        // "Always ready for a sync" (D342): the phone waits again after its restart and after an update of the app.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { com.notebudget.app.data.sync.SyncListenService.follow(context) }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            //noinspection InlinedApi: a constant string; below Android 12 this broadcast never comes, nothing else changes.
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
