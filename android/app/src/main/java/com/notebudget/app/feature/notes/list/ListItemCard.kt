package com.notebudget.app.feature.notes.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.common.Quantity
import com.notebudget.app.core.ui.AdjustDialog
import com.notebudget.app.core.ui.PlusMinusIcon
import com.notebudget.app.domain.model.ListEntry

/** Which number of a list item the "Change" button works on (D165). */
enum class ListField { QUANTITY, PRICE }

/** The dialog of a list screen that is open, shared by its View and Edit tabs (D165). */
sealed interface ListDialog {
    data object AddItem : ListDialog
    data object AddTotal : ListDialog
    data class EditItem(val id: String) : ListDialog
    data class EditTotal(val id: String) : ListDialog
    data class Adjust(val id: String, val field: ListField) : ListDialog
    data object Sort : ListDialog
}

/** The ways to sort the rows of a list (D178); the two that need prices only when the list is in the price mode. */
internal fun listSortChoices(priceMode: Boolean): List<com.notebudget.app.core.ui.SortChoice> = listOf(
    com.notebudget.app.core.ui.SortChoice("MANUAL", R.string.sort_key_manual),
    com.notebudget.app.core.ui.SortChoice("NAME", R.string.sort_key_name),
    com.notebudget.app.core.ui.SortChoice("CREATED", R.string.sort_key_created),
    com.notebudget.app.core.ui.SortChoice("QUANTITY", R.string.sort_key_quantity),
    com.notebudget.app.core.ui.SortChoice("PRICE", R.string.sort_key_price),
    com.notebudget.app.core.ui.SortChoice("COST", R.string.sort_key_cost),
    com.notebudget.app.core.ui.SortChoice("UNIT", R.string.sort_key_unit),
    com.notebudget.app.core.ui.SortChoice("DONE", R.string.sort_key_done),
).filter { priceMode || !com.notebudget.app.domain.model.ListSortKey.of(it.id).needsPrices }

/** The button of the sort at the top of a list, in View and in Edit (D178). */
@Composable
internal fun ListSortButton(c: ListController, onOpen: (ListDialog) -> Unit) {
    val choices = listSortChoices(c.doc.isPriceMode)
    TextButton(onClick = { onOpen(ListDialog.Sort) }) {
        Text(com.notebudget.app.core.ui.sortButtonText(choices, c.doc.sort, c.doc.sortDescending))
    }
}

/**
 * One item of a list, as a card like the items of a budget (D165): a check box, the text, the quantity and its unit, the price
 * (in a price list), the comment, and two buttons, **Quantity** and **Price**, that change the number by adding, subtracting or
 * entering it. The buttons are there in View and in Edit; in Edit the card also opens the item for editing and has a menu.
 */
@Composable
internal fun ItemCard(
    entry: ListEntry,
    c: ListController,
    editing: Boolean,
    onOpen: (ListDialog) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .then(if (editing) Modifier.clickable { onOpen(ListDialog.EditItem(entry.id)) } else Modifier)
                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = if (editing) 0.dp else 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = entry.done, onCheckedChange = { checked -> c.commit(c.doc.replace(entry.id) { it.copy(done = checked) }) })
            Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    // A URL in the item text is clickable in View, same as in a text note (user, 2026-09-22).
                    if (entry.text.isBlank()) androidx.compose.ui.text.AnnotatedString(stringResource(R.string.editor_item_hint))
                    else com.notebudget.app.core.ui.highlighted(com.notebudget.app.feature.notes.markdown.MarkdownRender.inline(entry.text), ListHitKey(entry.id, 0)),
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (entry.done) TextDecoration.LineThrough else null,
                    color = if (entry.text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val detail = quantityText(entry, c::label)
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (c.doc.isPriceMode) {
                    val price = priceDetail(entry, c)
                    if (price.isNotEmpty()) Text(price, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                entry.comment?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        com.notebudget.app.core.ui.highlighted(com.notebudget.app.feature.notes.markdown.MarkdownRender.inline(it), ListHitKey(entry.id, 1)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChangeButton(R.string.list_change_quantity) { onOpen(ListDialog.Adjust(entry.id, ListField.QUANTITY)) }
                    if (c.doc.isPriceMode) ChangeButton(R.string.list_change_price) { onOpen(ListDialog.Adjust(entry.id, ListField.PRICE)) }
                }
            }
            // Copy of the row (D362), in the View: the name with its quantity and price, then the comment.
            if (!editing && entry.text.isNotBlank()) com.notebudget.app.core.ui.CopyIcon(small = true) { itemCopyText(entry, c) }
            if (editing) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = { menuOpen = false; onOpen(ListDialog.EditItem(entry.id)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** A row of a list as one line of text (D362): `Bread 2 pcs × 50 rub = 100 rub — fresh`. */
internal fun itemCopyText(entry: ListEntry, c: ListController): String {
    val line = listOf(
        com.notebudget.app.feature.notes.markdown.MarkdownRender.inline(entry.text).text,
        quantityText(entry, c::label),
        if (c.doc.isPriceMode) priceDetail(entry, c) else "",
    ).filter { it.isNotBlank() }.joinToString(" ")
    return listOfNotNull(line, entry.comment?.takeIf { it.isNotBlank() }).joinToString(" — ")
}

/** A small button with the +/- icon and the name of the number it changes. */
@Composable
private fun ChangeButton(label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(PlusMinusIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label))
    }
}

/** "Total by unit" as a card: in Edit it opens for editing and has a menu, in View it is only read. */
@Composable
internal fun TotalCard(
    entry: ListEntry,
    c: ListController,
    editing: Boolean,
    onOpen: (ListDialog) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .then(if (editing) Modifier.clickable { onOpen(ListDialog.EditTotal(entry.id)) } else Modifier)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (editing) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (entry.text.isBlank()) androidx.compose.ui.text.AnnotatedString(stringResource(R.string.list_total_name_hint))
                    else com.notebudget.app.core.ui.highlighted(entry.text, ListHitKey(entry.id, 0)),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (entry.unit == null) stringResource(R.string.list_total_pick_unit)
                    else stringResource(R.string.list_total_result, Quantity.format(c.doc.totalOf(entry)) + " " + c.label(entry.unit)),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (c.doc.isPriceMode) {
                    val costs = sumsLabel(c.doc.priceTotalsOf(entry), c::label)
                    if (costs.isNotEmpty()) Text(stringResource(R.string.list_summary_price, costs), style = MaterialTheme.typography.titleMedium)
                }
                entry.comment?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        com.notebudget.app.core.ui.highlighted(com.notebudget.app.feature.notes.markdown.MarkdownRender.inline(it), ListHitKey(entry.id, 1)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (editing) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = { menuOpen = false; onOpen(ListDialog.EditTotal(entry.id)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** Shows the dialog that [dialog] names, if any, and applies its result to the list. */
@Composable
internal fun ListDialogHost(c: ListController, dialog: ListDialog?, onClose: () -> Unit) {
    val doc = c.doc
    when (dialog) {
        null -> Unit
        ListDialog.Sort -> com.notebudget.app.core.ui.ItemSortDialog(
            choices = listSortChoices(doc.isPriceMode),
            currentId = doc.sort,
            descending = doc.sortDescending,
            onApply = { id, desc ->
                c.commit(c.doc.copy(sort = id, sortDescending = desc))
                onClose()
            },
            onDismiss = onClose,
        )
        ListDialog.AddItem -> ItemDialog(
            title = stringResource(R.string.editor_add_item), initial = null, c = c,
            onConfirm = { c.commit(c.doc.add(it)); onClose() }, onDismiss = onClose,
        )
        ListDialog.AddTotal -> TotalDialog(
            title = stringResource(R.string.list_add_total), initial = null, c = c,
            onConfirm = { c.commit(c.doc.add(it)); onClose() }, onDismiss = onClose,
        )
        is ListDialog.EditItem -> doc.entries.firstOrNull { it.id == dialog.id }?.let { entry ->
            ItemDialog(
                title = stringResource(R.string.action_edit), initial = entry, c = c,
                onConfirm = { edited -> c.commit(c.doc.replace(entry.id) { edited }); onClose() }, onDismiss = onClose,
            )
        }
        is ListDialog.EditTotal -> doc.entries.firstOrNull { it.id == dialog.id }?.let { entry ->
            TotalDialog(
                title = stringResource(R.string.action_edit), initial = entry, c = c,
                onConfirm = { edited -> c.commit(c.doc.replace(entry.id) { edited }); onClose() }, onDismiss = onClose,
            )
        }
        is ListDialog.Adjust -> doc.entries.firstOrNull { it.id == dialog.id }?.let { entry ->
            val quantity = dialog.field == ListField.QUANTITY
            val unit = if (quantity) entry.unit else entry.priceUnit
            val unitText = unit?.let { " " + c.label(it) }.orEmpty()
            val current = (if (quantity) entry.quantityMilli else entry.priceMilli)
            AdjustDialog(
                title = stringResource(if (quantity) R.string.list_adjust_quantity_title else R.string.list_adjust_price_title),
                itemName = entry.text.ifBlank { stringResource(R.string.editor_item_hint) },
                currentLine = if (current == null) stringResource(R.string.list_adjust_not_set)
                else stringResource(
                    if (quantity) R.string.list_adjust_current_quantity else R.string.list_adjust_current_price,
                    Quantity.format(current) + unitText,
                ),
                current = current ?: 0L,
                parse = Quantity::parse,
                format = { Quantity.format(it) + unitText },
                invalidAmountRes = R.string.list_adjust_invalid,
                invalidValueRes = R.string.list_adjust_invalid_set,
                allowNegativeResult = false,
                onConfirm = { _, _, newValue, _, _ ->
                    c.commit(c.doc.replace(entry.id) { if (quantity) it.copy(quantityMilli = newValue) else it.copy(priceMilli = newValue) })
                    onClose()
                },
                onDismiss = onClose,
            )
        }
    }
}

/** Add / edit an item (D165): the text, the quantity with its unit, the price of one with its unit (price lists) and a comment. */
@Composable
private fun ItemDialog(title: String, initial: ListEntry?, c: ListController, onConfirm: (ListEntry) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.text.orEmpty()) }
    var quantity by remember { mutableStateOf(Quantity.formatForEdit(initial?.quantityMilli)) }
    var unit by remember { mutableStateOf(initial?.unit) }
    var price by remember { mutableStateOf(Quantity.formatForEdit(initial?.priceMilli)) }
    var priceUnit by remember { mutableStateOf(initial?.priceUnit) }
    var comment by remember { mutableStateOf(initial?.comment.orEmpty()) }
    var showErrors by remember { mutableStateOf(false) }
    val priceMode = c.doc.isPriceMode
    val nameInvalid = name.isBlank()
    val quantityInvalid = !Quantity.isValid(quantity)
    val priceInvalid = priceMode && !Quantity.isValid(price)

    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.budget_node_title)) },
                    singleLine = true,
                    isError = showErrors && nameInvalid,
                    supportingText = if (showErrors && nameInvalid) {
                        { Text(stringResource(R.string.error_blank_title)) }
                    } else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text(stringResource(R.string.list_quantity)) },
                        singleLine = true,
                        isError = quantityInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    UnitPicker(unit, c.options, c::label) { unit = it }
                }
                if (priceMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = price,
                            onValueChange = { price = it },
                            label = { Text(stringResource(R.string.list_price)) },
                            singleLine = true,
                            isError = priceInvalid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        UnitPicker(priceUnit, c.options, c::label) { priceUnit = it }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.list_comment)) },
                    minLines = 1,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (nameInvalid || quantityInvalid || priceInvalid) {
                    showErrors = true
                } else {
                    val base = initial ?: ListEntry.newItem()
                    onConfirm(
                        base.copy(
                            text = name.trim(),
                            quantityMilli = Quantity.parse(quantity),
                            unit = unit,
                            // A quantity list leaves the price it may already have alone.
                            priceMilli = if (priceMode) Quantity.parse(price) else base.priceMilli,
                            priceUnit = if (priceMode) priceUnit else base.priceUnit,
                            comment = comment.ifBlank { null },
                        ),
                    )
                }
            }) { Text(stringResource(if (initial == null) R.string.action_create else R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Add / edit a "Total by unit": its name, the unit it sums and a comment. */
@Composable
private fun TotalDialog(title: String, initial: ListEntry?, c: ListController, onConfirm: (ListEntry) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.text.orEmpty()) }
    var unit by remember { mutableStateOf(initial?.unit) }
    var comment by remember { mutableStateOf(initial?.comment.orEmpty()) }
    var showErrors by remember { mutableStateOf(false) }
    val nameInvalid = name.isBlank()

    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.list_total_name_hint)) },
                    singleLine = true,
                    isError = showErrors && nameInvalid,
                    supportingText = if (showErrors && nameInvalid) {
                        { Text(stringResource(R.string.error_blank_title)) }
                    } else null,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.list_total_pick_unit), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    UnitPicker(unit, c.options, c::label) { unit = it }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.list_comment)) },
                    minLines = 1,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (nameInvalid) showErrors = true
                else onConfirm((initial ?: ListEntry.newTotal()).copy(text = name.trim(), unit = unit, comment = comment.ifBlank { null }))
            }) { Text(stringResource(if (initial == null) R.string.action_create else R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
