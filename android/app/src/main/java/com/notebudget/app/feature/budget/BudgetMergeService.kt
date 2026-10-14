package com.notebudget.app.feature.budget

import com.notebudget.app.core.common.AppLog
import com.notebudget.app.domain.model.BudgetKind
import com.notebudget.app.domain.model.BudgetLogEntry
import com.notebudget.app.domain.model.BudgetLogKind
import com.notebudget.app.domain.model.BudgetLogRepository
import com.notebudget.app.domain.model.BudgetMergePlanner
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.MergeOptions
import com.notebudget.app.domain.model.MergeRecord
import com.notebudget.app.domain.model.MergeRepeat
import com.notebudget.app.domain.model.MergeSchedule
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.repository.BudgetRepository
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.usecase.BudgetCalculator
import com.notebudget.app.domain.usecase.BudgetEditor
import com.notebudget.app.domain.usecase.NoteUseCases
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Wakes the phone at the time of a merge (D237). The Android side is an exact alarm, like a reminder's. */
interface MergeScheduler {
    fun schedule(noteId: String, atMillis: Long)
    fun cancel(noteId: String)
}

/** What a merge came to. */
sealed interface MergeOutcome {
    data class Done(val targetTitle: String, val changed: Int, val created: Int) : MergeOutcome
    /** Not an income / expense, or already in the trash. */
    data object NotMergeable : MergeOutcome
    /** No account chosen: nothing happens (user, 2026-09-27). */
    data object NoTarget : MergeOutcome
    /** The chosen account is gone or is not an account any more. */
    data object TargetMissing : MergeOutcome
    /** Asked for as due, and its time has not come (or was merged a moment ago by the other caller). */
    data object NotDue : MergeOutcome
}

/**
 * Merges an income / expense budget into its account (D237): at its time (an alarm; at the next start when the phone was off), or
 * at once with "Merge now". The account changes in one go, its log gets a line that can undo the merge, the income / expense note
 * gets a line too and goes to the trash (it can be taken back and set up again). One merge at a time.
 */
class BudgetMergeService(
    private val notes: NoteRepository,
    private val budgets: BudgetRepository,
    private val log: BudgetLogRepository,
    private val noteUseCases: NoteUseCases,
    private val editor: BudgetEditor,
    private val scheduler: MergeScheduler?,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val lock = Mutex()

    /**
     * [due]: its time has come (the alarm, a start) — a merge that repeats (D339) then moves to its next time; "Merge now" leaves
     * the plan of such a merge as it is. A merge that does not repeat is done once either way: its time is cleared, it goes to the trash.
     */
    suspend fun merge(sourceNoteId: String, due: Boolean = false): MergeOutcome = lock.withLock {
        val source = notes.getById(sourceNoteId)?.takeIf { it.type == NoteType.BUDGET } ?: return@withLock MergeOutcome.NotMergeable
        val layout = BudgetLayout.parse(source.extras)
        val kind = layout.budgetKind
        if (kind == BudgetKind.ACCOUNT) return@withLock MergeOutcome.NotMergeable
        // Asked inside the lock: the alarm and a start may both come for the same time, and it is merged once.
        if (due && layout.mergeAt?.let { it <= now() } != true) return@withLock MergeOutcome.NotDue
        val targetId = layout.mergeTarget ?: return@withLock MergeOutcome.NoTarget
        val target = notes.getById(targetId)
            ?.takeIf { it.type == NoteType.BUDGET && it.id != source.id && BudgetLayout.parse(it.extras).budgetKind == BudgetKind.ACCOUNT }
            ?: return@withLock MergeOutcome.TargetMissing

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
        if (upserts.isNotEmpty()) budgets.applyChanges(upserts, emptyList())

        log.add(
            listOf(
                BudgetLogEntry(
                    newId(), target.id, at, BudgetLogKind.MERGED, source.title,
                    oldValue = plan.changed.toLong(), newValue = plan.created.toLong(),
                    detail = MergeRecord.of(source.id, source.title, kind, before).encode(),
                ),
            ),
        )
        log.add(listOf(BudgetLogEntry(newId(), source.id, at, BudgetLogKind.MERGED_INTO, target.title)))
        noteUseCases.touch(target.id)
        if (layout.repeat == MergeRepeat.NONE) {
            // Done once: the time is cleared, so taking the note back from the trash does not merge it again by itself.
            noteUseCases.updateExtras(source.id, layout.copy(mergeAt = null).toExtras())
            noteUseCases.delete(source.id)
            scheduler?.cancel(source.id)
        } else if (due) {
            // It repeats (D339): the note stays; its next time is counted from the one that was due, so times missed while the
            // phone was off are still due and are merged one by one (runDue). The alarm is set for a time to come only.
            val next = layout.mergeAt?.let { layout.nextMergeAfter(it) }
            noteUseCases.updateExtras(source.id, layout.copy(mergeAt = next).toExtras())
            if (next != null && next > now()) scheduler?.schedule(source.id, next) else if (next == null) scheduler?.cancel(source.id)
        }
        AppLog.i("budget", "merged a " + kind.name.lowercase() + " into an account: " + plan.changed + " items changed, " + plan.created + " made")
        MergeOutcome.Done(target.title, plan.changed, plan.created)
    }

    /**
     * Undoes a merge from the log of the account (D237): the account's items as they were before it, the income / expense back from
     * the trash (its time stays cleared: it is set again by hand). [entry] is the MERGED line of the account's log.
     */
    suspend fun undo(entry: BudgetLogEntry): Boolean = lock.withLock {
        if (entry.kind != BudgetLogKind.MERGED) return@withLock false
        val record = MergeRecord.decode(entry.detail) ?: return@withLock false
        val snapshot = BudgetCalculator.recalculateAll(record.toNodes(entry.noteId))
        val restored = editor.restoreNodes(entry.noteId, snapshot, BudgetLogKind.MERGE_UNDONE, record.sourceTitle, entry.id)
        if (restored !is com.notebudget.app.domain.model.DomainResult.Success) return@withLock false
        runCatching { noteUseCases.restore(record.sourceNoteId) }
        AppLog.i("budget", "a merge was undone")
        true
    }

    /** Has the MERGED line [entry] been undone already? */
    fun undone(entry: BudgetLogEntry, all: List<BudgetLogEntry>): Boolean =
        all.any { it.kind == BudgetLogKind.MERGE_UNDONE && it.detail == entry.id }

    /** Merges every income / expense whose time has come (at each start: the phone may have been off at that moment). */
    suspend fun runDue(): Int {
        var done = 0
        mergeable().forEach { (note, _) ->
            var times = 0
            // A merge that repeats (D339) may have several times behind it: each is merged, the oldest first.
            while (times < MergeSchedule.MAX_CATCH_UP) {
                val layout = BudgetLayout.parse(notes.getById(note.id)?.takeIf { it.deletedAt == null }?.extras)
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
    private suspend fun letGo(noteId: String) = lock.withLock {
        val note = notes.getById(noteId) ?: return@withLock
        val layout = BudgetLayout.parse(note.extras)
        if (layout.mergeAt?.let { it <= now() } != true) return@withLock
        val next = layout.nextMergeAfter(now())
        noteUseCases.updateExtras(noteId, layout.copy(mergeAt = next).toExtras())
        if (next != null) scheduler?.schedule(noteId, next) else scheduler?.cancel(noteId)
        AppLog.w("budget", "more than " + MergeSchedule.MAX_CATCH_UP + " missed merges: the rest was let go")
    }

    /** Arms the alarms of every scheduled income / expense again (after a reboot, an update, a change of time). */
    suspend fun rescheduleAll() {
        // Only a time to come (D345): an alarm set for a time that passed goes off at once, a wake-up for nothing when the merge
        // cannot be done. What is due is merged by [runDue], which the caller runs.
        val at = now()
        mergeable().forEach { (note, layout) -> layout.mergeAt?.takeIf { it > at }?.let { scheduler?.schedule(note.id, it) } }
    }

    /** After the settings of [noteId] changed: its alarm follows them. */
    suspend fun sync(noteId: String) {
        val note = notes.getById(noteId) ?: return scheduler?.cancel(noteId) ?: Unit
        val layout = BudgetLayout.parse(note.extras)
        val at = layout.mergeAt
        if (layout.budgetKind == BudgetKind.ACCOUNT || at == null) scheduler?.cancel(noteId) else scheduler?.schedule(noteId, at)
    }

    /** The accounts an income / expense can be merged into: every budget of kind Account, by title. */
    suspend fun accounts(except: String): List<Note> =
        budgets().filter { it.id != except && BudgetLayout.parse(it.extras).budgetKind == BudgetKind.ACCOUNT }
            .sortedBy { it.title.lowercase() }

    private suspend fun mergeable(): List<Pair<Note, BudgetLayout>> =
        budgets().map { it to BudgetLayout.parse(it.extras) }.filter { it.second.budgetKind != BudgetKind.ACCOUNT }

    /** The budgets outside the trash, asked for as such (D331): this runs at every start of the process, an alarm's too. */
    private suspend fun budgets(): List<Note> = notes.getActiveByType(NoteType.BUDGET)

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
