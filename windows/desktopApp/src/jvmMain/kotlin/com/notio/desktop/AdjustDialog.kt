package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.Adjust
import com.notio.shared.domain.AdjustMode

/**
 * A second number the "Change" dialog can act on at the same time (D215): the goal of a budget item next to its sum. [firstLabel]
 * and [secondLabel] name the two switches; [second] is the second number now.
 */
class AdjustSecond(val firstLabel: String, val secondLabel: String, val second: Long)

/**
 * The "Change" dialog of a budget item and of the quantity and price of a list item (D164, D165): choose **+**, **−** or **=**
 * (put a value in place of the current one), type a number, Save. Only the symbols are on the buttons (D215).
 *
 * With [second] (a budget item, D215) two switches choose what changes: the first number (the sum; on at first), the second
 * (the goal), or both — never neither: switching the last one off is refused. Both on: the same action on both numbers.
 *
 * @param current the number now, in the fixed-point units of the caller (minor units, thousandths)
 * @param parse the number typed, or `null` when it is not a number (blank is handled here)
 * @param format a number as text, for the "Now" and "Will be" lines
 * @param allowNegativeResult a budget sum may go below zero (a refund); a quantity or a price may not
 * @param onConfirm the mode, the amount typed, the value the first number becomes, and which numbers change
 */
@Composable
fun AdjustDialog(
    title: String,
    itemName: String,
    currentLine: String,
    current: Long,
    parse: (String) -> Long?,
    format: (Long) -> String,
    invalidAmountRes: String,
    invalidValueRes: String,
    allowNegativeResult: Boolean,
    footnote: String? = null,
    second: AdjustSecond? = null,
    onConfirm: (mode: AdjustMode, amount: Long, newValue: Long, first: Boolean, second: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(AdjustMode.ADD) }
    var text by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var onFirst by remember { mutableStateOf(true) }
    var onSecond by remember { mutableStateOf(false) }
    val useFirst = second == null || onFirst
    val useSecond = second != null && onSecond

    val amount = if (text.isBlank()) null else parse(text)
    // Adding and subtracting need something to add or take away; entering may be any number (zero clears it).
    val amountOk = amount != null && (mode == AdjustMode.SET || amount > 0)
    val newValue = if (amountOk && useFirst) Adjust.apply(current, mode, amount!!) else null
    val newSecond = if (amountOk && useSecond) Adjust.apply(second!!.second, mode, amount!!) else null
    val overflow = amountOk && ((useFirst && newValue == null) || (useSecond && newSecond == null))
    val negative = !allowNegativeResult && ((newValue ?: 0) < 0 || (newSecond ?: 0) < 0)
    val valid = amountOk && !overflow && !negative
    // "Will be": the one number, or both with their names (D215).
    val willBe = if (second == null) newValue?.let(format).orEmpty() else listOfNotNull(
        newValue?.let { second.firstLabel + " " + format(it) },
        newSecond?.let { second.secondLabel + " " + format(it) },
    ).joinToString(", ")

    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(itemName, style = MaterialTheme.typography.titleMedium)
                Text(currentLine, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SymbolChip("+", "budget_adjust_add", mode == AdjustMode.ADD, Modifier.weight(1f)) { mode = AdjustMode.ADD }
                    SymbolChip("\u2212", "budget_adjust_subtract", mode == AdjustMode.SUBTRACT, Modifier.weight(1f)) { mode = AdjustMode.SUBTRACT }
                    SymbolChip("=", "budget_adjust_set", mode == AdjustMode.SET, Modifier.weight(1f)) { mode = AdjustMode.SET }
                }
                if (second != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Never both off: the last one on stays on.
                        FilterChip(onFirst, { if (!onFirst || onSecond) onFirst = !onFirst }, { Text(second.firstLabel) }, Modifier.weight(1f))
                        FilterChip(onSecond, { if (!onSecond || onFirst) onSecond = !onSecond }, { Text(second.secondLabel) }, Modifier.weight(1f))
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(if (mode == AdjustMode.SET) "budget_adjust_new_value" else "budget_adjust_amount")) },
                    singleLine = true,
                    isError = showError && !valid,
                    supportingText = when {
                        showError && !amountOk -> {
                            { Text(stringResource(if (mode == AdjustMode.SET) invalidValueRes else invalidAmountRes)) }
                        }
                        showError && negative -> {
                            { Text(stringResource("budget_adjust_negative")) }
                        }
                        valid -> {
                            { Text(stringResource("budget_adjust_result", willBe)) }
                        }
                        else -> null
                    },
                )
                if (footnote != null) {
                    Text(footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (valid) onConfirm(mode, amount!!, newValue ?: current, useFirst, useSecond) else showError = true
            }) { Text(stringResource("action_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

/** A mode button showing only its symbol (D215); the word is still read out by TalkBack. */
@Composable
private fun SymbolChip(symbol: String, description: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val words = stringResource(description)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(symbol, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
        modifier = modifier.semantics { contentDescription = words },
    )
}

/** A plus and a minus either side of a slanting stroke: the icon of "Change" (D164). */
val PlusMinusIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PlusMinus", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        val stroke = SolidColor(Color.Black)
        // plus, top left
        path(stroke = stroke, strokeLineWidth = 2f) { moveTo(3f, 7.5f); lineTo(11f, 7.5f); moveTo(7f, 3.5f); lineTo(7f, 11.5f) }
        // minus, bottom right
        path(stroke = stroke, strokeLineWidth = 2f) { moveTo(13f, 16.5f); lineTo(21f, 16.5f) }
        // the slanting stroke between them
        path(stroke = stroke, strokeLineWidth = 1.5f) { moveTo(20f, 3f); lineTo(4f, 21f) }
    }.build()
}
