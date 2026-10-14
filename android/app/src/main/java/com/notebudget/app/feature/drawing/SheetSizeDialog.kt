package com.notebudget.app.feature.drawing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.notebudget.app.R

/** A number as typed: a comma is a decimal point too; `null` when it is not a number. */
internal fun parseSize(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

/** A size for the field: whole pixels, mm / cm without a needless ".0". */
internal fun formatSize(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()

/**
 * "Sheet size" (D260): a ready ratio or a width and a height typed in px, mm or cm, with the pixels it makes and why a size
 * cannot be used. [onConfirm] gets the size in pixels and the unit.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SheetSizeDialog(width: Int, height: Int, unit: SheetUnit, onConfirm: (Int, Int, SheetUnit) -> Unit, onDismiss: () -> Unit) {
    var chosenUnit by remember { mutableStateOf(unit) }
    var widthText by remember { mutableStateOf(formatSize(unit.fromPixels(width))) }
    var heightText by remember { mutableStateOf(formatSize(unit.fromPixels(height))) }
    val w = parseSize(widthText)?.let { chosenUnit.toPixels(it) }
    val h = parseSize(heightText)?.let { chosenUnit.toPixels(it) }
    val problem = if (w == null || h == null) SheetSizeProblem.TOO_SMALL else SheetSize.problem(w, h)

    fun show(pxW: Int, pxH: Int, u: SheetUnit) {
        widthText = formatSize(u.fromPixels(pxW))
        heightText = formatSize(u.fromPixels(pxH))
    }

    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawing_sheet_size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetSize.PRESETS.forEach { p ->
                        FilterChip(w == p.width && h == p.height, { show(p.width, p.height, chosenUnit) }, { Text(p.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetUnit.entries.forEach { u ->
                        FilterChip(u == chosenUnit, {
                            // The same size shown in the other unit.
                            if (w != null && h != null) show(w, h, u)
                            chosenUnit = u
                        }, { Text(unitLabel(u)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it },
                        label = { Text(stringResource(R.string.drawing_sheet_width)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it },
                        label = { Text(stringResource(R.string.drawing_sheet_height)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (w != null && h != null) Text(stringResource(R.string.drawing_sheet_pixels, w, h), style = MaterialTheme.typography.bodyMedium)
                when (problem) {
                    SheetSizeProblem.TOO_SMALL -> Text(
                        stringResource(R.string.drawing_sheet_too_small, SheetSize.MIN_SIDE),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                    SheetSizeProblem.TOO_LARGE -> Text(
                        stringResource(R.string.drawing_sheet_too_large, SheetSize.MAX_SIDE, 2048, 2048),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                    null -> Unit
                }
                if (chosenUnit != SheetUnit.PX) {
                    Text(stringResource(R.string.drawing_sheet_ppi, SheetSize.PPI), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.drawing_sheet_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (w != null && h != null && problem == null) onConfirm(w, h, chosenUnit) }, enabled = problem == null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun unitLabel(u: SheetUnit): String = stringResource(
    when (u) {
        SheetUnit.PX -> R.string.drawing_unit_px
        SheetUnit.MM -> R.string.drawing_unit_mm
        SheetUnit.CM -> R.string.drawing_unit_cm
    },
)

/** A frame with corner marks: "the size of the sheet". */
internal val SheetSizeIcon: ImageVector by lazy {
    ImageVector.Builder("SheetSize", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser()
                .parsePathString("M3,3h7v2h-5v5h-2z M14,3h7v7h-2v-5h-5z M3,14h2v5h5v2h-7z M19,14h2v7h-7v-2h5z M8,8h8v8h-8z")
                .toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}
