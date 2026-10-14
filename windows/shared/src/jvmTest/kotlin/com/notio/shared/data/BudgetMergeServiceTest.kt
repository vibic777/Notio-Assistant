package com.notio.shared.data

import com.notio.shared.SequentialIds
import com.notio.shared.domain.BudgetKind
import com.notio.shared.domain.BudgetLayout
import com.notio.shared.domain.BudgetLogEntry
import com.notio.shared.domain.BudgetLogKind
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.Clock
import com.notio.shared.domain.MergeRepeat
import com.notio.shared.domain.MergeSchedule
import com.notio.shared.domain.NoteType
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The merge of an income / expense into an account and its undo (Android D237; the phone's `BudgetMergeServiceTest` cases). */
class BudgetMergeServiceTest {
    private val dir = Files.createTempDirectory("notio-merge").toFile()
    private var now = 1_000L
    private val db = NotioDatabase.inMemory()
    private val editor = NotioEditor(db, AttachmentStore(dir), Clock { now }, SequentialIds())
    private var ids = 0
    private val service = BudgetMergeService(db, editor, now = { now }, newId = { "m-" + ids++ })
    private val folder by lazy { editor.createFolder("F", "#3366FF", "SOFT").id }

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun budget(title: String, vararg items: Pair<String, Long>): String {
        val id = editor.createNote(folder, title, NoteType.BUDGET).id
        val session = BudgetSession(db, id)
        val root = session.nodes().first { it.parentId == null }.id
        items.forEach { (name, sum) -> session.add(root, name, BudgetNodeKind.LEAF, null, sum, 0, 1) }
        return id
    }

    private fun setLayout(noteId: String, change: (BudgetLayout) -> BudgetLayout) {
        val note = db.note(noteId)!!
        editor.saveNote(note.copy(extras = change(BudgetLayout.parse(note.extras)).toExtras()), withExtras = true)
    }

    private fun sums(noteId: String) = db.budgetNodesOf(noteId).filter { it.kind == BudgetNodeKind.LEAF }.associate { it.title to it.manualSumMinor }

    private fun log(noteId: String) = db.budgetLogOf(noteId).map { BudgetLogEntry(it.id, it.noteId, it.at, it.kind, it.title, it.oldValue, it.newValue, it.detail) }

    @Test
    fun anExpenseIsTakenFromTheAccountAndGoesToTheTrash() {
        val account = budget("Wallet", "Food" to 1000, "Rent" to 5000)
        val expense = budget("Shop", "food" to 300, "Toys" to 200)
        setLayout(expense) { it.copy(kind = BudgetKind.EXPENSE.name, mergeTarget = account) }
        now += 1_000

        assertEquals(MergeOutcome.Done("Wallet", 1, 1), service.merge(expense))
        assertEquals(mapOf("Food" to 700L, "Rent" to 5000L, "Toys" to -200L), sums(account))
        assertNotNull(db.note(expense)?.deletedAt, "in the trash")
        assertTrue(log(account).any { it.kind == BudgetLogKind.MERGED })
        assertTrue(log(expense).any { it.kind == BudgetLogKind.MERGED_INTO })
        assertNull(BudgetLayout.parse(db.note(expense)!!.extras).mergeAt)
    }

    @Test
    fun anIncomeAddsAndWithoutAnAccountNothingHappens() {
        val account = budget("Wallet", "Salary" to 0)
        val income = budget("Job", "salary" to 5000)
        setLayout(income) { it.copy(kind = BudgetKind.INCOME.name) }
        assertEquals(MergeOutcome.NoTarget, service.merge(income))
        assertEquals(mapOf("Salary" to 0L), sums(account))
        setLayout(income) { it.copy(mergeTarget = account) }
        assertTrue(service.merge(income) is MergeOutcome.Done)
        assertEquals(mapOf("Salary" to 5000L), sums(account))
        // An account is never merged, and an account chosen that is not an account any more is refused.
        assertEquals(MergeOutcome.NotMergeable, service.merge(account))
    }

    @Test
    fun aMergeIsUndoneFromTheAccountsLogAndTheSourceComesBack() {
        val account = budget("Wallet", "Food" to 1000)
        val expense = budget("Shop", "Food" to 300)
        setLayout(expense) { it.copy(kind = BudgetKind.EXPENSE.name, mergeTarget = account) }
        service.merge(expense)
        assertEquals(mapOf("Food" to 700L), sums(account))
        val merged = log(account).last { it.kind == BudgetLogKind.MERGED }
        now += 1_000
        assertTrue(service.undo(merged))
        assertEquals(mapOf("Food" to 1000L), sums(account))
        assertNull(db.note(expense)?.deletedAt, "back from the trash")
        assertTrue(service.undone(merged, log(account)))
    }

    @Test
    fun theDueMergesRunAndTheNextTimeIsKnown() {
        val account = budget("Wallet", "Food" to 1000)
        val later = budget("Later", "Food" to 100)
        val due = budget("Due", "Food" to 200)
        setLayout(later) { it.copy(kind = BudgetKind.EXPENSE.name, mergeTarget = account, mergeAt = 50_000) }
        setLayout(due) { it.copy(kind = BudgetKind.EXPENSE.name, mergeTarget = account, mergeAt = 5_000) }
        assertEquals(5_000L, service.nextDue())
        now = 10_000
        assertEquals(1, service.runDue())
        assertEquals(mapOf("Food" to 800L), sums(account))
        assertEquals(50_000L, service.nextDue())
        assertEquals(listOf("Wallet"), service.accounts(except = later).map { it.title })
    }

    private val day = 86_400_000L

    @Test
    fun anIncomeThatRepeatsStaysAndMovesToItsNextTime() {
        val account = budget("Wallet", "Salary" to 0)
        val income = budget("Job", "salary" to 5000)
        now = 10 * day
        setLayout(income) { it.copy(kind = BudgetKind.INCOME.name, mergeTarget = account, mergeAt = now, mergeRepeat = MergeRepeat.DAILY.name) }
        fun planned() = BudgetLayout.parse(db.note(income)!!.extras).mergeAt

        assertEquals(1, service.runDue())
        assertEquals(mapOf("Salary" to 5000L), sums(account))
        assertNull(db.note(income)?.deletedAt, "it stays in its folder")
        assertEquals(11 * day, planned())
        assertEquals(11 * day, service.nextDue())
        assertEquals(0, service.runDue(), "the same time is not merged twice")
        assertEquals(MergeOutcome.NotDue, service.merge(income, due = true))

        // Notio was closed for three days: every missed time is merged, the oldest first.
        now = 13 * day + 5
        assertEquals(3, service.runDue())
        assertEquals(mapOf("Salary" to 20_000L), sums(account))
        assertEquals(14 * day, planned())

        // "Merge now" merges at once and leaves the plan as it is.
        now += 1_000
        assertTrue(service.merge(income) is MergeOutcome.Done)
        assertEquals(mapOf("Salary" to 25_000L), sums(account))
        assertEquals(14 * day, planned())
        assertNull(db.note(income)?.deletedAt)

        // Each merge is a line of its own in the log of the account and can be undone there.
        val merged = log(account).filter { it.kind == BudgetLogKind.MERGED }
        assertEquals(5, merged.size)
        assertTrue(service.undo(merged.maxBy { it.at }))
        assertEquals(mapOf("Salary" to 20_000L), sums(account))
        assertEquals(14 * day, planned(), "undoing does not move the time back")
    }

    @Test
    fun moreMissedTimesThanAreMergedInOneGoAreLetGo() {
        val account = budget("Wallet", "Salary" to 0)
        val income = budget("Job", "salary" to 1)
        setLayout(income) { it.copy(kind = BudgetKind.INCOME.name, mergeTarget = account, mergeAt = day, mergeRepeat = MergeRepeat.DAILY.name) }
        now = 1000 * day + 7
        assertEquals(MergeSchedule.MAX_CATCH_UP, service.runDue())
        assertEquals(mapOf("Salary" to MergeSchedule.MAX_CATCH_UP.toLong()), sums(account))
        assertTrue(BudgetLayout.parse(db.note(income)!!.extras).mergeAt!! > now)
        assertEquals(0, service.runDue())
    }

    @Test
    fun theCaseOfTheNamesCountsWhenTheTickIsOff() {
        val account = budget("Wallet", "Касса" to 100)
        val income = budget("Job", "касса" to 50)
        setLayout(income) { it.copy(kind = BudgetKind.INCOME.name, mergeTarget = account, mergeAt = 5, mergeRepeat = MergeRepeat.DAILY.name, mergeIgnoreItemCase = false) }
        now = 6
        assertEquals(1, service.runDue())
        assertEquals(mapOf("Касса" to 100L, "касса" to 50L), sums(account))
        setLayout(income) { it.copy(mergeIgnoreItemCase = true) }
        assertTrue(service.merge(income) is MergeOutcome.Done)
        // Two items fit now; the first one found takes it.
        assertEquals(200L, sums(account).values.sum())
        assertEquals(2, sums(account).size)
    }
}
