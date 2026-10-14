package com.notebudget.app.core.ui.tip

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Every icon button has a hint (D301: "all icons must have pop-up hints in the chosen language; on a touch screen they come up
 * when the icon is held"). The screens import IconButton and Icon from HERE instead of from Material 3: the button is the Material
 * one inside a tooltip, and the hint is the content description of the icon inside it — the words TalkBack reads, already in the
 * language of the app. So a button gets its hint without being told one, and a new button cannot forget it.
 */

/** Where the icon inside a button of this file leaves its name for the button's hint. */
private val LocalHint = compositionLocalOf<MutableState<String?>?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Hinted(modifier: Modifier, helpTopic: String?, button: @Composable () -> Unit) {
    val hint = remember { mutableStateOf<String?>(null) }
    // Read when a finger is held, not here: the button is composed once (see HintBox).
    com.notebudget.app.core.ui.tip.HintBox({ hint.value }, modifier, helpTopic) {
        CompositionLocalProvider(LocalHint provides hint, content = button)
    }
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    /** The section of the help its hint leads to (D334), when it is not the one of the screen around. */
    helpTopic: String? = null,
    content: @Composable () -> Unit,
) = Hinted(modifier, helpTopic) { androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled, colors = colors, interactionSource = interactionSource, content = content) }

@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The section of the help its hint leads to (D334), when it is not the one of the screen around. */
    helpTopic: String? = null,
    content: @Composable () -> Unit,
) = Hinted(modifier, helpTopic) { androidx.compose.material3.FilledTonalIconButton(onClick = onClick, enabled = enabled, content = content) }

/**
 * The hint of a button whose content is not an [Icon] of this file — a glyph, a drawing of its own (D334): said from inside the
 * button, as an icon says its own. Without it such a button had words for a screen reader and no hint.
 */
@Composable
fun IconHint(text: String?) = tell(text)

@Composable
private fun tell(contentDescription: String?) {
    val hint = LocalHint.current ?: return
    if (contentDescription != null) SideEffect { if (hint.value != contentDescription) hint.value = contentDescription }
}

@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    tell(contentDescription)
    androidx.compose.material3.Icon(imageVector, contentDescription, modifier, tint)
}

@Composable
fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    tell(contentDescription)
    androidx.compose.material3.Icon(painter, contentDescription, modifier, tint)
}
