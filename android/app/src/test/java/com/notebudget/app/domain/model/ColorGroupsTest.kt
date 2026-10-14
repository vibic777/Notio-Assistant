package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Group headers of a list sorted by colour (D196) and the stamps at the bottom of a note (D198). */
class ColorGroupsTest {

    @Test
    fun `colours fall into named families`() {
        assertEquals(ColorFamily.RED, Sorting.colorFamily("#FF0000"))
        assertEquals(ColorFamily.RED, Sorting.colorFamily("#FF0022")) // a red just below 360 degrees
        assertEquals(ColorFamily.ORANGE, Sorting.colorFamily("#FF8800"))
        assertEquals(ColorFamily.GREEN, Sorting.colorFamily("#00C853"))
        assertEquals(ColorFamily.BLUE, Sorting.colorFamily("#2962FF"))
        assertEquals(ColorFamily.PINK, Sorting.colorFamily("#FF4081"))
        assertEquals(ColorFamily.NEUTRAL, Sorting.colorFamily("#FFFFFF"))
        assertEquals(ColorFamily.NEUTRAL, Sorting.colorFamily("#000000"))
        assertEquals(ColorFamily.NONE, Sorting.colorFamily(null))
    }

    @Test
    fun `the reds are one run when sorted, not split between the start and the end`() {
        val hexes = listOf("#FF0022", "#00FF00", "#FF0000", "#0000FF", "#FF4081")
        val sorted = hexes.sortedBy { Sorting.colorRank(it) }
        assertEquals(setOf("#FF0022", "#FF0000"), sorted.take(2).toSet())
        assertTrue(Sorting.colorRank("#FF0000")!! < Sorting.colorRank("#808080")!!)
    }

    @Test
    fun `a header starts every new family and pinned items get none`() {
        data class Item(val hex: String?, val pinned: Boolean = false)
        val items = listOf(Item("#123456", pinned = true), Item("#FF0000"), Item("#EE0000"), Item("#00FF00"), Item(null))
        val starts = colorGroupStarts(items, { it.pinned }, { it.hex })
        assertEquals(mapOf(1 to ColorFamily.RED, 3 to ColorFamily.GREEN, 4 to ColorFamily.NONE), starts)
    }

    @Test
    fun `footer stamps are chosen per kind and survive encoding`() {
        val stamps = NoteFooterStamps().withCreated(NoteType.TEXT, true).withModified(NoteType.BUDGET, true)
        assertTrue(stamps.showsCreated(NoteType.TEXT))
        assertEquals(false, stamps.showsModified(NoteType.TEXT))
        assertTrue(stamps.showsModified(NoteType.BUDGET))
        assertEquals(stamps, NoteFooterStamps.decode(stamps.encode()))
        assertEquals(NoteFooterStamps(), NoteFooterStamps.decode("not json"))
        assertEquals(emptySet<String>(), stamps.withCreated(NoteType.TEXT, false).created)
    }
}
