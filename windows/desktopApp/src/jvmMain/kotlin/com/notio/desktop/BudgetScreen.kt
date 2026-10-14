package com.notio.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notio.shared.data.BudgetSession
import com.notio.shared.domain.AdjustMode
import com.notio.shared.domain.BudgetLayout
import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.BudgetRowItem
import com.notio.shared.domain.BudgetRowPart
import com.notio.shared.domain.BudgetSortKey
import com.notio.shared.domain.BuiltInCurrency
import com.notio.shared.domain.displayedChildren
import com.notio.shared.domain.storageOrder
import com.notio.shared.domain.withDisplayedOrder
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.notio.shared.domain.Comment
import com.notio.shared.domain.DomainError
import com.notio.shared.domain.Money
import com.notio.shared.domain.visibleBudgetRows
import com.notio.shared.transfer.BudgetLogRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the budget screen shows; read again after every change. */
private data class BudgetState(
    val nodes: List<BudgetNode> = emptyList(),
    val layout: BudgetLayout = BudgetLayout.Default,
    val log: List<BudgetLogRecord> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

private sealed interface BudgetDialog {
    data class Add(val parentId: String, val kind: BudgetNodeKind) : BudgetDialog
    data class Edit(val node: BudgetNode) : BudgetDialog
    data class Adjust(val node: BudgetNode) : BudgetDialog
    data class Merge(val node: BudgetNode) : BudgetDialog
    data class Delete(val node: BudgetNode) : BudgetDialog
    data object Currency : BudgetDialog
    data object Layout : BudgetDialog
    data object Sort : BudgetDialog
    data object ClearLog : BudgetDialog
}

/**
 * The budget note of the phone (feature/budget/BudgetScreen.kt): rows as cards from the total down, ▾ / ▸ to open and close a group,
 * "Sum / goal", "Remainder" (red below zero), the ± Change button on items (D164, D215), the ⋮ menu of a row in Edit (edit, add an
 * item or a group, delete), display options, the log (D166), the currency (D170), the sort (D178), collapse / expand all (D183),
 * Undo / Redo of the data (D197; Ctrl+Z / Ctrl+Y as well).
 */
@Composable
fun BudgetScreen(
    s: Strings, controller: NotioController, noteId: String, noteTitle: String, editing: Boolean, modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {}, footer: @Composable () -> Unit = {},
    /** The eye of the note was pressed: hidden sums / goals are shown (Android D272). */
    revealed: Boolean = false,
) {
    val session = remember(noteId) { controller.openBudget(noteId) }
    val scope = rememberCoroutineScope()
    var state by remember(noteId) { mutableStateOf(BudgetState()) }
    var version by remember(noteId) { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<BudgetDialog?>(null) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(noteId, version) {
        state = withContext(Dispatchers.IO) {
            BudgetState(session.nodes(), session.layout(), controller.budgetLog(noteId), session.canUndo, session.canRedo)
        }
    }
    /** Runs a change of the session off the UI thread, then reads everything again; a refused change is said at the bottom. */
    fun act(block: BudgetSession.() -> DomainError?) {
        // On the app's scope, not the screen's: a change is finished when the note is left in the middle of it (Android D294).
        controller.write({ session.block() }) { error ->
            error?.let { controller.say(s.domainError(it)) }
            version++
            controller.refresh()
        }
    }
    fun actUnit(block: BudgetSession.() -> Unit) = act { block(); null }
    val currency = state.layout.currencyLabel
    fun money(v: Long) = Money.formatLabelled(v, currency)
    // Hidden amounts (Android D272) are stars on the screen and in the log; what is exported is not masked, as on the phone.
    val privacy = com.notio.shared.domain.BudgetPrivacy.of(state.layout, revealed)
    fun shownSum(v: Long) = privacy.sum(v, currency)
    fun shownGoal(v: Long) = privacy.goal(v, currency)
    // The eye of one line (Android D317; in Edit too, D327): every line of a budget that hides amounts shows or hides its own,
    // whatever the eye of the note says; the eye of the note starts all lines anew.
    // An income and an expense have no goals or remainder (Android D326).
    val goals = state.layout.hasGoals
    // Merging into another item (Android D330) needs another item: counted once for the tree, not for every row.
    val severalItems = remember(state.nodes) { state.nodes.count { it.kind == BudgetNodeKind.LEAF } > 1 }
    var rowEyes by remember(noteId, revealed) { mutableStateOf(emptySet<String>()) }

    // Dragging (Android D176): a grip on every group and item moves it among its brothers while the sort is the own order. While the
    // mouse holds one, its brothers are shown closed, so each is a single row; a group takes everything inside it along.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var pending by remember(noteId) { mutableStateOf<Pair<String, List<String>>?>(null) }
    fun nodeOf(rowKey: Any) = state.nodes.firstOrNull { it.id == rowKey.toString().substringBefore(':') }
    val drag = rememberDragReorder(
        listState, horizontal = false,
        onStart = { key -> nodeOf(key)?.parentId?.let { parent -> pending = parent to displayedChildren(state.nodes, parent, state.layout.newestFirst) } },
        onSwap = { a, b ->
            pending?.let { (parent, ids) ->
                val from = ids.indexOf(nodeOf(a)?.id)
                val to = ids.indexOf(nodeOf(b)?.id)
                if (from >= 0 && to >= 0) pending = parent to ids.toMutableList().apply { add(to, removeAt(from)) }
            }
        },
        canSwap = { a, b -> val na = nodeOf(a); val nb = nodeOf(b); na?.parentId != null && na.parentId == nb?.parentId && !b.toString().endsWith(":TOTAL") },
        onDrop = {
            pending?.let { (parent, ids) ->
                if (ids == displayedChildren(state.nodes, parent, state.layout.newestFirst)) pending = null
                else act { reorderChildren(parent, storageOrder(ids, state.layout.newestFirst)).let { null } }
            }
        },
    )
    // Once the saved order shows in the tree, the picture of the drag is not needed any more.
    LaunchedEffect(state.nodes, drag.draggedKey) {
        pending?.let { (parent, ids) -> if (drag.draggedKey == null && displayedChildren(state.nodes, parent, state.layout.newestFirst) == ids) pending = null }
    }

    // The accounts an income / expense can go into (D237), read again when the layout changes.
    val mergeAccounts = rememberMergeAccounts(controller.budgetMerge, noteId, state.layout)
    // The search of the note (D238): the groups that hold a match are shown open while it lasts, without being saved so.
    val search = LocalNoteSearch.current
    val query = search?.activePattern
    val openForSearch = remember(state.nodes, query, state.layout.showComments) {
        com.notio.shared.domain.budgetSearchOpen(state.nodes, query, state.layout.showComments)
    }
    val current = pending
    val rows = remember(state.nodes, state.layout, current, drag.draggedKey, openForSearch) {
        if (current == null) visibleBudgetRows(state.nodes, state.layout, open = openForSearch) else {
            val (parent, ids) = current
            visibleBudgetRows(withDisplayedOrder(state.nodes, parent, ids, state.layout.newestFirst), state.layout, if (drag.draggedKey != null) ids.toSet() else emptySet(), openForSearch)
        }
    }
    val hits = remember(rows, query, state.layout.showComments) { budgetHits(rows, query, state.layout.showComments) }
    LaunchedEffect(hits) { search?.report(hits) }
    LaunchedEffect(search?.jumps, hits) {
        val key = search?.currentHit?.key as? com.notio.shared.domain.BudgetHitKey ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.node.id == key.id && it.part != BudgetRowPart.TOTAL }
        // The rows come after the title / meta, the kind line (D237) and the tools rows.
        if (index >= 0) listState.animateScrollToItem(index + 3)
    }
    if (showLog) {
        BudgetLogView(s, state.log, noteTitle, ::money, shown = ::shownSum, shownGoal = ::shownGoal, onBack = { showLog = false }, onClear = { dialog = BudgetDialog.ClearLog }, onUndoMerge = { e ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { controller.undoMerge(e) }
                if (!ok) controller.say(controller.res.str("budget_merge_target_missing"))
                version++
                controller.refresh()
            }
        }, onUndoLine = { e ->
            // Android D282: a line of the log taken back; when it no longer can be (the item is gone, the value out of range), said so.
            scope.launch {
                val error = withContext(Dispatchers.IO) { session.undoLogLine(e) }
                if (error != null) controller.say(controller.res.str("budget_log_undo_failed"))
                version++
                controller.refresh()
            }
        }, onRestore = { e ->
            // D139 (Android D359): what a deletion took away put back; when it cannot be (it is in the budget already), said so.
            scope.launch {
                val error = withContext(Dispatchers.IO) { session.restoreDeleted(e) }
                if (error != null) controller.say(controller.res.str("budget_log_restore_failed"))
                version++
                controller.refresh()
            }
        }, modifier = modifier)
    } else {
        androidx.compose.foundation.layout.Box(modifier) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
              item(key = "header") { header() }
              // D237: in Edit the kind and the merge settings, in View a line about the merge of an income / expense.
              item(key = "budget-kind") {
                  if (editing) BudgetMergePanel(state.layout, mergeAccounts, onChange = { next -> actUnit { setLayout(next) } }, onMergeNow = {
                      scope.launch {
                          val outcome = withContext(Dispatchers.IO) { controller.mergeNow(noteId) }
                          controller.say(when (outcome) {
                              is com.notio.shared.data.MergeOutcome.Done -> controller.res.str("budget_merge_done", outcome.targetTitle, outcome.changed, outcome.created)
                              com.notio.shared.data.MergeOutcome.NoTarget -> controller.res.str("budget_merge_no_target")
                              com.notio.shared.data.MergeOutcome.TargetMissing -> controller.res.str("budget_merge_target_missing")
                              else -> controller.res.str("budget_merge_target_missing")
                          })
                          controller.refresh()
                      }
                  })
                  else BudgetMergeStatus(state.layout, mergeAccounts)
              }
              item(key = "tools") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                    // Android D278: the log, the display options and folding the groups are icons (their names on hover and for a screen
                    // reader); the currency and the sort show a value, so they keep their words.
                    IconAction(AppIcons.History, s.budgetLog, { showLog = true })
                    if (editing) IconAction(AppIcons.Tune, s.budgetLayoutButton, { dialog = BudgetDialog.Layout })
                    TextButton(onClick = { dialog = BudgetDialog.Currency }) { Text(s.budgetCurrencyButton(currency)) }
                    TextButton(onClick = { dialog = BudgetDialog.Sort }) {
                        Text(s.sortTitle + ": " + s.budgetSortKey(state.layout.sortKey) + if (state.layout.sortKey != BudgetSortKey.MANUAL) (if (state.layout.sortDescending) " ↓" else " ↑") else "")
                    }
                    if (state.nodes.any { it.kind == BudgetNodeKind.GROUP && it.parentId != null }) {
                        IconAction(AppIcons.UnfoldLess, s.budgetCollapseAll, { act { setAllExpanded(false) } })
                        IconAction(AppIcons.UnfoldMore, s.budgetExpandAll, { act { setAllExpanded(true) } })
                    }
                    TextButton(onClick = { act { undo() } }, enabled = state.canUndo) { Text("↶ " + s.undoAction) }
                    TextButton(onClick = { act { redo() } }, enabled = state.canRedo) { Text("↷ " + s.redoAction) }
                }
              }
                items(rows.size, key = { rows[it].key }) { index ->
                    val row = rows[index]
                    val manual = state.layout.sortKey == BudgetSortKey.MANUAL
                    val rowPrivacy = if (row.node.id in rowEyes) privacy.copy(revealed = !revealed) else privacy
                    BudgetRow(
                        s, row, editing, { rowPrivacy.sum(it, currency) }, state.layout.showComments,
                        goalMoney = { rowPrivacy.goal(it, currency) }, remainderMoney = { rowPrivacy.remainder(it, currency) }, remainderMasked = rowPrivacy.remainderMasked,
                        goals = goals,
                        eye = if (privacy.hidesAnything) (rowPrivacy.revealed to {
                            rowEyes = if (row.node.id in rowEyes) rowEyes - row.node.id else rowEyes + row.node.id
                        }) else null,
                        shownOpen = row.node.isExpanded || row.node.id in openForSearch,
                        onToggle = { act { toggleExpanded(row.node) } },
                        onEdit = { dialog = BudgetDialog.Edit(row.node) },
                        onAdd = { kind -> dialog = BudgetDialog.Add(row.node.id, kind) },
                        onDelete = { dialog = BudgetDialog.Delete(row.node) },
                        onAdjust = { dialog = BudgetDialog.Adjust(row.node) },
                        onMerge = if (row.node.kind == BudgetNodeKind.LEAF && severalItems) ({ dialog = BudgetDialog.Merge(row.node) }) else null,
                        modifier = if (drag.draggedKey == row.key) drag.itemModifier(row.key) else Modifier.animateItem(),
                        dragHandle = if (manual && row.depth > 0 && row.part != BudgetRowPart.TOTAL) ({
                            val label = stringResource("cd_drag_handle")
                            Box(
                                Modifier.size(40.dp).then(drag.handleModifier(row.key))
                                    .semantics { contentDescription = label + " " + row.node.title },
                                contentAlignment = Alignment.Center,
                            ) { MoveHandleIcon() }
                        }) else null,
                    )
                }
                if (rows.size == 1) item(key = "empty") { Text(s.budgetEmptyHint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp)) }
                item(key = "footer") { footer() }
            }
            QuickScroll(listState)
        }
    }

    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        is BudgetDialog.Add -> NodeDialog(s, if (d.kind == BudgetNodeKind.GROUP) s.budgetAddGroup else s.budgetAddItem, null, d.kind, state.nodes, d.parentId, close, goals) { title, comment, sum, goal, qty, group ->
            act { add(group, title, d.kind, comment, sum, goal, qty) }; close()
        }
        is BudgetDialog.Edit -> NodeDialog(s, s.editAction, d.node, d.node.kind, state.nodes, d.node.parentId ?: d.node.id, close, goals) { title, comment, sum, goal, qty, group ->
            act { update(d.node.id, title, comment, sum, goal, qty, group) }; close()
        }
        is BudgetDialog.Adjust -> AdjustDialog(s, d.node, ::shownSum, close, goalMoney = ::shownGoal, goals = goals) { mode, amount, sum, goal, comment ->
            act { adjust(d.node.id, mode, amount, sum, goal, comment) }; close()
        }
        is BudgetDialog.Merge -> ItemMergeDialog(s, d.node, state.nodes, goals, close) { target, sum, goal ->
            act { mergeItem(d.node.id, target, sum, goal) }; close()
        }
        // What is deleted can be put back from the log (D139), and the question says so.
        is BudgetDialog.Delete -> ConfirmDialog(s, s.budgetDeleteTitle, s.budgetDeleteMessage(d.node.title) + "\n\n" + controller.res.str("budget_delete_restorable"), s.delete, close) {
            act { delete(d.node.id) }; close()
        }
        BudgetDialog.Currency -> CurrencyDialog(s, state.layout.currency, currency, controller.customCurrencies(), close) { id, label -> actUnit { setCurrency(id, label) }; close() }
        BudgetDialog.Layout -> LayoutDialog(s, state.layout, close) { actUnit { setLayout(it) }; close() }
        BudgetDialog.Sort -> SortDialog(s, state.layout, close) { key, desc -> actUnit { setSort(key, desc) }; close() }
        BudgetDialog.ClearLog -> ConfirmDialog(s, s.budgetLogClearTitle, s.budgetLogClearMessage, s.budgetLogClear, close) {
            scope.launch { withContext(Dispatchers.IO) { controller.clearBudgetLog(noteId) }; version++ }; close()
        }
    }
}

@Composable
private fun BudgetRow(
    s: Strings,
    row: BudgetRowItem,
    editing: Boolean,
    money: (Long) -> String,
    showComments: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onAdd: (BudgetNodeKind) -> Unit,
    onDelete: () -> Unit,
    onAdjust: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: (@Composable () -> Unit)? = null,
    /** Open on the screen: saved open, or opened for the search (D238). */
    shownOpen: Boolean = row.node.isExpanded,
    /** Hidden goals and remainder show as stars (Android D272); [money] shows the sums. */
    goalMoney: (Long) -> String = money,
    remainderMoney: (Long) -> String = money,
    remainderMasked: Boolean = false,
    /** The eye of this line (Android D317, D327): whether its amounts are shown now, and the press; `null` when there is none. */
    eye: Pair<Boolean, () -> Unit>? = null,
    /** Whether the budget has goals and a remainder (Android D326). */
    goals: Boolean = true,
    /** "Merge into another item…" of the ⋮ menu (Android D330); `null` when there is no other item. */
    onMerge: (() -> Unit)? = null,
) {
    val node = row.node
    val isRoot = row.depth == 0
    val isGroup = node.kind == BudgetNodeKind.GROUP
    val isTotalRow = row.part == BudgetRowPart.TOTAL
    val showAggregates = row.part != BudgetRowPart.HEADER
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier.fillMaxWidth().padding(start = (row.depth.coerceAtMost(6) * 16).dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRoot) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.then(if (editing) Modifier.clickable(onClick = onEdit) else Modifier).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isGroup && row.hasChildren && !isTotalRow) {
                // The arrow of a group has its hint too (D115, Android D334).
                HoverTip(stringResource(if (shownOpen) "budget_group_close" else "budget_group_open"), topic = "budget") {
                    Text(if (shownOpen) "▾" else "▸", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable(onClick = onToggle).padding(end = 8.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The name is marked where the search of the note finds it (D238); the total rows are not searched.
                val title = highlighted(node.title, com.notio.shared.domain.BudgetHitKey(node.id, 0))
                Text(
                    when {
                        isRoot && !isTotalRow -> androidx.compose.ui.text.AnnotatedString(s.budgetTotal)
                        isTotalRow -> androidx.compose.ui.text.AnnotatedString(if (isRoot) s.budgetTotal else s.budgetGroupTotal(node.title))
                        node.kind == BudgetNodeKind.LEAF && node.quantity > 1 -> title + androidx.compose.ui.text.AnnotatedString("  ×${node.quantity}")
                        else -> title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isGroup) FontWeight.Bold else FontWeight.Normal,
                )
                if (!isTotalRow && showComments) Comment.short(node.comment)?.let {
                    Text(highlighted(it, com.notio.shared.domain.BudgetHitKey(node.id, 1)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showAggregates && node.kind == BudgetNodeKind.LEAF && node.quantity > 1) {
                    Text(if (goals) s.budgetEach(money(node.manualSumMinor), goalMoney(node.manualGoalMinor)) else stringResource("budget_each_sum", money(node.manualSumMinor)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showAggregates) {
                    // Each on a line of its own (Android D302).
                    Text(stringResource("budget_sum") + " " + money(node.cachedSumMinor), style = MaterialTheme.typography.bodyMedium)
                    if (goals) Text(stringResource("budget_goal") + " " + goalMoney(node.cachedGoalMinor), style = MaterialTheme.typography.bodyMedium)
                    if (goals) Text(
                        s.budgetRemainder(remainderMoney(node.cachedRemainderMinor)), style = MaterialTheme.typography.bodyMedium,
                        // A hidden remainder does not tell by its colour that it is negative.
                        color = if (node.cachedRemainderMinor < 0 && !remainderMasked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // Copy of the line (D143, Android D362), in View: as it is shown - hidden amounts stay hidden.
            if (!editing && !isTotalRow) {
                val name = if (isRoot) s.budgetTotal else node.title + (if (node.kind == BudgetNodeKind.LEAF && node.quantity > 1) "  ×${node.quantity}" else "")
                val sum = stringResource("budget_sum") + " " + money(node.cachedSumMinor)
                val goal = stringResource("budget_goal") + " " + goalMoney(node.cachedGoalMinor)
                val rest = s.budgetRemainder(remainderMoney(node.cachedRemainderMinor))
                CopyIcon(small = true) { listOfNotNull(name, sum.takeIf { showAggregates }, goal.takeIf { showAggregates && goals }, rest.takeIf { showAggregates && goals }).joinToString(", ") }
            }
            if (eye != null && showAggregates) {
                val label = stringResource(if (eye.first) "budget_hide_amounts" else "budget_show_amounts")
                IconButton(onClick = eye.second) { Icon(if (eye.first) EyeOffIcon else EyeIcon, contentDescription = label) }
            }
            // "Change" (D164): on an item, in View and in Edit alike.
            if (node.kind == BudgetNodeKind.LEAF && !isTotalRow) {
                IconButton(onClick = onAdjust) { IconHint(s.budgetAdjust); Text("±", style = MaterialTheme.typography.titleLarge) }
            }
            // Delete (D139, Android D359): in View an icon of its own on every item and group; in Edit it is in the ⋮ menu. It asks first.
            if (!editing && !isRoot && !isTotalRow) {
                IconButton(onClick = onDelete, helpTopic = "budget") { Icon(AppIcons.Delete, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error) }
            }
            dragHandle?.invoke()
            if (editing && !isTotalRow) Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = s.more) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(s.editAction) }, onClick = { menuOpen = false; onEdit() })
                    if (isGroup) {
                        DropdownMenuItem(text = { Text(s.budgetAddItem) }, onClick = { menuOpen = false; onAdd(BudgetNodeKind.LEAF) })
                        DropdownMenuItem(text = { Text(s.budgetAddGroup) }, onClick = { menuOpen = false; onAdd(BudgetNodeKind.GROUP) })
                    }
                    if (onMerge != null) DropdownMenuItem(text = { Text(stringResource("budget_item_merge")) }, onClick = { menuOpen = false; onMerge() })
                    if (!isRoot) DropdownMenuItem(text = { Text(s.delete, color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

/** Add or change a row: name, group (D102), for an item the quantity (D94), sum and goal of one piece; the comment (D177). */
@Composable
private fun NodeDialog(
    s: Strings,
    title: String,
    initial: BudgetNode?,
    kind: BudgetNodeKind,
    nodes: List<BudgetNode>,
    initialGroupId: String,
    onDismiss: () -> Unit,
    /** Whether the budget has goals (Android D326): without them the field of the goal is not there, and the goal stays what it was. */
    goals: Boolean = true,
    onConfirm: (title: String, comment: String?, sum: Long, goal: Long, quantity: Int, groupId: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.title.orEmpty()) }
    var comment by remember { mutableStateOf(initial?.comment.orEmpty()) }
    var sum by remember { mutableStateOf(initial?.let { Money.formatForEdit(it.manualSumMinor) }.orEmpty()) }
    var goal by remember { mutableStateOf(initial?.let { Money.formatForEdit(it.manualGoalMinor) }.orEmpty()) }
    var qty by remember { mutableStateOf((initial?.quantity ?: 1).toString()) }
    var group by remember { mutableStateOf(initialGroupId) }
    var tried by remember { mutableStateOf(false) }
    val leaf = kind == BudgetNodeKind.LEAF
    val sumV = Money.parse(sum)
    val goalV = Money.parse(goal)
    val qtyV = qty.trim().toIntOrNull()?.takeIf { it in 1..BudgetNode.MAX_QUANTITY }
    val isRoot = initial != null && initial.parentId == null
    // The groups a row can go to: never itself or anything below it.
    val below = remember(nodes, initial) {
        val out = HashSet<String>()
        fun walk(id: String) { if (out.add(id)) nodes.filter { it.parentId == id }.forEach { walk(it.id) } }
        initial?.let { walk(it.id) }
        out
    }
    val groups = remember(nodes, below) {
        val children = nodes.groupBy { it.parentId }
        val list = ArrayList<Pair<BudgetNode, Int>>()
        fun walk(n: BudgetNode, d: Int) {
            if (n.kind != BudgetNodeKind.GROUP || n.id in below) return
            list += n to d
            children[n.id].orEmpty().sortedBy { it.sortOrder }.forEach { walk(it, d + 1) }
        }
        children[null].orEmpty().forEach { walk(it, 0) }
        list
    }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isRoot) {
                    OutlinedTextField(
                        name, { name = it }, label = { Text(s.budgetNodeTitle) }, singleLine = true, isError = tried && name.isBlank(),
                        supportingText = if (tried && name.isBlank()) ({ Text(s.editError(com.notio.shared.data.EditError.BLANK_TITLE)) }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(s.budgetGroup, style = MaterialTheme.typography.labelMedium)
                    groups.forEach { (g, depth) ->
                        Row(Modifier.fillMaxWidth().selectable(group == g.id, onClick = { group = g.id }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(group == g.id, onClick = { group = g.id })
                            Text(" ".repeat(depth * 2) + if (g.parentId == null) s.budgetTotal else g.title)
                        }
                    }
                }
                if (leaf) {
                    OutlinedTextField(
                        qty, { qty = it }, label = { Text(s.budgetQuantity) }, singleLine = true, isError = tried && qtyV == null,
                        supportingText = { Text(if (tried && qtyV == null) s.budgetInvalidQuantity else s.budgetQuantityHint) }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        sum, { sum = it }, label = { Text(s.sum) }, singleLine = true, isError = tried && sumV == null,
                        supportingText = if (tried && sumV == null) ({ Text(s.budgetInvalidNumber) }) else null, modifier = Modifier.fillMaxWidth(),
                    )
                    if (goals) OutlinedTextField(
                        goal, { goal = it }, label = { Text(s.goal) }, singleLine = true, isError = tried && goalV == null,
                        supportingText = if (tried && goalV == null) ({ Text(s.budgetInvalidNumber) }) else null, modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(comment, { comment = it.take(Comment.MAX_LENGTH) }, label = { Text(s.comment) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = (isRoot || name.isNotBlank()) && (!leaf || (sumV != null && goalV != null && qtyV != null))
                if (!ok) tried = true
                else onConfirm(if (isRoot) initial!!.title else name, comment.ifBlank { null }, sumV ?: 0, goalV ?: 0, qtyV ?: 1, group)
            }) { Text(if (initial == null) s.create else s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/**
 * Merging an item into another item of the same budget (Android D330): the item that stays is picked from the items of the
 * note, each with the groups it is in; the chips say what it gets — the sums, the goals or both. The item the dialog was
 * opened on is deleted, and the dialog says so before it is done.
 */
@Composable
private fun ItemMergeDialog(s: Strings, source: BudgetNode, nodes: List<BudgetNode>, goals: Boolean, onDismiss: () -> Unit, onConfirm: (String, Boolean, Boolean) -> Unit) {
    val candidates = remember(nodes, source.id) { com.notio.shared.domain.BudgetItems.others(nodes, source.id) }
    var target by remember { mutableStateOf<String?>(null) }
    var onSum by remember { mutableStateOf(true) }
    var onGoal by remember { mutableStateOf(goals) }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("budget_item_merge")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource("budget_item_merge_hint", source.title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (goals) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(onSum, { if (!onSum || onGoal) onSum = !onSum }, { Text(stringResource("budget_merge_sum")) }, Modifier.weight(1f))
                    FilterChip(onGoal, { if (!onGoal || onSum) onGoal = !onGoal }, { Text(stringResource("budget_merge_goal")) }, Modifier.weight(1f))
                }
                Text(stringResource("budget_item_merge_into"), style = MaterialTheme.typography.titleSmall)
                candidates.forEach { (node, path) ->
                    Row(Modifier.fillMaxWidth().selectable(target == node.id, onClick = { target = node.id }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(target == node.id, onClick = { target = node.id })
                        Text(node.title + "  ·  " + path.joinToString(" / ").ifEmpty { s.budgetTotal })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { target?.let { onConfirm(it, onSum, onGoal && goals) } }, enabled = target != null) { Text(stringResource("budget_item_merge_confirm")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** The Change dialog of an item (Android AdjustDialog, D164, D165, D215): + − = on the sum, the goal or both; never neither. */
@Composable
private fun AdjustDialog(s: Strings, node: BudgetNode, money: (Long) -> String, onDismiss: () -> Unit, goalMoney: (Long) -> String = money, goals: Boolean = true, onConfirm: (AdjustMode, Long, Boolean, Boolean, String) -> Unit) {
    var mode by remember { mutableStateOf(AdjustMode.ADD) }
    // An optional comment, written into the log line of the change (D262).
    var comment by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var onSum by remember { mutableStateOf(true) }
    var onGoal by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val amount = if (text.isBlank()) null else Money.parse(text)
    val amountOk = amount != null && (mode == AdjustMode.SET || amount > 0)
    val newSum = if (amountOk && onSum) com.notio.shared.domain.Adjust.apply(node.manualSumMinor, mode, amount!!) else null
    val newGoal = if (amountOk && onGoal) com.notio.shared.domain.Adjust.apply(node.manualGoalMinor, mode, amount!!) else null
    val valid = amountOk && (!onSum || newSum != null) && (!onGoal || newGoal != null)
    val willBe = listOfNotNull(newSum?.let { s.sum + " " + money(it) }, newGoal?.let { s.goal + " " + goalMoney(it) }).joinToString(", ")
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.budgetAdjust) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(node.title, style = MaterialTheme.typography.titleMedium)
                // The sum alone where there are no goals (Android D326).
                Text(if (goals) s.budgetAdjustCurrentBoth(money(node.manualSumMinor), goalMoney(node.manualGoalMinor)) else stringResource("budget_adjust_current", money(node.manualSumMinor)), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AdjustMode.ADD to "+", AdjustMode.SUBTRACT to "−", AdjustMode.SET to "=").forEach { (m, sym) ->
                        FilterChip(mode == m, { mode = m }, { Text(sym, style = MaterialTheme.typography.titleMedium) }, Modifier.weight(1f))
                    }
                }
                if (goals) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(onSum, { if (!onSum || onGoal) onSum = !onSum }, { Text(s.sum) }, Modifier.weight(1f))
                    FilterChip(onGoal, { if (!onGoal || onSum) onGoal = !onGoal }, { Text(s.goal) }, Modifier.weight(1f))
                }
                OutlinedTextField(
                    text, { text = it }, singleLine = true,
                    label = { Text(if (mode == AdjustMode.SET) s.adjustNewValue else s.adjustAmount) },
                    isError = tried && !valid,
                    supportingText = when {
                        tried && !amountOk -> ({ Text(if (mode == AdjustMode.SET) s.budgetInvalidNumber else s.adjustInvalid) })
                        valid -> ({ Text(s.adjustResult(willBe)) })
                        else -> null
                    },
                )
                if (node.quantity > 1) Text(s.adjustPerPiece(node.quantity), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    comment, { comment = it.take(300) }, singleLine = false, maxLines = 3,
                    label = { Text(stringResource("budget_adjust_comment")) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (valid) onConfirm(mode, amount!!, onSum, onGoal, comment.trim()) else tried = true }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** The currency of the whole budget (D170): none or a built-in one; one a budget already has from elsewhere stays offered. */
@Composable
private fun CurrencyDialog(
    s: Strings, currentId: String?, currentLabel: String?, custom: List<com.notio.shared.domain.CustomCurrency>,
    onDismiss: () -> Unit, onPick: (String?, String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.budgetCurrencyTitle) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(s.budgetCurrencyHint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                @Composable
                fun option(text: String, selected: Boolean, pick: () -> Unit) {
                    Row(Modifier.fillMaxWidth().selectable(selected, onClick = pick), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = pick)
                        Text(text)
                    }
                }
                option(s.budgetCurrencyNone, currentId == null) { onPick(null, null) }
                // The person's own currencies (Settings -> Notes -> Currencies, D170), then one this budget brought from elsewhere.
                custom.forEach { c -> option(c.name, currentId == c.id) { onPick(c.id, c.name) } }
                if (currentId != null && !BuiltInCurrency.isBuiltIn(currentId) && custom.none { it.id == currentId }) option(currentLabel ?: currentId, true) { onPick(currentId, currentLabel) }
                BuiltInCurrency.ALL.forEach { id ->
                    val sign = BuiltInCurrency.symbolOf(id)!!
                    option("$sign  $id", currentId == id) { onPick(id, sign) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
private fun LayoutDialog(s: Strings, current: BudgetLayout, onDismiss: () -> Unit, onConfirm: (BudgetLayout) -> Unit) {
    var layout by remember { mutableStateOf(current) }
    @Composable
    fun switch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked, change)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.budgetLayoutTitle) },
        text = {
            Column {
                switch(s.budgetTotalBottom, layout.totalAtBottom) { layout = layout.copy(totalAtBottom = it) }
                switch(s.budgetGroupTotalsBottom, layout.groupTotalsAtBottom) { layout = layout.copy(groupTotalsAtBottom = it) }
                switch(s.budgetNewestFirst, layout.newestFirst) { layout = layout.copy(newestFirst = it) }
                switch(s.budgetShowComments, layout.showComments) { layout = layout.copy(showComments = it) }
                // Android D272: stars instead of the amounts on the card and in the note, until the eye of the note is pressed.
                switch(stringResource("budget_layout_hide_sums"), layout.hideSums) { layout = layout.copy(hideSums = it) }
                if (layout.hasGoals) switch(stringResource("budget_layout_hide_goals"), layout.hideGoals) { layout = layout.copy(hideGoals = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(layout) }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** How the rows inside every group are ordered (D178); the manual order is kept underneath. */
@Composable
private fun SortDialog(s: Strings, current: BudgetLayout, onDismiss: () -> Unit, onApply: (BudgetSortKey, Boolean) -> Unit) {
    var key by remember { mutableStateOf(current.sortKey) }
    var desc by remember { mutableStateOf(current.sortDescending) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.sortTitle) },
        text = {
            Column {
                // An income and an expense have no goals (Android D326), so nothing sorts by one.
                BudgetSortKey.entries.filter { current.hasGoals || it !in BudgetLayout.GOAL_SORTS }.forEach { k ->
                    Row(Modifier.fillMaxWidth().selectable(key == k, onClick = { key = k }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(key == k, onClick = { key = k })
                        Text(s.budgetSortKey(k))
                    }
                }
                if (key != BudgetSortKey.MANUAL) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(!desc, { desc = false }, { Text(s.sortAscending) })
                    FilterChip(desc, { desc = true }, { Text(s.sortDescending) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(key, desc) }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/**
 * The log of the budget (D166), newest line first, with Save as TXT and Clear; a merge (D237) and a change of an item (Android D282)
 * can be undone from here. A deletion is put back from its line (D139). A scrolling list of its own: a long log (up to [com.notio.shared.domain.BudgetLogKind.MAX_PER_NOTE] lines)
 * is laid out only as far as it is seen.
 */
@Composable
internal fun BudgetLogView(
    s: Strings, log: List<BudgetLogRecord>, title: String, money: (Long) -> String, onBack: () -> Unit, onClear: () -> Unit,
    onUndoMerge: (BudgetLogRecord) -> Unit = {},
    onUndoLine: (BudgetLogRecord) -> Unit = {},
    /** A deletion put back from its line (D139, Android D359). */
    onRestore: (BudgetLogRecord) -> Unit = {},
    /** The sums and goals as the screen shows them: stars when hidden (Android D272); the export keeps [money]. */
    shown: (Long) -> String = money,
    shownGoal: (Long) -> String = money,
    modifier: Modifier = Modifier,
) {
    val lines = remember(log) { log.sortedByDescending { it.at } }
    val res = LocalRes.current
    val K = com.notio.shared.domain.BudgetLogKind
    // What was undone already, worked out once per change of the log, not per line (an undo can be done once).
    val undoneMerges = remember(log) { log.filter { it.kind == K.MERGE_UNDONE }.mapTo(HashSet()) { it.detail } }
    val undoneLines = remember(log) { log.filter { it.kind == K.OP_UNDONE }.mapTo(HashSet()) { it.detail.orEmpty().substringBefore('\n') } }
    fun undoable(e: BudgetLogRecord) = e.kind in K.UNDOABLE && e.nodeId != null
    // A deletion is put back once (D139, Android D359); the lines made before kept nothing and have no Restore.
    val restoredLines = remember(log) { log.filter { it.kind == K.RESTORED }.mapTo(HashSet()) { it.detail } }
    fun restorable(e: BudgetLogRecord) = e.kind == K.DELETED && e.nodeId != null && e.detail?.contains('\n') == true
    // The merge lines (Android D237) and the undo lines (D282) in the phone's words, in every language of the app.
    fun line(e: BudgetLogRecord, sum: (Long) -> String, goal: (Long) -> String): String = when (e.kind) {
        K.MERGED -> res.str("budget_log_merged", e.title, (e.oldValue ?: 0L).toInt(), (e.newValue ?: 0L).toInt())
        K.MERGED_INTO -> res.str("budget_log_merged_into", e.title)
        K.MERGE_UNDONE -> res.str("budget_log_merge_undone", e.title)
        K.RESTORED -> res.str("budget_log_restored", e.title)
        K.OP_UNDONE -> {
            val of = e.detail?.substringAfter('\n', "")
            fun v(x: Long?) = when {
                x == null -> s.budgetLogNotSet
                of == K.QUANTITY -> x.toString()
                of == K.GOAL -> goal(x)
                else -> sum(x)
            }
            when (of) {
                K.SUM, K.GOAL, K.QUANTITY -> res.str("budget_log_op_undone_value", e.title, v(e.oldValue), v(e.newValue))
                K.RENAMED -> res.str("budget_log_op_undone_rename", e.title)
                else -> res.str("budget_log_op_undone_added", e.title)
            }
        }
        else -> {
            val text = s.budgetLogLine(e.copy(detail = e.detail?.substringBefore('\n')), sum, goal)
            // A comment given with a + / − / = change (D262) follows the line.
            val comment = if (e.kind == K.SUM || e.kind == K.GOAL) e.detail?.substringAfter('\n', "").orEmpty() else ""
            if (comment.isBlank()) text else res.str("budget_log_with_comment", text, comment)
        }
    }
    var undoing by remember { mutableStateOf<BudgetLogRecord?>(null) }
    var undoingLine by remember { mutableStateOf<BudgetLogRecord?>(null) }
    androidx.compose.foundation.lazy.LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "log-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← " + s.back) }
                Text(s.budgetLog, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    // The export is never masked (Android D272) and says the merges and the undos in words too.
                    val text = (listOf(s.budgetLogExportTitle(title), "") + lines.map { Format.dateTime(it.at) + "  " + line(it, money, money) }).joinToString("\r\n")
                    saveTextFile(s.budgetLog + " - " + title + ".txt", text)
                }, enabled = lines.isNotEmpty()) { Text(s.budgetLogSaveTxt) }
                TextButton(onClick = onClear, enabled = lines.isNotEmpty()) { Text(s.budgetLogClear, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (lines.isEmpty()) item(key = "log-empty") { Text(s.budgetLogEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(lines.size, key = { lines[it].id }) { i ->
            val e = lines[i]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(Format.dateTime(e.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(line(e, shown, shownGoal), style = MaterialTheme.typography.bodyLarge)
                    // A merge can be undone from here while it has not been (D237).
                    if (e.kind == K.MERGED && e.id !in undoneMerges) {
                        TextButton(onClick = { undoing = e }) { Text(stringResource("budget_merge_undo")) }
                    }
                    // Any change of an item can be undone the same way (Android D282), once; a line undone says so.
                    if (undoable(e)) {
                        if (e.id in undoneLines) Text(stringResource("budget_log_was_undone"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else TextButton(onClick = { undoingLine = e }) { Text(stringResource("budget_log_undo")) }
                    }
                    // What was deleted is put back from here (D139), once; nothing is asked: a restore destroys nothing.
                    if (restorable(e)) {
                        if (e.id in restoredLines) Text(stringResource("budget_log_was_restored"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else TextButton(onClick = { onRestore(e) }) { Text(stringResource("budget_log_restore")) }
                    }
                }
            }
        }
    }
    undoing?.let { entry ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { undoing = null },
            title = { Text(stringResource("budget_merge_undo")) },
            text = { Text(stringResource("budget_merge_undo_confirm", entry.title)) },
            confirmButton = { TextButton(onClick = { undoing = null; onUndoMerge(entry) }) { Text(stringResource("budget_merge_undo")) } },
            dismissButton = { TextButton(onClick = { undoing = null }) { Text(stringResource("action_cancel")) } },
        )
    }
    undoingLine?.let { entry ->
        val added = entry.kind == K.ITEM_ADDED || entry.kind == K.GROUP_ADDED
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { undoingLine = null },
            title = { Text(stringResource("budget_log_undo_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(line(entry, shown, shownGoal), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (added) "budget_log_undo_added_message" else "budget_log_undo_message"), style = MaterialTheme.typography.bodyMedium)
                }
            },
            // Undoing an added item deletes it with what it holds now: the button is red then, as on the phone.
            confirmButton = {
                TextButton(onClick = { undoingLine = null; onUndoLine(entry) }) {
                    Text(stringResource("budget_log_undo"), color = if (added) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                }
            },
            dismissButton = { TextButton(onClick = { undoingLine = null }) { Text(stringResource("action_cancel")) } },
        )
    }
}

/** Asks where to save [text] (UTF-8 with BOM, so Notepad reads Cyrillic) and writes it. */
fun saveTextFile(suggested: String, text: String) {
    val fd = java.awt.FileDialog(null as java.awt.Frame?, suggested, java.awt.FileDialog.SAVE)
    fd.file = suggested.replace(Regex("""[\\/:*?"<>|]"""), "_")
    fd.isVisible = true
    val name = fd.file ?: return
    java.io.File(fd.directory, name).writeText("﻿" + text, Charsets.UTF_8)
}
