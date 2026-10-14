package com.notebudget.app.core.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.notebudget.app.R
import com.notebudget.app.core.alarm.ReminderReceiver
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NotificationPrivacy
import com.notebudget.app.domain.model.Reminder

/**
 * Notification channels for reminders (D129). Android fixes a channel's sound and vibration when it is created, and
 * remembers what the person changed in the phone's settings, so there is one channel per sound + vibration combination of
 * a reminder, made on demand: the chosen melody is set EXPLICITLY on it (before, a channel without a sound set could stay
 * silent), on the alarm audio stream so it rings like an alarm clock. The ids of the four channels of older versions are
 * left alone.
 */
object NotificationChannels {
    const val REMINDERS_SOUND_VIBRATION = "reminders_sound_vibration"
    const val REMINDERS_SOUND = "reminders_sound"
    const val REMINDERS_VIBRATION = "reminders_vibration"
    const val REMINDERS_SILENT = "reminders_silent"

    /** The id of the channel for a sound (`null` = the phone's default notification sound, `""` = none) and vibration. */
    fun idFor(soundUri: String?, soundEnabled: Boolean, vibration: Boolean): String {
        val sound = when {
            !soundEnabled -> "silent"
            soundUri.isNullOrBlank() -> "default"
            else -> Integer.toHexString(soundUri.hashCode())
        }
        return "rem2_${sound}_${if (vibration) "vib" else "novib"}"
    }

    /** Makes sure the channel of [reminder] exists and returns its id. */
    fun channelFor(context: Context, reminder: Reminder, silentSound: Boolean = false): String {
        // With [silentSound] the melody is played by the ring service, so the channel itself makes no sound.
        val plays = reminder.soundEnabled && !silentSound
        val id = idFor(reminder.soundUri, plays, reminder.vibrationEnabled)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(id) != null) return id
        val channel = NotificationChannel(id, context.getString(R.string.channel_reminders_generic), NotificationManager.IMPORTANCE_HIGH)
        if (plays) {
            val uri = reminder.soundUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            channel.setSound(
                uri,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        } else {
            channel.setSound(null, null)
        }
        channel.enableVibration(reminder.vibrationEnabled)
        if (reminder.vibrationEnabled) channel.vibrationPattern = longArrayOf(0, 500, 250, 500)
        manager.createNotificationChannel(channel)
        return id
    }

    /** Idempotent; call on app start: the channels of the default sound, with and without vibration. */
    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun legacy(id: String, nameRes: Int, sound: Boolean, vibration: Boolean) {
            val channel = NotificationChannel(id, context.getString(nameRes), NotificationManager.IMPORTANCE_HIGH)
            if (!sound) channel.setSound(null, null)
            channel.enableVibration(vibration)
            manager.createNotificationChannel(channel)
        }
        legacy(REMINDERS_SOUND_VIBRATION, R.string.channel_reminders_sound_vibration, sound = true, vibration = true)
        legacy(REMINDERS_SOUND, R.string.channel_reminders_sound, sound = true, vibration = false)
        legacy(REMINDERS_VIBRATION, R.string.channel_reminders_vibration, sound = false, vibration = true)
        legacy(REMINDERS_SILENT, R.string.channel_reminders_silent, sound = false, vibration = false)
    }
}

/** Shows reminder notifications honouring the privacy setting (D60). */
class ReminderNotifier(private val context: Context) {

    /**
     * Rings the reminder like an alarm clock (D150): a notification with Snooze, Done and Silence buttons that are also
     * visible on the lock screen (the text already honours the privacy setting, D60), and, when the reminder asks for a pop-up,
     * a full-screen alarm over the lock screen that switches the screen on ([ReminderAlarmActivity]).
     */
    @android.annotation.SuppressLint("MissingPermission") // without the permission the system drops the notification
    fun show(reminder: Reminder, note: Note?, privacy: NotificationPrivacy, snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES, ring: Int = 1) {
        if (!canPostNotifications()) return
        val (title, text) = content(note, privacy)

        fun build(channelId: String, silenceAction: Boolean): Notification {
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // A pop-up reminder is an alarm for the system: that is the category that may wake the screen (D152).
                .setCategory(if (reminder.popupEnabled) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openCalendarIntent(reminder))
                .setAutoCancel(!reminder.requiresConfirmation)
                .setOngoing(reminder.requiresConfirmation)
                // Swiped away (Android 14 lets a person swipe even the notification of a playing service): the melody must
                // not go on without anything left to stop it with (D296).
                .setDeleteIntent(dismissIntent(reminder.id))
            if (text != null) builder.setContentText(text)
            if (reminder.popupEnabled) builder.setFullScreenIntent(alarmIntent(reminder, title, text, snoozeMinutes, ring), true)
            // While the ring service plays the melody, Stop ends the sound and leaves the reminder as it is (D293): the phone's own
            // alarm clock or a call may have covered the alarm screen, and the sound must always have a way to be ended.
            if (silenceAction) builder.addAction(0, context.getString(R.string.reminder_stop_sound), silenceIntent(reminder.id))
            builder.addAction(0, context.getString(R.string.reminder_action_snooze, snoozeMinutes), snoozeIntent(reminder.id, snoozeMinutes))
            // One clear way to end it (D152): a reminder that asks for confirmation is confirmed ("Done"), any other is turned off.
            if (reminder.requiresConfirmation) {
                builder.addAction(0, context.getString(R.string.reminder_action_complete), confirmIntent(reminder.id))
            } else {
                builder.addAction(0, context.getString(R.string.alarm_dismiss), dismissIntent(reminder.id))
            }
            return builder.build()
        }

        // Every melody is played by the ring service (D296; before, only one with its own volume or a fade-in, D130): the service
        // gives way to the phone's alarm clock and to a call, ends the melody after a minute, has Stop in the notification and
        // tells the Reminders page what rings. A sound played by the notification channel had none of that: a long melody went
        // on under and after the phone's alarm and nothing in the app could end it. The channel is only the way out when
        // Android does not let the service start (no exact alarms, the app restricted in the background).
        if (reminder.soundEnabled) {
            val notification = build(NotificationChannels.channelFor(context, reminder, silentSound = true), silenceAction = true)
            if (ReminderRingService.start(context, notificationId(reminder.id), notification, reminder.soundUri, reminder.volumePercent, reminder.rampSeconds, reminder.id)) {
                changed()
                return
            }
        }
        NotificationManagerCompat.from(context).notify(notificationId(reminder.id), build(NotificationChannels.channelFor(context, reminder), silenceAction = false))
        changed()
    }

    /**
     * The reminders of [ids] that call for attention now (D296): their notification is still in the shade, whether a melody plays
     * or not. Asked from the system, so it is right after the app was restarted too.
     */
    fun shownNow(ids: Collection<String>): Set<String> {
        val shown = runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications.mapTo(HashSet()) { it.id }
        }.getOrDefault(emptySet<Int>())
        return ids.filterTo(HashSet()) { notificationId(it) in shown }
    }

    /**
     * A second way to bring the alarm screen up (D152): with the permission "display over other apps" Android lets the app
     * start it straight from the background, which does not depend on the full-screen notification being honoured.
     */
    fun startAlarmScreen(reminder: Reminder, note: Note?, privacy: NotificationPrivacy, snoozeMinutes: Int, ring: Int = 1) {
        if (!reminder.popupEnabled || !android.provider.Settings.canDrawOverlays(context)) return
        val (title, text) = content(note, privacy)
        val intent = Intent(context, ReminderAlarmActivity::class.java)
            .setData(Uri.parse("notebudget://alarm/${reminder.id}"))
            .putExtra(ReminderAlarmActivity.EXTRA_REMINDER_ID, reminder.id)
            .putExtra(ReminderAlarmActivity.EXTRA_NOTE_ID, reminder.noteId)
            .putExtra(ReminderAlarmActivity.EXTRA_TITLE, title)
            .putExtra(ReminderAlarmActivity.EXTRA_TEXT, text)
            .putExtra(ReminderAlarmActivity.EXTRA_CONFIRM, reminder.requiresConfirmation)
            .putExtra(ReminderAlarmActivity.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            .putExtra(ReminderAlarmActivity.EXTRA_RING, ring)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        runCatching { context.startActivity(intent) }
    }

    /** Ends a ring for good: the melody, and the notification whether it belongs to the ring service or to the channel. */
    fun dismiss(reminderId: String) {
        // Answered: the next ring of this reminder is a first ring again (D351).
        com.notebudget.app.core.alarm.RingSeries.answered(context, reminderId)
        ReminderRingService.stopAndRemove(context)
        NotificationManagerCompat.from(context).cancel(notificationId(reminderId))
        changed()
    }

    private fun content(note: Note?, privacy: NotificationPrivacy): Pair<String, String?> {
        val generic = context.getString(R.string.reminder_generic_title) to
            context.getString(R.string.reminder_generic_text)
        if (note == null || privacy == NotificationPrivacy.GENERIC) return generic
        return when (privacy) {
            NotificationPrivacy.TITLE_ONLY -> note.title to null
            NotificationPrivacy.FULL -> note.title to note.contentText?.take(PREVIEW_LENGTH)?.takeIf { it.isNotBlank() }
            NotificationPrivacy.GENERIC -> generic
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Tapping the notification opens THE CALENDAR on the day it rang, the reminder marked in the list of that day (D267, before
     * D126 it opened the note); from there one tap opens the note or the task. After the lock, if there is one. The data URI
     * makes the pending intent unique per reminder.
     */
    private fun openCalendarIntent(reminder: Reminder): PendingIntent {
        val intent = Intent(context, com.notebudget.app.MainActivity::class.java)
            .setAction(OpenNoteRequest.ACTION_OPEN_CALENDAR)
            .setData(Uri.parse("notebudget://calendar/${reminder.id}"))
            .putExtra(OpenNoteRequest.EXTRA_REMINDER_ID, reminder.id)
            .putExtra(OpenNoteRequest.EXTRA_NOTE_ID, reminder.noteId)
            .putExtra(OpenNoteRequest.EXTRA_AT, System.currentTimeMillis())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The full-screen alarm: title and text are computed here, so the screen shows exactly what the notification shows. */
    private fun alarmIntent(reminder: Reminder, title: String, text: String?, snoozeMinutes: Int, ring: Int): PendingIntent {
        val intent = Intent(context, ReminderAlarmActivity::class.java)
            .setData(Uri.parse("notebudget://alarm/${reminder.id}"))
            .putExtra(ReminderAlarmActivity.EXTRA_REMINDER_ID, reminder.id)
            .putExtra(ReminderAlarmActivity.EXTRA_NOTE_ID, reminder.noteId)
            .putExtra(ReminderAlarmActivity.EXTRA_TITLE, title)
            .putExtra(ReminderAlarmActivity.EXTRA_TEXT, text)
            .putExtra(ReminderAlarmActivity.EXTRA_CONFIRM, reminder.requiresConfirmation)
            .putExtra(ReminderAlarmActivity.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            .putExtra(ReminderAlarmActivity.EXTRA_RING, ring)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        return PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun snoozeIntent(reminderId: String, minutes: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_SNOOZE)
            .setData(Uri.parse("notebudget://reminder/$reminderId"))
            .putExtra(ReminderReceiver.EXTRA_MINUTES, minutes)
        return PendingIntent.getBroadcast(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun silenceIntent(reminderId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_SILENCE)
            .setData(Uri.parse("notebudget://reminder/$reminderId"))
        return PendingIntent.getBroadcast(context, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun dismissIntent(reminderId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_DISMISS)
            .setData(Uri.parse("notebudget://reminder/$reminderId"))
        return PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun confirmIntent(reminderId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_CONFIRM)
            .setData(Uri.parse("notebudget://reminder/$reminderId"))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notificationId(reminderId: String): Int = reminderId.hashCode()

    companion object {
        private const val PREVIEW_LENGTH = 120

        private val changeCount = kotlinx.coroutines.flow.MutableStateFlow(0)

        /** Goes up whenever a reminder's notification is shown or taken away by the app: the Reminders page looks again (D296). */
        val changes: kotlinx.coroutines.flow.StateFlow<Int> = changeCount

        fun changed() { changeCount.value = changeCount.value + 1 }
        const val DEFAULT_SNOOZE_MINUTES = 10

        /** Whether the phone lets this app open a full-screen alarm over the lock screen (Android 14 asks the person). */
        fun canUseFullScreen(context: Context): Boolean =
            Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
}
