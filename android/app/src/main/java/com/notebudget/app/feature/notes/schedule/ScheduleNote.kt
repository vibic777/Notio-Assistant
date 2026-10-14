package com.notebudget.app.feature.notes.schedule

import com.notebudget.app.core.ui.QuickScroll
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.DragHandle
import com.notebudget.app.core.ui.rememberArrangedDrag
import com.notebudget.app.domain.model.ScheduleDocument
import com.notebudget.app.domain.model.ScheduleEntry
import com.notebudget.app.domain.model.ScheduleMath
import com.notebudget.app.feature.notes.ViewEditTabs

/** The ways to sort a schedule (D182). */
private val scheduleSortChoices = listOf(
    com.notebudget.app.core.ui.SortChoice("MANUAL", R.string.sort_key_manual),
    com.notebudget.app.core.ui.SortChoice("TIME", R.string.sort_key_time),
    com.notebudget.app.core.ui.SortChoice("NAME", R.string.sort_key_name),
)

/** `1 h 30 min`, `45 min`, `2 h`: the time between two items, written in the language of the app. */
fun scheduleDuration(context: Context, minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> context.getString(R.string.schedule_duration_m, m)
        m == 0 -> context.getString(R.string.schedule_duration_h, h)
        else -> context.getString(R.string.schedule_duration_hm, h, m)
    }
}

/**
 * SCHEDULE note (D155): rows in an order the person chooses. An item is a time of day, a description and a comment; a
 * calculator shows the time between two items, with a description and a comment of its own, and there can be any number of
 * them. Every row has a grip to drag it up or down, in View as well as in Edit. Opens in View; Edit is the second tab.
 */
@Composable
fun ScheduleNote(content: String, extras: String?, onEdit: (String, String?) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val doc = remember(extras) { ScheduleDocument.parse(extras) }
    val onEditState = rememberUpdatedState(onEdit)
    val docState = rememberUpdatedState(doc)
    val unknown = stringResource(R.string.schedule_unknown)
    val commit: (ScheduleDocument) -> Unit = { next ->
        onEditState.value(next.fallbackText({ scheduleDuration(context, it) }, unknown), next.encode())
    }
    var editing by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        ViewEditTabs(editing, { editing = it })
        val listState = rememberLazyListState()
        // The rows as shown: the own order, or sorted by time or by description (D182); a sorted schedule cannot be dragged.
        val shownEntries = remember(doc) { doc.displayEntries() }
        val arranged = rememberArrangedDrag(listState, shownEntries, { it.id }) { ids -> commit(docState.value.reorder(ids)) }
        var showSort by remember { mutableStateOf(false) }
        val sortChoices = scheduleSortChoices
        if (showSort) {
            com.notebudget.app.core.ui.ItemSortDialog(
                choices = sortChoices,
                currentId = doc.sort,
                descending = doc.sortDescending,
                onApply = { id, desc -> commit(docState.value.copy(sort = id, sortDescending = desc)); showSort = false },
                onDismiss = { showSort = false },
            )
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "sort") {
                    TextButton(onClick = { showSort = true }) {
                        Text(com.notebudget.app.core.ui.sortButtonText(sortChoices, doc.sort, doc.sortDescending))
                    }
                }
                if (doc.entries.isEmpty()) {
                    item(key = "empty") { Text(stringResource(R.string.schedule_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(arranged.shown, key = { it.id }) { entry ->
                    Row(
                        if (arranged.drag.draggedKey == entry.id) arranged.drag.itemModifier(entry.id) else Modifier.animateItem(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (!doc.isSorted) DragHandle(arranged.drag, entry.id, Modifier.padding(top = 4.dp))
                        Box(Modifier.weight(1f)) {
                            val update: (ScheduleEntry) -> Unit = { changed -> commit(docState.value.replace(entry.id) { changed }) }
                            val delete: () -> Unit = { commit(docState.value.remove(entry.id)) }
                            when {
                                entry.isItem && editing -> ItemEditor(entry, update, delete)
                                entry.isItem -> ItemView(entry)
                                entry.isCalc && editing -> CalcEditor(entry, doc, update, delete)
                                entry.isCalc -> CalcView(entry, doc)
                                // A row kind from a newer app: shown as text, kept when saving.
                                else -> Text(entry.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (!editing) item(key = "footer") { com.notebudget.app.core.ui.NoteFooter() }
                if (editing) {
                    item(key = "add-buttons") {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // D278: a clock with a plus for a time, a calculator for a calculator.
                            com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.MoreTime, stringResource(R.string.schedule_add_item), { commit(docState.value.add(ScheduleEntry.newItem())) }, tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                            com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Calculate, stringResource(R.string.schedule_add_calc), { commit(docState.value.add(ScheduleEntry.newCalc())) }, tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            QuickScroll(listState)
        }
    }
}

@Composable
private fun ItemView(entry: ScheduleEntry) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.minutes?.let { ScheduleMath.clock(it) } ?: "--:--", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(entry.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        entry.comment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun CalcView(entry: ScheduleEntry, doc: ScheduleDocument) {
    val context = LocalContext.current
    val result = doc.result(entry)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.text.ifBlank { stringResource(R.string.schedule_calc_default) }, style = MaterialTheme.typography.titleSmall)
            Text(
                result?.let { scheduleDuration(context, it) } ?: stringResource(R.string.schedule_unknown),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
            )
            val from = doc.item(entry.fromId)
            val to = doc.item(entry.toId)
            if (from != null && to != null) {
                Text(itemLabel(from) + "  →  " + itemLabel(to), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            entry.comment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun itemLabel(e: ScheduleEntry): String = (e.minutes?.let { ScheduleMath.clock(it) } ?: "--:--") + " " + e.text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemEditor(entry: ScheduleEntry, onChange: (ScheduleEntry) -> Unit, onDelete: () -> Unit) {
    var text by remember(entry.id) { mutableStateOf(entry.text) }
    var comment by remember(entry.id) { mutableStateOf(entry.comment.orEmpty()) }
    // Undo / Redo (D197) change the row from outside: the fields show what came back.
    LaunchedEffect(entry.text) { if (entry.text != text) text = entry.text }
    LaunchedEffect(entry.comment) { if (entry.comment.orEmpty() != comment) comment = entry.comment.orEmpty() }
    var commentOpen by remember(entry.id) { mutableStateOf(!entry.comment.isNullOrBlank()) }
    var pickTime by remember(entry.id) { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { pickTime = true }) { Text(entry.minutes?.let { ScheduleMath.clock(it) } ?: "--:--") }
            TextField(
                value = text,
                onValueChange = { text = it; onChange(entry.copy(text = it)) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.schedule_item_hint)) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_remove_item)) }
        }
        if (commentOpen) {
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it; onChange(entry.copy(comment = it.ifBlank { null })) },
                label = { Text(stringResource(R.string.list_comment)) },
                minLines = 1, maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        } else {
            com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.AddComment, stringResource(R.string.comment_add_cd), { commentOpen = true })
        }
    }
    if (pickTime) {
        val context = LocalContext.current
        val start = entry.minutes ?: (8 * 60)
        val state = rememberTimePickerState(initialHour = start / 60, initialMinute = start % 60, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { onChange(entry.copy(minutes = state.hour * 60 + state.minute)); pickTime = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = {
                Row {
                    if (entry.minutes != null) TextButton(onClick = { onChange(entry.copy(minutes = null)); pickTime = false }) { Text(stringResource(R.string.schedule_clear_time)) }
                    TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun CalcEditor(entry: ScheduleEntry, doc: ScheduleDocument, onChange: (ScheduleEntry) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var text by remember(entry.id) { mutableStateOf(entry.text) }
    var comment by remember(entry.id) { mutableStateOf(entry.comment.orEmpty()) }
    // Undo / Redo (D197) change the row from outside: the fields show what came back.
    LaunchedEffect(entry.text) { if (entry.text != text) text = entry.text }
    LaunchedEffect(entry.comment) { if (entry.comment.orEmpty() != comment) comment = entry.comment.orEmpty() }
    var commentOpen by remember(entry.id) { mutableStateOf(!entry.comment.isNullOrBlank()) }
    val result = doc.result(entry)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it; onChange(entry.copy(text = it)) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.schedule_calc_hint)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_remove_item)) }
            }
            ItemPicker(stringResource(R.string.schedule_from), doc, entry.fromId) { onChange(entry.copy(fromId = it)) }
            ItemPicker(stringResource(R.string.schedule_to), doc, entry.toId) { onChange(entry.copy(toId = it)) }
            Text(
                result?.let { scheduleDuration(context, it) } ?: stringResource(R.string.schedule_unknown),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
            )
            if (commentOpen) {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it; onChange(entry.copy(comment = it.ifBlank { null })) },
                    label = { Text(stringResource(R.string.list_comment)) },
                    minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.AddComment, stringResource(R.string.comment_add_cd), { commentOpen = true })
            }
        }
    }
}

/** A button that names the chosen item and opens the list of the items of the schedule to choose another. */
@Composable
private fun ItemPicker(label: String, doc: ScheduleDocument, chosenId: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = doc.item(chosenId)
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label + ": " + (chosen?.let { itemLabel(it) } ?: stringResource(R.string.schedule_pick_item)), maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items = doc.entries.filter { it.isItem }
            if (items.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.schedule_no_items)) }, onClick = { open = false }, enabled = false)
            items.forEach { item ->
                DropdownMenuItem(text = { Text(itemLabel(item)) }, onClick = { onPick(item.id); open = false })
            }
        }
    }
}
