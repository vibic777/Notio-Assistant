package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind

/**
 * Pure cascading recalculation of a budget tree (AGENT_EN.md §6.6, INV-4, INV-5).
 *
 * LEAF:  cached = manual values (one piece) times the quantity (D94).
 * GROUP: cached sum/goal = sum of the children's cached values (manual values are ignored).
 * Remainder is always `goal - sum`; it may be negative.
 */
object BudgetCalculator {

    /** Recomputes every node bottom-up. Used after import (INV-13) and for repair. */
    fun recalculateAll(nodes: List<BudgetNode>): List<BudgetNode> {
        val children = nodes.groupBy { it.parentId }
        val result = HashMap<String, BudgetNode>(nodes.size)

        fun compute(node: BudgetNode, depth: Int): BudgetNode {
            check(depth <= nodes.size) { "Cycle in budget tree" }
            val computed = when (node.kind) {
                BudgetNodeKind.LEAF -> leafValues(node)
                BudgetNodeKind.GROUP -> {
                    val kids = children[node.id].orEmpty().map { compute(it, depth + 1) }
                    withValues(node, kids.sumOf { it.cachedSumMinor }, kids.sumOf { it.cachedGoalMinor })
                }
            }
            result[node.id] = computed
            return computed
        }

        nodes.filter { it.parentId == null }.forEach { compute(it, 0) }
        // Nodes not reachable from a root (corrupt data) are left untouched.
        return nodes.map { result[it.id] ?: it }
    }

    /**
     * Recomputes only [startIds] and their ancestors up to the root (§14). The cached values
     * of everything else are trusted, so the tree must have been consistent before the edit.
     */
    fun recalculateBranch(nodes: List<BudgetNode>, startIds: Collection<String>): List<BudgetNode> {
        val byId = LinkedHashMap<String, BudgetNode>(nodes.size)
        nodes.forEach { byId[it.id] = it }
        val children = nodes.groupBy { it.parentId }

        for (startId in startIds) {
            var current: BudgetNode? = byId[startId]
            var steps = 0
            while (current != null && steps++ <= nodes.size) {
                val node = current
                val recomputed = when (node.kind) {
                    BudgetNodeKind.LEAF -> leafValues(node)
                    BudgetNodeKind.GROUP -> {
                        val kids = children[node.id].orEmpty().map { byId.getValue(it.id) }
                        withValues(node, kids.sumOf { it.cachedSumMinor }, kids.sumOf { it.cachedGoalMinor })
                    }
                }
                byId[node.id] = recomputed
                current = node.parentId?.let { byId[it] }
            }
        }
        return nodes.map { byId.getValue(it.id) }
    }

    /** One piece's manual sum and goal, repeated `quantity` times (never less than once). */
    private fun leafValues(node: BudgetNode): BudgetNode {
        val pieces = node.quantity.coerceAtLeast(1).toLong()
        return withValues(node, node.manualSumMinor * pieces, node.manualGoalMinor * pieces)
    }

    private fun withValues(node: BudgetNode, sum: Long, goal: Long) = node.copy(
        cachedSumMinor = sum,
        cachedGoalMinor = goal,
        cachedRemainderMinor = goal - sum,
    )
}
