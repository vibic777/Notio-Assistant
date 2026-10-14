package com.notio.shared.data

import com.notio.shared.AppLog
import com.notio.shared.domain.BudgetCalculator
import com.notio.shared.domain.Clock
import com.notio.shared.domain.IdGenerator
import com.notio.shared.domain.BudgetEditor
import com.notio.shared.domain.BudgetKind
import com.notio.shared.domain.BudgetLayout
import com.notio.shared.domain.BudgetLogEntry
import com.notio.shared.domain.BudgetLogKind
import com.notio.shared.domain.BudgetMergePlanner
import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.DomainResult
import com.notio.shared.domain.MergeOptions
import com.notio.shared.domain.MergeRecord
import com.notio.shared.domain.MergeRepeat
import com.notio.shared.domain.MergeSchedule
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType

/** What a merge came to. */
sealed interface MergeOutcome {
    data class Done(val targetTitle: String, val changed: Int, val created: Int) : MergeOutcome
    /** Not an income / expense, or already in the trash. */
    data object NotMergeable : MergeOutcome
    /** No account chosen: nothing happens. */
    data object NoTarget : MergeOutcome
    /** The chosen account is gone or is not an account any more. */
    data object TargetMissing : MergeOutcome
    /** Asked for as due, and its time has not come (or was merged a moment ago by the other caller). */
    data object NotDue : MergeOutcome
}

/**
 * Merges an income / expense budget into its account (Android D237's `BudgetMergeService` on the desktop database): at its time (the
 * timer of the running Notio, and at every start for a time that passed while Notio was closed) or at once with "Merge now". The
 * account changes in one go, its log gets a line that can undo the merge, the income / expense gets a line too and goes to the trash
 * (it can be taken back and set up again). One merge at a time.
 */
class BudgetMergeService(
    private val db: NotioDatabase,
    private val editor: NotioEditor,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val budgets = DbBudgets(db)
    private val budgetEditor = BudgetEditor(budgets, Clock { now() }, IdGenerator.Random, budgets)

    /**
     * [due]: its time has come (the timer, a start) — a merge that repeats (D119) then moves to its next time; "Merge now" leaves
     * the plan of such a merge as it is. A merge that does not repeat is done once either way: its time is cleared, it goes to the trash.
     */
    @Synchronized
    fun merge(sourceNoteId: String, due: Boolean = false): MergeOutcome {
        val source = db.note(sourceNoteId)?.takeIf { it.type == NoteType.BUDGET && it.deletedAt == null } ?: return MergeOutcome.NotMergeable
        val layout = BudgetLayout.parse(source.extras)
        val kind = layout.budgetKind
        if (kind == BudgetKind.ACCOUNT) return MergeOutcome.NotMergeable
        // Asked inside the lock: the timer and a start may both come for the same time, and it is merged once.
        if (due && layout.mergeAt?.let { it <= now() } != true) return MergeOutcome.NotDue
        val targetId = layout.mergeTarget ?: return MergeOutcome.NoTarget
        val target = db.note(targetId)
            ?.takeIf { it.type == NoteType.BUDGET && it.deletedAt == null && it.id != source.id && BudgetLayout.parse(it.extras).budgetKind == BudgetKind.ACCOUNT }
            ?: return MergeOutcome.TargetMissing

        val at = now()
        val before = budgets.getNodes(target.id)
        val plan = BudgetMergePlanner.plan(
            source = budgets.getNodes(source.id),
            target = before,
            options = MergeOptions(
                // An income and an expense have no goals (D326): only the sums are carried.
                sign = kind.sign, sum = layout.mergeSum, goal = layout.mergeGoal && layout.hasGoals,
                sameItems = layout.mergeSameItems, transferAll = layout.mergeTransferAll, byPath = layout.mergeByPath,
                ignoreItemCase = layout.mergeIgnoreItemCase, ignoreGroupCase = layout.mergeIgnoreGroupCase,
            ),
            newId = newId,
            now = at,
        )
        val after = BudgetCalculator.recalculateAll(plan.nodes)
        val old = before.associateBy { it.id }
        val upserts = parentsFirst(after).filter { old[it.id] != it }
        db.transaction {
            if (upserts.isNotEmpty()) budgets.applyChanges(upserts, emptyList())
            budgets.add(
                listOf(
                    BudgetLogEntry(
                        newId(), target.id, at, BudgetLogKind.MERGED, source.title,
                        oldValue = plan.changed.toLong(), newValue = plan.created.toLong(),
                        detail = MergeRecord.of(source.id, source.title, kind, before).encode(),
                    ),
                    BudgetLogEntry(newId(), source.id, at, BudgetLogKind.MERGED_INTO, target.title),
                ),
            )
        }
        db.note(target.id)?.let { db.upsertNote(it.copy(updatedAt = at)) }
        if (layout.repeat == com.notio.shared.domain.MergeRepeat.NONE) {
            // Done once: the time is cleared, so taking the note back from the trash does not merge it again by itself.
            editor.saveNote(source.copy(extras = layout.copy(mergeAt = null).toExtras()), withExtras = true)
            editor.trashNote(source.id)
        } else if (due) {
            // It repeats (D119): the note stays; its next time is counted from the one that was due, so times missed while Notio
            // was closed are still due and are merged one by one.
            editor.saveNote(source.copy(extras = layout.copy(mergeAt = layout.mergeAt?.let { layout.nextMergeAfter(it) }).toExtras()), withExtras = true)
        }
        AppLog.i("budget", "merged a " + kind.name.lowercase() + " into an account: " + plan.changed + " items changed, " + plan.created + " made")
        return MergeOutcome.Done(target.title, plan.changed, plan.created)
    }

    /**
     * Undoes a merge from the log of the account: the account's items as they were before it, the income / expense back from the
     * trash (its time stays cleared: it is set again by hand). [entry] is the MERGED line of the account's log.
     */
    @Synchronized
    fun undo(entry: BudgetLogEntry): Boolean {
        if (entry.kind != BudgetLogKind.MERGED) return false
        val record = MergeRecord.decode(entry.detail) ?: return false
        val snapshot = BudgetCalculator.recalculateAll(record.toNodes(entry.noteId))
        val restored = budgetEditor.restoreNodes(entry.noteId, snapshot, BudgetLogKind.MERGE_UNDONE, record.sourceTitle, entry.id)
        if (restored !is DomainResult.Success) return false
        runCatching { editor.restoreNote(record.sourceNoteId) }
        AppLog.i("budget", "a merge was undone")
        return true
    }

    /** Has the MERGED line [entry] been undone already? */
    fun undone(entry: BudgetLogEntry, all: List<BudgetLogEntry>): Boolean =
        all.any { it.kind == BudgetLogKind.MERGE_UNDONE && it.detail == entry.id }

    /** Merges every income / expense whose time has come (at each start, and by the timer). */
    fun runDue(): Int {
        var done = 0
        mergeable().forEach { (note, _) ->
            var times = 0
            // A merge that repeats (D119) may have several times behind it: each is merged, the oldest first.
            while (times < MergeSchedule.MAX_CATCH_UP) {
                val layout = BudgetLayout.parse(db.note(note.id)?.takeIf { it.deletedAt == null }?.extras)
                if (layout.mergeTarget == null || layout.mergeAt?.let { it <= now() } != true) break
                val outcome = runCatching { merge(note.id, due = true) }.onFailure { AppLog.e("budget", "a merge failed", it) }.getOrNull()
                if (outcome !is MergeOutcome.Done) break
                done++
                times++
                if (layout.repeat == MergeRepeat.NONE) break
            }
            if (times == MergeSchedule.MAX_CATCH_UP) letGo(note.id)
        }
        return done
    }

    /** More times were missed than are merged in one go: the rest is let go, the next one is the first after now. */
    private fun letGo(noteId: String) {
        val note = db.note(noteId) ?: return
        val layout = BudgetLayout.parse(note.extras)
        if (layout.mergeAt?.let { it <= now() } != true) return
        editor.saveNote(note.copy(extras = layout.copy(mergeAt = layout.nextMergeAfter(now())).toExtras()), withExtras = true)
        AppLog.w("budget", "more than " + MergeSchedule.MAX_CATCH_UP + " missed merges: the rest was let go")
    }

    /** The next time a merge is due (the timer waits for it), or `null`. */
    fun nextDue(): Long? = mergeable().mapNotNull { (_, layout) -> layout.mergeAt?.takeIf { layout.mergeTarget != null } }.minOrNull()

    /** The accounts an income / expense can be merged into: every budget of kind Account, by title. */
    fun accounts(except: String): List<Note> =
        liveNotes().filter { it.type == NoteType.BUDGET && it.id != except && BudgetLayout.parse(it.extras).budgetKind == BudgetKind.ACCOUNT }
            .sortedBy { it.title.lowercase() }

    private fun mergeable(): List<Pair<Note, BudgetLayout>> =
        liveNotes().filter { it.type == NoteType.BUDGET }.map { it to BudgetLayout.parse(it.extras) }.filter { it.second.budgetKind != BudgetKind.ACCOUNT }

    private fun liveNotes(): List<Note> {
        val trashedFolders = db.folders().filter { it.deletedAt != null }.mapTo(HashSet()) { it.id }
        return db.notes().filter { it.deletedAt == null && it.folderId !in trashedFolders }
    }

    /** [nodes] ordered so that every node comes after its parent. */
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
}
