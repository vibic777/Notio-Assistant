package com.notio.shared.domain

import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.BudgetSortKey
import com.notio.shared.domain.valueComparator
import com.notio.shared.transfer.TransferFormat
import kotlinx.serialization.Serializable

/**
 * How one budget note is laid out. Stored inside the note (`Note.extras` as JSON), so every
 * budget keeps its own order and it travels with export and backup.
 *
 *  - [totalAtBottom]: the overall total is the last row instead of the first;
 *  - [groupTotalsAtBottom]: each group shows its total row after its items instead of in
 *    its header (a collapsed group still shows its aggregates in the header);
 *  - [newestFirst]: items are listed in reverse order of entry.
 *
 * Unknown or missing fields fall back to the defaults, so old and future notes still load.
 */
@Serializable
data class BudgetLayout(
    val totalAtBottom: Boolean = false,
    val groupTotalsAtBottom: Boolean = false,
    val newestFirst: Boolean = false,
    /** The currency of the whole budget (D170): its id, and the text shown after every sum (kept here so a budget moved to another phone reads right). */
    val currency: String? = null,
    val currencyLabel: String? = null,
    /** Whether the rows show their comment, cut to 16 characters (D177); on for a budget that never chose. */
    val showComments: Boolean = true,
    /** A [BudgetSortKey] name (D178): how the rows inside each group are ordered; the manual order is kept underneath. */
    val sort: String = BudgetSortKey.MANUAL.name,
    val sortDescending: Boolean = false,
    /** D237: what the budget is — an account (as always), or an income / expense that is merged into an account once. */
    val kind: String = com.notio.shared.domain.BudgetKind.ACCOUNT.name,
    /** The account note an income / expense is merged into; `null` = none chosen, nothing happens. */
    val mergeTarget: String? = null,
    /** When it is merged (UTC millis); `null` = not scheduled (only "Merge now"). */
    val mergeAt: Long? = null,
    /** What is carried: the sums, the goals, or both. */
    val mergeSum: Boolean = true,
    val mergeGoal: Boolean = false,
    /** Items with the same name (or path) in the account get the values added / subtracted. */
    val mergeSameItems: Boolean = true,
    /** Items with no match in the account are created there. */
    val mergeTransferAll: Boolean = true,
    /** Match by the whole path group / item instead of the item name only (the name is the default, user 2026-09-27). */
    val mergeByPath: Boolean = false,
    /**
     * D339: a [com.notio.shared.domain.MergeRepeat] name — the merge happens again and again from [mergeAt] on, and the note stays. [mergeDays] are
     * the days of a weekly one (one bit per day, Monday = 1 ... Sunday = 64), [mergeDay] the day of the month of a monthly or yearly one
     * (0 = the day of [mergeAt]; kept apart, because the 31st merges on the 30th in April and must come back to the 31st in May).
     */
    val mergeRepeat: String = "NONE",
    val mergeDays: Int = 0,
    val mergeDay: Int = 0,
    /** D340: names that differ only by case are one item / one group (as it always was); off, they are two. */
    val mergeIgnoreItemCase: Boolean = true,
    val mergeIgnoreGroupCase: Boolean = true,
    /** Android D272: the sums and / or the goals show as stars on the card and in the note until its eye is pressed. */
    val hideSums: Boolean = false,
    val hideGoals: Boolean = false,
) {
    /** The sort in use: a budget without goals (D326) is not ordered by a goal, a remainder or a progress it does not show. */
    val sortKey: BudgetSortKey get() = BudgetSortKey.of(sort).let { if (hasGoals || it !in GOAL_SORTS) it else BudgetSortKey.MANUAL }

    val budgetKind: com.notio.shared.domain.BudgetKind get() = com.notio.shared.domain.BudgetKind.of(kind)

    val repeat: com.notio.shared.domain.MergeRepeat get() = com.notio.shared.domain.MergeRepeat.of(mergeRepeat)

    /** The time after [mergeAt] of a merge that repeats (D339); `null` when it does not, or has no time. */
    fun nextMergeAfter(after: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? =
        mergeAt?.let { com.notio.shared.domain.MergeSchedule.next(repeat, mergeDays, mergeDay, it, after, zone) }

    /** D326: only an account has goals and a remainder; an income and an expense are sums alone. */
    val hasGoals: Boolean get() = budgetKind == com.notio.shared.domain.BudgetKind.ACCOUNT

    fun toExtras(): String = TransferFormat.json.encodeToString(serializer(), this)

    companion object {
        val Default = BudgetLayout()

        /** The sorts that need a goal (D326). */
        val GOAL_SORTS = setOf(BudgetSortKey.GOAL, BudgetSortKey.REMAINDER, BudgetSortKey.PROGRESS)

        fun parse(extras: String?): BudgetLayout {
            if (extras.isNullOrBlank()) return Default
            return try {
                TransferFormat.json.decodeFromString(serializer(), extras)
            } catch (_: Exception) {
                Default
            }
        }
    }
}

/** What a row of the budget list shows for its node. */
enum class BudgetRowPart {
    /** Name and aggregates together (the classic card). */
    FULL,

    /** Name only; the aggregates come in a separate [TOTAL] row below the group's items. */
    HEADER,

    /** Aggregates only, labelled as the total of the group (or of the whole budget). */
    TOTAL,
}

/** A row on screen: [depth] is 0 for the root. */
data class BudgetRowItem(
    val node: BudgetNode,
    val depth: Int,
    val hasChildren: Boolean,
    val part: BudgetRowPart = BudgetRowPart.FULL,
) {
    val key: String get() = node.id + ":" + part.name
}

/** The order of the rows of a group for a sort by value (D178); `null` for [BudgetSortKey.MANUAL]. Ties are ordered by name. */
fun budgetComparator(key: BudgetSortKey, descending: Boolean): Comparator<BudgetNode>? {
    val main: Comparator<BudgetNode> = when (key) {
        BudgetSortKey.MANUAL -> return null
        BudgetSortKey.NAME -> valueComparator(descending) { it.title.trim().lowercase() }
        BudgetSortKey.CREATED -> valueComparator(descending) { it.createdAt }
        BudgetSortKey.CHANGED -> valueComparator(descending) { it.updatedAt }
        BudgetSortKey.SUM -> valueComparator(descending) { it.cachedSumMinor }
        BudgetSortKey.GOAL -> valueComparator(descending) { it.cachedGoalMinor }
        BudgetSortKey.REMAINDER -> valueComparator(descending) { it.cachedRemainderMinor }
        BudgetSortKey.PROGRESS -> valueComparator(descending) { node ->
            // Ten thousandths of the goal reached; a row with no goal has no progress and comes last.
            if (node.cachedGoalMinor > 0) node.cachedSumMinor * 10_000 / node.cachedGoalMinor else null
        }
    }
    return main.thenComparing(valueComparator<BudgetNode>(false) { it.title.trim().lowercase() }).thenComparing(compareBy<BudgetNode> { it.sortOrder })
}

/**
 * The ids of the children of [parentId] in the order they are SHOWN (D176): by their place, and reversed when the layout lists the
 * newest first.
 */
fun displayedChildren(nodes: List<BudgetNode>, parentId: String, newestFirst: Boolean): List<String> {
    val sorted = nodes.filter { it.parentId == parentId }.sortedBy { it.sortOrder }.map { it.id }
    return if (newestFirst) sorted.asReversed() else sorted
}

/** The order to store for children shown as [displayedIds] (the first stored is the first entered, so a newest-first list is reversed). */
fun storageOrder(displayedIds: List<String>, newestFirst: Boolean): List<String> =
    if (newestFirst) displayedIds.asReversed() else displayedIds

/** The nodes with the children of [parentId] numbered so that they are SHOWN as [displayedIds], for the picture while an item is dragged. */
fun withDisplayedOrder(nodes: List<BudgetNode>, parentId: String, displayedIds: List<String>, newestFirst: Boolean): List<BudgetNode> {
    val place = storageOrder(displayedIds, newestFirst).withIndex().associate { it.value to it.index }
    return nodes.map { node -> if (node.parentId == parentId) place[node.id]?.let { node.copy(sortOrder = it) } ?: node else node }
}

/**
 * Flattens the tree depth-first following [layout]; collapsed groups hide their items. The groups named in [collapse] are shown
 * closed whatever they are (the picture while an item is dragged: each brother is then one row); those in [open] are shown open
 * without being saved so (the groups that hold a match of the search, D238).
 */
fun visibleBudgetRows(
    nodes: List<BudgetNode>,
    layout: BudgetLayout = BudgetLayout.Default,
    collapse: Set<String> = emptySet(),
    open: Set<String> = emptySet(),
): List<BudgetRowItem> {
    val byParent = nodes.groupBy { it.parentId }
    val root = byParent[null]?.firstOrNull() ?: return emptyList()
    val rows = ArrayList<BudgetRowItem>(nodes.size)

    val byValue = budgetComparator(layout.sortKey, layout.sortDescending)

    fun children(node: BudgetNode): List<BudgetNode> {
        val kids = byParent[node.id].orEmpty()
        // A sort by value replaces the manual order (and "newest first", which is only a way to read it) inside every group.
        if (byValue != null) return kids.sortedWith(byValue)
        val sorted = kids.sortedBy { it.sortOrder }
        return if (layout.newestFirst) sorted.asReversed() else sorted
    }

    fun walk(node: BudgetNode, depth: Int) {
        val kids = children(node)
        val isRoot = depth == 0
        val bottom = if (isRoot) layout.totalAtBottom else layout.groupTotalsAtBottom
        val expanded = node.kind == BudgetNodeKind.GROUP && (node.isExpanded || node.id in open) && kids.isNotEmpty() && node.id !in collapse
        // A total row below the items only makes sense while the items are visible.
        val split = bottom && node.kind == BudgetNodeKind.GROUP && expanded
        // With the overall total at the bottom the first row is simply the first item.
        if (!(isRoot && split)) {
            rows += BudgetRowItem(node, depth, kids.isNotEmpty(), if (split) BudgetRowPart.HEADER else BudgetRowPart.FULL)
        }
        if (expanded) kids.forEach { walk(it, depth + 1) }
        if (split) rows += BudgetRowItem(node, depth, kids.isNotEmpty(), BudgetRowPart.TOTAL)
    }
    walk(root, 0)
    return rows
}

/** A match of the search in a budget (D238): the [field] (0 the name, 1 the comment as shown) of the group or item [id]. */
data class BudgetHitKey(val id: String, val field: Int)

/** The comment of a row as the row shows it, or `null` when it shows none. */
fun shownComment(node: BudgetNode, showComments: Boolean): String? =
    if (showComments) com.notio.shared.domain.Comment.short(node.comment) else null

/** The groups to open for the search: every group above a group or item whose name or shown comment has [query]. */
fun budgetSearchOpen(nodes: List<BudgetNode>, query: com.notio.shared.domain.TextPattern?, showComments: Boolean): Set<String> {
    if (query == null) return emptySet()
    val byId = nodes.associateBy { it.id }
    val open = HashSet<String>()
    for (node in nodes) {
        if (node.parentId == null) continue
        val found = query.foundIn(node.title) || shownComment(node, showComments)?.let { query.foundIn(it) } == true
        if (!found) continue
        var parent = node.parentId?.let { byId[it] }
        var guard = 0
        while (parent != null && guard++ <= nodes.size && open.add(parent.id)) parent = parent.parentId?.let { byId[it] }
    }
    return open
}
