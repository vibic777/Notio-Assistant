package com.notebudget.app.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.notebudget.app.domain.repository.ReminderScheduler

/**
 * `AlarmManager` scheduler (AGENT_EN.md §6.10, §10.2). Uses an exact alarm that fires in Doze
 * when allowed; without the exact-alarm permission it falls back to an inexact
 * "allow while idle" alarm, so the reminder is late rather than lost. The UI must explain the
 * missing permission (`canScheduleExact()`).
 */
class AndroidReminderScheduler(private val context: Context) : ReminderScheduler {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(reminderId: String, triggerAtMillis: Long) {
        // All reminders are off (D318): no alarm is set; turning them on arms them all again.
        if (!RemindersSwitch.isOn(context)) return
        val intent = pendingIntent(reminderId, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        // An alarm that cannot be set must not end the save of the reminder with an error and must not be lost (D341): the leave
        // for exact alarms can be taken away between the question and the call, and the phone then refuses with an exception.
        val exact = !com.notebudget.app.core.common.PowerSaving.isOn(context) && canScheduleExact()
        val set = exact && runCatching { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent) }
            .onFailure { com.notebudget.app.core.common.AppLog.w("reminder", "an exact alarm was refused: a reminder may come late", it) }.isSuccess
        if (set) return
        // Battery saver (D308): the system may batch this wake-up with others; without the leave for exact alarms the same.
        // The reminder may come a few minutes late rather than not at all.
        if (!exact && !com.notebudget.app.core.common.PowerSaving.isOn(context)) {
            com.notebudget.app.core.common.AppLog.w("reminder", "exact alarms are not allowed: a reminder may come late")
        }
        runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent) }
            .onFailure { com.notebudget.app.core.common.AppLog.critical("reminder", "the alarm of a reminder could not be set", it) }
    }

    override fun cancel(reminderId: String) {
        pendingIntent(reminderId, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it) }
    }

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(reminderId: String, flag: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            // The data URI makes the PendingIntent unique per reminder.
            .setData(Uri.parse("notebudget://reminder/$reminderId"))
        return PendingIntent.getBroadcast(context, 0, intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }
}
