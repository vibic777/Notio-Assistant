package com.notebudget.app.feature.notes.list

import com.notebudget.app.core.ui.QuickScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.common.Quantity
import com.notebudget.app.domain.model.ListEntry
import com.notebudget.app.feature.notes.ViewEditTabs

/** A LIST note (D108): always opens in the read-only View; Edit is the second tab. */
@Composable
fun ListNote(content: String, extras: String?, onEdit: (String, String?) -> Unit, modifier: Modifier) {
    val controller = rememberListController(content, extras, onEdit)
    var editing by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<ListDialog?>(null) }
    Column(modifier.fillMaxSize()) {
        // Copy of the whole list as text (D362), in the View.
        ViewEditTabs(editing, { editing = it }, extra = if (!editing && content.isNotBlank() && !(com.notebudget.app.core.ui.LocalNoteHide.current?.masked == true)) ({ com.notebudget.app.core.ui.CopyIcon { content } }) else null)
        if (editing) ListEditor(controller, Modifier.weight(1f)) { dialog = it }
        // The list hides its content (D363): the cover, until the eye is pressed.
        else if (com.notebudget.app.core.ui.LocalNoteHide.current?.masked == true) com.notebudget.app.core.ui.HiddenContent(Modifier.fillMaxWidth().weight(1f))
        else ListView(controller, Modifier.weight(1f)) { dialog = it }
    }
    ListDialogHost(controller, dialog) { dialog = null }
}

/** The quantity and unit of an item as text: `2.5 kg`, `3 pcs`, or just the unit; empty when there is neither. */
internal fun quantityText(entry: ListEntry, label: (String) -> String): String = buildString {
    entry.quantityMilli?.let { append(Quantity.format(it)) }
    entry.unit?.let { if (isNotEmpty()) append(' '); append(label(it)) }
}

/** `5 pcs, 3 kg` / `400 rub` in the user's number format. */
internal fun sumsLabel(sums: List<Pair<String?, Long>>, label: (String) -> String): String =
    sums.joinToString(", ") { (unit, milli) -> Quantity.format(milli) + unit?.let { " " + label(it) }.orEmpty() }

/** `× 50 rub = 100 rub` for an item of a PRICE list that has a price; empty otherwise. */
internal fun priceDetail(entry: ListEntry, c: ListController): String {
    val price = entry.priceMilli ?: return ""
    val unit = entry.priceUnit?.let { " " + c.label(it) }.orEmpty()
    val cost = c.doc.lineCostMilli(entry)?.let { " = " + Quantity.format(it) + unit }.orEmpty()
    return "× " + Quantity.format(price) + unit + cost
}

/** The totals of a PRICE list: everything added up per unit, and every cost added up per price unit. */
@Composable
internal fun PriceSummary(c: ListController, modifier: Modifier = Modifier) {
    val quantities = sumsLabel(c.doc.quantityTotals(), c::label)
    val prices = sumsLabel(c.doc.priceTotals(), c::label)
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.list_summary_title), fontWeight = FontWeight.Bold)
            if (quantities.isEmpty() && prices.isEmpty()) {
                Text(stringResource(R.string.list_summary_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (quantities.isNotEmpty()) Text(stringResource(R.string.list_summary_quantity, quantities), style = MaterialTheme.typography.titleMedium)
            if (prices.isNotEmpty()) Text(stringResource(R.string.list_summary_price, prices), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * The View tab: the list as a person reads it. **The check boxes can be ticked here**, and every item has the **Change**
 * buttons for its quantity and price (D165); the rest is changed in Edit.
 */
@Composable
fun ListView(c: ListController, modifier: Modifier, onOpen: (ListDialog) -> Unit = {}) {
    val doc = c.doc
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The rows as shown: the own order, or sorted by a value (D178); a sorted list cannot be dragged.
    // Sorted once per change of the list (or of the units), not on every frame of a scroll or a drag (D207).
    val shownEntries = androidx.compose.runtime.remember(doc, c.options) { doc.displayEntries(c::label) }
    val arranged = com.notebudget.app.core.ui.rememberArrangedDrag(listState, shownEntries, { it.id }) { ids -> c.commit(c.doc.reorder(ids)) }
    ListSearchEffects(shownEntries, listState, before = 1)
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "sort") { ListSortButton(c, onOpen) }
            if (doc.entries.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.list_view_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(arranged.shown, key = { it.id }) { entry ->
                // The grip is there in View too, so the rows can be swapped without going to Edit (D155).
                Row(
                    if (arranged.drag.draggedKey == entry.id) arranged.drag.itemModifier(entry.id) else Modifier.animateItem(),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (!doc.isSorted) com.notebudget.app.core.ui.DragHandle(arranged.drag, entry.id, Modifier.padding(top = 4.dp))
                    Column(Modifier.weight(1f)) {
                        when (entry.kind) {
                            ListEntry.ITEM -> ItemCard(entry, c, editing = false, onOpen = onOpen, onDelete = {})
                            ListEntry.TOTAL -> TotalCard(entry, c, editing = false, onOpen = onOpen, onDelete = {})
                            else -> Text(entry.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (doc.isPriceMode) item(key = "price-summary") { PriceSummary(c, Modifier.padding(top = 8.dp)) }
            item(key = "footer") { com.notebudget.app.core.ui.NoteFooter() }
        }
        QuickScroll(listState)
    }
}

/** A match of the search in a list (D238): the [field] (0 the text, 1 the comment) of the entry [id]. */
internal data class ListHitKey(val id: String, val field: Int)

/** The matches of [query] in the entries as they are shown, in that order: the text of each, then its comment. */
internal fun listHits(entries: List<ListEntry>, query: com.notebudget.app.domain.model.TextPattern?): List<com.notebudget.app.core.ui.SearchHit> {
    if (query == null) return emptyList()
    val md = com.notebudget.app.feature.notes.markdown.MarkdownRender
    return entries.flatMap { e ->
        val text = if (e.kind == ListEntry.ITEM) md.inline(e.text).text else e.text
        com.notebudget.app.core.ui.TextSearch.hits(ListHitKey(e.id, 0), text, query) +
            e.comment?.takeIf { it.isNotBlank() }?.let { com.notebudget.app.core.ui.TextSearch.hits(ListHitKey(e.id, 1), md.inline(it).text, query) }.orEmpty()
    }
}

/** Finds the words of the note's search in [entries], reports them, and scrolls [listState] to the current one ([before] rows come first). */
@Composable
internal fun ListSearchEffects(entries: List<ListEntry>, listState: androidx.compose.foundation.lazy.LazyListState, before: Int) {
    val search = com.notebudget.app.core.ui.LocalNoteSearch.current ?: return
    val query = search.activePattern
    val hits = androidx.compose.runtime.remember(entries, query) { listHits(entries, query) }
    androidx.compose.runtime.LaunchedEffect(hits) { search.report(hits) }
    androidx.compose.runtime.LaunchedEffect(search.jumps, hits) {
        val key = search.currentHit?.key as? ListHitKey ?: return@LaunchedEffect
        val index = entries.indexOfFirst { it.id == key.id }
        if (index >= 0) listState.animateScrollToItem(before + index)
    }
}
