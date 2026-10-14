package com.notio.shared.data

import com.notio.shared.domain.AdjustMode
import com.notio.shared.domain.BudgetEditor
import com.notio.shared.domain.BudgetLayout
import com.notio.shared.domain.BudgetLogEntry
import com.notio.shared.domain.BudgetLogKind
import com.notio.shared.domain.BudgetLogRepository
import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.BudgetRepository
import com.notio.shared.domain.BudgetSortKey
import com.notio.shared.domain.Clock
import com.notio.shared.domain.DomainError
import com.notio.shared.domain.DomainResult
import com.notio.shared.domain.IdGenerator
import com.notio.shared.domain.UndoHistory
import com.notio.shared.transfer.BudgetLogRecord

/** A stored line of the log as the rules of the editor see it. */
fun BudgetLogRecord.toEntry() = BudgetLogEntry(id, noteId, at, kind, title, oldValue, newValue, detail, nodeId)

/** The budget tree and the budget log on the desktop database (the Android Room repositories). */
class DbBudgets(private val db: NotioDatabase) : BudgetRepository, BudgetLogRepository {
    override fun getNodes(noteId: String): List<BudgetNode> = db.budgetNodesOf(noteId)

    override fun applyChanges(upserts: List<BudgetNode>, deletedIds: List<String>) = db.transaction {
        // Delete first: a moved or deleted subtree must not clash with the new state; a new node's parent is stored or earlier.
        deletedIds.forEach { db.deleteNode(it) }
        upserts.forEach { db.upsertNode(it) }
    }

    override fun add(entries: List<BudgetLogEntry>) {
        db.addBudgetLog(entries.map { BudgetLogRecord(it.id, it.noteId, it.at, it.kind, it.title, it.oldValue, it.newValue, it.detail, it.nodeId) })
        entries.map { it.noteId }.distinct().forEach { db.trimBudgetLog(it, BudgetLogKind.MAX_PER_NOTE) }
    }
}

/**
 * One open budget note: the Android `BudgetViewModel` without the screen. Every change of the data goes through [change], so the
 * tree before it becomes one Undo step (D197); opening or closing a group is not a change of the data and is not undone. The layout
 * (order, totals at the bottom, currency, sort) lives in the note's `extras`, as on the phone.
 */
class BudgetSession(
    private val db: NotioDatabase,
    val noteId: String,
    clock: Clock = Clock.System,
    ids: IdGenerator = IdGenerator.Random,
    undoSteps: Int = UndoHistory.DEFAULT_STEPS,
) {
    private val budgets = DbBudgets(db)
    private val editor = BudgetEditor(budgets, clock, ids, budgets)
    private val history = UndoHistory<List<BudgetNode>>(undoSteps)
    private val clockRef = clock

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    fun nodes(): List<BudgetNode> = budgets.getNodes(noteId)

    fun layout(): BudgetLayout = BudgetLayout.parse(db.note(noteId)?.extras)

    @Synchronized
    private fun change(action: () -> DomainResult<*>): DomainError? {
        val before = nodes()
        val result = action()
        if (result is DomainResult.Failure) return result.error
        if (nodes() != before) history.record(before)
        return null
    }

    private fun run(action: () -> DomainResult<*>): DomainError? = (action() as? DomainResult.Failure)?.error

    fun add(parentId: String, title: String, kind: BudgetNodeKind, comment: String?, sum: Long, goal: Long, quantity: Int) =
        change { editor.addNode(noteId, parentId, title, kind, comment, sum, goal, quantity) }

    /** The edit dialog: name and comment, and for an item its values; a new group moves the node (Android `updateNode`). */
    fun update(nodeId: String, title: String, comment: String?, sum: Long?, goal: Long?, quantity: Int?, parentId: String?) = change {
        val node = nodes().firstOrNull { it.id == nodeId } ?: return@change DomainResult.Failure(DomainError.BUDGET_NODE_NOT_FOUND)
        editor.updateDetails(noteId, nodeId, title, comment).let { if (it is DomainResult.Failure) return@change it }
        if (node.kind == BudgetNodeKind.LEAF && sum != null && goal != null) {
            editor.updateLeaf(noteId, nodeId, sum, goal, quantity).let { if (it is DomainResult.Failure) return@change it }
        }
        if (parentId != null && node.parentId != null && parentId != node.parentId) editor.moveNode(noteId, nodeId, parentId) else DomainResult.Success(Unit)
    }

    /** [comment]: optional, written into the log line of the change (D262). */
    fun adjust(nodeId: String, mode: AdjustMode, amount: Long, sum: Boolean, goal: Boolean, comment: String = "") =
        change { editor.adjustLeaf(noteId, nodeId, mode, amount, sum, goal, comment) }

    fun delete(nodeId: String) = change { editor.deleteNode(noteId, nodeId) }

    /** One item merged into another of this budget (Android D330), as one undo step. */
    fun mergeItem(sourceId: String, targetId: String, sum: Boolean, goal: Boolean) = change { editor.mergeLeaf(noteId, sourceId, targetId, sum, goal) }

    /**
     * A line of the log undone from the log (Android D282): a change taken back by its own difference, a rename by the old name, an
     * added item or group deleted again; the undo is a line of its own, and one Undo step of the note like any change.
     */
    fun undoLogLine(line: BudgetLogRecord) =
        change { editor.undoLogEntry(line.toEntry(), db.budgetLogOf(noteId).map { it.toEntry() }) }

    /** What a DELETED line of the log took away put back (Android D359), as one Undo step of the note. */
    fun restoreDeleted(line: BudgetLogRecord) =
        change { editor.restoreDeleted(line.toEntry(), db.budgetLogOf(noteId).map { it.toEntry() }) }

    fun reorderChildren(parentId: String, storedOrder: List<String>) = change { editor.reorderChildren(noteId, parentId, storedOrder) }

    fun toggleExpanded(node: BudgetNode) = run { editor.setExpanded(noteId, node.id, !node.isExpanded) }

    fun setAllExpanded(expanded: Boolean) = run { editor.setAllExpanded(noteId, expanded) }

    @Synchronized
    fun undo(): DomainError? = step(BudgetLogKind.UNDONE) { history.undo(it) }

    @Synchronized
    fun redo(): DomainError? = step(BudgetLogKind.REDONE) { history.redo(it) }

    private fun step(kind: String, pick: (List<BudgetNode>) -> List<BudgetNode>?): DomainError? {
        val target = pick(nodes()) ?: return null
        return run { editor.restoreNodes(noteId, target, kind) }
    }

    fun setLayout(layout: BudgetLayout) {
        val note = db.note(noteId) ?: return
        db.upsertNote(note.copy(extras = layout.toExtras(), updatedAt = clockRef.now()))
    }

    fun setCurrency(id: String?, label: String?) = setLayout(layout().copy(currency = id, currencyLabel = label))

    fun setSort(key: BudgetSortKey, descending: Boolean) = setLayout(layout().copy(sort = key.name, sortDescending = descending))
}
