package com.notebudget.app.feature.colors

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.common.ArgbColor
import com.notebudget.app.core.ui.rememberAppContainer
import kotlinx.coroutines.launch

/** The ready-made colours, shared by drawings and blueprints. */
val PALETTE: List<Int> = listOf(
    0xFF000000, 0xFF616161, 0xFFD32F2F, 0xFFF57C00, 0xFFFBC02D, 0xFF388E3C, 0xFF1976D2, 0xFF7B1FA2, 0xFF6D4C41, 0xFFFFFFFF,
).map { it.toInt() }

/**
 * A row of colour swatches: the ready-made colours, the colours the user picked by RGB recently, and an RGB button
 * that opens the colour picker (D103). With [allowNone] the first swatch means "no colour" (used for fills).
 */
@Composable
fun ColorRow(
    @StringRes label: Int,
    selected: Int?,
    allowNone: Boolean,
    modifier: Modifier = Modifier,
    @StringRes noneLabel: Int = R.string.drawing_fill_none,
    onPick: (Int?) -> Unit,
) {
    val container = rememberAppContainer()
    val recent by container.settings.recentColors.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    val name = stringResource(label)
    val noneDescription = stringResource(noneLabel)

    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
        if (allowNone) Swatch(color = null, selected = selected == null, description = noneDescription) { onPick(null) }
        PALETTE.forEach { argb ->
            Swatch(argb, argb == selected, "$name ${ArgbColor.toHex(argb)}") { onPick(argb) }
        }
        // Colours picked by RGB before, so a favourite one is one tap away.
        val extra = recent.filter { it !in PALETTE }
        // The colour that is chosen now, if it is in no list yet (an old folder colour, say), so the choice is visible.
        selected?.takeIf { it !in PALETTE && it !in extra }?.let { argb ->
            Swatch(argb, true, "$name ${ArgbColor.toHex(argb)}") { onPick(argb) }
        }
        extra.forEach { argb ->
            Swatch(argb, argb == selected, "$name ${ArgbColor.toHex(argb)}") { onPick(argb) }
        }
        OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.color_custom_button)) }
    }

    if (picking) {
        ColorPickerDialog(
            initial = selected ?: PALETTE.first(),
            onPick = { color ->
                picking = false
                scope.launch { container.settings.addRecentColor(color) }
                onPick(color)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Swatch(color: Int?, selected: Boolean, description: String, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (color == null) Color.White else Color(color))
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // "No colour" is drawn as an empty circle with a mark.
        if (color == null) Text("∅", style = MaterialTheme.typography.labelLarge)
    }
}

/** Any colour by its red, green and blue parts (sliders) or by its code (`#RRGGBB`), with a preview. */
@Composable
fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var argb by remember { mutableIntStateOf(initial or (0xFF shl 24)) }
    var hex by remember { mutableStateOf(ArgbColor.toHex(argb)) }
    val hexValid = ArgbColor.parseHex(hex) != null

    fun setParts(red: Int, green: Int, blue: Int) {
        argb = ArgbColor.opaque(red, green, blue)
        hex = ArgbColor.toHex(argb)
    }

    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color(argb))
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                        .semantics { contentDescription = ArgbColor.toHex(argb) },
                )
                ChannelSlider(R.string.color_red, ArgbColor.red(argb)) { setParts(it, ArgbColor.green(argb), ArgbColor.blue(argb)) }
                ChannelSlider(R.string.color_green, ArgbColor.green(argb)) { setParts(ArgbColor.red(argb), it, ArgbColor.blue(argb)) }
                ChannelSlider(R.string.color_blue, ArgbColor.blue(argb)) { setParts(ArgbColor.red(argb), ArgbColor.green(argb), it) }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text.take(7)
                        ArgbColor.parseHex(hex)?.let { argb = it }
                    },
                    singleLine = true,
                    isError = !hexValid,
                    label = { Text(stringResource(R.string.color_hex_label)) },
                    supportingText = if (hexValid) null else {
                        { Text(stringResource(R.string.color_hex_invalid)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(argb) }, enabled = hexValid) { Text(stringResource(R.string.color_use)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChannelSlider(@StringRes label: Int, value: Int, onChange: (Int) -> Unit) {
    val name = stringResource(label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$name $value", modifier = Modifier.size(width = 84.dp, height = 24.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..255f,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = name },
        )
    }
}
