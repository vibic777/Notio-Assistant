package com.notebudget.app.domain.model

import kotlinx.serialization.Serializable

/** What a budget note is (D237): an account, as every budget was, or an income / expense merged into an account once. */
enum class BudgetKind {
    ACCOUNT, INCOME, EXPENSE;

    /** +1 for an income, -1 for an expense (the kind gives the sign, user 2026-09-27); an account is not merged. */
    val sign: Int get() = when (this) { INCOME -> 1; EXPENSE -> -1; ACCOUNT -> 0 }

    companion object {
        fun of(name: String?): BudgetKind = entries.firstOrNull { it.name == name } ?: ACCOUNT
    }
}

/** How often an income / expense is merged (D339): once, as it always was, or again and again. */
enum class MergeRepeat {
    NONE, DAILY, WEEKLY, MONTHLY, YEARLY;

    companion object {
        fun of(name: String?): MergeRepeat = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** The times of an income / expense that repeats (D339); pure, so the calendar rules are tested. */
object MergeSchedule {
    /** How many missed times are merged in one go; the rest is let go (a clock set years ahead must not fill an account). */
    const val MAX_CATCH_UP = 60

    /**
     * The first time after [after] of a merge that was at [at]: at the time of day of [at], every day; on the days of [weekdays]
     * (one bit per day, Monday = 1 ... Sunday = 64; none = the weekday of [at]); every month on [monthDay] (0 = the day of [at]);
     * every year on that day of the month of [at]. A month that has no such day takes its last one (the 31st in April is the
     * 30th, 29 February is 28 February in other years): money of "the end of the month" does not skip a month. `null` for a
     * merge that does not repeat.
     */
    fun next(
        rule: MergeRepeat,
        weekdays: Int,
        monthDay: Int,
        at: Long,
        after: Long = at,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Long? {
        if (rule == MergeRepeat.NONE) return null
        val base = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime()
        val from = java.time.Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        val day = if (monthDay in 1..31) monthDay else base.dayOfMonth
        val mask = (weekdays and RecurrenceRules.ALL_DAYS).takeIf { it != 0 } ?: RecurrenceRules.bit(base.dayOfWeek)
        fun millis(date: java.time.LocalDate) = java.time.LocalDateTime.of(date, base.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
        fun inMonth(month: java.time.YearMonth) = month.atDay(minOf(day, month.lengthOfMonth()))
        when (rule) {
            MergeRepeat.DAILY, MergeRepeat.WEEKLY -> {
                var date = from
                repeat(9) {
                    if ((rule == MergeRepeat.DAILY || RecurrenceRules.includes(mask, date.dayOfWeek)) && millis(date) > after) return millis(date)
                    date = date.plusDays(1)
                }
            }
            MergeRepeat.MONTHLY -> {
                var month = java.time.YearMonth.from(from)
                repeat(3) {
                    millis(inMonth(month)).let { if (it > after) return it }
                    month = month.plusMonths(1)
                }
            }
            MergeRepeat.YEARLY -> {
                var year = from.year
                repeat(3) {
                    millis(inMonth(java.time.YearMonth.of(year, base.month))).let { if (it > after) return it }
                    year++
                }
            }
            MergeRepeat.NONE -> Unit
        }
        return null
    }
}

/** How an income / expense is merged into its account (D237). */
data class MergeOptions(
    val sign: Int,
    val sum: Boolean = true,
    val goal: Boolean = false,
    val sameItems: Boolean = true,
    val transferAll: Boolean = true,
    val byPath: Boolean = false,
    /** D340: "Cash" and "cash" are one item / one group; off, they are two. The spaces around a name never count. */
    val ignoreItemCase: Boolean = true,
    val ignoreGroupCase: Boolean = true,
)

/** What a merge did to the account: the nodes of the account after it, and how many items were changed and created. */
data class MergePlan(val nodes: List<BudgetNode>, val changed: Int, val created: Int) {
    val empty: Boolean get() = changed == 0 && created == 0
}

/**
 * Merges the items of an income / expense budget into an account budget (D237). Pure: the nodes of both go in, the account's
 * nodes as they must be after the merge come out (their totals recalculated by the caller).
 *
 * Every item (leaf) of the source counts with its whole value (the piece times its quantity) and its sign. An item of the account
 * that matches — by name (spaces around ignored; the case too unless [MergeOptions.ignoreItemCase] / [MergeOptions.ignoreGroupCase] is off,
 * D340) or by the path of group names and the name — gets the value added to
 * its sum and / or goal ([MergeOptions.sum], [MergeOptions.goal]) when [MergeOptions.sameItems] is on; an account item of more
 * than one piece becomes one piece of its whole value first, so the addition stays exact. An item with no match is created in the
 * account when [MergeOptions.transferAll] is on: at the root when matching by name, under the same groups (made where missing)
 * when matching by path.
 */
object BudgetMergePlanner {

    fun plan(
        source: List<BudgetNode>,
        target: List<BudgetNode>,
        options: MergeOptions,
        newId: () -> String,
        now: Long,
    ): MergePlan {
        val targetRoot = target.firstOrNull { it.parentId == null } ?: return MergePlan(target, 0, 0)
        val result = LinkedHashMap<String, BudgetNode>().apply { target.forEach { put(it.id, it) } }
        val srcById = source.associateBy { it.id }
        var changed = 0
        var created = 0

        fun itemKey(text: String) = text.trim().let { if (options.ignoreItemCase) it.lowercase() else it }
        fun groupKey(text: String) = text.trim().let { if (options.ignoreGroupCase) it.lowercase() else it }

        /** The group names from the root (not included) down to the parent of [node]. */
        fun pathOf(node: BudgetNode, byId: Map<String, BudgetNode>): List<String> {
            val names = ArrayList<String>()
            var parent = node.parentId?.let { byId[it] }
            var guard = 0
            while (parent != null && parent.parentId != null && guard++ < byId.size) {
                names.add(0, parent.title)
                parent = parent.parentId?.let { byId[it] }
            }
            return names
        }

        fun leavesOfTarget() = result.values.filter { it.kind == BudgetNodeKind.LEAF }

        fun findMatch(item: BudgetNode): BudgetNode? {
            val name = itemKey(item.title)
            val candidates = leavesOfTarget().filter { itemKey(it.title) == name }
            if (!options.byPath) return candidates.firstOrNull()
            val wanted = pathOf(item, srcById).map(::groupKey)
            return candidates.firstOrNull { c -> pathOf(c, result).map(::groupKey) == wanted }
        }

        /** The group of the account for [path] (group names from the root), made where missing. */
        fun groupFor(path: List<String>): BudgetNode {
            var parent = targetRoot
            for (name in path) {
                val existing = result.values.firstOrNull { it.parentId == parent.id && it.kind == BudgetNodeKind.GROUP && groupKey(it.title) == groupKey(name) }
                parent = existing ?: BudgetNode(
                    id = newId(), noteId = targetRoot.noteId, parentId = parent.id, title = name.trim(), kind = BudgetNodeKind.GROUP,
                    manualSumMinor = 0, manualGoalMinor = 0, comment = null, cachedSumMinor = 0, cachedGoalMinor = 0, cachedRemainderMinor = 0,
                    isExpanded = true, sortOrder = nextOrder(result.values, parent.id), createdAt = now, updatedAt = now,
                ).also { result[it.id] = it; created++ }
            }
            return parent
        }

        val items = source.filter { it.kind == BudgetNodeKind.LEAF }
        for (item in items) {
            val sumDelta = if (options.sum) item.manualSumMinor * item.quantity * options.sign else 0L
            val goalDelta = if (options.goal) item.manualGoalMinor * item.quantity * options.sign else 0L
            val match = if (options.sameItems) findMatch(item) else null
            if (match != null) {
                if (sumDelta == 0L && goalDelta == 0L) continue
                val whole = if (match.quantity > 1) match.copy(
                    manualSumMinor = match.manualSumMinor * match.quantity,
                    manualGoalMinor = match.manualGoalMinor * match.quantity,
                    quantity = 1,
                ) else match
                result[match.id] = whole.copy(
                    manualSumMinor = whole.manualSumMinor + sumDelta,
                    manualGoalMinor = whole.manualGoalMinor + goalDelta,
                    updatedAt = now,
                )
                changed++
            } else if (options.transferAll) {
                val parent = if (options.byPath) groupFor(pathOf(item, srcById)) else targetRoot
                val node = BudgetNode(
                    id = newId(), noteId = targetRoot.noteId, parentId = parent.id, title = item.title.trim(), kind = BudgetNodeKind.LEAF,
                    manualSumMinor = sumDelta, manualGoalMinor = goalDelta, comment = item.comment, cachedSumMinor = 0, cachedGoalMinor = 0,
                    cachedRemainderMinor = 0, isExpanded = true, sortOrder = nextOrder(result.values, parent.id), createdAt = now, updatedAt = now,
                )
                result[node.id] = node
                created++
            }
        }
        return MergePlan(result.values.toList(), changed, created)
    }

    private fun nextOrder(nodes: Collection<BudgetNode>, parentId: String): Int =
        (nodes.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
}

/**
 * What a merge leaves in the log of the account (D237), so that it can be undone from there: the note that was merged, and the
 * account's nodes as they were just before.
 */
@Serializable
data class MergeRecord(
    val sourceNoteId: String,
    val sourceTitle: String,
    val kind: String,
    val nodes: List<Node>,
) {
    @Serializable
    data class Node(
        val id: String,
        val parentId: String? = null,
        val title: String,
        val kind: String,
        val sum: Long = 0,
        val goal: Long = 0,
        val comment: String? = null,
        val expanded: Boolean = true,
        val order: Int = 0,
        val quantity: Int = 1,
        val createdAt: Long = 0,
        val updatedAt: Long = 0,
    )

    fun encode(): String = JSON.encodeToString(serializer(), this)

    /** The account's nodes as they were (totals recalculated by the caller). */
    fun toNodes(noteId: String): List<BudgetNode> = nodes.map { n ->
        BudgetNode(
            id = n.id, noteId = noteId, parentId = n.parentId, title = n.title,
            kind = runCatching { BudgetNodeKind.valueOf(n.kind) }.getOrDefault(BudgetNodeKind.LEAF),
            manualSumMinor = n.sum, manualGoalMinor = n.goal, comment = n.comment, cachedSumMinor = 0, cachedGoalMinor = 0,
            cachedRemainderMinor = 0, isExpanded = n.expanded, sortOrder = n.order, createdAt = n.createdAt, updatedAt = n.updatedAt, quantity = n.quantity,
        )
    }

    companion object {
        private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun of(sourceNoteId: String, sourceTitle: String, kind: BudgetKind, account: List<BudgetNode>) = MergeRecord(
            sourceNoteId, sourceTitle, kind.name,
            account.map { Node(it.id, it.parentId, it.title, it.kind.name, it.manualSumMinor, it.manualGoalMinor, it.comment, it.isExpanded, it.sortOrder, it.quantity, it.createdAt, it.updatedAt) },
        )

        fun decode(text: String?): MergeRecord? = text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * What a deletion leaves in the log of the budget (D359), so that it can be put back from there: the deleted item or group and
 * everything that was inside it, as they were. It stands in the detail of the DELETED line after a line break.
 */
@Serializable
data class DeletedRecord(val nodes: List<MergeRecord.Node>) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    /** The deleted nodes as they were (totals recalculated by the caller). */
    fun toNodes(noteId: String): List<BudgetNode> = MergeRecord("", "", "", nodes).toNodes(noteId)

    companion object {
        private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun of(deleted: List<BudgetNode>) = DeletedRecord(
            deleted.map { MergeRecord.Node(it.id, it.parentId, it.title, it.kind.name, it.manualSumMinor, it.manualGoalMinor, it.comment, it.isExpanded, it.sortOrder, it.quantity, it.createdAt, it.updatedAt) },
        )

        /** From the detail of a DELETED line; `null` when the line kept nothing (made before) or cannot be read. */
        fun decode(detail: String?): DeletedRecord? =
            detail?.substringAfter('\n', "")?.takeIf { it.isNotEmpty() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** The items of a budget for a choice among them (D330). */
object BudgetItems {
    /**
     * Every item but [exceptId], each with the names of the groups it is in from the top down (none for an item right under
     * the total), in the order of the names.
     */
    fun others(nodes: List<BudgetNode>, exceptId: String): List<Pair<BudgetNode, List<String>>> {
        val byId = nodes.associateBy { it.id }
        fun path(node: BudgetNode): List<String> {
            val names = ArrayList<String>()
            var parent = node.parentId?.let { byId[it] }
            var guard = 0
            while (parent != null && parent.parentId != null && guard++ < nodes.size) {
                names.add(0, parent.title)
                parent = parent.parentId?.let { byId[it] }
            }
            return names
        }
        return nodes.filter { it.kind == BudgetNodeKind.LEAF && it.id != exceptId }
            .map { it to path(it) }
            .sortedWith(compareBy({ it.first.title.trim().lowercase() }, { it.second.joinToString("/").lowercase() }))
    }
}
