package com.notio.desktop

import com.notio.shared.domain.OverlaySettings
import com.notio.shared.domain.StampLayer
import com.notio.shared.domain.TextLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overlays of photos and videos (Android D148; AGENT.md D40) drawn on frames. */
class OverlayTest {
    private val gray = 0xFF808080.toInt()

    private fun changed(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int) =
        (y0 until y1).sumOf { y -> (x0 until x1).count { x -> px[y * w + x] != gray } }

    @Test
    fun `the time stamp lands in the bottom right corner and nowhere else`() {
        val w = 640; val h = 360
        val px = IntArray(w * h) { gray }
        val settings = OverlaySettings(stamp = StampLayer(enabled = true, sizePercent = 6))
        OverlayRenderer.drawOnPixels(px, w, h, settings, OverlayInputs(1_789_916_829_000L, null, null))
        assertTrue(changed(px, w, w / 2, h * 3 / 4, w, h) > 300)
        assertEquals(0, changed(px, w, 0, 0, w / 2, h / 2))
    }

    @Test
    fun `a video overlay is blended into every frame and follows the clock`() {
        val w = 320; val h = 180
        val overlay = VideoOverlay(OverlaySettings(text = TextLayer(enabled = true, text = "Notio", sizePercent = 10)), null)
        repeat(3) { i ->
            val px = IntArray(w * h) { gray }
            overlay.apply(px, w, h, i * 33L)
            assertTrue(px.any { it != gray })
            assertTrue(px.all { it ushr 24 == 0xFF })
        }
        // Nothing switched on: the frame stays as it is.
        val px = IntArray(w * h) { gray }
        VideoOverlay(OverlaySettings(), null).apply(px, w, h, 0)
        assertTrue(px.all { it == gray })
    }
}
