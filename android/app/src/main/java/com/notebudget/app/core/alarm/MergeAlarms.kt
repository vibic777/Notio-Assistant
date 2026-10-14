package com.notebudget.app.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.notebudget.app.NoteBudgetApplication
import com.notebudget.app.core.common.AppLog
import com.notebudget.app.feature.budget.MergeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The alarm of a budget merge (D237): exact when the phone allows it, like a reminder's, else "allow while idle" (a little late
 * rather than lost). One alarm per income / expense note, told apart by the note id in the intent's data.
 */
class AndroidMergeScheduler(private val context: Context) : MergeScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun schedule(noteId: String, atMillis: Long) {
        val pending = pendingIntent(noteId, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching {
            // Battery saver (D308): a merge a few minutes late harms nothing.
            if (!com.notebudget.app.core.common.PowerSaving.isOn(context) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms())) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        }.onFailure { AppLog.e("budget", "the alarm of a merge could not be set", it) }
    }

    override fun cancel(noteId: String) {
        pendingIntent(noteId, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it) }
    }

    private fun pendingIntent(noteId: String, flag: Int): PendingIntent? {
        val intent = Intent(context, MergeReceiver::class.java)
            .setAction(MergeReceiver.ACTION_MERGE)
            .setData(Uri.parse("notebudget://merge/$noteId"))
        return PendingIntent.getBroadcast(context, 0, intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Runs the merge of a budget note at its time (D237). */
class MergeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MERGE) return
        val noteId = intent.data?.lastPathSegment ?: return
        val container = (context.applicationContext as NoteBudgetApplication).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Whatever is due (D339: a merge that repeats may have missed times); the note of this alarm is among it.
                val done = container.budgetMerge.runDue()
                AppLog.i("budget", "the time of a merge came (" + noteId.take(8) + "): " + done + " merged")
            } catch (e: Exception) {
                AppLog.critical("budget", "a merge at its time failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MERGE = "com.notebudget.app.action.BUDGET_MERGE"
    }
}
