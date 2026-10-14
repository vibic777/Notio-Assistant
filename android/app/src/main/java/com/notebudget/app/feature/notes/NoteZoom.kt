package com.notebudget.app.feature.notes

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import kotlin.math.roundToInt

/** The zoom of a note (D118): one number, 1.0 = normal, changed by the + / - / Normal buttons and by pinching. */
object Zoom {
    const val MIN = 0.6f
    const val MAX = 3.0f
    const val STEP = 0.15f
    const val NORMAL = 1.0f

    fun clamp(z: Float): Float = z.coerceIn(MIN, MAX)
    fun plus(z: Float): Float = clamp(((z + STEP) * 100).roundToInt() / 100f)
    fun minus(z: Float): Float = clamp(((z - STEP) * 100).roundToInt() / 100f)
    fun pinch(z: Float, factor: Float): Float = if (factor.isFinite() && factor > 0f) clamp(z * factor) else z
    fun percent(z: Float): Int = (z * 100).roundToInt()
}

/**
 * Calls [onZoom] with the pinch factor while two or more fingers are down. A single finger is left alone, so
 * scrolling and tapping keep working; the pinch itself is consumed so the list underneath does not scroll.
 */
fun Modifier.pinchZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size >= 2) {
                val factor = event.calculateZoom()
                if (factor != 1f) {
                    onZoom(factor)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** The zoom buttons: -, the level (tap = Normal) and +. */
@Composable
fun ZoomBar(
    zoom: Float,
    onMinus: () -> Unit,
    onNormal: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    percentOverride: Int? = null,
    canZoomOut: Boolean = zoom > Zoom.MIN,
    canZoomIn: Boolean = zoom < Zoom.MAX,
) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            // "−" and "+" are signs, not words: their hints are said here (D334).
            com.notebudget.app.core.ui.tip.HintBox(stringResource(R.string.zoom_out_cd), topic = "zoom") { OutlinedButton(onClick = onMinus, enabled = canZoomOut) { Text("\u2212") } }
            OutlinedButton(onClick = onNormal) { Text(stringResource(R.string.zoom_normal) + " " + (percentOverride ?: Zoom.percent(zoom)) + "%") }
            com.notebudget.app.core.ui.tip.HintBox(stringResource(R.string.zoom_in_cd), topic = "zoom") { OutlinedButton(onClick = onPlus, enabled = canZoomIn) { Text("+") } }
        }
    }
}

/**
 * The zoom buttons of a note (D142): they live in the row of the View / Edit tabs, so they are visible on a phone and on a
 * tablet and never cover the page. [density] is the density WITHOUT the zoom: the row is drawn with it and is not scaled.
 */
class ZoomControl(
    val percent: Int,
    val canZoomOut: Boolean,
    val canZoomIn: Boolean,
    val onMinus: () -> Unit,
    val onNormal: () -> Unit,
    val onPlus: () -> Unit,
    val density: Density?,
)

/** The zoom the tabs row of the current note shows; `null` when the note has none. */
val LocalZoomControl = androidx.compose.runtime.compositionLocalOf<ZoomControl?> { null }

/**
 * Minus, the level (a tap = back to normal) and plus, small enough to sit beside the tabs. D270: compact — a TextButton's own
 * 58 dp minimum width made the three too wide and the + was cut off on a phone; now each is as narrow as a touch target allows
 * (40 dp), and they never shrink (the tabs before them do).
 */
@Composable
fun ZoomControls(control: ZoomControl, modifier: Modifier = Modifier) {
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
    val narrow = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
    val minus = com.notebudget.app.core.ui.tip.stringResource(com.notebudget.app.R.string.zoom_out_cd)
    val plus = com.notebudget.app.core.ui.tip.stringResource(com.notebudget.app.R.string.zoom_in_cd)
    val normal = com.notebudget.app.core.ui.tip.stringResource(com.notebudget.app.R.string.zoom_level_cd, control.percent)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
        // "−", "%" and "+" are signs, not words: their hints are said here (D334).
        com.notebudget.app.core.ui.tip.HintBox(minus, topic = "zoom") {
            com.notebudget.app.core.ui.tip.TextButton(onClick = control.onMinus, enabled = control.canZoomOut, contentPadding = padding, modifier = narrow.semantics { contentDescription = minus }) {
                Text("\u2212", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
        }
        com.notebudget.app.core.ui.tip.HintBox(normal, topic = "zoom") {
            com.notebudget.app.core.ui.tip.TextButton(onClick = control.onNormal, contentPadding = padding, modifier = narrow.semantics { contentDescription = normal }) {
                Text(control.percent.toString() + "%", maxLines = 1, softWrap = false)
            }
        }
        com.notebudget.app.core.ui.tip.HintBox(plus, topic = "zoom") {
            com.notebudget.app.core.ui.tip.TextButton(onClick = control.onPlus, enabled = control.canZoomIn, contentPadding = padding, modifier = narrow.semantics { contentDescription = plus }) {
                Text("+", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * A text-like note (text, list, budget) with zoom (D118, D142): the font scale of everything inside is multiplied by the
 * zoom; the buttons are in the tabs row and are NOT scaled. The zoom is kept while the screen lives (rotation included).
 */
@Composable
fun ZoomableText(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var zoom by rememberSaveable { mutableFloatStateOf(Zoom.NORMAL) }
    val density = LocalDensity.current
    val control = ZoomControl(
        percent = Zoom.percent(zoom),
        canZoomOut = zoom > Zoom.MIN,
        canZoomIn = zoom < Zoom.MAX,
        onMinus = { zoom = Zoom.minus(zoom) },
        onNormal = { zoom = Zoom.NORMAL },
        onPlus = { zoom = Zoom.plus(zoom) },
        density = density,
    )
    Box(modifier.pinchZoom { factor -> zoom = Zoom.pinch(zoom, factor) }) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale * zoom),
            LocalZoomControl provides control,
        ) { content() }
    }
}
