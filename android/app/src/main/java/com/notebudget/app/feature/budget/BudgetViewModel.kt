package com.notebudget.app.feature.budget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.core.common.UndoHistory
import com.notebudget.app.domain.model.BudgetLogKind
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.repository.BudgetRepository
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.usecase.BudgetEditor
import com.notebudget.app.domain.usecase.NoteUseCases
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BudgetViewModel(
    private val noteId: String,
    private val budgets: BudgetRepository,
    notes: NoteRepository,
    private val editor: BudgetEditor,
    private val noteUseCases: NoteUseCases,
    /** How many changes back can be undone (Settings, D197). */
    undoSteps: kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flowOf(UndoHistory.DEFAULT_STEPS),
    /** The merge of an income / expense into its account (D237); `null` in tests of other things. */
    private val merge: BudgetMergeService? = null,
) : ViewModel(), com.notebudget.app.feature.notes.UndoTarget {

    /** What "Merge now" came to, for the screen to tell (D237). */
    val mergeOutcome = kotlinx.coroutines.flow.MutableStateFlow<MergeOutcome?>(null)

    fun mergeNow() {
        val service = merge ?: return
        viewModelScope.launch { mergeOutcome.value = runCatching { service.merge(noteId) }.getOrNull() }
    }

    /**
     * Undo / Redo (D197): the whole tree as it was before each change of the data (adding, editing, the Change button,
     * moving, deleting, dragging). Opening or closing a group is not a change of the data and is not undone.
     */
    private val history = UndoHistory<List<BudgetNode>>()

    /** One change at a time, so that "before" of a change really is the tree before it. */
    private val changes = Mutex()

    override var canUndo by mutableStateOf(false)
        private set
    override var canRedo by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { undoSteps.collect { history.limit = it; syncUndo() } }
    }

    private fun syncUndo() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    override fun undo() = step(BudgetLogKind.UNDONE) { history.undo(it) }

    override fun redo() = step(BudgetLogKind.REDONE) { history.redo(it) }

    private fun step(logKind: String, pick: (List<BudgetNode>) -> List<BudgetNode>?) {
        // A change of the budget is finished even when the screen goes away in the middle of it (D294).
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            changes.withLock {
                val target = pick(budgets.getNodes(noteId)) ?: return@withLock
                syncUndo()
                val result = editor.restoreNodes(noteId, target, logKind)
                if (result is DomainResult.Failure) errorChannel.send(result.error)
            }
        }
    }

    /** Layout of this budget, kept in the note itself. */
    val layout: StateFlow<BudgetLayout> = notes.observeById(noteId)
        .map { BudgetLayout.parse(it?.extras) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetLayout.Default)

    /** Every node of the tree (also the collapsed ones): the choices of the group drop-down (D102). */
    val nodes: StateFlow<List<BudgetNode>> = budgets.observeNodes(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** `null` while loading. */
    val rows: StateFlow<List<BudgetRowItem>?> = combine(budgets.observeNodes(noteId), layout) { nodes, layout ->
        visibleBudgetRows(nodes, layout)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Chooses how the rows of every group are sorted (D178); [BudgetSortKey.MANUAL] gives the own order back. */
    fun setSort(key: com.notebudget.app.domain.model.BudgetSortKey, descending: Boolean) {
        setLayout(layout.value.copy(sort = key.name, sortDescending = descending))
    }

    /** Chooses the currency of the budget (D170); `null` = none. */
    fun setCurrency(id: String?, label: String?) {
        val now = layout.value
        setLayout(now.copy(currency = id, currencyLabel = label))
    }

    fun setLayout(layout: BudgetLayout) {
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            noteUseCases.updateExtras(noteId, layout.toExtras())
            // The alarm of a merge follows its settings at once (D237).
            merge?.sync(noteId)
        }
    }

    private val errorChannel = Channel<DomainError>(Channel.BUFFERED)
    val errors: Flow<DomainError> = errorChannel.receiveAsFlow()

    fun add(
        parentId: String,
        title: String,
        kind: BudgetNodeKind,
        comment: String? = null,
        sumMinor: Long = 0,
        goalMinor: Long = 0,
        quantity: Int = 1,
    ) = change { editor.addNode(noteId, parentId, title, kind, comment, sumMinor, goalMinor, quantity) }

    fun updateNode(
        node: BudgetNode,
        title: String,
        comment: String?,
        sumMinor: Long,
        goalMinor: Long,
        quantity: Int = node.quantity,
        newParentId: String? = null,
    ) = change {
        val details = editor.updateDetails(noteId, node.id, title, comment)
        val saved = if (details is DomainResult.Failure || node.kind != BudgetNodeKind.LEAF) details
        else editor.updateLeaf(noteId, node.id, sumMinor, goalMinor, quantity)
        // A different group was chosen (D102): the node moves there, at the end; the root never moves.
        if (saved is DomainResult.Success && newParentId != null && node.parentId != null && newParentId != node.parentId) {
            editor.moveNode(noteId, node.id, newParentId)
        } else saved
    }

    /** The "Change" button of an item (D164, D165, D215): +, − or = on the sum, the goal or both, as one undo step. */
    fun adjust(nodeId: String, mode: com.notebudget.app.core.common.AdjustMode, amountMinor: Long, sum: Boolean, goal: Boolean, comment: String = "") =
        change { editor.adjustLeaf(noteId, nodeId, mode, amountMinor, sum, goal, comment) }

    /** The "Change" button of an item (D164): adds [deltaMinor] to its sum (negative subtracts). */
    fun adjustSum(nodeId: String, deltaMinor: Long) = change { editor.adjustLeafSum(noteId, nodeId, deltaMinor) }

    /** The "Enter" choice of the Change button (D165): [sumMinor] takes the place of the sum. */
    fun setSum(nodeId: String, sumMinor: Long) = change { editor.setLeafSum(noteId, nodeId, sumMinor) }

    /** A drag ended (D176): the children of [parentId] were shown in the order [displayedIds]; that order is saved. */
    fun reorderChildren(parentId: String, displayedIds: List<String>) =
        change { editor.reorderChildren(noteId, parentId, storageOrder(displayedIds, layout.value.newestFirst)) }

    /** "Collapse all groups" / "Expand all groups" (D183). */
    fun setAllExpanded(expanded: Boolean) = run { editor.setAllExpanded(noteId, expanded) }

    fun toggleExpanded(node: BudgetNode) = run { editor.setExpanded(noteId, node.id, !node.isExpanded) }

    fun delete(nodeId: String) = change { editor.deleteNode(noteId, nodeId) }

    /** One item merged into another of this budget (D330), as one undo step. */
    fun mergeItem(sourceId: String, targetId: String, sum: Boolean, goal: Boolean) = change { editor.mergeLeaf(noteId, sourceId, targetId, sum, goal) }

    /** A change of the data: done, and when it changed anything, the tree before it becomes one undo step. */
    private fun change(action: suspend () -> DomainResult<*>) {
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            changes.withLock {
                val before = budgets.getNodes(noteId)
                val result = action()
                if (result is DomainResult.Failure) {
                    errorChannel.send(result.error)
                } else if (budgets.getNodes(noteId) != before) {
                    history.record(before)
                    syncUndo()
                }
            }
        }
    }

    private fun run(action: suspend () -> DomainResult<*>) {
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            val result = action()
            if (result is DomainResult.Failure) errorChannel.send(result.error)
        }
    }
}
