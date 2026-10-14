package com.notebudget.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog in which something is edited, on the whole screen (D320; owner, 2026-10-06: "where a window over the windows is made
 * — the narrowed window of a reminder's settings — it must take the whole screen; everywhere something is edited"). The same
 * slots as Material's AlertDialog, so a dialog becomes this one by its name alone: the title at the top, what is edited under
 * it over the whole width and height (above the keyboard), Cancel and Save at the bottom. Questions and messages stay the small
 * AlertDialog.
 *
 * In UI tests it is drawn in place, as [AppAlertDialog] is ([LocalInlineDialogs]).
 */
@Composable
fun EditorDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    if (LocalInlineDialogs.current) {
        AppAlertDialog(onDismissRequest, confirmButton, modifier, dismissButton, title, text)
        return
    }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (title != null || icon != null) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        icon?.invoke()
                        title?.let { ProvideTextStyle(MaterialTheme.typography.titleLarge) { it() } }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text?.invoke() }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
