package com.notebudget.app.core.notification

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.notebudget.app.core.ui.tip.Button
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.alarm.ReminderReceiver
import com.notebudget.app.core.ui.theme.NoteBudgetTheme

/**
 * The reminder as an alarm clock's screen (D150): opened by the notification's full-screen intent, it shows over the lock
 * screen, switches the screen on, and has big buttons: Snooze, Done (when the reminder asks for confirmation) or Dismiss,
 * and Open note. It shows only what the notification shows, so the privacy setting (D60) is kept; opening the note goes
 * through the app lock as usual.
 */
class ReminderAlarmActivity : ComponentActivity() {
    private var reminderId by mutableStateOf("")
    private var noteId by mutableStateOf("")
    private var title by mutableStateOf("")
    private var text by mutableStateOf<String?>(null)
    private var confirm by mutableStateOf(false)
    private var minutes by mutableStateOf(ReminderNotifier.DEFAULT_SNOOZE_MINUTES)
    /** The number of this ring in its series (D351): the later the ring, the shorter the screen is held. */
    private var ring = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        take(intent)
        holdScreen()
        setContent {
            NoteBudgetTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("⏰", style = MaterialTheme.typography.displayLarge)
                        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        text?.let { Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
                        OutlinedButton(onClick = { send(ReminderReceiver.ACTION_SNOOZE) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.reminder_action_snooze, minutes))
                        }
                        if (confirm) {
                            Button(onClick = { send(ReminderReceiver.ACTION_CONFIRM) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.reminder_action_complete))
                            }
                        }
                        if (!confirm) {
                            Button(onClick = { send(ReminderReceiver.ACTION_DISMISS) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.alarm_dismiss))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
        // Another reminder rings: its minute starts.
        holdScreen()
    }

    /** Lets the screen go dark by the phone's own timeout again; the alarm stays on the lock screen, to be answered later. */
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    /**
     * The screen is held on for as long as a melody may play and no longer (D331). Before, an alarm nobody answered — the phone
     * on a table, its owner away — kept the screen lit until somebody came: hours of the most expensive thing a phone does.
     * A ring that repeats unanswered holds it for less each time (D351): a minute, then 15 seconds, then 5.
     */
    private fun holdScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.removeCallbacks(releaseScreen)
        window.decorView.postDelayed(releaseScreen, com.notebudget.app.domain.model.RingScreen.holdMillis(ring))
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(releaseScreen)
        super.onDestroy()
    }

    private fun take(intent: Intent?) {
        reminderId = intent?.getStringExtra(EXTRA_REMINDER_ID).orEmpty()
        noteId = intent?.getStringExtra(EXTRA_NOTE_ID).orEmpty()
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        text = intent?.getStringExtra(EXTRA_TEXT)
        confirm = intent?.getBooleanExtra(EXTRA_CONFIRM, false) ?: false
        minutes = intent?.getIntExtra(EXTRA_SNOOZE_MINUTES, ReminderNotifier.DEFAULT_SNOOZE_MINUTES) ?: ReminderNotifier.DEFAULT_SNOOZE_MINUTES
        ring = intent?.getIntExtra(EXTRA_RING, 1) ?: 1
    }

    /** Tells the receiver what the person chose (it stops the sound and the notification) and leaves. */
    private fun send(action: String) {
        sendBroadcast(
            Intent(this, ReminderReceiver::class.java).setAction(action)
                .setData(Uri.parse("notebudget://reminder/$reminderId"))
                .putExtra(ReminderReceiver.EXTRA_MINUTES, minutes),
        )
        finish()
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_CONFIRM = "confirm"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        const val EXTRA_RING = "ring"
    }
}
