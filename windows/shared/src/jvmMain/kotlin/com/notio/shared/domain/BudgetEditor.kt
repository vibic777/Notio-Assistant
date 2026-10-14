package com.notio.shared.domain

import com.notio.shared.domain.Clock
import com.notio.shared.domain.IdGenerator
import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetLogEntry
import com.notio.shared.domain.BudgetLogKind
import com.notio.shared.domain.BudgetLogRepository
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.DomainError
import com.notio.shared.domain.DomainResult
import com.notio.shared.domain.asFailure
import com.notio.shared.domain.asSuccess

/**
 * Edits the budget tree and keeps cached values consistent: every mutation recomputes the
 * affected branch up to the root and persists all changed nodes in one transaction.
 * Money is `Long` minor units.
 */
class BudgetEditor(
    private val budgets: BudgetRepository,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.Random,
    /** Where the log of the note is kept (D166); `null` = no log (tests of other things). */
    private val log: BudgetLogRepository? = null,
) {

    /** Writes lines to the log of a note; the lines given first are shown lower (the log is read newest first). */
    private fun record(noteId: String, now: Long, vararg lines: LogLine?) {
        val repo = log ?: return
        val entries = lines.filterNotNull().map {
            BudgetLogEntry(ids.newId(), noteId, now, it.kind, it.title, it.old, it.new, it.detail, it.nodeId)
        }
        if (entries.isNotEmpty()) repo.add(entries)
    }

    /** [nodeId]: the item or group the line is about (D282), so the line can be undone from the log. */
    private class LogLine(
        val kind: String,
        val title: String,
        val old: Long? = null,
        val new: Long? = null,
        val detail: String? = null,
        val nodeId: String? = null,
    )

    private fun groupTitle(nodes: List<BudgetNode>, id: String?): String =
        nodes.find { it.id == id }?.takeIf { it.parentId != null }?.title.orEmpty()

    /**
     * Adds a node under a GROUP parent. A LEAF cannot have children (INV-4).
     * A new LEAF is created with the given [sumMinor] and [goalMinor] and its totals are counted into
     * every ancestor at once; a GROUP takes no values of its own (INV-4), so they are ignored for it.
     * [quantity] (D94, 1..9999) is how many times a LEAF repeats; a GROUP always has 1.
     */
    fun addNode(
        noteId: String,
        parentId: String,
        title: String,
        kind: BudgetNodeKind,
        comment: String? = null,
        sumMinor: Long = 0,
        goalMinor: Long = 0,
        quantity: Int = 1,
    ): DomainResult<BudgetNode> {
        if (title.isBlank()) return DomainError.BLANK_TITLE.asFailure()
        if (kind == BudgetNodeKind.LEAF && quantity !in 1..BudgetNode.MAX_QUANTITY) {
            return DomainError.INVALID_QUANTITY.asFailure()
        }
        val nodes = budgets.getNodes(noteId)
        val parent = nodes.find { it.id == parentId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        if (parent.kind != BudgetNodeKind.GROUP) return DomainError.BUDGET_PARENT_IS_LEAF.asFailure()

        val now = clock.now()
        val node = BudgetNode(
            id = ids.newId(),
            noteId = noteId,
            parentId = parentId,
            title = title.trim(),
            kind = kind,
            manualSumMinor = if (kind == BudgetNodeKind.LEAF) sumMinor else 0,
            manualGoalMinor = if (kind == BudgetNodeKind.LEAF) goalMinor else 0,
            comment = comment?.takeIf { it.isNotBlank() },
            cachedSumMinor = 0,
            cachedGoalMinor = 0,
            cachedRemainderMinor = 0,
            isExpanded = true,
            sortOrder = (nodes.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAt = now,
            updatedAt = now,
            quantity = if (kind == BudgetNodeKind.LEAF) quantity else 1,
        )
        persist(nodes, nodes + node, recomputeFrom = listOf(node.id), touched = setOf(node.id), now = now)
        val leaf = kind == BudgetNodeKind.LEAF
        record(
            noteId, now,
            if (leaf && quantity > 1) LogLine(BudgetLogKind.QUANTITY, node.title, null, quantity.toLong(), nodeId = node.id) else null,
            if (leaf && goalMinor != 0L) LogLine(BudgetLogKind.GOAL, node.title, null, goalMinor, nodeId = node.id) else null,
            if (leaf && sumMinor != 0L) LogLine(BudgetLogKind.SUM, node.title, null, sumMinor, nodeId = node.id) else null,
            LogLine(if (leaf) BudgetLogKind.ITEM_ADDED else BudgetLogKind.GROUP_ADDED, node.title, detail = groupTitle(nodes, parentId), nodeId = node.id),
        )
        return node.asSuccess()
    }

    /**
     * Sets the manual sum and goal (for ONE piece) of a LEAF and, when [quantity] is given, how many
     * times it repeats (D94); `null` keeps the current quantity.
     */
    fun updateLeaf(
        noteId: String,
        nodeId: String,
        sumMinor: Long,
        goalMinor: Long,
        quantity: Int? = null,
    ): DomainResult<Unit> {
        if (quantity != null && quantity !in 1..BudgetNode.MAX_QUANTITY) return DomainError.INVALID_QUANTITY.asFailure()
        val nodes = budgets.getNodes(noteId)
        val node = nodes.find { it.id == nodeId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        if (node.kind != BudgetNodeKind.LEAF) return DomainError.BUDGET_NODE_NOT_LEAF.asFailure()

        val edited = nodes.map {
            if (it.id == nodeId) {
                it.copy(manualSumMinor = sumMinor, manualGoalMinor = goalMinor, quantity = quantity ?: it.quantity)
            } else it
        }
        val now = clock.now()
        persist(nodes, edited, recomputeFrom = listOf(nodeId), touched = setOf(nodeId), now = now)
        val newQuantity = quantity ?: node.quantity
        record(
            noteId, now,
            if (newQuantity != node.quantity) LogLine(BudgetLogKind.QUANTITY, node.title, node.quantity.toLong(), newQuantity.toLong(), nodeId = nodeId) else null,
            if (goalMinor != node.manualGoalMinor) LogLine(BudgetLogKind.GOAL, node.title, node.manualGoalMinor, goalMinor, nodeId = nodeId) else null,
            if (sumMinor != node.manualSumMinor) LogLine(BudgetLogKind.SUM, node.title, node.manualSumMinor, sumMinor, nodeId = nodeId) else null,
        )
        return Unit.asSuccess()
    }

    /**
     * Adds [deltaMinor] (negative to subtract) to the manual sum of a LEAF at once (D164): the "Change" button of an item.
     * The sum is the one for ONE piece, as in the edit dialog, so an item repeated 3 times changes its total by 3 x delta.
     * The result may go below zero (a refund); an amount that does not fit into a `Long` is refused.
     */
    fun adjustLeafSum(noteId: String, nodeId: String, deltaMinor: Long): DomainResult<Unit> {
        val nodes = budgets.getNodes(noteId)
        val node = nodes.find { it.id == nodeId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        if (node.kind != BudgetNodeKind.LEAF) return DomainError.BUDGET_NODE_NOT_LEAF.asFailure()
        val newSum = try {
            Math.addExact(node.manualSumMinor, deltaMinor)
        } catch (_: ArithmeticException) {
            return DomainError.INVALID_AMOUNT.asFailure()
        }
        val edited = nodes.map { if (it.id == nodeId) it.copy(manualSumMinor = newSum) else it }
        val now = clock.now()
        persist(nodes, edited, recomputeFrom = listOf(nodeId), touched = setOf(nodeId), now = now)
        record(
            noteId, now,
            LogLine(BudgetLogKind.SUM, node.title, node.manualSumMinor, newSum, if (deltaMinor >= 0) "ADD" else "SUBTRACT", nodeId),
        )
        return Unit.asSuccess()
    }

    /**
     * The Change button (D215): + [amountMinor], − [amountMinor] or = [amountMinor] on the manual sum ([sum]), the manual goal
     * ([goal]) or both, of ONE piece, in one write. At least one must be chosen; a result that does not fit a `Long` is refused.
     */
    fun adjustLeaf(
        noteId: String,
        nodeId: String,
        mode: AdjustMode,
        amountMinor: Long,
        sum: Boolean,
        goal: Boolean,
        /** An optional comment on the change (D262): kept in its log lines after the kind of change and a line break. */
        comment: String = "",
    ): DomainResult<Unit> {
        if (!sum && !goal) return DomainError.INVALID_AMOUNT.asFailure()
        val nodes = budgets.getNodes(noteId)
        val node = nodes.find { it.id == nodeId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        if (node.kind != BudgetNodeKind.LEAF) return DomainError.BUDGET_NODE_NOT_LEAF.asFailure()
        val apply = Adjust::apply
        val newSum = if (sum) apply(node.manualSumMinor, mode, amountMinor) ?: return DomainError.INVALID_AMOUNT.asFailure() else node.manualSumMinor
        val newGoal = if (goal) apply(node.manualGoalMinor, mode, amountMinor) ?: return DomainError.INVALID_AMOUNT.asFailure() else node.manualGoalMinor
        val edited = nodes.map { if (it.id == nodeId) it.copy(manualSumMinor = newSum, manualGoalMinor = newGoal) else it }
        val now = clock.now()
        persist(nodes, edited, recomputeFrom = listOf(nodeId), touched = setOf(nodeId), now = now)
        val how = when (mode) {
            AdjustMode.ADD -> "ADD"
            AdjustMode.SUBTRACT -> "SUBTRACT"
            AdjustMode.SET -> "SET"
        } + comment.trim().replace('\n', ' ').take(300).let { if (it.isEmpty()) "" else "\n" + it }
        record(
            noteId, now,
            if (goal && newGoal != node.manualGoalMinor) LogLine(BudgetLogKind.GOAL, node.title, node.manualGoalMinor, newGoal, how, nodeId) else null,
            if (sum && newSum != node.manualSumMinor) LogLine(BudgetLogKind.SUM, node.title, node.manualSumMinor, newSum, how, nodeId) else null,
        )
        return Unit.asSuccess()
    }

    /** Puts [sumMinor] in place of the manual sum of a LEAF (the "Enter" choice of the Change button, D165). */
    fun setLeafSum(noteId: String, nodeId: String, sumMinor: Long): DomainResult<Unit> {
        val nodes = budgets.getNodes(noteId)
        val node = nodes.find { it.id == nodeId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        if (node.kind != BudgetNodeKind.LEAF) return DomainError.BUDGET_NODE_NOT_LEAF.asFailure()
        val edited = nodes.map { if (it.id == nodeId) it.copy(manualSumMinor = sumMinor) else it }
        val now = clock.now()
        persist(nodes, edited, recomputeFrom = listOf(nodeId), touched = setOf(nodeId), now = now)
        if (sumMinor != node.manualSumMinor) record(noteId, now, LogLine(BudgetLogKind.SUM, node.title, node.manualSumMinor, sumMinor, "SET", nodeId))
        return Unit.asSuccess()
    }

    /** Title and comment (D34, D70). Values are not affected. */
    fun updateDetails(
        noteId: String,
        nodeId: String,
        title: String,
        comment: String?,
    ): DomainResult<Unit> {
        if (title.isBlank()) return DomainError.BLANK_TITLE.asFailure()
        val nodes = budgets.getNodes(noteId)
        val node = nodes.find { it.id == nodeId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val edited = nodes.map {
            if (it.id == nodeId) it.copy(title = title.trim(), comment = comment?.takeIf { c -> c.isNotBlank() }) else it
        }
        val now = clock.now()
        persist(nodes, edited, recomputeFrom = emptyList(), touched = setOf(nodeId), now = now)
        if (node.parentId != null && node.title != title.trim()) {
            record(noteId, now, LogLine(BudgetLogKind.RENAMED, title.trim(), detail = node.title, nodeId = nodeId))
        }
        return Unit.asSuccess()
    }

    /** Collapse / expand. Never changes values. */
    fun setExpanded(noteId: String, nodeId: String, expanded: Boolean): DomainResult<Unit> {
        val nodes = budgets.getNodes(noteId)
        if (nodes.none { it.id == nodeId }) return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val edited = nodes.map { if (it.id == nodeId) it.copy(isExpanded = expanded) else it }
        persist(nodes, edited, recomputeFrom = emptyList(), touched = setOf(nodeId), now = clock.now())
        return Unit.asSuccess()
    }

    /**
     * Opens or closes every group of the budget at once (D183). The total (the root) always stays open, so a closed budget still shows its
     * top-level groups. Only the open / closed marks change: no value, no "updated" time.
     */
    fun setAllExpanded(noteId: String, expanded: Boolean): DomainResult<Unit> {
        val nodes = budgets.getNodes(noteId)
        if (nodes.isEmpty()) return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val edited = nodes.map {
            if (it.kind == BudgetNodeKind.GROUP && it.parentId != null && it.isExpanded != expanded) it.copy(isExpanded = expanded) else it
        }
        persist(nodes, edited, recomputeFrom = emptyList(), touched = emptySet(), now = clock.now())
        return Unit.asSuccess()
    }

    /**
     * Undoes one line of the log (D282), as a merge can be undone (D237): a mistake found later is taken back without touching
     * what came after it. A change of the sum, the goal or the quantity is taken back by its difference (the value after the
     * later changes minus what that line added), so later changes stay; a rename gives the old name back; an added item or group
     * is deleted with what is in it now (the screen asks first). The undo is a line of its own ([BudgetLogKind.OP_UNDONE]); a
     * line can be undone once. Lines made before D282 do not know their item and cannot be undone here.
     */
    fun undoLogEntry(entry: BudgetLogEntry, all: List<BudgetLogEntry>): DomainResult<Unit> {
        if (!canUndo(entry, all)) return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val noteId = entry.noteId
        val nodes = budgets.getNodes(noteId)
        val node = nodes.find { it.id == entry.nodeId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val delta = (entry.newValue ?: 0L) - (entry.oldValue ?: 0L)
        val now = clock.now()
        val detail = entry.id + "\n" + entry.kind
        fun undoneLine(old: Long?, new: Long?) = LogLine(BudgetLogKind.OP_UNDONE, node.title, old, new, detail, node.id)
        when (entry.kind) {
            BudgetLogKind.SUM, BudgetLogKind.GOAL, BudgetLogKind.QUANTITY -> {
                if (node.kind != BudgetNodeKind.LEAF) return DomainError.BUDGET_NODE_NOT_LEAF.asFailure()
                val before = when (entry.kind) {
                    BudgetLogKind.SUM -> node.manualSumMinor
                    BudgetLogKind.GOAL -> node.manualGoalMinor
                    else -> node.quantity.toLong()
                }
                val after = try { Math.subtractExact(before, delta) } catch (_: ArithmeticException) { return DomainError.INVALID_AMOUNT.asFailure() }
                if (entry.kind == BudgetLogKind.QUANTITY && after !in 1..BudgetNode.MAX_QUANTITY.toLong()) return DomainError.INVALID_QUANTITY.asFailure()
                val edited = nodes.map {
                    if (it.id != node.id) it else when (entry.kind) {
                        BudgetLogKind.SUM -> it.copy(manualSumMinor = after)
                        BudgetLogKind.GOAL -> it.copy(manualGoalMinor = after)
                        else -> it.copy(quantity = after.toInt())
                    }
                }
                persist(nodes, edited, recomputeFrom = listOf(node.id), touched = setOf(node.id), now = now)
                record(noteId, now, undoneLine(before, after))
            }
            BudgetLogKind.RENAMED -> {
                val oldName = entry.detail?.takeIf { it.isNotBlank() } ?: return DomainError.BLANK_TITLE.asFailure()
                val edited = nodes.map { if (it.id == node.id) it.copy(title = oldName) else it }
                persist(nodes, edited, recomputeFrom = emptyList(), touched = setOf(node.id), now = now)
                record(noteId, now, LogLine(BudgetLogKind.OP_UNDONE, oldName, detail = detail, nodeId = node.id))
            }
            BudgetLogKind.ITEM_ADDED, BudgetLogKind.GROUP_ADDED -> {
                val parentId = node.parentId ?: return DomainError.BUDGET_ROOT_IMMUTABLE.asFailure()
                val doomed = subtreeIds(nodes, node.id)
                val remaining = nodes.filter { it.id !in doomed }
                val recalculated = BudgetCalculator.recalculateBranch(remaining, listOf(parentId))
                budgets.applyChanges(upserts = changed(remaining, recalculated, touched = emptySet(), now = now), deletedIds = doomed.toList())
                record(noteId, now, undoneLine(null, null))
            }
            else -> return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        }
        return Unit.asSuccess()
    }

    /** Whether [entry] can be undone from the log (D282): an undoable kind, it knows its item, and it was not undone yet. */
    fun canUndo(entry: BudgetLogEntry, all: List<BudgetLogEntry>): Boolean =
        entry.kind in BudgetLogKind.UNDOABLE && entry.nodeId != null && !undone(entry, all)

    /** Has [entry] been undone from the log already? */
    fun undone(entry: BudgetLogEntry, all: List<BudgetLogEntry>): Boolean =
        all.any { it.kind == BudgetLogKind.OP_UNDONE && it.detail?.substringBefore('\n') == entry.id }

    /**
     * Merges the item [sourceId] into the item [targetId] of the same budget (D330): the target keeps its name and gets the
     * sum ([sum]), the goal ([goal]) or both of the source added to its own, and the source is deleted — one write. For putting
     * together items made by mistake and sums scattered over several items. Every item counts with its whole value (a piece
     * times its quantity); a target of more than one piece becomes one piece of its whole value first, so the addition stays
     * exact (as in [BudgetMergePlanner]). At least one of the two must be chosen.
     */
    fun mergeLeaf(noteId: String, sourceId: String, targetId: String, sum: Boolean, goal: Boolean): DomainResult<Unit> {
        if (!sum && !goal) return DomainError.INVALID_AMOUNT.asFailure()
        val nodes = budgets.getNodes(noteId)
        val source = nodes.find { it.id == sourceId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val target = nodes.find { it.id == targetId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        if (sourceId == targetId) return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        if (source.kind != BudgetNodeKind.LEAF || target.kind != BudgetNodeKind.LEAF) return DomainError.BUDGET_NODE_NOT_LEAF.asFailure()
        val sourceParent = source.parentId ?: return DomainError.BUDGET_ROOT_IMMUTABLE.asFailure()
        // A value that does not fit a Long is refused, nothing is written.
        val merged = runCatching {
            val wholeSum = Math.multiplyExact(target.manualSumMinor, target.quantity.toLong())
            val wholeGoal = Math.multiplyExact(target.manualGoalMinor, target.quantity.toLong())
            target.copy(
                manualSumMinor = if (sum) Math.addExact(wholeSum, Math.multiplyExact(source.manualSumMinor, source.quantity.toLong())) else wholeSum,
                manualGoalMinor = if (goal) Math.addExact(wholeGoal, Math.multiplyExact(source.manualGoalMinor, source.quantity.toLong())) else wholeGoal,
                quantity = 1,
            )
        }.getOrNull() ?: return DomainError.INVALID_AMOUNT.asFailure()
        val before = nodes.filter { it.id != sourceId }
        val recalculated = BudgetCalculator.recalculateBranch(before.map { if (it.id == targetId) merged else it }, listOf(targetId, sourceParent))
        val now = clock.now()
        budgets.applyChanges(upserts = changed(before, recalculated, touched = setOf(targetId), now = now), deletedIds = listOf(sourceId))
        // The values are the target's whole ones before and after; the lines are taken back by Undo of the note, all at once.
        val oldSum = target.manualSumMinor * target.quantity
        val oldGoal = target.manualGoalMinor * target.quantity
        record(
            noteId, now,
            if (merged.manualGoalMinor != oldGoal) LogLine(BudgetLogKind.GOAL, target.title, oldGoal, merged.manualGoalMinor, "ADD") else null,
            if (merged.manualSumMinor != oldSum) LogLine(BudgetLogKind.SUM, target.title, oldSum, merged.manualSumMinor, "ADD") else null,
            LogLine(BudgetLogKind.ITEM_MERGED, source.title, detail = target.title),
        )
        return Unit.asSuccess()
    }

    /**
     * Deletes a node with its whole subtree; the root cannot be deleted. The line of the log keeps what was deleted (Android D359), so
     * that [restoreDeleted] can put it back.
     */
    fun deleteNode(noteId: String, nodeId: String): DomainResult<Unit> {
        val nodes = budgets.getNodes(noteId)
        val node = nodes.find { it.id == nodeId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val parentId = node.parentId ?: return DomainError.BUDGET_ROOT_IMMUTABLE.asFailure()

        val doomed = subtreeIds(nodes, nodeId)
        val remaining = nodes.filter { it.id !in doomed }
        val recalculated = BudgetCalculator.recalculateBranch(remaining, listOf(parentId))
        val now = clock.now()
        budgets.applyChanges(
            upserts = changed(remaining, recalculated, touched = emptySet(), now = now),
            deletedIds = doomed.toList(),
        )
        val kept = DeletedRecord.of(nodes.filter { it.id in doomed }).encode()
        record(noteId, now, LogLine(BudgetLogKind.DELETED, node.title, detail = (if (doomed.size > 1) BudgetLogKind.TREE else "") + "\n" + kept, nodeId = nodeId))
        return Unit.asSuccess()
    }

    /**
     * Puts back what a DELETED line of the log took away (Android D359): the item, or the group with everything that was inside it, as
     * they were — the same ids, names, sums, goals, quantities, comments and place among the brothers. It goes into the group it
     * was in, or under the total when that group is no longer there. The restore is a line of its own
     * ([BudgetLogKind.RESTORED]); a deletion is restored once. Refused when the item is in the budget already (Undo of the note
     * brought it back) or the record is not one whole tree; nothing is written then.
     */
    fun restoreDeleted(entry: BudgetLogEntry, all: List<BudgetLogEntry>): DomainResult<Unit> {
        if (!canRestore(entry, all)) return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val noteId = entry.noteId
        val back = DeletedRecord.decode(entry.detail)?.toNodes(noteId) ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val top = back.find { it.id == entry.nodeId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val nodes = budgets.getNodes(noteId)
        val root = nodes.find { it.parentId == null } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val byId = back.associateBy { it.id }
        // The deleted node first, then what was inside it: a parent is written before its children.
        val order = subtreeIds(back, top.id)
        if (byId.size != back.size || order.size != back.size) return DomainError.BUDGET_INVALID_MOVE.asFailure()
        if (nodes.any { it.id in byId }) return DomainError.BUDGET_INVALID_MOVE.asFailure()
        val group = nodes.find { it.id == top.parentId && it.kind == BudgetNodeKind.GROUP } ?: root
        val place = if (group.id == top.parentId) top.sortOrder else (nodes.filter { it.parentId == group.id }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val placed = order.map { id -> byId.getValue(id).let { if (id == top.id) it.copy(parentId = group.id, sortOrder = place) else it } }
        val now = clock.now()
        // The record keeps no totals: the whole tree is counted again.
        val recalculated = BudgetCalculator.recalculateAll(nodes + placed)
        budgets.applyChanges(upserts = changed(nodes, recalculated, touched = setOf(top.id), now = now), deletedIds = emptyList())
        record(noteId, now, LogLine(BudgetLogKind.RESTORED, top.title, detail = entry.id, nodeId = top.id))
        return Unit.asSuccess()
    }

    /** Whether the DELETED line [entry] can be restored from the log (Android D359): it kept what it deleted and was not restored yet. */
    fun canRestore(entry: BudgetLogEntry, all: List<BudgetLogEntry>): Boolean =
        entry.kind == BudgetLogKind.DELETED && entry.nodeId != null && entry.detail?.contains('\n') == true && !restored(entry, all)

    /** Has the DELETED line [entry] been restored from the log already? */
    fun restored(entry: BudgetLogEntry, all: List<BudgetLogEntry>): Boolean =
        all.any { it.kind == BudgetLogKind.RESTORED && it.detail == entry.id }

    /**
     * Puts the children of [parentId] in the order of [orderedIds] (the drag of an item or a group among its brothers, D176). The ids
     * must be exactly the children of that group. A group keeps everything inside it. Nothing is written when the order is the same.
     */
    fun reorderChildren(noteId: String, parentId: String, orderedIds: List<String>): DomainResult<Unit> {
        val nodes = budgets.getNodes(noteId)
        if (nodes.none { it.id == parentId }) return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val children = nodes.filter { it.parentId == parentId }
        if (orderedIds.size != children.size || orderedIds.toSet() != children.map { it.id }.toSet()) return DomainError.BUDGET_INVALID_MOVE.asFailure()
        val place = orderedIds.withIndex().associate { it.value to it.index }
        val edited = nodes.map { node -> place[node.id]?.let { node.copy(sortOrder = it) } ?: node }
        val moved = children.filter { place[it.id] != it.sortOrder }.map { it.id }.toSet()
        if (moved.isEmpty()) return Unit.asSuccess()
        persist(nodes, edited, recomputeFrom = emptyList(), touched = moved, now = clock.now())
        return Unit.asSuccess()
    }

    /** Moves a node under another GROUP of the same note (appended last); no cycles, no root move. */
    fun moveNode(noteId: String, nodeId: String, newParentId: String): DomainResult<Unit> {
        val nodes = budgets.getNodes(noteId)
        val node = nodes.find { it.id == nodeId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val newParent = nodes.find { it.id == newParentId } ?: return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val oldParentId = node.parentId ?: return DomainError.BUDGET_ROOT_IMMUTABLE.asFailure()
        if (newParent.kind != BudgetNodeKind.GROUP) return DomainError.BUDGET_PARENT_IS_LEAF.asFailure()
        if (newParentId in subtreeIds(nodes, nodeId)) return DomainError.BUDGET_INVALID_MOVE.asFailure()
        if (newParentId == oldParentId) return Unit.asSuccess()

        val order = (nodes.filter { it.parentId == newParentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val edited = nodes.map { if (it.id == nodeId) it.copy(parentId = newParentId, sortOrder = order) else it }
        val now = clock.now()
        persist(nodes, edited, recomputeFrom = listOf(oldParentId, newParentId), touched = setOf(nodeId), now = now)
        record(noteId, now, LogLine(BudgetLogKind.MOVED, node.title, detail = groupTitle(nodes, oldParentId) + "\n" + groupTitle(nodes, newParentId)))
        return Unit.asSuccess()
    }

    /**
     * Puts the tree of a note back to [snapshot], a list of its nodes taken earlier (Undo / Redo of the note, D197): nodes that
     * are not in it are deleted, the others are written as they were, values and cached totals included. Whether a group is
     * open or closed stays as it is now: undo is about the data, not about what is folded. One line goes to the log ([logKind]:
     * [BudgetLogKind.UNDONE] or [BudgetLogKind.REDONE]). Everything is written in one transaction.
     */
    fun restoreNodes(noteId: String, snapshot: List<BudgetNode>, logKind: String, logTitle: String = "", logDetail: String? = null): DomainResult<Unit> {
        if (snapshot.none { it.parentId == null } || snapshot.any { it.noteId != noteId }) return DomainError.BUDGET_NODE_NOT_FOUND.asFailure()
        val current = budgets.getNodes(noteId)
        val openNow = current.associate { it.id to it.isExpanded }
        val target = snapshot.map { n -> openNow[n.id]?.let { n.copy(isExpanded = it) } ?: n }
        val currentById = current.associateBy { it.id }
        val targetIds = target.mapTo(HashSet()) { it.id }
        val deleted = current.filter { it.id !in targetIds }.map { it.id }
        // Deleting a group takes everything under it with it (the database cascades), so what is still wanted from under it is
        // written again even if it did not change.
        val swept = deleted.flatMapTo(HashSet()) { subtreeIds(current, it) }
        // A parent is written before its children: a node that comes back after a delete needs its group stored first.
        val upserts = parentsFirst(target).filter { currentById[it.id] != it || it.id in swept }
        if (upserts.isEmpty() && deleted.isEmpty()) return Unit.asSuccess()
        budgets.applyChanges(upserts, deleted)
        record(noteId, clock.now(), LogLine(logKind, logTitle, detail = logDetail))
        return Unit.asSuccess()
    }

    /** [nodes] ordered so that every node comes after its parent (breadth first from the root). */
    private fun parentsFirst(nodes: List<BudgetNode>): List<BudgetNode> {
        val children = nodes.groupBy { it.parentId }
        val result = ArrayList<BudgetNode>(nodes.size)
        val queue = ArrayDeque(children[null].orEmpty())
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result += node
            children[node.id]?.let { queue.addAll(it) }
        }
        return result
    }

    private fun persist(
        before: List<BudgetNode>,
        edited: List<BudgetNode>,
        recomputeFrom: Collection<String>,
        touched: Set<String>,
        now: Long,
    ) {
        val recalculated = BudgetCalculator.recalculateBranch(edited, recomputeFrom)
        // `before` may lack a freshly added node, which then counts as changed.
        budgets.applyChanges(changed(before, recalculated, touched, now), deletedIds = emptyList())
    }

    /** Nodes that differ from [before] (or are in [touched]); touched ones get a new `updatedAt`. */
    private fun changed(
        before: List<BudgetNode>,
        after: List<BudgetNode>,
        touched: Set<String>,
        now: Long,
    ): List<BudgetNode> {
        val old = before.associateBy { it.id }
        return after.mapNotNull { node ->
            val stamped = if (node.id in touched) node.copy(updatedAt = now) else node
            if (old[node.id] == stamped) null else stamped
        }
    }

    /** [rootId] plus all its descendants. */
    private fun subtreeIds(nodes: List<BudgetNode>, rootId: String): Set<String> {
        val children = nodes.groupBy { it.parentId }
        val result = LinkedHashSet<String>()
        val stack = ArrayDeque(listOf(rootId))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (result.add(id)) children[id].orEmpty().forEach { stack.addLast(it.id) }
        }
        return result
    }
}

/** The budget nodes of a note, as the editor needs them (the desktop database). */
interface BudgetRepository {
    fun getNodes(noteId: String): List<BudgetNode>

    /** Upserts and deletes nodes in one transaction (the deleted subtree included). */
    fun applyChanges(upserts: List<BudgetNode>, deletedIds: List<String>)
}
