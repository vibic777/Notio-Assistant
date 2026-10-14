package com.notio.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloodFillTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    /** Builds an image from rows of characters: `.` white, `#` black. */
    private fun image(vararg rows: String): IntArray =
        rows.joinToString("").map { if (it == '#') black else white }.toIntArray()

    private fun IntArray.rows(width: Int): List<String> =
        toList().chunked(width).map { row -> row.joinToString("") { if (it == black) "#" else if (it == red) "r" else "." } }

    @Test
    fun `fills the whole open area and reports how many pixels changed`() {
        val px = image("...", "...", "...")
        assertEquals(9, FloodFill.fill(px, 3, 3, 1, 1, red))
        assertEquals(listOf("rrr", "rrr", "rrr"), px.rows(3))
    }

    @Test
    fun `stops at a wall`() {
        val px = image(
            "..#..",
            "..#..",
            "..#..",
        )
        assertEquals(6, FloodFill.fill(px, 5, 3, 0, 0, red))
        assertEquals(listOf("rr#..", "rr#..", "rr#.."), px.rows(5))
    }

    @Test
    fun `does not leak through a diagonal gap (4 neighbours)`() {
        val px = image(
            ".#.",
            "#.#",
            ".#.",
        )
        assertEquals(1, FloodFill.fill(px, 3, 3, 1, 1, red))
        assertEquals(listOf(".#.", "#r#", ".#."), px.rows(3))
    }

    @Test
    fun `filling with the colour that is already there changes nothing`() {
        val px = image("..", "..")
        assertEquals(0, FloodFill.fill(px, 2, 2, 0, 0, white))
        assertEquals(listOf("..", ".."), px.rows(2))
    }

    @Test
    fun `a start outside the image changes nothing`() {
        val px = image("..", "..")
        assertEquals(0, FloodFill.fill(px, 2, 2, -1, 0, red))
        assertEquals(0, FloodFill.fill(px, 2, 2, 0, 2, red))
    }

    @Test
    fun `anti-aliased edge pixels close to the start colour are filled, dark ones are not`() {
        val almostWhite = 0xFFF0F0F0.toInt() // 15 per channel away
        val grey = 0xFF808080.toInt() // 127 away
        val px = intArrayOf(white, almostWhite, grey, white)
        FloodFill.fill(px, 4, 1, 0, 0, red)
        assertEquals(listOf(red, red, grey, white), px.toList())
    }

    @Test
    fun `zero tolerance fills only the exact colour`() {
        val almostWhite = 0xFFF0F0F0.toInt()
        val px = intArrayOf(white, almostWhite, white)
        FloodFill.fill(px, 3, 1, 0, 0, red, tolerance = 0)
        assertEquals(listOf(red, almostWhite, white), px.toList())
    }

    @Test
    fun `fills a full sheet without running out of stack or time`() {
        val w = 1080
        val h = 1440
        val px = IntArray(w * h) { white }
        // A vertical wall in the middle, so the fill has to go around nothing but stop at it.
        for (y in 0 until h) px[y * w + 540] = black
        val started = System.nanoTime()
        val changed = FloodFill.fill(px, w, h, 0, 0, red)
        assertEquals(540 * h, changed)
        assertEquals(white, px[w - 1])
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pixel array smaller than the image is rejected`() {
        FloodFill.fill(IntArray(3), 2, 2, 0, 0, red)
    }
}
