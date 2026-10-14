package com.notebudget.app.core.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityTest {
    @Test
    fun `parses whole and decimal numbers with a dot or a comma into thousandths`() {
        assertEquals(2_000L, Quantity.parse("2"))
        assertEquals(2_500L, Quantity.parse("2.5"))
        assertEquals(2_500L, Quantity.parse("2,5"))
        assertEquals(125L, Quantity.parse("0.125"))
        assertEquals(500L, Quantity.parse(".5"))
        assertEquals(1_234_567L, Quantity.parse(" 1 234,567 "))
        assertEquals(-1_500L, Quantity.parse("-1.5"))
    }

    @Test
    fun `blank means no quantity and is valid, anything else that is not a number is invalid`() {
        assertNull(Quantity.parse(""))
        assertNull(Quantity.parse("   "))
        assertTrue(Quantity.isBlank("  "))
        assertTrue(Quantity.isValid(""))
        assertTrue(Quantity.isValid("3"))
        for (bad in listOf("abc", "1.2.3", "1.2345", "--1", "1e3", "2 kg")) {
            assertNull(bad, Quantity.parse(bad))
            assertFalse(bad, Quantity.isValid(bad))
        }
    }

    @Test
    fun `a number too big for a Long is invalid instead of overflowing`() {
        assertNull(Quantity.parse("99999999999999999999"))
    }

    @Test
    fun `formats without trailing zeros, plain and per locale, and for editing`() {
        assertEquals("2.5", Quantity.formatPlain(2_500))
        assertEquals("2", Quantity.formatPlain(2_000))
        assertEquals("0.125", Quantity.formatPlain(125))
        assertEquals("-1.5", Quantity.formatPlain(-1_500))
        assertEquals("1,234.5", Quantity.format(1_234_500, Locale.US))
        assertEquals("2,5", Quantity.format(2_500, Locale.GERMANY))
        assertEquals("", Quantity.formatForEdit(null))
        assertEquals("0", Quantity.formatForEdit(0))
        assertEquals("2.5", Quantity.formatForEdit(2_500))
    }

    @Test
    fun `sums stay exact where floating point would not`() {
        val sum = Quantity.parse("0.1")!! + Quantity.parse("0.2")!!
        assertEquals(Quantity.parse("0.3"), sum)
        assertEquals("0.3", Quantity.formatPlain(sum))
    }
}
