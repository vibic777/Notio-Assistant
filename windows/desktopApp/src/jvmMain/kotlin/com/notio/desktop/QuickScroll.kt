package com.notio.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick scroll (D250): "to the beginning" and "to the end" at the bottom right of a long list. They show while the list moves
 * (and a little after), each only when there is somewhere to go, and never while the list fits the screen.
 */
@Composable
fun BoxScope.QuickScroll(state: LazyListState, bottomPadding: Dp = 16.dp) {
    val scope = rememberCoroutineScope()
    ScrollKeys.Register { top -> scope.launch { jump(state, if (top) 0 else (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } }
    QuickScrollButtons(
        moving = state.isScrollInProgress,
        canUp = state.canScrollBackward,
        canDown = state.canScrollForward,
        onTop = { scope.launch { jump(state, 0) } },
        onBottom = { scope.launch { jump(state, (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
        bottomPadding = bottomPadding,
    )
}

/** The same for a column that scrolls as a whole (the View of a text note). */
@Composable
fun BoxScope.QuickScroll(state: ScrollState, bottomPadding: Dp = 16.dp) {
    val scope = rememberCoroutineScope()
    ScrollKeys.Register { top -> scope.launch { state.scrollTo(if (top) 0 else state.maxValue) } }
    QuickScrollButtons(
        moving = state.isScrollInProgress,
        canUp = state.canScrollBackward,
        canDown = state.canScrollForward,
        onTop = { scope.launch { state.scrollTo(0) } },
        onBottom = { scope.launch { state.scrollTo(state.maxValue) } },
        bottomPadding = bottomPadding,
    )
}

/**
 * For a place without a scroll of its own to read (the text field of Edit): the buttons are there while [long] and call
 * [onTop] / [onBottom] (which move the cursor to the start / the end, so the field scrolls there).
 */
@Composable
fun BoxScope.QuickScrollAlways(long: Boolean, onTop: () -> Unit, onBottom: () -> Unit, bottomPadding: Dp = 16.dp) {
    if (!long) return
    ScrollKeys.Register { top -> if (top) onTop() else onBottom() }
    QuickScrollButtons(moving = true, canUp = true, canDown = true, onTop = onTop, onBottom = onBottom, bottomPadding = bottomPadding, keep = true)
}

/**
 * The keys of the computer for the quick scroll (D250 on the desktop): Home / End and Ctrl+Home / Ctrl+End that no text field took go
 * to the beginning / the end of the list shown last with its quick scroll.
 */
object ScrollKeys {
    private val targets = ArrayList<(Boolean) -> Unit>()

    /** `true` when a list took the key. */
    fun go(top: Boolean): Boolean = targets.lastOrNull()?.let { it(top); true } ?: false

    @Composable
    fun Register(onGo: (top: Boolean) -> Unit) {
        val current = androidx.compose.runtime.rememberUpdatedState(onGo)
        androidx.compose.runtime.DisposableEffect(Unit) {
            val entry: (Boolean) -> Unit = { top -> current.value(top) }
            targets += entry
            onDispose { targets.remove(entry) }
        }
    }
}

/** Far away: jump at once; near: a short animated scroll. */
private suspend fun jump(state: LazyListState, index: Int) {
    val distance = kotlin.math.abs(state.firstVisibleItemIndex - index)
    if (distance > NEAR) state.scrollToItem(index) else state.animateScrollToItem(index)
}

private const val NEAR = 12
private const val SHOW_AFTER_MILLIS = 2_500L

@Composable
private fun BoxScope.QuickScrollButtons(
    moving: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    bottomPadding: Dp,
    keep: Boolean = false,
) {
    // Shown while the list moves and for a moment after, so a finger can reach them.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(moving, keep) {
        if (keep || moving) shown = true
        else if (shown) { delay(SHOW_AFTER_MILLIS); shown = false }
    }
    AnimatedVisibility(
        visible = shown && (canUp || canDown),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomPadding),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val tint = MaterialTheme.colorScheme.onSecondaryContainer
            val topLabel = stringResource("quick_scroll_top")
            val bottomLabel = stringResource("quick_scroll_bottom")
            if (canUp) {
                FilledTonalIconButton(onClick = { onTop(); shown = true }, modifier = Modifier.size(40.dp).semantics { contentDescription = topLabel }) {
                    IconHint(topLabel); EdgeArrow(up = true, tint = tint)
                }
            }
            if (canDown) {
                FilledTonalIconButton(onClick = { onBottom(); shown = true }, modifier = Modifier.size(40.dp).semantics { contentDescription = bottomLabel }) {
                    IconHint(bottomLabel); EdgeArrow(up = false, tint = tint)
                }
            }
        }
    }
}

/** A bar and an arrow pointing at it: "to the very top" or "to the very bottom". Drawn by hand, the icon pack has no such glyph. */
@Composable
private fun EdgeArrow(up: Boolean, tint: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(22.dp).drawBehind {
            val w = size.width
            val h = size.height
            val stroke = w * 0.12f
            val barY = if (up) h * 0.14f else h * 0.86f
            drawLine(tint, Offset(w * 0.18f, barY), Offset(w * 0.82f, barY), strokeWidth = stroke, cap = StrokeCap.Round)
            val tipY = if (up) h * 0.34f else h * 0.66f
            val tailY = if (up) h * 0.92f else h * 0.08f
            val wing = if (up) h * 0.22f else -h * 0.22f
            drawLine(tint, Offset(w / 2f, tipY), Offset(w / 2f, tailY), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(w / 2f, tipY), Offset(w * 0.24f, tipY + wing), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(w / 2f, tipY), Offset(w * 0.76f, tipY + wing), strokeWidth = stroke, cap = StrokeCap.Round)
        },
    )
}
