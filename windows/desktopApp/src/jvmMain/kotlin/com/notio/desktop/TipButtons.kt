package com.notio.desktop

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Every icon button has a hint (Android D301: "all icons must have pop-up hints in the chosen language; on a touch screen they
 * come up when the icon is held"). IconButton and Icon of this package stand in for the Material 3 ones in every file of the app
 * (a declaration of the same package comes before a star import; no file imports the Material ones by name): the button is the
 * Material one inside a tooltip, and the hint is the content description of the icon inside it — the words a screen reader says,
 * already in the language of the app. The tooltip of Material 3 comes up under the mouse and under a finger held on a touch screen.
 */

/** Where the icon inside a button of this file leaves its name for the button's hint. */
private val LocalHint = compositionLocalOf<MutableState<String?>?> { null }

/**
 * The [name] of what is under the mouse, or under a finger held on it (Android D301, D307); nothing is shown without a name.
 *
 * Its own small thing, not Material's TooltipBox: that one also shows the tooltip when the button takes the focus, and it lays its
 * content out loosely. This box passes the size it is given on to [content], so a button inside it fills, weighs and aligns
 * exactly as it did without the box. A hold that brought the hint up is not a click.
 */
@Composable
fun HoverTip(name: String?, modifier: Modifier = Modifier, topic: String? = null, content: @Composable () -> Unit) = HoverTip({ name }, modifier, topic, content)

/** Whose hint is on show: one at a time, the newest (a hint stays for five seconds, also after the mouse went on). */
private val hintOwner = mutableStateOf<Any?>(null)

/**
 * The same with the name asked for only when it is needed — under a resting mouse, a held finger and while the hint is shown.
 * An icon button learns its name from the icon inside it, after it is composed: read here in the composition, that made every
 * icon button of every card compose twice, start its pointer handling again and start a coroutine each time it came onto the
 * screen (the pass of 2026-10-05). Nothing runs here now until the mouse comes over the button.
 *
 * The hint stays for five seconds — also when the mouse has left the button, so that it can be reached — and a press on it
 * opens the help at the section it belongs to (D113, Android D332): [topic] when the button's own words say where it lives,
 * else the section of the screen on show ([HelpLink.around]). A press of the button itself takes the hint away.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HoverTip(name: () -> String?, modifier: Modifier = Modifier, topic: String? = null, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val hint by androidx.compose.runtime.rememberUpdatedState(name)
    val me = remember { Any() }
    // The mouse has rested on it for a moment.
    if (hovered) androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(HOVER_MILLIS)
        if (!hint().isNullOrBlank()) { hintOwner.value = me; visible = true }
    }
    androidx.compose.foundation.layout.Box(
        modifier
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { hovered = true }
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hovered = false }
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press) { if (it.changes.none { c -> c.type == androidx.compose.ui.input.pointer.PointerType.Touch }) { hovered = false; visible = false } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (down.type != androidx.compose.ui.input.pointer.PointerType.Touch || hint().isNullOrBlank()) return@awaitEachGesture
                    val held = try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(androidx.compose.ui.input.pointer.PointerEventPass.Initial) }
                        false
                    } catch (_: androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException) {
                        true
                    }
                    if (held) {
                        hintOwner.value = me
                        visible = true
                        // The rest of this touch belongs to the hint: lifting the finger must not press the button.
                        do {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            event.changes.forEach { if (it.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
        propagateMinConstraints = true,
    ) {
        content()
        // Another button's hint came up meanwhile: this one is gone.
        val shown = if (visible && hintOwner.value === me) hint() else null
        if (!shown.isNullOrBlank()) {
            val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
            androidx.compose.ui.window.Popup(popupPositionProvider = remember(gap) { AboveAnchor(gap) }, onDismissRequest = { visible = false }) {
                androidx.compose.material3.Surface(
                    onClick = { visible = false; HelpLink.open(topic) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.inverseSurface,
                ) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        androidx.compose.material3.Text(
                            shown, color = androidx.compose.material3.MaterialTheme.colorScheme.inverseOnSurface,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                        // The mark of "there is more about it": the hint leads into the help.
                        androidx.compose.material3.Icon(
                            AppIcons.Help, contentDescription = null,
                            tint = androidx.compose.material3.MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            // Five seconds, from the moment it came up.
            androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(HINT_MILLIS); visible = false }
        }
    }
}

private const val HOVER_MILLIS = 500L
/** How long a hint stays (Android D332: five seconds, time enough to read it and to press it). */
private const val HINT_MILLIS = 5_000L

/** Over the button, centred on it; under it when there is no room above; never off the window. */
private class AboveAnchor(private val gap: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (above >= 0) above else (anchorBounds.bottom + gap).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return androidx.compose.ui.unit.IntOffset(x, y)
    }
}

@Composable
private fun Hinted(modifier: Modifier, helpTopic: String?, button: @Composable () -> Unit) {
    val hint = remember { mutableStateOf<String?>(null) }
    // Read under the mouse, not here: the button is composed once (see HoverTip).
    HoverTip({ hint.value }, modifier, helpTopic) { CompositionLocalProvider(LocalHint provides hint, content = button) }
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
