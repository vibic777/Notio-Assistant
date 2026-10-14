package com.notebudget.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * `true` draws [AppAlertDialog] in place instead of in a window of its own. Only UI tests set it (D202): under Robolectric a
 * dialog WINDOW with a text field in it never lets Compose become idle (checked with AlertDialog, BasicAlertDialog and a plain
 * Dialog: all hang, the same dialog without the field does not), so the real dialog content is tested in place.
 */
val LocalInlineDialogs = staticCompositionLocalOf { false }

/**
 * The app's AlertDialog: the same slots as Material's, and in the app it IS Material's. Dialogs with text input go through it so
 * that their real content can be tested (see [LocalInlineDialogs]).
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    if (!LocalInlineDialogs.current) {
        AlertDialog(onDismissRequest, confirmButton, modifier, dismissButton, title = title, text = text)
        return
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            title?.let { ProvideTextStyle(MaterialTheme.typography.headlineSmall) { it() } }
            text?.let { Box { it() } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
