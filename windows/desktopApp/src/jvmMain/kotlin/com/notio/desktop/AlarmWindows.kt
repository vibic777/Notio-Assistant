package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.notio.desktop.theme.NotioTheme
import com.notio.shared.domain.Comment

/** How many minutes Snooze puts a reminder off (the phone's ReminderNotifier.DEFAULT_SNOOZE_MINUTES). */
const val DEFAULT_SNOOZE_MINUTES = 10

/**
 * The alarm screen of the phone (D150, D152) as a window of its own over every other window: the bell, the note, Snooze, and
 * Done (a reminder that asks for a confirmation) or Turn off; Open note brings the main window to it. The Windows notification
 * with the same text is shown by [showReminderToast].
 */
@Composable
fun ApplicationScope.AlarmWindows(controller: NotioController, onOpenNote: (String) -> Unit) {
    val ringing = controller.reminders.ringing.collectAsStateValue()
    ringing.forEach { r ->
        androidx.compose.runtime.key(r.reminder.id) {
            LaunchedEffect(r.reminder.id) { showReminderToast(controller, r); controller.reminderNotified(r.reminder.id) }
            if (r.reminder.popupEnabled) {
                Window(
                    onCloseRequest = { controller.reminders.done(r) },
                    title = Glyphs.alarm + " " + r.noteTitle,
                    alwaysOnTop = true,
                    resizable = false,
                    state = rememberWindowState(size = DpSize(420.dp, 460.dp), position = WindowPosition(Alignment.Center)),
                ) {
                    val state = controller.state.collectAsStateValue()
                    NotioTheme(state.theme) {
                        CompositionLocalProvider(LocalRes provides controller.res) {
                            AlarmContent(controller, r, onOpenNote)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmContent(controller: NotioController, r: Ringing, onOpenNote: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(Glyphs.alarm, style = MaterialTheme.typography.displayLarge)
            Text(r.noteTitle, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            // Read once for the window, not at each recomposition (the whole-code pass).
            val comment = androidx.compose.runtime.remember(r.reminder.noteId) { controller.noteComment(r.reminder.noteId) }
            Comment.short(comment, 80)?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            Text(Format.dateTime(r.reminder.triggerAt), style = MaterialTheme.typography.bodyMedium)
            // Stop the melody only (Android D293): the window stays for Snooze / Done.
            if (controller.reminders.sounding.collectAsStateValue() == r.reminder.id) {
                OutlinedButton(onClick = controller.reminders::silence, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcons.VolumeOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource("reminder_stop_sound"), Modifier.padding(start = 8.dp))
                }
            }
            // How long, as chosen in Settings -> Security -> Alarm (Android D150).
            val snooze = controller.appSettings.snoozeNow
            OutlinedButton(onClick = { controller.reminders.snooze(r, snooze) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource("reminder_action_snooze", snooze))
            }
            Button(onClick = { controller.reminders.done(r) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (r.reminder.requiresConfirmation) "reminder_action_complete" else "alarm_dismiss"))
            }
            if (r.reminder.noteId.isNotEmpty()) OutlinedButton(onClick = { controller.reminders.done(r); onOpenNote(r.reminder.noteId) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource("alarm_open_note"))
            }
        }
    }
}

/**
 * A Windows notification for a reminder (the phone's notification), through the tray icon of the app. What it says follows
 * Settings -> Security -> Notification privacy (Android D46): a general text, the title, or the title and the comment.
 */
fun showReminderToast(controller: NotioController, r: Ringing) {
    val tray = TrayHolder.state ?: return
    val privacy = runCatching { com.notio.shared.domain.NotificationPrivacy.valueOf(controller.appSettings.current.value.notificationPrivacy ?: "") }
        .getOrDefault(com.notio.shared.domain.NotificationPrivacy.GENERIC)
    val (title, text) = when (privacy) {
        com.notio.shared.domain.NotificationPrivacy.GENERIC -> controller.res.str("reminder_generic_title") to controller.res.str("reminder_generic_text")
        com.notio.shared.domain.NotificationPrivacy.TITLE_ONLY -> r.noteTitle to Format.dateTime(r.reminder.triggerAt)
        com.notio.shared.domain.NotificationPrivacy.FULL -> r.noteTitle to (Comment.short(controller.noteComment(r.reminder.noteId), 80) ?: Format.dateTime(r.reminder.triggerAt))
    }
    DesktopNotice.send(tray, Glyphs.alarm + " $title", text)
}

/**
 * A notification of the desktop. Through the tray icon where the system has one; a Linux desktop without a tray (GNOME as it
 * comes, WSL) gets it from `notify-send`, which is there wherever notifications are (D129). Never an error: a notification that
 * cannot be shown leaves the alarm window and the melody, which do not depend on it.
 */
object DesktopNotice {
    val trayThere: Boolean by lazy { runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }

    fun send(tray: androidx.compose.ui.window.TrayState?, title: String, text: String) {
        if (trayThere && tray != null) {
            runCatching { tray.sendNotification(androidx.compose.ui.window.Notification(title, text)) }
            return
        }
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        // "--": the title and the text are words of a note; one that begins with a dash must not be read as an option (D134).
        runCatching { ProcessBuilder("notify-send", "--app-name=Notio", "--", title, text).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
    }
}

/** The tray icon of the app, used for notifications. */
object TrayHolder {
    var state: androidx.compose.ui.window.TrayState? = null
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): T = collectAsState().value
