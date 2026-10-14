package com.notebudget.app.core.ui

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.notebudget.app.R

/** The section of the help that tells about Copy (D362). */
const val COPY_HELP = "share"

/** How the tests find a Copy icon. */
const val COPY_TAG = "copy"

/** "Copied", said after a copy. Android 13+ shows its own panel of the clipboard, so nothing more is said there. */
fun sayCopied(context: Context) {
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, context.getString(R.string.sync_copied), Toast.LENGTH_SHORT).show()
}

/** [text] to the clipboard, and "Copied" (D362). */
fun copyShown(context: Context, text: String) {
    copyText(context, context.getString(R.string.action_copy), text)
    sayCopied(context)
}

/**
 * Copy (D362): the icon of two sheets, with the hint "Copy" that leads to the help. [text] is asked for at the press, so it is
 * what is shown at that moment. [small] - in a row of a list, beside other icons.
 */
@Composable
fun CopyIcon(modifier: Modifier = Modifier, small: Boolean = false, text: () -> String) {
    val context = LocalContext.current
    CopyIconButton(modifier, small) { copyShown(context, text()) }
}

/** The same icon for a copy that is not a text (the picture of a drawing, a whole note). */
@Composable
fun CopyIconButton(modifier: Modifier = Modifier, small: Boolean = false, onClick: () -> Unit) {
    com.notebudget.app.core.ui.tip.IconButton(
        onClick = onClick,
        modifier = (if (small) modifier.size(36.dp) else modifier.size(40.dp)).testTag(COPY_TAG),
        helpTopic = COPY_HELP,
    ) {
        com.notebudget.app.core.ui.tip.Icon(
            AppIcons.ContentCopy,
            contentDescription = stringResource(R.string.action_copy),
            modifier = Modifier.size(if (small) 18.dp else 20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
