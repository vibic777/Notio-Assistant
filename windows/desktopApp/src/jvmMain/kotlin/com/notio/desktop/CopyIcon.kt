package com.notio.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The section of the help that tells about Copy (D143, Android D362). */
const val COPY_HELP = "share"

/** How the tests find a Copy icon. */
const val COPY_TAG = "copy"

/** Puts a text on the clipboard and says "Copied" (the window provides the controller's; without it the text is only copied). */
val LocalCopyText = staticCompositionLocalOf<(String) -> Unit> { { text -> textToClipboard(text) } }

/** [text] on the system clipboard; `false` where there is none (a computer without a screen). */
fun textToClipboard(text: String): Boolean =
    runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }.isSuccess

/**
 * Copy (D143, Android D362): the icon of two sheets, with the hint "Copy" that leads to the help. [text] is asked for at the
 * press, so it is what is shown at that moment. [small] - in a row of a list, beside other icons.
 */
@Composable
fun CopyIcon(modifier: Modifier = Modifier, small: Boolean = false, text: () -> String) {
    val copy = LocalCopyText.current
    CopyIconButton(modifier, small) { copy(text()) }
}

/** The same icon for a copy that is not a text (the picture of a drawing, a whole note). */
@Composable
fun CopyIconButton(modifier: Modifier = Modifier, small: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = (if (small) modifier.size(36.dp) else modifier.size(40.dp)).testTag(COPY_TAG),
        helpTopic = COPY_HELP,
    ) {
        Icon(
            AppIcons.ContentCopy,
            contentDescription = stringResource("action_copy"),
            modifier = Modifier.size(if (small) 18.dp else 20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
