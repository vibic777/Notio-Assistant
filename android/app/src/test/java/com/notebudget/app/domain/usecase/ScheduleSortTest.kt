package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.ScheduleDocument
import com.notebudget.app.domain.model.ScheduleEntry
import com.notebudget.app.domain.model.ScheduleSortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sorting the rows of a schedule note by time or by description (D182); the own order stays underneath. */
class ScheduleSortTest {
    private fun item(id: String, text: String, minutes: Int?) = ScheduleEntry(id = id, text = text, minutes = minutes)

    private val wake = item("wake", "Wake up", 7 * 60)
    private val lunch = item("lunch", "lunch", 13 * 60)
    private val night = item("night", "Bed", 23 * 60 + 30)
    private val early = item("early", "Run", 5 * 60 + 45)
    private val undecided = item("later", "Call mum", null)
    private val calc = ScheduleEntry(id = "calc", kind = ScheduleEntry.CALC, text = "Work day", fromId = "wake", toId = "lunch")

    private fun doc(key: ScheduleSortKey, desc: Boolean = false, vararg entries: ScheduleEntry) =
        ScheduleDocument(entries = entries.toList(), sort = key.name, sortDescending = desc)

    private fun ScheduleDocument.ids() = displayEntries().map { it.id }

    @Test
    fun `by time from the early morning, the items without a time last, the calculators after the items`() {
        val d = doc(ScheduleSortKey.TIME, entries = arrayOf(lunch, calc, night, undecided, wake, early))
        assertEquals(listOf("early", "wake", "lunch", "night", "later", "calc"), d.ids())
    }

    @Test
    fun `descending puts the latest time first and still the items without a time last`() {
        val d = doc(ScheduleSortKey.TIME, true, entries = arrayOf(lunch, undecided, wake, night))
        assertEquals(listOf("night", "lunch", "wake", "later"), d.ids())
    }

    @Test
    fun `by description, ignoring case, and an item without a description comes last`() {
        val nameless = item("nameless", "  ", 8 * 60)
        val d = doc(ScheduleSortKey.NAME, entries = arrayOf(wake, lunch, night, nameless, undecided))
        assertEquals(listOf("night", "later", "lunch", "wake", "nameless"), d.ids()) // Bed, Call mum, lunch, Wake up
        assertEquals(listOf("wake", "lunch", "later", "night", "nameless"), doc(ScheduleSortKey.NAME, true, entries = arrayOf(wake, lunch, night, nameless, undecided)).ids())
    }

    @Test
    fun `equal times keep the own order`() {
        val a = item("a", "same time", 600)
        val b = item("b", "same time too", 600)
        assertEquals(listOf("b", "a"), doc(ScheduleSortKey.TIME, entries = arrayOf(b, a)).ids())
        assertEquals(listOf("a", "b"), doc(ScheduleSortKey.TIME, entries = arrayOf(a, b)).ids())
    }

    @Test
    fun `the own order is what is shown until a sort is chosen, and it is kept underneath`() {
        val manual = doc(ScheduleSortKey.MANUAL, entries = arrayOf(night, wake, lunch))
        assertFalse(manual.isSorted)
        assertEquals(listOf("night", "wake", "lunch"), manual.ids())
        val sorted = manual.copy(sort = ScheduleSortKey.TIME.name)
        assertTrue(sorted.isSorted)
        assertEquals(listOf("wake", "lunch", "night"), sorted.ids())
        // The stored order did not change; going back shows it again.
        assertEquals(listOf("night", "wake", "lunch"), sorted.entries.map { it.id })
        assertEquals(listOf("night", "wake", "lunch"), sorted.copy(sort = ScheduleSortKey.MANUAL.name).ids())
        assertEquals(ScheduleSortKey.MANUAL, ScheduleSortKey.of("from the future"))
    }

    @Test
    fun `the sort is stored with the schedule and a schedule from before has none`() {
        val original = doc(ScheduleSortKey.NAME, true, entries = arrayOf(wake, lunch))
        val back = ScheduleDocument.parse(original.encode())
        assertEquals(ScheduleSortKey.NAME, back.sortKey)
        assertTrue(back.sortDescending)
        assertEquals(ScheduleSortKey.MANUAL, ScheduleDocument.parse("""{"v":1,"entries":[]}""").sortKey)
    }
}
