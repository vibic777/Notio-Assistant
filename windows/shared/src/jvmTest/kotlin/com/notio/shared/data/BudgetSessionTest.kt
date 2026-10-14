package com.notio.shared.data

import com.notio.shared.SequentialIds
import com.notio.shared.domain.AdjustMode
import com.notio.shared.domain.BudgetLayout
import com.notio.shared.domain.BudgetLogKind
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.BudgetRowPart
import com.notio.shared.domain.Clock
import com.notio.shared.domain.DomainError
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.visibleBudgetRows
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The budget editor of the phone (BudgetEditor, BudgetViewModel) on the desktop database. */
class BudgetSessionTest {
    private val dir = Files.createTempDirectory("notio-budget").toFile()
    private var now = 1_000L
    private val ids = SequentialIds()
    private val db = NotioDatabase.inMemory()
    private val editor = NotioEditor(db, AttachmentStore(dir), Clock { now }, ids)
    private val folder = editor.createFolder("F", "#3366FF", "SOFT")
    private val note = editor.createNote(folder.id, "Budget", NoteType.BUDGET)
    private val session = BudgetSession(db, note.id, Clock { now }, ids)
    private val root get() = session.nodes().single { it.parentId == null }

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun node(title: String) = session.nodes().single { it.title == title }

    /** Food { Bread 10.00 / 25.00 }, Rent 500.00 / 400.00 — the tree of the Android TransferTestSupport. */
    private fun sample() {
        session.add(root.id, "Food", BudgetNodeKind.GROUP, null, 0, 0, 1)
        session.add(node("Food").id, "Bread", BudgetNodeKind.LEAF, null, 1_000, 2_500, 1)
        session.add(root.id, "Rent", BudgetNodeKind.LEAF, null, 50_000, 40_000, 1)
    }

    @Test
    fun totalsCascadeToTheRoot() {
        sample()
        assertEquals(51_000, root.cachedSumMinor)
        assertEquals(42_500, root.cachedGoalMinor)
        assertEquals(-8_500, root.cachedRemainderMinor)
        session.update(node("Bread").id, "Bread", null, 1_000, 2_500, 3, null)
        assertEquals(3_000, node("Food").cachedSumMinor)
    }

    @Test
    fun changeButtonAddsSubtractsOrSets() {
        sample()
        val bread = node("Bread").id
        session.adjust(bread, AdjustMode.ADD, 500, sum = true, goal = false)
        assertEquals(1_500, node("Bread").manualSumMinor)
        session.adjust(bread, AdjustMode.SUBTRACT, 2_000, sum = true, goal = true)
        assertEquals(-500, node("Bread").manualSumMinor)
        assertEquals(500, node("Bread").manualGoalMinor)
        session.adjust(bread, AdjustMode.SET, 700, sum = false, goal = true)
        assertEquals(700, node("Bread").manualGoalMinor)
        assertEquals(DomainError.INVALID_AMOUNT, session.adjust(bread, AdjustMode.ADD, 1, sum = false, goal = false))
        assertEquals(DomainError.BUDGET_NODE_NOT_LEAF, session.adjust(node("Food").id, AdjustMode.ADD, 1, sum = true, goal = false))
    }

    @Test
    fun aCommentGivenWithAChangeGoesIntoItsLogLine() {
        // Android D262: after the kind of change and a line break, one line of at most 300 characters.
        sample()
        val bread = node("Bread").id
        now += 1_000
        session.adjust(bread, com.notio.shared.domain.AdjustMode.ADD, 500, sum = true, goal = false, comment = " bakery\non the corner ")
        assertEquals("ADD\nbakery on the corner", db.budgetLogOf(note.id).maxByOrNull { it.at }!!.detail)
        now += 1_000
        session.adjust(bread, com.notio.shared.domain.AdjustMode.SET, 100, sum = true, goal = false)
        assertEquals("SET", db.budgetLogOf(note.id).maxByOrNull { it.at }!!.detail)
    }

    @Test
    fun deleteTakesTheSubtreeAndTheRootStays() {
        sample()
        session.delete(node("Food").id)
        assertEquals(listOf("Budget", "Rent"), session.nodes().map { it.title }.sorted())
        assertEquals(50_000, root.cachedSumMinor)
        assertEquals(DomainError.BUDGET_ROOT_IMMUTABLE, session.delete(root.id))
    }

    @Test
    fun moveUnderAnotherGroupAndNeverUnderALeaf() {
        sample()
        session.update(node("Rent").id, "Rent", null, 50_000, 40_000, 1, node("Food").id)
        assertEquals(node("Food").id, node("Rent").parentId)
        assertEquals(51_000, node("Food").cachedSumMinor)
        assertEquals(DomainError.BUDGET_PARENT_IS_LEAF, session.add(node("Bread").id, "x", BudgetNodeKind.LEAF, null, 0, 0, 1))
    }

    @Test
    fun undoAndRedoTheData() {
        sample()
        session.adjust(node("Bread").id, AdjustMode.SET, 9_900, sum = true, goal = false)
        assertTrue(session.canUndo)
        session.undo()
        assertEquals(1_000, node("Bread").manualSumMinor)
        assertEquals(1_000, node("Food").cachedSumMinor)
        session.redo()
        assertEquals(9_900, node("Bread").manualSumMinor)
        // Opening or closing is not a change of the data.
        session.toggleExpanded(node("Food"))
        assertFalse(node("Food").isExpanded)
    }

    /** Android D330: an item merged into another one of the budget — the other stays and gets its sum and / or goal. */
    @Test
    fun anItemIsMergedIntoAnotherOne() {
        sample()
        session.update(node("Bread").id, "Bread", null, 1_000, 2_500, 3, null) // three pieces: 30.00 / 75.00 as a whole
        // Sums only: Rent 500.00 + 30.00, its goal as it was; Bread is gone and its group is empty.
        assertEquals(null, session.mergeItem(node("Bread").id, node("Rent").id, sum = true, goal = false))
        assertEquals(53_000, node("Rent").manualSumMinor)
        assertEquals(40_000, node("Rent").manualGoalMinor)
        assertTrue(session.nodes().none { it.title == "Bread" })
        assertEquals(0, node("Food").cachedSumMinor)
        assertEquals(53_000, root.cachedSumMinor)
        assertEquals(40_000, root.cachedGoalMinor)
        val line = db.budgetLogOf(note.id).single { it.kind == BudgetLogKind.ITEM_MERGED }
        assertEquals("Bread" to "Rent", line.title to line.detail)
        // One undo step brings the item and the old sum back.
        session.undo()
        assertEquals(50_000, node("Rent").manualSumMinor)
        assertEquals(3, node("Bread").quantity)
        // Sums and goals; a target of several pieces becomes one piece of its whole value.
        assertEquals(null, session.mergeItem(node("Rent").id, node("Bread").id, sum = true, goal = true))
        assertEquals(Triple(53_000L, 47_500L, 1), node("Bread").let { Triple(it.manualSumMinor, it.manualGoalMinor, it.quantity) })
        // Nothing chosen, itself, a group: refused, nothing changes.
        assertTrue(session.mergeItem(node("Bread").id, node("Bread").id, sum = true, goal = true) != null)
        assertTrue(session.mergeItem(node("Bread").id, node("Food").id, sum = true, goal = true) != null)
        session.add(root.id, "Tea", BudgetNodeKind.LEAF, null, 100, 0, 1)
        assertTrue(session.mergeItem(node("Tea").id, node("Bread").id, sum = false, goal = false) != null)
        assertEquals(100, node("Tea").manualSumMinor)
    }

    @Test
    fun theLogRecordsTheChanges() {
        sample()
        session.adjust(node("Bread").id, AdjustMode.ADD, 100, sum = true, goal = false)
        session.update(node("Bread").id, "Loaf", null, 1_100, 2_500, 1, null)
        val kinds = db.budgetLogOf(note.id).map { it.kind }
        assertTrue(BudgetLogKind.GROUP_ADDED in kinds && BudgetLogKind.ITEM_ADDED in kinds && BudgetLogKind.RENAMED in kinds)
        val sum = db.budgetLogOf(note.id).first { it.kind == BudgetLogKind.SUM && it.detail == "ADD" }
        assertEquals(1_000L, sum.oldValue)
        assertEquals(1_100L, sum.newValue)
    }

    @Test
    fun layoutLivesInTheNoteAndShapesTheRows() {
        sample()
        assertNull(session.layout().currencyLabel)
        session.setCurrency("RUB", "₽")
        session.setLayout(session.layout().copy(totalAtBottom = true, groupTotalsAtBottom = true))
        val layout = BudgetLayout.parse(db.note(note.id)!!.extras)
        assertEquals("₽", layout.currencyLabel)
        val rows = visibleBudgetRows(session.nodes(), layout)
        assertEquals(BudgetRowPart.TOTAL, rows.last().part)
        assertEquals(root.id, rows.last().node.id)
    }
}
