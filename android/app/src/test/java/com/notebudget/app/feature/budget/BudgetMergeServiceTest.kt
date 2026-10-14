package com.notebudget.app.feature.budget

import com.notebudget.app.domain.model.BudgetKind
import com.notebudget.app.domain.model.BudgetLogKind
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.TestEnv
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The merge of an income / expense into an account, its alarm and its undo (D237). */
class BudgetMergeServiceTest {
    private val env = TestEnv()
    private val alarms = mutableMapOf<String, Long>()
    private val scheduler = object : MergeScheduler {
        override fun schedule(noteId: String, atMillis: Long) { alarms[noteId] = atMillis }
        override fun cancel(noteId: String) { alarms.remove(noteId) }
    }
    private var ids = 0
    private val service = BudgetMergeService(
        env.noteRepo, env.budgetRepo, env.logRepo, env.notes, env.budget, scheduler,
        now = { env.clock.current }, newId = { "m-" + ids++ },
    )

    private suspend fun budget(title: String, vararg items: Pair<String, Long>): String {
        val folder = env.folderRepo.getAll().firstOrNull()?.id
            ?: (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value.id
        val id = (env.notes.create(folder, title, NoteType.BUDGET) as DomainResult.Success).value.id
        val root = env.budgetRepo.getNodes(id).first { it.parentId == null }.id
        items.forEach { (name, sum) -> env.budget.addNode(id, root, name, BudgetNodeKind.LEAF, sumMinor = sum) }
        return id
    }

    private suspend fun setLayout(noteId: String, change: (BudgetLayout) -> BudgetLayout) {
        val note = env.noteRepo.getById(noteId)!!
        env.notes.updateExtras(noteId, change(BudgetLayout.parse(note.extras)).toExtras())
        service.sync(noteId)
    }

    private suspend fun sums(noteId: String): Map<String, Long> =
        env.budgetRepo.getNodes(noteId).filter { it.kind == BudgetNodeKind.LEAF }.associate { it.title to it.manualSumMinor }

    @Test
    fun `an expense is taken from the account and goes to the trash`() = runTest {
        val account = budget("Wallet", "Food" to 1000, "Rent" to 5000)
        val expense = budget("Shop", "food" to 300, "Toys" to 200)
        setLayout(expense) { it.copy(kind = BudgetKind.EXPENSE.name, mergeTarget = account) }

        val outcome = service.merge(expense)

        assertEquals(MergeOutcome.Done("Wallet", 1, 1), outcome)
        assertEquals(mapOf("Food" to 700L, "Rent" to 5000L, "Toys" to -200L), sums(account))
        assertNull(env.noteRepo.getById(expense))
        assertEquals(BudgetLogKind.MERGED, env.logRepo.getByNote(account).first().kind)
        assertEquals(BudgetLogKind.MERGED_INTO, env.logRepo.getByNote(expense).first().kind)
    }

    @Test
    fun `nothing happens without an account, or when it is gone`() = runTest {
        val income = budget("Salary", "Work" to 100)
        setLayout(income) { it.copy(kind = BudgetKind.INCOME.name) }
        assertEquals(MergeOutcome.NoTarget, service.merge(income))

        setLayout(income) { it.copy(mergeTarget = "missing") }
        assertEquals(MergeOutcome.TargetMissing, service.merge(income))
        assertNotNull(env.noteRepo.getById(income))

        val account = budget("Wallet")
        assertEquals(MergeOutcome.NotMergeable, service.merge(account))
    }

    @Test
    fun `undo from the account's log puts the items and the income back`() = runTest {
        val account = budget("Wallet", "Work" to 1000)
        val income = budget("Salary", "Work" to 500, "Bonus" to 50)
        setLayout(income) { it.copy(kind = BudgetKind.INCOME.name, mergeTarget = account) }
        service.merge(income)
        assertEquals(mapOf("Work" to 1500L, "Bonus" to 50L), sums(account))

        val merged = env.logRepo.getByNote(account).first { it.kind == BudgetLogKind.MERGED }
        assertTrue(service.undo(merged))

        assertEquals(mapOf("Work" to 1000L), sums(account))
        assertNotNull(env.noteRepo.getById(income))
        assertTrue(service.undone(merged, env.logRepo.getByNote(account)))
        // Taken back, it does not merge again by itself: its time was cleared.
        assertNull(BudgetLayout.parse(env.noteRepo.getById(income)!!.extras).mergeAt)
    }

    @Test
    fun `the alarm follows the settings and a due merge runs at start`() = runTest {
        val account = budget("Wallet", "Work" to 0)
        val income = budget("Salary", "Work" to 70)
        val at = env.clock.current + 60_000
        setLayout(income) { it.copy(kind = BudgetKind.INCOME.name, mergeTarget = account, mergeAt = at) }
        assertEquals(at, alarms[income])

        assertEquals(0, service.runDue())
        env.clock.current = at
        assertEquals(1, service.runDue())

        assertEquals(mapOf("Work" to 70L), sums(account))
        assertFalse(alarms.containsKey(income))
    }

    @Test
    fun `after a restart only a merge to come gets an alarm, a due one that cannot be done gets none`() = runTest {
        val account = budget("Wallet", "Work" to 0)
        val later = budget("Salary", "Work" to 70)
        val stuck = budget("Bonus", "Work" to 5)
        val at = env.clock.current + 60_000
        setLayout(later) { it.copy(kind = BudgetKind.INCOME.name, mergeTarget = account, mergeAt = at) }
        // Its account is gone and its time passed: an alarm for it would go off at once, every time, for nothing (D345).
        setLayout(stuck) { it.copy(kind = BudgetKind.INCOME.name, mergeTarget = "missing", mergeAt = env.clock.current - 1) }
        alarms.clear()

        service.rescheduleAll()

        assertEquals(mapOf(later to at), alarms)
        assertEquals(0, service.runDue())
    }

    @Test
    fun `only accounts can be chosen, never the note itself`() = runTest {
        val a = budget("B account")
        val b = budget("A account")
        val income = budget("Salary")
        setLayout(income) { it.copy(kind = BudgetKind.INCOME.name) }
        assertEquals(listOf("A account", "B account"), service.accounts(income).map { it.title })
        assertEquals(listOf(b), service.accounts(a).map { it.id })
    }

    private val day = 86_400_000L

    @Test
    fun `an income that repeats stays, moves to its next time and catches up the missed ones`() = runTest {
        val account = budget("Wallet", "Salary" to 0)
        val income = budget("Job", "salary" to 5000)
        env.clock.current = 10 * day
        setLayout(income) {
            it.copy(kind = BudgetKind.INCOME.name, mergeTarget = account, mergeAt = 10 * day, mergeRepeat = com.notebudget.app.domain.model.MergeRepeat.DAILY.name)
        }
        suspend fun planned() = BudgetLayout.parse(env.noteRepo.getById(income)!!.extras).mergeAt
        assertEquals(10 * day, alarms[income])

        assertEquals(1, service.runDue())
        assertEquals(mapOf("Salary" to 5000L), sums(account))
        assertNull("it stays in its folder", env.noteRepo.getById(income)!!.deletedAt)
        assertEquals(11 * day, planned())
        assertEquals("the alarm waits for the next time", 11 * day, alarms[income])
        assertEquals("the same time is not merged twice", 0, service.runDue())
        assertEquals(MergeOutcome.NotDue, service.merge(income, due = true))

        // The phone was off for three days: every missed time is merged, the oldest first; the alarm is set for a time to come.
        env.clock.current = 13 * day + 5
        assertEquals(3, service.runDue())
        assertEquals(mapOf("Salary" to 20_000L), sums(account))
        assertEquals(14 * day, planned())
        assertEquals(14 * day, alarms[income])

        // "Merge now" merges at once and leaves the plan as it is.
        assertTrue(service.merge(income) is MergeOutcome.Done)
        assertEquals(mapOf("Salary" to 25_000L), sums(account))
        assertEquals(14 * day, planned())
        assertNull(env.noteRepo.getById(income)!!.deletedAt)
        assertEquals(5, env.logRepo.getByNote(account).count { it.kind == BudgetLogKind.MERGED })
    }

    @Test
    fun `more missed times than are merged in one go are let go, and the case of the names counts when its tick is off`() = runTest {
        val account = budget("Wallet", "Касса" to 0)
        val income = budget("Job", "касса" to 1)
        setLayout(income) {
            it.copy(kind = BudgetKind.INCOME.name, mergeTarget = account, mergeAt = day, mergeRepeat = com.notebudget.app.domain.model.MergeRepeat.DAILY.name, mergeIgnoreItemCase = false)
        }
        env.clock.current = 1000 * day + 7
        val max = com.notebudget.app.domain.model.MergeSchedule.MAX_CATCH_UP
        assertEquals(max, service.runDue())
        // "касса" is not "Касса": an item of its own was made and takes them all.
        assertEquals(mapOf("Касса" to 0L, "касса" to max.toLong()), sums(account))
        assertTrue(BudgetLayout.parse(env.noteRepo.getById(income)!!.extras).mergeAt!! > env.clock.current)
        assertTrue(alarms[income]!! > env.clock.current)
        assertEquals(0, service.runDue())
    }
}
