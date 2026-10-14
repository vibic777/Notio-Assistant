package com.notio.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * "Sheet background" (D265): the colour of the sheet, and "Transparent" with how see-through it is (100 % = no background at
 * all). The sample under the controls shows the result on a checkerboard, as the sheet does.
 */
@Composable
fun SheetBackgroundDialog(initial: SheetBackground, onConfirm: (SheetBackground) -> Unit, onDismiss: () -> Unit) {
    var background by remember { mutableStateOf(initial) }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("drawing_bg")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorRow("drawing_bg_color", selected = background.color or (0xFF shl 24), allowNone = false) { picked ->
                    picked?.let { background = background.copy(color = it or (0xFF shl 24)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(background.transparent, { background = background.copy(transparent = it) })
                    Text(stringResource("drawing_bg_transparent"))
                }
                if (background.transparent) {
                    Text(stringResource("drawing_bg_transparency", background.transparency), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = background.transparency.toFloat(),
                        onValueChange = { background = background.copy(transparency = it.roundToInt().coerceIn(0, 100)) },
                        valueRange = 0f..100f,
                    )
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                ) { drawSheetBackground(background) }
                Text(
                    stringResource("drawing_bg_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(background) }) { Text(stringResource("action_save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

/**
 * The sheet's background as the screen shows it: a light checkerboard wherever it is see-through (so "transparent" can be seen),
 * the colour on top.
 */
internal fun DrawScope.drawSheetBackground(background: SheetBackground) {
    val argb = background.argb
    if (argb ushr 24 != 255) {
        drawRect(Color.White)
        val cell = 8.dp.toPx()
        val grey = Color(0xFFE0E0E0)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else cell
            while (x < size.width) {
                drawRect(grey, topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(cell, cell))
                x += 2 * cell
            }
            y += cell
            row++
        }
    }
    if (background.visible) drawRect(Color(argb))
}

/** A square half filled: "the background of the sheet". */
internal val SheetBackgroundIcon: ImageVector by lazy {
    ImageVector.Builder("SheetBackground", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString("M4,4h16v16h-16z M6,6v12h12v-12z").toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
            pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
        )
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString("M6,18L18,6v12z").toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
        )
    }.build()
}
