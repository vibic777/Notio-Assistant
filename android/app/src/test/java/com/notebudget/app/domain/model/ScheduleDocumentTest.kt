package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SCHEDULE notes (D155): the time between two items, the order of rows, the readable copy, compatibility. */
class ScheduleDocumentTest {
    private fun item(id: String, h: Int, m: Int = 0, text: String = id) = ScheduleEntry(id = id, text = text, minutes = h * 60 + m)

    @Test
    fun `the time between two items, over midnight and equal`() {
        assertEquals(90, ScheduleMath.between(9 * 60, 10 * 60 + 30))
        assertEquals("23:00 to 01:00 is two hours of the next day", 120, ScheduleMath.between(23 * 60, 60))
        assertEquals(0, ScheduleMath.between(600, 600))
        assertNull(ScheduleMath.between(null, 600))
        assertNull(ScheduleMath.between(600, null))
        assertEquals("08:05", ScheduleMath.clock(485))
    }

    @Test
    fun `a calculator reads its two items and shows nothing when one is gone`() {
        val doc = ScheduleDocument(entries = listOf(item("a", 9), item("b", 10, 30), ScheduleEntry(id = "c", kind = ScheduleEntry.CALC, text = "Work", fromId = "a", toId = "b")))
        assertEquals(90, doc.result(doc.entries[2]))
        assertNull(doc.remove("b").result(doc.entries[2]))
        assertNull("a calculator is not an item", doc.result(ScheduleEntry(id = "x", kind = ScheduleEntry.CALC, fromId = "c", toId = "a")))
    }

    @Test
    fun `any number of calculators and free order of rows`() {
        var doc = ScheduleDocument(entries = listOf(item("a", 8), item("b", 9), item("c", 12)))
        doc = doc.add(ScheduleEntry(id = "k1", kind = ScheduleEntry.CALC, fromId = "a", toId = "b"))
        doc = doc.add(ScheduleEntry(id = "k2", kind = ScheduleEntry.CALC, fromId = "b", toId = "c"))
        doc = doc.reorder(listOf("k1", "c", "a", "k2", "b"))
        assertEquals(listOf("k1", "c", "a", "k2", "b"), doc.entries.map { it.id })
        assertEquals("a row that is not named keeps its place after the named ones", listOf("k2", "k1", "c", "a", "b"), doc.reorder(listOf("k2")).entries.map { it.id })
        assertEquals(60, doc.result(doc.entries.first { it.id == "k1" }))
        assertEquals(180, doc.result(doc.entries.first { it.id == "k2" }))
    }

    @Test
    fun `the readable copy and storage survive, unknown fields are ignored`() {
        val doc = ScheduleDocument(entries = listOf(item("a", 9, 0, "Wake up").copy(comment = "slowly"), item("b", 10, 30, "Leave"), ScheduleEntry(id = "c", kind = ScheduleEntry.CALC, text = "Breakfast", fromId = "a", toId = "b")))
        val text = doc.fallbackText({ "$it min" }, "?")
        assertEquals("09:00 Wake up (slowly)\n10:30 Leave\n= Breakfast: 90 min", text)
        assertEquals(doc, ScheduleDocument.parse(doc.encode()))
        assertEquals(1, ScheduleDocument.parse("""{"v":5,"entries":[{"id":"x","future":1}],"more":2}""").entries.size)
        assertEquals(0, ScheduleDocument.parse("garbage").entries.size)
        assertEquals("--:-- no time", ScheduleDocument(entries = listOf(ScheduleEntry(id = "z", text = "no time"))).fallbackText({ "" }, "?"))
    }

    @Test
    fun `a list keeps the order it was dragged into`() {
        val doc = ListDocument(entries = listOf(ListEntry("1", text = "a"), ListEntry("2", text = "b"), ListEntry("3", text = "c")))
        assertEquals(listOf("3", "1", "2"), doc.reorder(listOf("3", "1", "2")).entries.map { it.id })
        assertEquals(listOf("2", "1", "3"), doc.reorder(listOf("2")).entries.map { it.id })
    }
}
