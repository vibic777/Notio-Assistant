package com.notebudget.app.feature.notes

import com.notebudget.app.domain.model.FontSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zoom of a note (D118) and the global font size (D119). */
class NoteZoomTest {
    @Test
    fun `plus and minus step by fifteen percent and stay in range`() {
        assertEquals(1.15f, Zoom.plus(1.0f), 0.0001f)
        assertEquals(0.85f, Zoom.minus(1.0f), 0.0001f)
        var z = Zoom.NORMAL
        repeat(50) { z = Zoom.plus(z) }
        assertEquals(Zoom.MAX, z, 0.0001f)
        repeat(50) { z = Zoom.minus(z) }
        assertEquals(Zoom.MIN, z, 0.0001f)
    }

    @Test
    fun `a pinch multiplies the zoom, is clamped, and ignores nonsense`() {
        assertEquals(1.5f, Zoom.pinch(1.0f, 1.5f), 0.0001f)
        assertEquals(Zoom.MAX, Zoom.pinch(2.5f, 2.0f), 0.0001f)
        assertEquals(Zoom.MIN, Zoom.pinch(0.7f, 0.5f), 0.0001f)
        assertEquals(1.2f, Zoom.pinch(1.2f, Float.NaN), 0.0001f)
        assertEquals(1.2f, Zoom.pinch(1.2f, 0f), 0.0001f)
        assertEquals(1.2f, Zoom.pinch(1.2f, Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `the percent is what the button shows`() {
        assertEquals(100, Zoom.percent(1.0f))
        assertEquals(115, Zoom.percent(Zoom.plus(1.0f)))
    }

    @Test
    fun `there are five font sizes in growing order and normal changes nothing`() {
        assertEquals(listOf("SMALL", "NORMAL", "MEDIUM", "LARGE", "EXTRA_LARGE"), FontSize.entries.map { it.name })
        assertEquals(1.0f, FontSize.NORMAL.factor, 0f)
        val factors = FontSize.entries.map { it.factor }
        assertEquals(factors.sorted(), factors)
        assertEquals(5, factors.toSet().size)
        assertTrue(FontSize.SMALL.factor < 1f && FontSize.EXTRA_LARGE.factor > 1f)
    }
}
