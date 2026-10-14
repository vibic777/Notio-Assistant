package com.notebudget.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.notebudget.app.R
import com.notebudget.app.core.common.CrashLog

/**
 * After the app closed with an error (D203) and its window could not be shown at that moment (D224): what went wrong, a button
 * to copy it, and Close, which forgets it.
 */
@Composable
fun LastErrorDialog() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.read(context)) }
    val shown = text ?: return
    ErrorReportDialog(
        text = shown,
        title = stringResource(R.string.crash_title),
        message = stringResource(R.string.crash_message),
        onClose = {
            CrashLog.clear(context)
            text = null
        },
    )
}

/**
 * The error window (D203, D224): the error text (selectable, scrollable), **Copy error text**, Close and, when given,
 * [onRestart]. Tapping outside does not close it, so the text is not lost by a stray touch.
 */
@Composable
fun ErrorReportDialog(text: String, title: String, message: String, onClose: () -> Unit, onRestart: (() -> Unit)? = null) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    )
                }
                if (onRestart != null) TextButton(onClick = onRestart) { Text(stringResource(R.string.crash_restart)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyText(context, "Notio error", text)
                android.widget.Toast.makeText(context, context.getString(R.string.crash_copied), android.widget.Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.db_encryption_copy_error)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}
