package com.notebudget.app.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.withLock
import com.notebudget.app.MainActivity
import com.notebudget.app.R
import com.notebudget.app.domain.model.DateTimeFormats
import com.notebudget.app.domain.model.ReminderSummary
import com.notebudget.app.domain.model.TaskSummary
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.ReminderRepository

/**
 * A quiet notification that stays in the shade while at least one reminder is set (D154), like the "next alarm" of an
 * alarm-clock app: it says how many reminders are on and when the next rings, and a tap opens the app. Since D266 it also
 * counts the tasks (all, still open, overdue) and stays while there is a task even without a reminder. It is removed when
 * there is neither, when the person switches it off in Settings, or when notifications are not allowed.
 */
class ReminderStatusNotification(
    private val context: Context,
    private val reminders: ReminderRepository,
    private val notes: NoteRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** One refresh at a time: an alarm asks for it and so does the change of the reminder it made. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** What this process showed last: the title and the text, `""` for "taken away", `null` for "nothing yet". */
    private var shown: String? = null

    /**
     * Recomputes and shows or removes the notification; called after every change of a reminder. It is posted only when its
     * words changed (D331): an alarm that goes off asks for it up to three times (the start of the process, the alarm, the
     * change of the reminder), and each post made the system draw the same notification again.
     */
    @android.annotation.SuppressLint("MissingPermission") // without the permission the system drops the notification
    suspend fun refresh(enabled: Boolean, formats: DateTimeFormats) = lock.withLock {
        val manager = NotificationManagerCompat.from(context)
        val summary = ReminderSummary.of(reminders.getActive())
        val tasks = TaskSummary.ofExtras(notes.getExtrasByType(com.notebudget.app.domain.model.NoteType.TASK), now())
        if (!enabled || (summary == null && tasks == null) || !canPost()) {
            if (shown != "") manager.cancel(ID)
            shown = ""
            return@withLock
        }
        val title = context.getString(if (summary != null) R.string.status_title else R.string.status_title_tasks)
        val words = title + "\n" + text(summary, tasks, formats)
        if (words == shown) return@withLock
        // A new process (started for an alarm, say) does not know what is in the shade: it looks before it posts again.
        if (shown == null && words == inTheShade()) { shown = words; return@withLock }
        shown = words
        ensureChannel()
        val open = PendingIntent.getActivity(
            context, 4, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text(summary, tasks, formats))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        manager.notify(ID, notification)
    }

    /** "Active: 2 · next: …" and / or "Tasks: 5 · open: 2 · overdue: 1" (D266), one line. */
    private fun text(summary: ReminderSummary?, tasks: TaskSummary?, formats: DateTimeFormats): String = listOfNotNull(
        summary?.let { context.getString(R.string.status_text, it.count, formats.format(it.nextAt)) },
        tasks?.let {
            context.getString(R.string.status_tasks, it.count, it.active) +
                (if (it.overdue > 0) " · " + context.getString(R.string.status_tasks_overdue, it.overdue) else "")
        },
    ).joinToString(" · ")

    /** The title and the text of this notification as the system shows it now, or `null` when it is not there. */
    private fun inTheShade(): String? = runCatching {
        val extras = context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == ID }?.notification?.extras
        extras?.let { it.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() + "\n" + it.getCharSequence(android.app.Notification.EXTRA_TEXT).toString() }
    }.getOrNull()

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_reminder_status), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL = "reminder_status"
        const val ID = 7401
    }
}
