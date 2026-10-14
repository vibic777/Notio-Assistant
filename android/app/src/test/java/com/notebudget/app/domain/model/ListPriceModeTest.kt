package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PRICE mode of a list (D101): quantity x price of one, totals per unit and per price unit. */
class ListPriceModeTest {
    private val labels = mapOf("kg" to "kg", "pcs" to "pcs", "rub" to "rub", "eur" to "€")
    private fun label(id: String) = labels[id] ?: id

    private fun item(id: String, text: String, q: Long?, unit: String?, price: Long?, priceUnit: String?) =
        ListEntry(id, ListEntry.ITEM, text, quantityMilli = q, unit = unit, priceMilli = price, priceUnit = priceUnit)

    /** The example of the requirements: apples 2 pcs x 50 rub, bananas 3 pcs x 100 rub. */
    private val fruit = ListDocument(
        mode = ListDocument.MODE_PRICE,
        entries = listOf(
            item("a", "apples", 2_000, "pcs", 50_000, "rub"),
            item("b", "bananas", 3_000, "pcs", 100_000, "rub"),
        ),
    )

    @Test
    fun `apples and bananas add up to 5 pieces and 400 roubles`() {
        assertEquals(100_000L, fruit.lineCostMilli(fruit.entries[0]))
        assertEquals(300_000L, fruit.lineCostMilli(fruit.entries[1]))
        assertEquals(listOf<Pair<String?, Long>>("pcs" to 5_000L), fruit.quantityTotals())
        assertEquals(listOf<Pair<String?, Long>>("rub" to 400_000L), fruit.priceTotals())
    }

    @Test
    fun `the list is a quantity list by default and price fields are ignored there`() {
        assertFalse(ListDocument().isPriceMode)
        assertTrue(fruit.isPriceMode)
        val text = fruit.copy(mode = ListDocument.MODE_QUANTITY).fallbackText(::label)
        assertEquals("- [ ] apples — 2 pcs\n- [ ] bananas — 3 pcs", text)
    }

    @Test
    fun `different price units are added up separately, and the price unit may be any unit`() {
        val doc = ListDocument(
            mode = ListDocument.MODE_PRICE,
            entries = listOf(
                item("a", "apples", 2_000, "kg", 3_000, "eur"),
                item("b", "beans", 1_000, "kg", 40_000, "rub"),
                item("c", "pears", 4_000, "kg", 500, "kg"), // paid in kilograms of something
                item("d", "figs", 2_000, "kg", 1_000, "eur"),
            ),
        )
        assertEquals(listOf<Pair<String?, Long>>("eur" to 8_000L, "rub" to 40_000L, "kg" to 2_000L), doc.priceTotals())
        assertEquals(listOf<Pair<String?, Long>>("kg" to 9_000L), doc.quantityTotals())
    }

    @Test
    fun `no quantity counts as one, no price means no cost, and a price without a unit is its own sum`() {
        val doc = ListDocument(
            mode = ListDocument.MODE_PRICE,
            entries = listOf(
                item("a", "bread", null, null, 45_500, "rub"),
                item("b", "note", 2_000, "pcs", null, null),
                item("c", "loose", 2_000, null, 1_500, null),
            ),
        )
        assertEquals(45_500L, doc.lineCostMilli(doc.entries[0]))
        assertNull(doc.lineCostMilli(doc.entries[1]))
        assertEquals(3_000L, doc.lineCostMilli(doc.entries[2]))
        assertEquals(listOf<Pair<String?, Long>>("rub" to 45_500L, null to 3_000L), doc.priceTotals())
        assertEquals(listOf<Pair<String?, Long>>("pcs" to 2_000L, null to 2_000L), doc.quantityTotals())
    }

    @Test
    fun `amounts are exact and rounded to a thousandth, never through floating point`() {
        // 0.333 x 0.333 = 0.110889 -> 0.111; 1.5 x 0.005 = 0.0075 -> 0.008 (half up)
        val doc = ListDocument(entries = listOf(item("a", "x", 333, null, 333, "rub"), item("b", "y", 1_500, null, 5, "rub")))
        assertEquals(111L, doc.lineCostMilli(doc.entries[0]))
        assertEquals(8L, doc.lineCostMilli(doc.entries[1]))
        // a product that does not fit into a Long is "no cost", not a wrong number
        assertNull(doc.lineCostMilli(item("z", "z", Long.MAX_VALUE, null, Long.MAX_VALUE, "rub")))
    }

    @Test
    fun `a total by unit also shows the cost of the items in its unit`() {
        val doc = fruit.add(ListEntry.newTotal("pcs").copy(id = "t")).add(item("k", "salt", 1_000, "kg", 20_000, "rub"))
        assertEquals(5_000L, doc.totalOf(doc.entries.first { it.id == "t" }))
        assertEquals(listOf<Pair<String?, Long>>("rub" to 400_000L), doc.priceTotalsOf(doc.entries.first { it.id == "t" }))
        assertTrue(doc.priceTotalsOf(ListEntry.newTotal()).isEmpty())
    }

    @Test
    fun `the readable text of a price list shows each cost and the totals`() {
        assertEquals(
            "- [ ] apples — 2 pcs × 50 rub = 100 rub\n- [ ] bananas — 3 pcs × 100 rub = 300 rub\n= Total: 5 pcs; 400 rub",
            fruit.fallbackText(::label),
        )
    }

    @Test
    fun `mode, prices and price units survive a round trip and count as used units`() {
        val encoded = fruit.encode(::label, emptyMap())
        val back = ListDocument.fromNote(encoded.contentText, encoded.extras)
        assertEquals(fruit.entries, back.entries)
        assertTrue(back.isPriceMode)
        assertEquals(50_000L, back.entries[0].priceMilli)
        assertEquals("rub", back.entries[0].priceUnit)
        assertEquals(setOf("pcs", "rub"), back.usedUnitIds())
    }

    @Test
    fun `a list saved by an older version has no mode and reads as a quantity list`() {
        val old = """{"v":1,"entries":[{"id":"a","text":"milk","quantityMilli":2000,"unit":"kg"}],"textHash":0}"""
        val doc = ListDocument.parseExtras(old)!!
        assertFalse(doc.isPriceMode)
        assertNull(doc.entries[0].priceMilli)
    }

    @Test
    fun `when the text was changed elsewhere the rows are rebuilt but the mode is kept`() {
        val encoded = fruit.encode(::label, emptyMap())
        val rebuilt = ListDocument.fromNote(encoded.contentText + "\n- [ ] extra", encoded.extras)
        assertTrue(rebuilt.isPriceMode)
    }
}
