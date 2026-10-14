package com.notebudget.app.feature.budget

import com.notebudget.app.core.ui.QuickScroll
import androidx.compose.foundation.layout.size
import com.notebudget.app.core.ui.AdjustDialog
import com.notebudget.app.core.ui.PlusMinusIcon
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.OutlinedButton
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import com.notebudget.app.core.ui.tip.Icon
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FilterChip
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.notebudget.app.feature.notes.ViewEditTabs
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.common.Money
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind

/** The ways to sort the rows of a budget (D178). */
private val budgetSortChoices = listOf(
    com.notebudget.app.core.ui.SortChoice("MANUAL", R.string.sort_key_manual),
    com.notebudget.app.core.ui.SortChoice("NAME", R.string.sort_key_name),
    com.notebudget.app.core.ui.SortChoice("CREATED", R.string.sort_key_created),
    com.notebudget.app.core.ui.SortChoice("CHANGED", R.string.sort_key_updated),
    com.notebudget.app.core.ui.SortChoice("SUM", R.string.sort_key_sum),
    com.notebudget.app.core.ui.SortChoice("GOAL", R.string.sort_key_goal),
    com.notebudget.app.core.ui.SortChoice("REMAINDER", R.string.sort_key_remainder),
    com.notebudget.app.core.ui.SortChoice("PROGRESS", R.string.sort_key_progress),
)

private sealed interface BudgetDialog {
    data class Add(val parentId: String, val kind: BudgetNodeKind) : BudgetDialog
    data class Edit(val node: BudgetNode) : BudgetDialog
    data class ConfirmDelete(val node: BudgetNode) : BudgetDialog
    data class Adjust(val node: BudgetNode) : BudgetDialog
    data class Merge(val node: BudgetNode) : BudgetDialog
    data object PickCurrency : BudgetDialog
}

/**
 * Budget tree of one BUDGET note (§6.6): the root card shows the total, groups can be
 * collapsed, leaves hold manual sum and goal. Remainder is `goal - sum` and may be negative.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(noteId: String, modifier: Modifier = Modifier) {
    val viewModel: BudgetViewModel = appViewModel {
        BudgetViewModel(noteId, it.budgetRepository, it.noteRepository, it.budgetEditor, it.noteUseCases, it.settings.undoSteps, it.budgetMerge)
    }
    com.notebudget.app.feature.notes.ProvideUndoTarget(viewModel)
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val allNodes by viewModel.nodes.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<BudgetDialog?>(null) }
    var adjustComment by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var showLayout by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    // D272: hidden sums / goals show as stars until the eye is pressed; open again, the note hides them again.
    var revealed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { Toast.makeText(context, context.getString(it.messageRes()), Toast.LENGTH_SHORT).show() }
    }
    // D237: the kind of the budget and the merge of an income / expense into its account.
    val mergeContainer = com.notebudget.app.core.ui.rememberAppContainer()
    val mergeAccounts = rememberMergeAccounts(mergeContainer.budgetMerge, noteId, layout)
    val mergeOutcome by viewModel.mergeOutcome.collectAsStateWithLifecycle()
    LaunchedEffect(mergeOutcome) {
        val text = when (val o = mergeOutcome) {
            is MergeOutcome.Done -> context.getString(R.string.budget_merge_done, o.targetTitle, o.changed, o.created)
            MergeOutcome.NoTarget -> context.getString(R.string.budget_merge_no_target)
            MergeOutcome.TargetMissing -> context.getString(R.string.budget_merge_target_missing)
            else -> null
        }
        if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    val items = rows
    if (items == null) {
        Box(modifier.fillMaxSize())
        return
    }

    // The log of the budget (D166) takes the place of the budget while it is open.
    if (showLog) {
        BudgetLogView(noteId, onBack = { showLog = false }, modifier = modifier, revealed = revealed)
        return
    }

    // Dragging (D176): a grip on every group and item moves it among its brothers, in View and in Edit like in the lists. While a finger
    // holds one, its brothers are shown closed, so each is a single row; a group takes everything inside it along.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var pending by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    fun nodeOf(rowKey: Any) = allNodes.firstOrNull { it.id == rowKey.toString().substringBefore(':') }
    val drag = com.notebudget.app.core.ui.rememberDragReorder(
        listState, horizontal = false,
        onStart = { key -> nodeOf(key)?.parentId?.let { parent -> pending = parent to displayedChildren(allNodes, parent, layout.newestFirst) } },
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
                if (ids == displayedChildren(allNodes, parent, layout.newestFirst)) pending = null else viewModel.reorderChildren(parent, ids)
            }
        },
    )
    // The search of the note (D238): the groups that hold a match are shown open while it lasts, without being saved so.
    val search = com.notebudget.app.core.ui.LocalNoteSearch.current
    val query = search?.activePattern
    val openForSearch = remember(allNodes, query, layout.showComments) { budgetSearchOpen(allNodes, query, layout.showComments) }
    val shownRows = remember(items, allNodes, layout, pending, drag.draggedKey, openForSearch) {
        val current = pending
        when {
            current != null -> {
                val (parent, ids) = current
                val nodes = withDisplayedOrder(allNodes, parent, ids, layout.newestFirst)
                visibleBudgetRows(nodes, layout, if (drag.draggedKey != null) ids.toSet() else emptySet(), openForSearch)
            }
            openForSearch.isNotEmpty() -> visibleBudgetRows(allNodes, layout, open = openForSearch)
            else -> items
        }
    }
    val hits = remember(shownRows, query, layout.showComments) { budgetHits(shownRows, query, layout.showComments) }
    LaunchedEffect(hits) { search?.report(hits) }
    LaunchedEffect(search?.jumps, hits) {
        val key = search?.currentHit?.key as? BudgetHitKey ?: return@LaunchedEffect
        val index = shownRows.indexOfFirst { it.node.id == key.id && it.part != BudgetRowPart.TOTAL }
        // The rows come after the lines at the top: the kind line (D237), then the one line of buttons (D278).
        if (index >= 0) listState.animateScrollToItem(index + 2)
    }
    // Once the saved order shows up in the tree, the picture of the drag is no longer needed.
    LaunchedEffect(allNodes, drag.draggedKey) {
        pending?.let { (parent, ids) -> if (drag.draggedKey == null && displayedChildren(allNodes, parent, layout.newestFirst) == ids) pending = null }
    }

    val privacy = BudgetPrivacy.of(layout, revealed)
    // Merging into another item (D330) needs another item: counted once for the tree, not for every row.
    val severalItems = remember(allNodes) { allNodes.count { it.kind == BudgetNodeKind.LEAF } > 1 }
    // An income and an expense have no goals (D326), so nothing sorts by one.
    val sortChoices = if (layout.hasGoals) budgetSortChoices else budgetSortChoices.filter { BudgetLayout.GOAL_SORTS.none { key -> key.name == it.id } }
    // The eye of one line (D317; in Edit too, D327): every line of a budget that hides amounts shows or hides its own, whatever
    // the eye of the note says; the eye of the note starts all lines anew.
    var rowEyes by remember(revealed) { mutableStateOf(emptySet<String>()) }
    Column(modifier.fillMaxSize()) {
    ViewEditTabs(editing, { editing = it }, extra = if (!privacy.hidesAnything) null else ({
        val label = stringResource(if (revealed) R.string.budget_hide_amounts else R.string.budget_show_amounts)
        IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(40.dp)) {
            Icon(if (revealed) EyeOffIcon else EyeIcon, contentDescription = label)
        }
    }))
    androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // D237: in Edit the kind and the merge settings, in View a line about the merge of an income / expense.
        item(key = "budget-kind") {
            if (editing) BudgetMergePanel(layout, mergeAccounts, onChange = viewModel::setLayout, onMergeNow = viewModel::mergeNow)
            else BudgetMergeStatus(layout, mergeAccounts)
        }
        // One line of buttons, the same in View and in Edit (the display options only in Edit): the log, the display options and
            // folding the groups are icons (D278, a long press says what they are); the currency and the sort show a value, so they
            // keep their words. On a narrow screen the line wraps instead of being squeezed (D175).
            item(key = "log-currency") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    com.notebudget.app.core.ui.IconAction(
                        com.notebudget.app.core.ui.AppIcons.History, stringResource(R.string.budget_log_button), { showLog = true },
                    )
                    if (editing) {
                        com.notebudget.app.core.ui.IconAction(
                            com.notebudget.app.core.ui.AppIcons.Tune, stringResource(R.string.budget_layout_button), { showLayout = true },
                        )
                    }
                    // Only when there is a group to open or close (D183).
                    if (allNodes.any { it.kind == BudgetNodeKind.GROUP && it.parentId != null }) {
                        com.notebudget.app.core.ui.IconAction(
                            com.notebudget.app.core.ui.AppIcons.UnfoldLess, stringResource(R.string.budget_collapse_all), { viewModel.setAllExpanded(false) },
                        )
                        com.notebudget.app.core.ui.IconAction(
                            com.notebudget.app.core.ui.AppIcons.UnfoldMore, stringResource(R.string.budget_expand_all), { viewModel.setAllExpanded(true) },
                        )
                    }
                    TextButton(onClick = { dialog = BudgetDialog.PickCurrency }) {
                        Text(stringResource(R.string.budget_currency_button, layout.currencyLabel ?: stringResource(R.string.budget_currency_none)))
                    }
                    TextButton(onClick = { showSort = true }) {
                        Text(com.notebudget.app.core.ui.sortButtonText(sortChoices, layout.sortKey.name, layout.sortDescending))
                    }
                }
            }
            items(shownRows, key = { it.key }) { row ->
                BudgetRow(
                    row = row,
                    modifier = if (drag.draggedKey == row.key) drag.itemModifier(row.key) else Modifier.animateItem(),
                    dragHandle = if (layout.sortKey == com.notebudget.app.domain.model.BudgetSortKey.MANUAL && row.depth > 0 && row.part != BudgetRowPart.TOTAL) ({ com.notebudget.app.core.ui.DragHandle(drag, row.key) }) else null,
                    editing = editing,
                    onToggle = { viewModel.toggleExpanded(row.node) },
                    onEdit = { dialog = BudgetDialog.Edit(row.node) },
                    onAdd = { kind -> dialog = BudgetDialog.Add(row.node.id, kind) },
                    onDelete = { dialog = BudgetDialog.ConfirmDelete(row.node) },
                    onAdjust = { dialog = BudgetDialog.Adjust(row.node) },
                    onMerge = if (row.node.kind == BudgetNodeKind.LEAF && severalItems) ({ dialog = BudgetDialog.Merge(row.node) }) else null,
                    currency = layout.currencyLabel,
                    showComments = layout.showComments,
                    privacy = if (row.node.id in rowEyes) privacy.copy(revealed = !revealed) else privacy,
                    onEye = if (privacy.hidesAnything) ({
                        rowEyes = if (row.node.id in rowEyes) rowEyes - row.node.id else rowEyes + row.node.id
                    }) else null,
                    shownOpen = row.node.isExpanded || row.node.id in openForSearch,
                )
            }
            if (items.size == 1) {
                item(key = "hint") {
                    Text(
                        stringResource(R.string.budget_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            // "Created" / "changed" at the very bottom, when Settings shows them for budgets (D198).
            if (!editing) item(key = "footer") { com.notebudget.app.core.ui.NoteFooter() }
        }
        QuickScroll(listState)
    }
    }

    if (showSort) {
        com.notebudget.app.core.ui.ItemSortDialog(
            choices = sortChoices,
            currentId = layout.sortKey.name,
            descending = layout.sortDescending,
            onApply = { id, desc -> viewModel.setSort(com.notebudget.app.domain.model.BudgetSortKey.of(id), desc); showSort = false },
            onDismiss = { showSort = false },
        )
    }

    if (showLayout) {
        LayoutDialog(
            current = layout,
            onConfirm = { viewModel.setLayout(it); showLayout = false },
            onDismiss = { showLayout = false },
        )
    }

    when (val current = dialog) {
        null -> Unit
        is BudgetDialog.Add -> NodeDialog(
            title = stringResource(
                if (current.kind == BudgetNodeKind.GROUP) R.string.budget_add_group else R.string.budget_add_item,
            ),
            initial = null,
            kind = current.kind,
            groups = groupOptions(allNodes),
            initialGroupId = current.parentId,
            goals = layout.hasGoals,
            onConfirm = { title, comment, sum, goal, quantity, groupId ->
                viewModel.add(groupId ?: current.parentId, title, current.kind, comment, sum, goal, quantity)
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        is BudgetDialog.Edit -> NodeDialog(
            title = stringResource(R.string.budget_edit_node),
            initial = current.node,
            kind = current.node.kind,
            groups = groupOptions(allNodes, excludeId = current.node.id),
            initialGroupId = current.node.parentId,
            goals = layout.hasGoals,
            onConfirm = { title, comment, sum, goal, quantity, groupId ->
                viewModel.updateNode(current.node, title, comment, sum, goal, quantity, groupId)
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        BudgetDialog.PickCurrency -> CurrencyDialog(
            currentId = layout.currency,
            onPick = { id, label -> viewModel.setCurrency(id, label); dialog = null },
            onDismiss = { dialog = null },
        )

        is BudgetDialog.Adjust -> AdjustDialog(
            title = stringResource(R.string.budget_adjust),
            itemName = current.node.title,
            currentLine = if (layout.hasGoals) stringResource(R.string.budget_adjust_current_both, privacy.sum(current.node.manualSumMinor, layout.currencyLabel), privacy.goal(current.node.manualGoalMinor, layout.currencyLabel))
            else stringResource(R.string.budget_adjust_current, privacy.sum(current.node.manualSumMinor, layout.currencyLabel)),
            current = current.node.manualSumMinor,
            parse = Money::parse,
            format = { privacy.sum(it, layout.currencyLabel) },
            invalidAmountRes = R.string.budget_adjust_invalid,
            invalidValueRes = R.string.budget_invalid_number,
            allowNegativeResult = true,
            footnote = if (current.node.quantity > 1) stringResource(R.string.budget_adjust_per_piece, current.node.quantity) else null,
            // The sum, the goal or both (D215); the sum alone where there are no goals (D326).
            second = if (!layout.hasGoals) null else com.notebudget.app.core.ui.AdjustSecond(
                stringResource(R.string.budget_sum), stringResource(R.string.budget_goal), current.node.manualGoalMinor,
            ),
            onConfirm = { mode, amount, _, sum, goal ->
                viewModel.adjust(current.node.id, mode, amount, sum, goal, adjustComment)
                dialog = null
            },
            onDismiss = { dialog = null },
            // An optional comment, written into the log line of the change (D262).
            onComment = { adjustComment = it },
        )

        is BudgetDialog.Merge -> ItemMergeDialog(
            source = current.node,
            nodes = allNodes,
            goals = layout.hasGoals,
            onConfirm = { targetId, sum, goal -> viewModel.mergeItem(current.node.id, targetId, sum, goal); dialog = null },
            onDismiss = { dialog = null },
        )

        is BudgetDialog.ConfirmDelete -> com.notebudget.app.core.ui.AppAlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.budget_delete_title)) },
            // What is deleted can be put back from the log (D359), and the question says so.
            text = { Text(stringResource(R.string.budget_delete_message, current.node.title) + "\n\n" + stringResource(R.string.budget_delete_restorable)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(current.node.id); dialog = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun BudgetRow(
    row: BudgetRowItem,
    modifier: Modifier = Modifier,
    dragHandle: (@Composable () -> Unit)? = null,
    editing: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onAdd: (BudgetNodeKind) -> Unit,
    onDelete: () -> Unit,
    onAdjust: () -> Unit,
    /** "Merge into another item…" of the ⋮ menu (D330); `null` when there is no other item. */
    onMerge: (() -> Unit)? = null,
    currency: String? = null,
    showComments: Boolean = true,
    privacy: BudgetPrivacy = BudgetPrivacy.NONE,
    /** The eye of this line (D317, D327): shows or hides its amounts, in View and in Edit; `null` when the budget hides nothing. */
    onEye: (() -> Unit)? = null,
    /** Open on the screen: saved open, or opened for the search (D238). */
    shownOpen: Boolean = row.node.isExpanded,
) {
    val node = row.node
    val isRoot = row.depth == 0
    val isGroup = node.kind == BudgetNodeKind.GROUP
    val isTotalRow = row.part == BudgetRowPart.TOTAL
    val showAggregates = row.part != BudgetRowPart.HEADER
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (row.depth.coerceAtMost(6) * 16).dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRoot) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .then(if (editing) Modifier.clickable(onClick = onEdit) else Modifier)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dragHandle?.invoke()
            if (isGroup && row.hasChildren && !isTotalRow) {
                // The arrow of a group has its hint too (D334).
                com.notebudget.app.core.ui.tip.HintBox(stringResource(if (shownOpen) R.string.budget_group_close else R.string.budget_group_open), topic = "budget") {
                    Text(
                        text = if (shownOpen) "▾" else "▸",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .clickable(onClick = onToggle)
                            .padding(end = 8.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The name is marked where the search of the note finds it (D238); the total rows are not searched.
                val title = com.notebudget.app.core.ui.highlighted(node.title, BudgetHitKey(node.id, 0))
                Text(
                    text = when {
                        isRoot -> androidx.compose.ui.text.AnnotatedString(stringResource(R.string.budget_total))
                        isTotalRow -> androidx.compose.ui.text.AnnotatedString(stringResource(R.string.budget_group_total, node.title))
                        // The quantity sits next to the name: "Bread ×3".
                        node.kind == BudgetNodeKind.LEAF && node.quantity > 1 -> title + androidx.compose.ui.text.AnnotatedString("  ×${node.quantity}")
                        else -> title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isGroup) FontWeight.Bold else FontWeight.Normal,
                )
                if (!isTotalRow && showComments) {
                    // The first 16 characters (D177); the whole comment is in the edit dialog.
                    com.notebudget.app.domain.model.Comment.short(node.comment)?.let {
                        Text(com.notebudget.app.core.ui.highlighted(it, BudgetHitKey(node.id, 1)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (showAggregates && node.kind == BudgetNodeKind.LEAF && node.quantity > 1) {
                    Text(
                        text = if (privacy.goals) stringResource(
                            R.string.budget_each,
                            privacy.sum(node.manualSumMinor, currency),
                            privacy.goal(node.manualGoalMinor, currency),
                        ) else stringResource(R.string.budget_each_sum, privacy.sum(node.manualSumMinor, currency)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showAggregates) {
                    // Each on a line of its own (D302): in one line they broke in the middle on a phone.
                    Text(text = stringResource(R.string.budget_sum) + " " + privacy.sum(node.cachedSumMinor, currency), style = MaterialTheme.typography.bodyMedium)
                    // The goal and the remainder are an account's (D326): an income and an expense show their sum alone.
                    if (privacy.goals) Text(text = stringResource(R.string.budget_goal) + " " + privacy.goal(node.cachedGoalMinor, currency), style = MaterialTheme.typography.bodyMedium)
                    if (privacy.goals) Text(
                        text = stringResource(R.string.budget_remainder, privacy.remainder(node.cachedRemainderMinor, currency)),
                        style = MaterialTheme.typography.bodyMedium,
                        // A hidden remainder does not tell by its colour that it is negative.
                        color = if (node.cachedRemainderMinor < 0 && !privacy.remainderMasked) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // Copy of the line (D362), in View: as it is shown - hidden amounts stay hidden.
            if (!editing && !isTotalRow) {
                val name = if (isRoot) stringResource(R.string.budget_total) else node.title + (if (node.kind == BudgetNodeKind.LEAF && node.quantity > 1) "  ×${node.quantity}" else "")
                val sum = stringResource(R.string.budget_sum) + " " + privacy.sum(node.cachedSumMinor, currency)
                val goal = stringResource(R.string.budget_goal) + " " + privacy.goal(node.cachedGoalMinor, currency)
                val rest = stringResource(R.string.budget_remainder, privacy.remainder(node.cachedRemainderMinor, currency))
                com.notebudget.app.core.ui.CopyIcon(small = true) {
                    listOfNotNull(name, sum.takeIf { showAggregates }, goal.takeIf { showAggregates && privacy.goals }, rest.takeIf { showAggregates && privacy.goals }).joinToString(", ")
                }
            }
            if (onEye != null && showAggregates) {
                val label = stringResource(if (privacy.revealed) R.string.budget_hide_amounts else R.string.budget_show_amounts)
                IconButton(onClick = onEye) { Icon(if (privacy.revealed) EyeOffIcon else EyeIcon, contentDescription = label) }
            }
            // "Change" (D164): on an item, in View and in Edit alike.
            if (node.kind == BudgetNodeKind.LEAF && !isTotalRow) {
                IconButton(onClick = onAdjust) {
                    Icon(PlusMinusIcon, contentDescription = stringResource(R.string.budget_adjust))
                }
            }
            // Delete (D359): in View an icon of its own on every item and group; in Edit it is in the ⋮ menu. It asks first.
            if (!editing && !isRoot && !isTotalRow) {
                IconButton(onClick = onDelete, helpTopic = "budget") {
                    Icon(com.notebudget.app.core.ui.AppIcons.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                }
            }
            if (editing) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    if (isGroup) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.budget_add_item)) },
                            onClick = { menuOpen = false; onAdd(BudgetNodeKind.LEAF) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.budget_add_group)) },
                            onClick = { menuOpen = false; onAdd(BudgetNodeKind.GROUP) },
                        )
                    }
                    if (onMerge != null && !isTotalRow) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.budget_item_merge)) },
                            onClick = { menuOpen = false; onMerge() },
                        )
                    }
                    if (!isRoot) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The currency of the whole budget (D170): none, one the person added, or a built-in one. It is written after every sum. A currency
 * the budget already has but this phone does not list (a budget from another phone) stays offered as it is.
 */
@Composable
private fun CurrencyDialog(currentId: String?, onPick: (id: String?, label: String?) -> Unit, onDismiss: () -> Unit) {
    val container = com.notebudget.app.core.ui.rememberAppContainer()
    val context = LocalContext.current
    val custom by container.currencyRepository.observeCustomCurrencies()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val ids = buildList {
        custom.forEach { add(it.id) }
        addAll(com.notebudget.app.domain.model.BuiltInCurrency.ALL)
    }
    com.notebudget.app.core.ui.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.budget_currency_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.budget_currency_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                CurrencyOption(stringResource(R.string.budget_currency_none), currentId == null) { onPick(null, null) }
                ids.forEach { id ->
                    val label = com.notebudget.app.feature.currencies.CurrencyLabels.fullLabel(context, id, custom)
                    val shown = com.notebudget.app.feature.currencies.CurrencyLabels.symbol(id, custom)
                    CurrencyOption(label, currentId == id) { onPick(id, shown) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CurrencyOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "●" else "○", Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * Merging an item into another item of the same budget (D330): the item that stays is picked from the items of the note, each
 * with the groups it is in; the switches say what it gets — the sums, the goals or both ([goals] `false`: the budget has sums
 * alone, D326). The item the dialog was opened on is deleted, and the dialog says so before it is done.
 */
@Composable
private fun ItemMergeDialog(
    source: BudgetNode,
    nodes: List<BudgetNode>,
    goals: Boolean,
    onConfirm: (targetId: String, sum: Boolean, goal: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val total = stringResource(R.string.budget_total)
    val candidates = remember(nodes, source.id) { com.notebudget.app.domain.model.BudgetItems.others(nodes, source.id) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var sum by remember { mutableStateOf(true) }
    var goal by remember { mutableStateOf(goals) }
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.budget_item_merge)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.budget_item_merge_hint, source.title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (goals) {
                    SwitchRow(stringResource(R.string.budget_merge_sum), sum) { if (it || goal) sum = it }
                    SwitchRow(stringResource(R.string.budget_merge_goal), goal) { if (it || sum) goal = it }
                }
                Text(stringResource(R.string.budget_item_merge_into), style = MaterialTheme.typography.titleSmall)
                candidates.forEach { (node, path) ->
                    CurrencyOption(node.title + "  ·  " + (path.joinToString(" / ").ifEmpty { total }), targetId == node.id) { targetId = node.id }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { targetId?.let { onConfirm(it, sum, goal && goals) } }, enabled = targetId != null) { Text(stringResource(R.string.budget_item_merge_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Add / edit dialog. Sum and goal are only editable for a LEAF (a group sums its children). */
@Composable
internal fun NodeDialog(
    title: String,
    initial: BudgetNode?,
    kind: BudgetNodeKind,
    groups: List<GroupOption>,
    /** The group the node is in (or is being added to); `null` for the root, which has no group to choose. */
    initialGroupId: String?,
    /** Whether the budget has goals (D326): without them the field of the goal is not there, and the goal stays what it was. */
    goals: Boolean = true,
    onConfirm: (title: String, comment: String?, sumMinor: Long, goalMinor: Long, quantity: Int, groupId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var groupId by remember { mutableStateOf(initialGroupId) }
    var name by remember { mutableStateOf(initial?.title.orEmpty()) }
    var comment by remember { mutableStateOf(initial?.comment.orEmpty()) }
    var sum by remember { mutableStateOf(Money.formatForEdit(initial?.manualSumMinor ?: 0)) }
    var goal by remember { mutableStateOf(Money.formatForEdit(initial?.manualGoalMinor ?: 0)) }
    var quantityField by remember { mutableStateOf(TextFieldValue((initial?.quantity ?: 1).toString())) }
    var showErrors by remember { mutableStateOf(false) }

    val quantity = QuantityInput.parse(quantityField.text)
    val sumMinor = Money.parse(sum)
    val goalMinor = Money.parse(goal)
    val isRoot = initial != null && initial.parentId == null
    val nameInvalid = !isRoot && name.isBlank()
    val numbersInvalid = kind == BudgetNodeKind.LEAF && (sumMinor == null || goalMinor == null || quantity == null)

    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Scrolls: with the keyboard open the lower fields would otherwise be out of reach (D114).
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isRoot) {
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
                }
                if (initialGroupId != null && groups.isNotEmpty()) {
                    GroupPicker(groups, groupId ?: initialGroupId) { groupId = it }
                }
                if (kind == BudgetNodeKind.LEAF) {
                    // How many times the item repeats (D94, D114): starts at 1; the field selects its text when
                    // tapped so typing replaces it, and - / + change it without the keyboard.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { quantityField = quantityField.copy(text = QuantityInput.step(quantityField.text, -1)) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(48.dp),
                        ) { Text("\u2212", style = MaterialTheme.typography.titleLarge) }
                        OutlinedTextField(
                            value = quantityField,
                            onValueChange = { quantityField = it.copy(text = it.text.filter(Char::isDigit).take(4)) },
                            label = { Text(stringResource(R.string.budget_quantity)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = quantity == null,
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (quantity == null) R.string.budget_invalid_quantity else R.string.budget_quantity_hint,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f).onFocusChanged { focus ->
                                if (focus.isFocused) quantityField = quantityField.copy(selection = TextRange(0, quantityField.text.length))
                            },
                        )
                        OutlinedButton(
                            onClick = { quantityField = quantityField.copy(text = QuantityInput.step(quantityField.text, 1)) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(48.dp),
                        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    }
                }
                if (kind == BudgetNodeKind.LEAF) {
                    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    OutlinedTextField(
                        value = sum,
                        onValueChange = { sum = it },
                        label = { Text(stringResource(R.string.budget_sum)) },
                        singleLine = true,
                        keyboardOptions = keyboard,
                        isError = sumMinor == null,
                        supportingText = if (sumMinor == null) {
                            { Text(stringResource(R.string.budget_invalid_number)) }
                        } else null,
                    )
                    if (goals) OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        label = { Text(stringResource(R.string.budget_goal)) },
                        singleLine = true,
                        keyboardOptions = keyboard,
                        isError = goalMinor == null,
                        supportingText = if (goalMinor == null) {
                            { Text(stringResource(R.string.budget_invalid_number)) }
                        } else null,
                    )
                }
                if (initial != null || kind == BudgetNodeKind.LEAF) {
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text(stringResource(R.string.budget_node_comment)) },
                        minLines = 1,
                        maxLines = 3,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (nameInvalid || numbersInvalid) {
                    showErrors = true
                } else {
                    onConfirm(name, comment.ifBlank { null }, sumMinor ?: 0, goalMinor ?: 0, quantity ?: 1, groupId)
                }
            }) {
                Text(stringResource(if (initial == null) R.string.action_create else R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Group": a drop-down of "Total" and every group below it; nested groups are indented (D102). */
@Composable
private fun GroupPicker(groups: List<GroupOption>, selectedId: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val total = stringResource(R.string.budget_total)
    fun name(option: GroupOption) = "\u2007".repeat(option.depth * 2) + if (option.isRoot) total else option.title
    val selected = groups.firstOrNull { it.id == selectedId }
    Column {
        Text(stringResource(R.string.budget_group_label), style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(selected?.let { name(it).trim() } ?: total) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                groups.forEach { option ->
                    DropdownMenuItem(text = { Text(name(option)) }, onClick = { open = false; onPick(option.id) })
                }
            }
        }
    }
}

/** Per-note display options; they are saved in the note, not in the app settings. */
@Composable
private fun LayoutDialog(current: BudgetLayout, onConfirm: (BudgetLayout) -> Unit, onDismiss: () -> Unit) {
    var layout by remember { mutableStateOf(current) }
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.budget_layout_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchRow(stringResource(R.string.budget_layout_total_bottom), layout.totalAtBottom) {
                    layout = layout.copy(totalAtBottom = it)
                }
                SwitchRow(stringResource(R.string.budget_layout_group_totals_bottom), layout.groupTotalsAtBottom) {
                    layout = layout.copy(groupTotalsAtBottom = it)
                }
                SwitchRow(stringResource(R.string.budget_layout_newest_first), layout.newestFirst) {
                    layout = layout.copy(newestFirst = it)
                }
                SwitchRow(stringResource(R.string.budget_layout_comments), layout.showComments) {
                    layout = layout.copy(showComments = it)
                }
                // D272: stars instead of the amounts on the card and in the note, until the eye of the note is pressed.
                SwitchRow(stringResource(R.string.budget_layout_hide_sums), layout.hideSums) {
                    layout = layout.copy(hideSums = it)
                }
                if (layout.hasGoals) SwitchRow(stringResource(R.string.budget_layout_hide_goals), layout.hideGoals) {
                    layout = layout.copy(hideGoals = it)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(layout) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
