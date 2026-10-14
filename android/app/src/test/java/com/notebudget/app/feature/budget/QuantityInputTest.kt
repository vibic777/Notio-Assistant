package com.notebudget.app.feature.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The quantity field of a budget item (D114): parsing and the - / + buttons. */
class QuantityInputTest {
    @Test
    fun `only whole numbers from 1 to 9999 are accepted`() {
        assertEquals(1, QuantityInput.parse("1"))
        assertEquals(3, QuantityInput.parse(" 3 "))
        assertEquals(9_999, QuantityInput.parse("9999"))
        assertNull(QuantityInput.parse(""))
        assertNull(QuantityInput.parse("0"))
        assertNull(QuantityInput.parse("10000"))
        assertNull(QuantityInput.parse("1.5"))
        assertNull(QuantityInput.parse("abc"))
    }

    @Test
    fun `plus and minus step by one and stay in range`() {
        assertEquals("2", QuantityInput.step("1", 1))
        assertEquals("1", QuantityInput.step("2", -1))
        assertEquals("1", QuantityInput.step("1", -1))
        assertEquals("9999", QuantityInput.step("9999", 1))
    }

    @Test
    fun `an empty or broken field is treated as one`() {
        assertEquals("2", QuantityInput.step("", 1))
        assertEquals("1", QuantityInput.step("", -1))
        assertEquals("2", QuantityInput.step("x", 1))
        assertEquals("1", QuantityInput.step("0", 1))
        assertEquals("9999", QuantityInput.step("12000", 1))
    }
}
