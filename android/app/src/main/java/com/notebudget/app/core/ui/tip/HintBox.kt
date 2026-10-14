package com.notebudget.app.core.ui.tip

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay

/**
 * The hint of an icon (D301, D307): [text] comes up over [content] when a finger is held on it, and that hold is not a click.
 *
 * Its own small thing, not Material's TooltipBox: that one also shows the tooltip when the button takes the focus (a dialog gives
 * the focus to its first button the moment it opens, so every dialog opened with a hint hanging over it), and it lays its content
 * out loosely. This box passes the size it is given on to [content], so a button inside it fills, weighs and aligns exactly as it
 * did without the box, and it holds nothing but [content] until the hint is shown.
 */
@Composable
fun HintBox(text: String?, modifier: Modifier = Modifier, topic: String? = null, content: @Composable () -> Unit) = HintBox({ text }, modifier, topic, content)

/**
 * The same with the hint asked for only when it is needed — at a held finger and while it is shown. An icon button learns its
 * hint from the icon inside it, after it is composed: read here in the composition, that made every icon button of every card
 * compose twice and start its touch handling again each time it came onto the screen (the pass of 2026-10-05).
 *
 * The hint stays for five seconds, and a press on it opens the help at the section it belongs to (D332): [topic] when the
 * button's own words say where it lives, else the section of the screen around ([LocalHelpTopic]).
 */
@Composable
fun HintBox(text: () -> String?, modifier: Modifier = Modifier, topic: String? = null, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val around = LocalHelpTopic.current
    val hint by rememberUpdatedState(text)
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (hint().isNullOrBlank()) return@awaitEachGesture
                val held = try {
                    withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(PointerEventPass.Initial) }
                    false
                } catch (_: PointerEventTimeoutCancellationException) {
                    true
                }
                if (held) {
                    visible = true
                    // The rest of this touch belongs to the hint: lifting the finger must not press the button. Judged by the
                    // times of the touch itself, not by the clock of the frames, so a quick tap always stays a tap.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { if (it.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        },
        propagateMinConstraints = true,
    ) {
        content()
        val shown = if (visible) hint() else null
        if (!shown.isNullOrBlank()) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            Popup(popupPositionProvider = remember(gap) { AboveAnchor(gap) }, onDismissRequest = { visible = false }) {
                Surface(
                    onClick = { visible = false; HelpLink.open(topic ?: around) },
                    shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        Text(shown, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall)
                        // The mark of "there is more about it": the hint leads into the help.
                        androidx.compose.material3.Icon(
                            com.notebudget.app.core.ui.AppIcons.Help, contentDescription = null,
                            tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            LaunchedEffect(Unit) { delay(HINT_MILLIS); visible = false }
        }
    }
}

/** How long a hint stays (D332: five seconds, time enough to read it and to press it). */
private const val HINT_MILLIS = 5_000L

/** Over the button, centred on it; under it when there is no room above; never off the screen. */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (above >= 0) above else (anchorBounds.bottom + gap).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
