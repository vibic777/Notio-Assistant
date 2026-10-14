package com.notio.shared.domain


/**
 * One line of the log of a budget note (D166): what was added, changed, moved or deleted, and when. The log belongs to one note,
 * can be cleared and exported (TXT, PDF), and travels in a backup. It is stored as data, not as text, so it is shown (and
 * exported) in the language of the app at that moment.
 *
 * [kind] is one of [BudgetLogKind]. [oldValue] / [newValue] are the numbers of a change (`null` = not set before / now): minor
 * units for a sum and a goal, a count for a quantity. [detail] depends on the kind (see [BudgetLogKind]).
 */
data class BudgetLogEntry(
    val id: String,
    val noteId: String,
    val at: Long,
    val kind: String,
    /** The name of the item or group the line is about (for a rename, the new name). */
    val title: String,
    val oldValue: Long? = null,
    val newValue: Long? = null,
    val detail: String? = null,
    /** The item or group the line is about (Android D282); `null` in lines made before, which cannot be undone from the log. */
    val nodeId: String? = null,
)

object BudgetLogKind {
    /** Added an item; [BudgetLogEntry.detail] = the name of the group it went into ("" for the total). */
    const val ITEM_ADDED = "ITEM_ADDED"
    const val GROUP_ADDED = "GROUP_ADDED"

    /** The sum of an item changed; [BudgetLogEntry.detail] = `ADD` / `SUBTRACT` / `SET` when the Change button did it, else `null`. */
    const val SUM = "SUM"
    const val GOAL = "GOAL"
    const val QUANTITY = "QUANTITY"

    /** Renamed; the entry holds the new name, [BudgetLogEntry.detail] the old one. */
    const val RENAMED = "RENAMED"

    /** Moved to another group; [BudgetLogEntry.detail] = "from\nto" (group names, "" for the total). */
    const val MOVED = "MOVED"

    /**
     * Deleted; [BudgetLogEntry.detail] = `TREE` when a group was deleted with what was inside it, then (Android D359) a line break and the
     * [DeletedRecord] that puts it back. Lines made before kept nothing: their detail is `TREE` or `null`.
     */
    const val DELETED = "DELETED"

    const val TREE = "TREE"

    /** The Undo / Redo button of the note (D197) put the budget back as it was before / after the last change. */
    const val UNDONE = "UNDONE"
    const val REDONE = "REDONE"

    /** D237: an income / expense was merged into this account ([BudgetLogEntry.detail] = the [MergeRecord] to undo it). */
    const val MERGED = "MERGED"

    /** D237: this income / expense was merged into the account named in the title. */
    const val MERGED_INTO = "MERGED_INTO"

    /**
     * D282: a line of the log was undone from the log; [BudgetLogEntry.detail] = the id of that line, a line break, and its kind.
     * The values are the item's before and after the undo.
     */
    const val OP_UNDONE = "OP_UNDONE"

    /** The kinds of line that can be undone from the log (D282), when they know their item or group. */
    val UNDOABLE = setOf(SUM, GOAL, QUANTITY, RENAMED, ITEM_ADDED, GROUP_ADDED)

    /** D237: a merge was undone ([BudgetLogEntry.detail] = the id of the MERGED line). */
    const val MERGE_UNDONE = "MERGE_UNDONE"

    /** Android D359: what a DELETED line took away was put back from the log; [BudgetLogEntry.detail] = the id of that line. */
    const val RESTORED = "RESTORED"

    /** D330: an item was merged into another item of the same budget; the entry holds its name, [BudgetLogEntry.detail] the name of the item that stays. */
    const val ITEM_MERGED = "ITEM_MERGED"

    /** The log of a note keeps its newest lines only, so it cannot grow without end. */
    const val MAX_PER_NOTE = 2000
}

/** Where the log of the budget notes is written (the desktop database). */
interface BudgetLogRepository {
    fun add(entries: List<BudgetLogEntry>)
}
