package com.notebudget.app.feature.notes.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a dragged checklist line of the Markdown View lands (D235). */
class CheckDragTest {
    private val heights = mapOf(1 to 100, 2 to 100, 3 to 100)
    private val gap = 16f

    @Test
    fun `a line pulled just past the middle of the next slot changes place once and stays there`() {
        // 0.9.46 froze here: 51 px passed the old threshold (half of 100), and after the swap the rest (-65) passed it the
        // other way, for ever.
        val (order, offset) = CheckDrag.settle(listOf(1, 2, 3), 1, 59f, heights, gap)
        assertEquals(listOf(2, 1, 3), order)
        assertEquals(59f - 116f, offset, 0.001f)
        // Settling again changes nothing.
        assertEquals(order to offset, CheckDrag.settle(order, 1, offset, heights, gap))
    }

    @Test
    fun `a short pull keeps the place`() {
        assertEquals(listOf(1, 2, 3) to 51f, CheckDrag.settle(listOf(1, 2, 3), 1, 51f, heights, gap))
        assertEquals(listOf(1, 2, 3) to -40f, CheckDrag.settle(listOf(1, 2, 3), 3, -40f, heights, gap))
    }

    @Test
    fun `a long pull passes several lines, in both directions, and stops at the ends`() {
        val (down, rest) = CheckDrag.settle(listOf(1, 2, 3), 1, 300f, heights, gap)
        assertEquals(listOf(2, 3, 1), down)
        assertEquals(300f - 232f, rest, 0.001f)
        val (up, _) = CheckDrag.settle(listOf(1, 2, 3), 3, -250f, heights, gap)
        assertEquals(listOf(3, 1, 2), up)
    }

    @Test
    fun `lines of different heights and an unmeasured neighbour`() {
        val tall = mapOf(1 to 40, 2 to 300)
        assertEquals(listOf(1, 2), CheckDrag.settle(listOf(1, 2), 1, 150f, tall, gap).first) // 150 < (300 + 16) / 2
        assertEquals(listOf(2, 1), CheckDrag.settle(listOf(1, 2), 1, 160f, tall, gap).first)
        assertEquals(listOf(1, 2) to 500f, CheckDrag.settle(listOf(1, 2), 1, 500f, mapOf(1 to 40), gap))
    }

    @Test
    fun `the text scrolls at an edge only towards where the line is pulled`() {
        // View from 100 to 1100, edge band 48, speed 20.
        assertEquals(20f, CheckDrag.edgeScroll(1080f, 100f, 1100f, 48f, pulled = 300f, speed = 20f))
        assertEquals(-20f, CheckDrag.edgeScroll(120f, 100f, 1100f, 48f, pulled = -300f, speed = 20f))
        // Taken near the top and pulled down: no scroll up (the line ran off upwards before).
        assertEquals(0f, CheckDrag.edgeScroll(120f, 100f, 1100f, 48f, pulled = 50f, speed = 20f))
        assertEquals(0f, CheckDrag.edgeScroll(1080f, 100f, 1100f, 48f, pulled = -50f, speed = 20f))
        assertEquals(0f, CheckDrag.edgeScroll(600f, 100f, 1100f, 48f, pulled = 300f, speed = 20f))
    }
}
