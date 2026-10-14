package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.Quantity
import com.notio.shared.domain.BuiltInUnit
import com.notio.shared.domain.CustomUnit
import com.notio.shared.domain.ListDocument
import com.notio.shared.domain.ListEntry

/**
 * LIST editor (D92): items with an optional quantity, unit and comment, and "Total by unit" rows that sum
 * the quantities of the items in one unit. The structure is stored in `note.extras`, and a readable text
 * copy in `contentText` (see [ListDocument]); both are reported together through [onEdit].
 */
/** What a list screen needs, shared by its View and Edit tabs. [doc] is always the latest document. */
class ListController(
    private val docState: State<ListDocument>,
    val options: List<UnitOption>,
    private val labelOf: (String) -> String,
    private val commitDoc: (ListDocument) -> Unit,
) {
    val doc: ListDocument get() = docState.value
    fun label(id: String): String = labelOf(id)
    fun commit(next: ListDocument) = commitDoc(next)
}

data class UnitOption(val id: String, val label: String)

@Composable
fun rememberListController(content: String, extras: String?, onEdit: (String, String?) -> Unit): ListController {
    val units = LocalUnits.current
    val context = LocalRes.current
    val custom = units.custom
    val doc = remember(content, extras) { ListDocument.fromNote(content, extras) }
    val docState = rememberUpdatedState(doc)
    val customState = rememberUpdatedState(custom)
    val onEditState = rememberUpdatedState(onEdit)

    // Custom units that came with the list (imported from another phone) join this phone's units.
    LaunchedEffect(doc.unitNames) {
        units.adopt(doc.unitNames, UnitLabels.builtInNames(context))
    }

    val label: (String) -> String = { id -> UnitLabels.symbol(context, id, customState.value, docState.value.unitNames) }
    val commit: (ListDocument) -> Unit = { next ->
        val names = next.usedUnitIds().filterNot(BuiltInUnit::isBuiltIn).associateWith { id ->
            customState.value.firstOrNull { it.id == id }?.name ?: docState.value.unitNames[id] ?: id
        }
        val encoded = next.encode(label, names)
        onEditState.value(encoded.contentText, encoded.extras)
    }

    // Units to choose from: built-in, the user's, and any unknown one this list already uses.
    val options: List<UnitOption> = buildList {
        BuiltInUnit.ALL.forEach { add(UnitOption(it, UnitLabels.fullLabel(context, it, custom, doc.unitNames))) }
        custom.forEach { add(UnitOption(it.id, it.name)) }
        doc.usedUnitIds().filter { id -> none { it.id == id } }.forEach { add(UnitOption(it, label(it))) }
    }
    return ListController(docState, options, label, commit)
}

/** The Edit tab: every row can be changed. */
@Composable
fun ListEditor(c: ListController, modifier: Modifier, onOpen: (ListDialog) -> Unit = {}) {
    val doc = c.doc
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The rows as shown: the own order, or sorted by a value (D178); a sorted list cannot be dragged.
    // Sorted once per change of the list (or of the units), not on every frame of a scroll or a drag (D207).
    val shownEntries = androidx.compose.runtime.remember(doc, c.options) { doc.displayEntries(c::label) }
    val arranged = rememberArrangedDrag(listState, shownEntries, { it.id }) { ids -> c.commit(c.doc.reorder(ids)) }
    ListSearchEffects(shownEntries, listState, before = 2)
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "mode") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !doc.isPriceMode,
                        onClick = { c.commit(c.doc.copy(mode = ListDocument.MODE_QUANTITY)) },
                        label = { Text(stringResource("list_mode_quantity")) },
                    )
                    FilterChip(
                        selected = doc.isPriceMode,
                        onClick = { c.commit(c.doc.copy(mode = ListDocument.MODE_PRICE)) },
                        label = { Text(stringResource("list_mode_price")) },
                    )
                }
            }
            item(key = "sort") { ListSortButton(c, onOpen) }
            items(arranged.shown, key = { it.id }) { entry ->
                val delete: () -> Unit = { c.commit(c.doc.remove(entry.id)) }
                // Every row has a grip on its left: dragging it moves the row (D155).
                Row(
                    if (arranged.drag.draggedKey == entry.id) arranged.drag.itemModifier(entry.id) else Modifier.animateItem(),
                    verticalAlignment = Alignment.Top,
                ) {
                if (!doc.isSorted) DragHandle(arranged.drag, entry.id, Modifier.padding(top = 12.dp))
                Box(Modifier.weight(1f)) {
                when (entry.kind) {
                    ListEntry.ITEM -> ItemCard(entry, c, editing = true, onOpen = onOpen, onDelete = delete)
                    ListEntry.TOTAL -> TotalCard(entry, c, editing = true, onOpen = onOpen, onDelete = delete)
                    // A row kind from a newer app: shown as text, kept when saving, removable.
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RemoveButton(delete)
                    }
                }
                }
                }
            }
            if (doc.isPriceMode) item(key = "price-summary") { PriceSummary(c) }
            item(key = "add-buttons") {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Android D278: two icons that differ — a line added to the list, and the sum sign for a total by unit.
                    IconAction(AppIcons.PlaylistAdd, stringResource("editor_add_item"), { onOpen(ListDialog.AddItem) }, tint = MaterialTheme.colorScheme.primary)
                    IconAction(AppIcons.Functions, stringResource("list_add_total"), { onOpen(ListDialog.AddTotal) }, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        QuickScroll(listState)
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Close, contentDescription = stringResource("editor_remove_item"))
    }
}

/** A button showing the chosen unit that opens a menu of all units; the first entry clears the choice. */
@Composable
internal fun UnitPicker(current: String?, options: List<UnitOption>, label: (String) -> String, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }) {
            Text(current?.let(label) ?: stringResource("list_no_unit"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource("list_no_unit")) },
                onClick = { open = false; onPick(null) },
            )
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { open = false; onPick(option.id) })
            }
        }
    }
}
