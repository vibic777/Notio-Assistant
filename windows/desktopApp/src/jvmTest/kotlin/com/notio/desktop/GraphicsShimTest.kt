package com.notio.desktop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Skia-backed android.graphics (D26): the phone's renderer paints strokes, fills and shapes, and PNG / JPEG round-trip. */
class GraphicsShimTest {
    @Test
    fun strokesFillsAndShapesPaintPixels() {
        val sheet = DrawingRenderer.newSheet()
        assertEquals(0, sheet.getPixel(10, 10))
        DrawingRenderer.apply(sheet, Stroke(DrawingTool.PENCIL, 0xFFD32F2F.toInt(), 20f, listOf(Stroke.Point(100f, 100f), Stroke.Point(300f, 100f))))
        assertEquals(0xFFD32F2F.toInt(), sheet.getPixel(200, 100))
        DrawingRenderer.apply(sheet, Fill(500, 500, 0xFF1976D2.toInt()))
        assertEquals(0xFF1976D2.toInt(), sheet.getPixel(900, 1300))
        DrawingRenderer.apply(sheet, Stroke(DrawingTool.ERASER, 0, 40f, listOf(Stroke.Point(900f, 1300f))))
        assertEquals(0, sheet.getPixel(900, 1300) ushr 24)
        val png = DrawingRenderer.toPng(sheet)
        val back = assertNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        assertEquals(DrawingRenderer.WIDTH, back.width)
        assertEquals(0xFFD32F2F.toInt(), back.getPixel(200, 100))
        assertTrue(DrawingRenderer.pngToJpeg(png).size > 100)
    }

    @Test
    fun textIsMeasuredAndDrawnWithRoboto() {
        DrawingRenderer.useFonts()
        val (w, h) = DrawingRenderer.measureText("Кабинет", 40f, 0)
        assertTrue(w > 100f && h > 30f, "$w x $h")
        val b = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        Canvas(b).drawText("Кабинет", 10f, 60f, DrawingRenderer.textPaint(40f, 0xFF000000.toInt(), 0))
        var inked = 0
        for (x in 0 until 400) for (y in 0 until 100) if (b.getPixel(x, y) ushr 24 > 0) inked++
        assertTrue(inked > 200, "inked $inked")
    }
}
