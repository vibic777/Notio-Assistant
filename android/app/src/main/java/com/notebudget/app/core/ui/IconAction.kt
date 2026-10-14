package com.notebudget.app.core.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A button that is only an icon (D278): TalkBack reads its [label], a long press shows the [label] as a tooltip, so an icon
 * never leaves anyone guessing. Used where a word did not fit or an icon is known to everyone (play, sort, delete …); dialog
 * buttons, rare and dangerous actions and buttons that show a value keep their words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    topic: String? = null,
) {
    com.notebudget.app.core.ui.tip.HintBox(label, topic = topic) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
    }
}

/**
 * One of a few things to choose from, shown as its icon (D298): the chosen one is filled. A long press shows the [label], TalkBack
 * reads it; the screen that uses it also writes the chosen [label] in words beside the icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconChoice(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    com.notebudget.app.core.ui.tip.HintBox(label) {
        // One of several (a radio button for a screen reader), not a switch: the chosen one is filled with the main colour.
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onClick, enabled = enabled,
            modifier = modifier.semantics { this.selected = selected; role = androidx.compose.ui.semantics.Role.RadioButton },
            colors = if (selected) androidx.compose.material3.IconButtonDefaults.filledIconButtonColors() else androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(),
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** The round button of a screen's main action as an icon alone (D298), with the same long-press [label] as [IconAction]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconFab(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    com.notebudget.app.core.ui.tip.HintBox(label) {
        androidx.compose.material3.FloatingActionButton(onClick = onClick, modifier = modifier) { Icon(icon, contentDescription = label) }
    }
}
