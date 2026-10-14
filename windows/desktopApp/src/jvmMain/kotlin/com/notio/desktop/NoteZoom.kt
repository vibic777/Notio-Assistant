package com.notio.desktop

import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
            // Words for a screen reader (Android D274 step 1).
            val out = stringResource("zoom_out_cd")
            val level = stringResource("zoom_level_cd", percentOverride ?: Zoom.percent(zoom))
            val into = stringResource("zoom_in_cd")
            // "−" and "+" are signs, not words: their hints are said here (D115, Android D334).
            HoverTip(out, topic = "zoom") { OutlinedButton(onClick = onMinus, enabled = canZoomOut, modifier = Modifier.semantics { contentDescription = out }) { Text("\u2212", maxLines = 1) } }
            OutlinedButton(onClick = onNormal, modifier = Modifier.semantics { contentDescription = level }) { Text(stringResource("zoom_normal") + " " + (percentOverride ?: Zoom.percent(zoom)) + "%", maxLines = 1, softWrap = false) }
            HoverTip(into, topic = "zoom") { OutlinedButton(onClick = onPlus, enabled = canZoomIn, modifier = Modifier.semantics { contentDescription = into }) { Text("+", maxLines = 1) } }
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

/** Minus, the level (a tap = back to normal) and plus, small enough to sit beside the tabs. */
@Composable
fun ZoomControls(control: ZoomControl, modifier: Modifier = Modifier) {
    val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
        // Words for a screen reader (Android D270, D274 step 1): "\u2212", "%" and "+" alone say nothing; never wrapped or cut.
        val out = stringResource("zoom_out_cd")
        val level = stringResource("zoom_level_cd", control.percent)
        val into = stringResource("zoom_in_cd")
        HoverTip(out, topic = "zoom") { TextButton(onClick = control.onMinus, enabled = control.canZoomOut, contentPadding = padding, modifier = Modifier.semantics { contentDescription = out }) { Text("\u2212", maxLines = 1) } }
        HoverTip(level, topic = "zoom") { TextButton(onClick = control.onNormal, contentPadding = padding, modifier = Modifier.semantics { contentDescription = level }) { Text(control.percent.toString() + "%", maxLines = 1, softWrap = false) } }
        HoverTip(into, topic = "zoom") { TextButton(onClick = control.onPlus, enabled = control.canZoomIn, contentPadding = padding, modifier = Modifier.semantics { contentDescription = into }) { Text("+", maxLines = 1) } }
    }
}

/**
 * A text-like note (text, list, budget) with zoom (D118, D142): the font scale of everything inside is multiplied by the
 * zoom; the buttons are in the tabs row and are NOT scaled. The zoom is kept while the screen lives (rotation included).
 */
@Composable
fun ZoomableText(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var zoom by remember { mutableFloatStateOf(Zoom.NORMAL) }
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
