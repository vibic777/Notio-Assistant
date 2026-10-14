package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListDocumentTest {
    private val labels = mapOf("kg" to "kg", "pcs" to "pcs", "rub" to "₽", "u-custom" to "packs")
    private fun label(id: String) = labels[id] ?: id

    private fun item(id: String, text: String, q: Long? = null, unit: String? = null, done: Boolean = false, comment: String? = null) =
        ListEntry(id, ListEntry.ITEM, text, done, comment, q, unit)

    private fun total(id: String, name: String, unit: String?, comment: String? = null) =
        ListEntry(id, ListEntry.TOTAL, name, comment = comment, unit = unit)

    // ---- lists made before D92: plain check boxes in the text ----

    @Test
    fun `an old list is read from its text lines`() {
        val doc = ListDocument.fromNote("- [ ] milk\n- [x] bread\nplain line\n\n- [X] eggs", null)
        assertEquals(listOf("milk", "bread", "plain line", "eggs"), doc.entries.map { it.text })
        assertEquals(listOf(false, true, false, true), doc.entries.map { it.done })
        assertTrue(doc.entries.all { it.isItem && it.quantityMilli == null && it.unit == null && it.comment == null })
    }

    @Test
    fun `an empty or missing text is an empty list, and the ids of an old list are stable`() {
        assertTrue(ListDocument.fromNote(null, null).entries.isEmpty())
        assertTrue(ListDocument.fromNote("", null).entries.isEmpty())
        val a = ListDocument.fromNote("- [ ] a\n- [ ] b", null)
        val b = ListDocument.fromNote("- [ ] a\n- [ ] b", null)
        assertEquals(a.entries.map { it.id }, b.entries.map { it.id })
        assertEquals(2, a.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `an item with an empty text survives a round trip like it did before`() {
        val doc = ListDocument(entries = listOf(item("1", ""), item("2", "x")))
        val encoded = doc.encode(::label, emptyMap())
        assertEquals("- [ ] \n- [ ] x", encoded.contentText)
        assertEquals(listOf("", "x"), ListDocument.fromNote(encoded.contentText, encoded.extras).entries.map { it.text })
    }

    // ---- totals by unit ----

    @Test
    fun `a total sums the quantities of the items in its unit and nothing else`() {
        val doc = ListDocument(
            entries = listOf(
                item("a", "Flour", 1_500, "kg"),
                item("b", "Sugar", 2_250, "kg"),
                item("c", "Bottles", 6_000, "pcs"),
                item("d", "No unit", 9_000, null),
                item("e", "No quantity", null, "kg"),
                total("t1", "Kilos", "kg"),
                total("t2", "Pieces", "pcs"),
                total("t3", "Roubles", "rub"),
                total("t4", "Nothing chosen", null),
            ),
        )
        val totals = doc.entries.filter { it.isTotal }.associate { it.id to doc.totalOf(it) }
        assertEquals(3_750L, totals["t1"])
        assertEquals(6_000L, totals["t2"])
        assertEquals(0L, totals["t3"])
        assertEquals(0L, totals["t4"])
    }

    @Test
    fun `totals count checked items too and add exactly`() {
        val doc = ListDocument(
            entries = listOf(
                item("a", "a", 100, "kg", done = true),
                item("b", "b", 200, "kg"),
                item("c", "c", -50, "kg"),
                total("t", "T", "kg"),
            ),
        )
        assertEquals(250L, doc.totalOf(doc.entries.last())) // 0.1 + 0.2 - 0.05, no rounding error
    }

    @Test
    fun `a total never counts another total`() {
        val doc = ListDocument(entries = listOf(item("a", "a", 1_000, "kg"), total("t1", "one", "kg"), total("t2", "two", "kg")))
        assertEquals(1_000L, doc.totalOf(doc.entries[1]))
        assertEquals(1_000L, doc.totalOf(doc.entries[2]))
    }

    // ---- the text copy ----

    @Test
    fun `the text copy is readable and shows quantity, unit, comment and totals`() {
        val doc = ListDocument(
            entries = listOf(
                item("a", "Milk", 2_000, "pcs", comment = "whole"),
                item("b", "Flour", 1_500, "kg", done = true),
                item("c", "Bread"),
                item("d", "Cash", 120_500, "rub"),
                total("t", "Kilos", "kg", comment = "for baking"),
            ),
        )
        assertEquals(
            "- [ ] Milk — 2 pcs (whole)\n- [x] Flour — 1.5 kg\n- [ ] Bread\n- [ ] Cash — 120.5 ₽\n= Kilos: 1.5 kg (for baking)",
            doc.fallbackText(::label),
        )
    }

    @Test
    fun `line breaks in a text cannot break the line based copy`() {
        val doc = ListDocument(entries = listOf(item("a", "two\nlines", comment = null)))
        assertEquals("- [ ] two lines", doc.fallbackText(::label))
    }

    // ---- structure and text stay in step ----

    @Test
    fun `the stored structure comes back exactly when the text still matches`() {
        val doc = ListDocument(
            entries = listOf(item("a", "Milk", 2_000, "pcs", comment = "whole"), total("t", "Pieces", "pcs")),
        )
        val encoded = doc.encode(::label, emptyMap())
        val back = ListDocument.fromNote(encoded.contentText, encoded.extras)
        assertEquals(doc.entries, back.entries)
    }

    @Test
    fun `when the text was changed elsewhere the text wins and the rows are rebuilt from it`() {
        val doc = ListDocument(entries = listOf(item("a", "Milk", 2_000, "pcs", comment = "whole")))
        val encoded = doc.encode(::label, mapOf("u-custom" to "packs"))

        // An older app edited the check boxes and saved only the text.
        val edited = "- [x] Milk — 2 pcs (whole)\n- [ ] Eggs"
        val back = ListDocument.fromNote(edited, encoded.extras)

        assertEquals(listOf("Milk — 2 pcs (whole)", "Eggs"), back.entries.map { it.text })
        assertEquals(listOf(true, false), back.entries.map { it.done })
        assertNull(back.entries[0].quantityMilli) // the structure was stale, so it is not trusted
        assertEquals(mapOf("u-custom" to "packs"), back.unitNames) // but the unit names are kept
    }

    @Test
    fun `extras that are not a list document never break a list`() {
        // Another kind's settings, garbage, or nothing at all.
        assertEquals(listOf("a"), ListDocument.fromNote("- [ ] a", """{"totalAtBottom":true}""").entries.map { it.text })
        assertEquals(listOf("a"), ListDocument.fromNote("- [ ] a", "not json at all").entries.map { it.text })
        assertEquals(listOf("a"), ListDocument.fromNote("- [ ] a", "[1,2,3]").entries.map { it.text })
        assertEquals(listOf("a"), ListDocument.fromNote("- [ ] a", "").entries.map { it.text })
        assertNull(ListDocument.parseExtras(null))
        assertNull(ListDocument.parseExtras("garbage"))
    }

    // ---- compatibility (D84) ----

    /**
     * Golden structure of a list, version 1. Never edit it to make a test pass: if it stops decoding,
     * lists that users already have (and their backups) would lose their quantities.
     */
    @Test
    fun `golden v1 list structure still decodes`() {
        val text = "- [ ] Milk — 2 pcs (whole)\n= Pieces: 2 pcs"
        val golden = """{"entries":[
            {"id":"e1","kind":"ITEM","text":"Milk","comment":"whole","quantityMilli":2000,"unit":"pcs"},
            {"id":"e2","kind":"TOTAL","text":"Pieces","unit":"pcs"}
        ],"unitNames":{"u-custom":"packs"},"textHash":${text.hashCode()}}"""
        val doc = ListDocument.fromNote(text, golden)
        assertEquals(listOf("e1", "e2"), doc.entries.map { it.id })
        assertEquals(2_000L, doc.entries[0].quantityMilli)
        assertEquals("whole", doc.entries[0].comment)
        assertEquals(false, doc.entries[0].done) // a field the old file lacks comes from the default
        assertEquals(2_000L, doc.totalOf(doc.entries[1]))
        assertEquals("packs", doc.unitNames["u-custom"])
    }

    @Test
    fun `a structure from a newer app keeps unknown row kinds and ignores unknown fields`() {
        val text = "- [ ] Milk\n? Sticker"
        val future = """{"v":7,"shiny":true,"entries":[
            {"id":"a","text":"Milk","sparkle":1},
            {"id":"b","kind":"SEPARATOR","text":"Sticker"}
        ],"textHash":${text.hashCode()}}"""
        val doc = ListDocument.fromNote(text, future)
        assertEquals(2, doc.entries.size)
        assertEquals("SEPARATOR", doc.entries[1].kind)

        // Saving again keeps the unknown row untouched and writes a matching text copy.
        val encoded = doc.encode(::label, emptyMap())
        assertEquals("- [ ] Milk\n? Sticker", encoded.contentText)
        val again = ListDocument.fromNote(encoded.contentText, encoded.extras)
        assertEquals("SEPARATOR", again.entries[1].kind)
        assertEquals("Sticker", again.entries[1].text)
    }

    @Test
    fun `the names of custom units used by the list travel with it`() {
        val doc = ListDocument(entries = listOf(item("a", "Tea", 3_000, "u-custom")))
        val encoded = doc.encode(::label, mapOf("u-custom" to "packs"))
        val back = ListDocument.fromNote(encoded.contentText, encoded.extras)
        assertEquals(mapOf("u-custom" to "packs"), back.unitNames)
        assertEquals(setOf("u-custom"), back.usedUnitIds())
        assertNotNull(back.entries.single().unit)
    }

    // ---- editing helpers ----

    @Test
    fun `add, replace and remove change only what they name`() {
        val start = ListDocument(entries = listOf(item("a", "a"), item("b", "b")))
        val added = start.add(item("c", "c"))
        assertEquals(listOf("a", "b", "c"), added.entries.map { it.id })
        val replaced = added.replace("b") { it.copy(text = "B", quantityMilli = 1_000, unit = "kg") }
        assertEquals("B", replaced.entries[1].text)
        assertEquals("a", replaced.entries[0].text)
        assertEquals(listOf("a", "c"), replaced.remove("b").entries.map { it.id })
        assertEquals(start, start.replace("missing") { it.copy(text = "x") })
    }
}
