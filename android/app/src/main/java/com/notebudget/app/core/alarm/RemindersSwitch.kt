package com.notebudget.app.core.alarm

import android.content.Context
import com.notebudget.app.core.common.AppContainer
import com.notebudget.app.core.common.AppLog
import com.notebudget.app.core.notification.ReminderRingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * All reminders on or off at once (D318; owner, 2026-10-06: "reminders as a function in the menu, with a way to turn them off
 * completely, all at once"). One switch of this phone, kept beside the app's other device settings (plain preferences, as
 * [com.notebudget.app.core.common.PowerSaving]: it is read where there is no time to wait for a settings flow — an alarm being
 * set, a broadcast). While it is off no alarm of a reminder is set and none fires; the reminders themselves stay as they are,
 * each with its own switch. Turned on again, they are armed as after a restart of the phone: what was due meanwhile rings now.
 *
 * A new installation starts with the reminders off (D328; owner, 2026-10-07: "by default the alarm, the reminders are off after
 * the program is installed; at an attempt to make a reminder, at once a hint that they must be turned on"). A phone that had
 * Notio before, where they were on without a choice, keeps them on.
 */
object RemindersSwitch {
    private const val PREFS = "reminders"
    private const val KEY = "enabled"
    private val state = MutableStateFlow<Boolean?>(null)
    private val shown = MutableStateFlow(true)

    /** For the screens: follows the switch. */
    val enabled: StateFlow<Boolean> get() = shown

    fun isOn(context: Context): Boolean {
        state.value?.let { return it }
        val on = runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Never chosen: decided once and written down, so that a later update does not read a new installation as an old one.
            if (prefs.contains(KEY)) prefs.getBoolean(KEY, true) else (!newInstallation(context)).also { prefs.edit().putBoolean(KEY, it).apply() }
        }.getOrDefault(true)
        state.value = on
        shown.value = on
        return on
    }

    /** Installed and never updated since: the first version on this phone is this one or a later one. */
    private fun newInstallation(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)

    /** The switch alone, nothing armed or taken away: the screens call [change]. */
    internal fun set(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        state.value = on
        shown.value = on
        AppLog.i("reminder", "all reminders " + if (on) "on" else "off")
    }

    /** Turns all reminders off (every alarm is taken away, a melody that plays ends) or on again (armed as after a restart). */
    suspend fun change(context: Context, container: AppContainer, on: Boolean) {
        set(context, on)
        if (!on) {
            runCatching { container.reminderUseCases.disarmAll() }
            runCatching { ReminderRingService.stop(context) }
        } else {
            runCatching {
                container.reminderUseCases.restoreAll().forEach { reminder ->
                    val note = container.noteRepository.getById(reminder.noteId)
                    container.reminderNotifier.show(reminder, note, container.settings.notificationPrivacy.first(), container.settings.snoozeMinutes.first())
                }
            }
        }
        runCatching { container.refreshReminderStatus() }
    }
}
