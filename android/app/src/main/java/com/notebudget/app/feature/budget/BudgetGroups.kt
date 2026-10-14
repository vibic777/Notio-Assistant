package com.notebudget.app.feature.budget

import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind

/** One choice of the "Group" drop-down (D102). [depth] 0 is the root ("Total"); nested groups are indented by depth. */
data class GroupOption(val id: String, val title: String, val depth: Int, val isRoot: Boolean)

/**
 * The groups an item or group can be put into: the root and every group below it, in tree order.
 * [excludeId] is the node being edited: it and everything below it are left out, so a group can never be moved
 * into itself or into one of its own descendants.
 */
fun groupOptions(nodes: List<BudgetNode>, excludeId: String? = null): List<GroupOption> {
    val children = nodes.groupBy { it.parentId }
    val out = ArrayList<GroupOption>()
    val seen = HashSet<String>()
    fun walk(node: BudgetNode, depth: Int) {
        if (!seen.add(node.id) || node.id == excludeId || node.kind != BudgetNodeKind.GROUP) return
        out += GroupOption(node.id, node.title, depth, isRoot = node.parentId == null)
        children[node.id].orEmpty().sortedBy { it.sortOrder }.forEach { walk(it, depth + 1) }
    }
    children[null].orEmpty().forEach { walk(it, 0) }
    return out
}
