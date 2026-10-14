package com.notebudget.app.core.common

import com.notebudget.app.domain.model.ListDocument
import com.notebudget.app.domain.model.ListEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdjustTest {

    @Test
    fun `add, subtract and enter`() {
        assertEquals(1_250L, Adjust.apply(1_000, AdjustMode.ADD, 250))
        assertEquals(750L, Adjust.apply(1_000, AdjustMode.SUBTRACT, 250))
        assertEquals(-500L, Adjust.apply(1_000, AdjustMode.SUBTRACT, 1_500))
        // Enter puts the value in place of the current one, whatever it was.
        assertEquals(42L, Adjust.apply(1_000, AdjustMode.SET, 42))
        assertEquals(0L, Adjust.apply(1_000, AdjustMode.SET, 0))
    }

    @Test
    fun `a result that does not fit into a Long is refused`() {
        assertNull(Adjust.apply(Long.MAX_VALUE, AdjustMode.ADD, 1))
        assertNull(Adjust.apply(Long.MIN_VALUE, AdjustMode.SUBTRACT, 1))
        assertEquals(5L, Adjust.apply(Long.MAX_VALUE, AdjustMode.SET, 5))
    }

    @Test
    fun `changing the quantity and the price of a list item leaves the rest alone`() {
        val item = ListEntry(
            id = "milk", text = "Milk", quantityMilli = 2_000, unit = "L", priceMilli = 50_000, priceUnit = "RUB", comment = "whole",
        )
        val doc = ListDocument(entries = listOf(item, ListEntry(id = "bread", text = "Bread")), mode = ListDocument.MODE_PRICE)

        // + 0.5 to the quantity, price entered as 60.
        val quantity = Adjust.apply(item.quantityMilli ?: 0, AdjustMode.ADD, 500)!!
        val price = Adjust.apply(item.priceMilli ?: 0, AdjustMode.SET, 60_000)!!
        val changed = doc.replace("milk") { it.copy(quantityMilli = quantity) }.replace("milk") { it.copy(priceMilli = price) }

        val milk = changed.entries[0]
        assertEquals(2_500L, milk.quantityMilli)
        assertEquals(60_000L, milk.priceMilli)
        assertEquals("L", milk.unit)
        assertEquals("RUB", milk.priceUnit)
        assertEquals("whole", milk.comment)
        assertEquals("Bread", changed.entries[1].text)
        // The cost follows: 2.5 x 60 = 150.
        assertEquals(150_000L, changed.lineCostMilli(milk))
    }

    @Test
    fun `an item without a quantity counts as zero when a quantity is added`() {
        assertEquals(3_000L, Adjust.apply(0, AdjustMode.ADD, 3_000))
        assertEquals(-1_000L, Adjust.apply(0, AdjustMode.SUBTRACT, 1_000)) // the dialog refuses a negative quantity
    }
}
