package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words of a search: case, wildcards and regular expressions (D246). */
class TextPatternTest {
    private fun ranges(text: String, words: String, ignoreCase: Boolean = true, regex: Boolean = false) =
        TextPattern.of(words, SearchOptions(ignoreCase, regex))!!.ranges(text)

    @Test
    fun `plain words, with and without the case of the letters`() {
        assertEquals(listOf(0..2, 9..11), ranges("Milk and MILK", "mil"))
        assertEquals(listOf(0..2), ranges("Milk and MILK", "Mil", ignoreCase = false))
        assertEquals(listOf(7..12), ranges("Купить Молоко", "молоко"))
        assertTrue(ranges("Купить Молоко", "молоко", ignoreCase = false).isEmpty())
        // Characters that mean something in a regular expression are plain here.
        assertEquals(listOf(3..6), ranges("1+1=(2)", "=(2)"))
        assertNull(TextPattern.of("   "))
    }

    @Test
    fun `stars and question marks are wildcards, stars at the ends change nothing`() {
        val text = "Вкусная каша, вкусный чай"
        assertEquals(listOf(0..6), ranges(text, "*Вкусная*"))
        assertEquals(listOf(0..6, 14..20), ranges(text, "вкусн??"))
        // A star takes as little as it can: from the first "вкус" to the first "ы" after it, and one more character.
        assertEquals(listOf(0..20), ranges(text, "вкус*ы?"))
        // Case exact: only the capital one.
        assertEquals(listOf(0..6), ranges(text, "Вкусн?я", ignoreCase = false))
        // A star stays within its line.
        assertTrue(ranges("tea\ncup", "tea*cup").isEmpty())
        assertNull(TextPattern.of("**"))
    }

    @Test
    fun `a regular expression, and a wrong one says why instead of failing`() {
        assertEquals(listOf(4..6, 11..12), ranges("Buy 250 g, 10 eggs", "\\d+", regex = true))
        assertEquals(listOf(0..2), ranges("Чай и чай", "^чай", regex = true))
        assertTrue(ranges("Чай и чай", "^чай", ignoreCase = false, regex = true).isEmpty())
        val wrong = TextPattern.of("*Вкусная*", SearchOptions(regex = true))
        assertNotNull(wrong)
        assertNotNull(wrong!!.error)
        assertTrue(wrong.ranges("Вкусная").isEmpty())
        assertFalse(wrong.foundIn("Вкусная"))
    }

    @Test
    fun `an expression that matches empty text finds nothing there, and a slow one is stopped`() {
        assertTrue(ranges("abc", "x*", regex = true).isEmpty())
        val slow = TextPattern.of("(a+)+b", SearchOptions(regex = true))!!
        val started = System.nanoTime()
        assertTrue(slow.ranges("a".repeat(40)).isEmpty())
        assertTrue("stopped in time", System.nanoTime() - started < 5_000_000_000L)
    }
}
