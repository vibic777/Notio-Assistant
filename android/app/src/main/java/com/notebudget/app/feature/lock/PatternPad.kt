package com.notebudget.app.feature.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.notebudget.app.domain.model.PatternGrid

/**
 * The 3x3 pad (§6.1): touch a dot and slide to the next ones without lifting; [onComplete] gets the dots in order when the
 * finger lifts. The path is shown while it is drawn and cleared after.
 */
@Composable
fun PatternPad(onComplete: (List<Int>) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var dots by remember { mutableStateOf(listOf<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    val line = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outline

    Canvas(
        modifier
            .widthIn(max = 320.dp)
            .aspectRatio(1f)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val side = size.width.toFloat()
                    val radius = side / 3f * 0.32f
                    fun touch(p: Offset) {
                        PatternGrid.dotAt(p.x, p.y, side, radius)?.let { if (it !in dots) dots = dots + it }
                    }
                    val down = awaitFirstDown()
                    dots = emptyList()
                    touch(down.position)
                    finger = down.position
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        touch(change.position)
                        finger = change.position
                        change.consume()
                    } while (change.pressed)
                    val result = dots
                    dots = emptyList()
                    finger = null
                    if (result.isNotEmpty()) onComplete(result)
                }
            },
    ) {
        val side = size.width
        val dotRadius = side / 3f * 0.09f
        for (i in 0..8) {
            val (x, y) = PatternGrid.centre(i, side)
            drawCircle(if (i in dots) line else idle, dotRadius * if (i in dots) 1.5f else 1f, Offset(x, y))
        }
        val points = dots.map { PatternGrid.centre(it, side).let { (x, y) -> Offset(x, y) } }
        for (i in 1 until points.size) {
            drawLine(line, points[i - 1], points[i], strokeWidth = dotRadius, cap = StrokeCap.Round)
        }
        val tail = finger
        if (points.isNotEmpty() && tail != null) drawLine(line.copy(alpha = 0.5f), points.last(), tail, strokeWidth = dotRadius / 2, cap = StrokeCap.Round)
        drawCircle(line.copy(alpha = 0f), 0f, Offset.Zero, style = Stroke(0f))
    }
}
