package com.notebudget.app.core.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun `parse accepts dot and comma and blank as zero`() {
        assertEquals(123_450L, Money.parse("1234.5"))
        assertEquals(123_450L, Money.parse("1 234,50"))
        assertEquals(0L, Money.parse(""))
        assertEquals(0L, Money.parse("  "))
        assertEquals(-500L, Money.parse("-5"))
        assertEquals(50L, Money.parse(".5"))
        assertEquals(500L, Money.parse("5."))
    }

    @Test
    fun `parse rejects garbage, three decimals and overflow`() {
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1.234"))
        assertNull(Money.parse("1.2.3"))
        assertNull(Money.parse("99999999999999999999"))
        assertNull(Money.parse("-"))
    }

    @Test
    fun `format groups digits and always shows two decimals`() {
        assertEquals("1,234.50", Money.format(123_450, Locale.US))
        assertEquals("0.05", Money.format(5, Locale.US))
        assertEquals("-12.00", Money.format(-1_200, Locale.US))
        assertEquals("1.234,50", Money.format(123_450, Locale.GERMANY))
    }

    @Test
    fun `format handles the extreme Long values`() {
        assertEquals("92,233,720,368,547,758.07", Money.format(Long.MAX_VALUE, Locale.US))
        assertEquals("-92,233,720,368,547,758.08", Money.format(Long.MIN_VALUE, Locale.US))
    }

    @Test
    fun `edit text round trips through parse`() {
        for (v in listOf(0L, 1L, 100L, 123_456L, -250L)) {
            assertEquals(v, Money.parse(Money.formatForEdit(v)))
        }
    }
}
