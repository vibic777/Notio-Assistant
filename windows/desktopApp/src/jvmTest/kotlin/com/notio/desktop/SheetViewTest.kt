package com.notio.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zoom and pan of a drawing sheet in the note area (D118, D241, D261). */
class SheetViewTest {
    // A tall phone area and a 3:4 sheet: the sheet is as wide as the area, with room above and below.
    private val frame = SheetFrame.fit(600f, 1000f, 3f / 4f)

    @Test
    fun `the sheet fits the area with its own proportions`() {
        assertEquals(600f, frame.sheetWidth, 0.001f)
        assertEquals(800f, frame.sheetHeight, 0.001f)
        val wide = SheetFrame.fit(1600f, 800f, 16f / 9f)
        assertEquals(800f, wide.sheetHeight, 0.001f)
        assertEquals(1422.22f, wide.sheetWidth, 0.01f)
    }

    @Test
    fun `at 1x the sheet is centred where it is smaller than the area`() {
        val v = SheetView.NORMAL.within(frame)
        assertEquals(0f, v.offsetX, 0.001f)
        assertEquals(100f, v.offsetY, 0.001f)
    }

    @Test
    fun `a pinch keeps the point between the fingers where it is`() {
        val start = SheetView.NORMAL.within(frame)
        val v = start.zoomAround(2f, 300f, 500f, frame)
        assertEquals(2f, v.scale, 0.0001f)
        // the sheet point that was under (300, 500) is still under it
        assertEquals(start.toSheetX(300f), v.toSheetX(300f), 0.001f)
        assertEquals(start.toSheetY(500f), v.toSheetY(500f), 0.001f)
    }

    @Test
    fun `zoomed in the sheet can be moved but no empty strip shows at its edges`() {
        var v = SheetView.NORMAL.within(frame).zoomAround(3f, 0f, 0f, frame)
        v = v.zoomAround(1f, 0f, 0f, frame, panX = 5000f, panY = -9000f)
        assertEquals(0f, v.offsetX, 0.001f)
        assertEquals(1000f - 3f * 800f, v.offsetY, 0.001f)
        repeat(40) { v = v.zoomAround(1.5f, 10f, 10f, frame) }
        assertEquals(SheetView.MAX, v.scale, 0.0001f)
    }

    @Test
    fun `the sheet can be made smaller than the area, then it stays in the middle (D261)`() {
        var v = SheetView.NORMAL.within(frame)
        repeat(40) { v = v.zoomAround(0.5f, 0f, 0f, frame, panX = 300f) }
        assertEquals(SheetView.MIN, v.scale, 0.0001f)
        assertEquals((600f - 150f) / 2f, v.offsetX, 0.001f)
        assertEquals((1000f - 200f) / 2f, v.offsetY, 0.001f)
    }

    @Test
    fun `the panel at the bottom or on the right narrows the free part, and Fit shows the whole sheet in it (D261)`() {
        val bottom = frame.copy(coveredBottom = 400f)
        val fitted = SheetView.fit(bottom)
        assertEquals(600f / 800f, fitted.scale, 0.0001f)
        assertEquals(0f, fitted.offsetY, 0.001f)
        assertEquals((600f - 450f) / 2f, fitted.offsetX, 0.001f)
        val right = frame.copy(coveredRight = 300f)
        val fittedRight = SheetView.fit(right)
        assertEquals(0.5f, fittedRight.scale, 0.0001f)
        assertTrue(fittedRight.offsetX + fittedRight.scale * right.sheetWidth <= 300f + 0.001f)
        // Nothing covered: Fit is 1x.
        assertEquals(1f, SheetView.fit(frame).scale, 0.0001f)
    }

    @Test
    fun `a panel at the top or on the left moves the free part, and the sheet lies in it (D281)`() {
        val top = frame.copy(coveredTop = 400f)
        val fittedTop = SheetView.fit(top)
        assertEquals(600f / 800f, fittedTop.scale, 0.0001f)
        // The sheet starts where the panel ends, not under it.
        assertEquals(400f, fittedTop.offsetY, 0.001f)
        val left = frame.copy(coveredLeft = 300f)
        val fittedLeft = SheetView.fit(left)
        assertEquals(0.5f, fittedLeft.scale, 0.0001f)
        assertTrue(fittedLeft.offsetX >= 300f - 0.001f)
        assertTrue(fittedLeft.offsetX + fittedLeft.scale * left.sheetWidth <= 600f + 0.001f)
        // A big sheet is never moved so far that it slips under the panel.
        val big = SheetView(2f, 0f, 0f).within(left)
        assertTrue(big.offsetX <= 300f + 0.001f)
        assertTrue(big.offsetX + 2f * left.sheetWidth >= 600f - 0.001f)
    }

    @Test
    fun `the old right-or-bottom choice carries over to the four sides`() {
        assertEquals(ToolPanelSide.RIGHT, ToolPanelSide.of(null, oldRight = true))
        assertEquals(ToolPanelSide.BOTTOM, ToolPanelSide.of(null, oldRight = false))
        assertEquals(ToolPanelSide.TOP, ToolPanelSide.of("TOP", oldRight = true))
        assertEquals(ToolPanelSide.BOTTOM, ToolPanelSide.of("SIDEWAYS"))
    }

    @Test
    fun `plus and minus step around the middle, and nonsense factors change nothing`() {
        val v = SheetView.NORMAL.within(frame)
        assertEquals(125, v.step(true, frame).percent)
        assertEquals(100, v.step(true, frame).step(false, frame).percent)
        assertEquals(v, v.zoomAround(Float.NaN, 0f, 0f, frame))
        assertEquals(v, v.zoomAround(0f, 0f, 0f, frame))
    }
}
