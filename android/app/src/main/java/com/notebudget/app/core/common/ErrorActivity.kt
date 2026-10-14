package com.notebudget.app.core.common

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.notebudget.app.core.ui.tip.stringResource
import com.notebudget.app.MainActivity
import com.notebudget.app.R
import com.notebudget.app.core.ui.ErrorReportDialog

/**
 * The error window shown right when Notio closes with an error (D224), on top of whatever is on the screen. It runs in its own
 * process (`:error`, see the manifest): the app's process is already gone, this one is not. It reads the text [CrashLog] wrote,
 * offers Copy error text, Open Notio again and Close; closing forgets the text, so the next start does not show it again.
 */
class ErrorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = CrashLog.read(this)
        if (text == null) {
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ErrorReportDialog(
                    text = text,
                    title = stringResource(R.string.crash_now_title),
                    message = stringResource(R.string.crash_now_message),
                    onClose = {
                        CrashLog.clear(this)
                        finishAndRemoveTask()
                    },
                    onRestart = {
                        CrashLog.clear(this)
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        finishAndRemoveTask()
                    },
                )
            }
        }
    }
}
