package com.notebudget.app.feature.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hidden sums / goals of a budget (D272). */
class BudgetPrivacyTest {
    @Test
    fun `nothing hidden shows every amount`() {
        val p = BudgetPrivacy.NONE
        assertFalse(p.hidesAnything)
        assertNotEquals(BudgetPrivacy.MASK, p.sum(1234, null))
        assertNotEquals(BudgetPrivacy.MASK, p.remainder(-5, null))
    }

    @Test
    fun `sums and goals are hidden apart, the remainder with either`() {
        val sums = BudgetPrivacy(hideSums = true)
        assertEquals(BudgetPrivacy.MASK, sums.sum(1234, "€"))
        assertNotEquals(BudgetPrivacy.MASK, sums.goal(1234, "€"))
        assertEquals(BudgetPrivacy.MASK, sums.remainder(0, "€"))

        val goals = BudgetPrivacy(hideGoals = true)
        assertNotEquals(BudgetPrivacy.MASK, goals.sum(1234, "€"))
        assertEquals(BudgetPrivacy.MASK, goals.goal(1234, "€"))
        assertTrue(goals.remainderMasked)
    }

    @Test
    fun `the eye shows everything and the layout keeps the choice`() {
        val layout = BudgetLayout.parse(BudgetLayout(hideSums = true, hideGoals = true).toExtras())
        assertTrue(BudgetPrivacy.of(layout).hidesAnything)
        val open = BudgetPrivacy.of(layout, revealed = true)
        assertNotEquals(BudgetPrivacy.MASK, open.sum(1, null))
        assertNotEquals(BudgetPrivacy.MASK, open.goal(1, null))
        assertFalse(open.remainderMasked)
        // A budget saved before D272 hides nothing.
        assertFalse(BudgetPrivacy.of(BudgetLayout.parse("""{"totalAtBottom":true}""")).hidesAnything)
    }
}
