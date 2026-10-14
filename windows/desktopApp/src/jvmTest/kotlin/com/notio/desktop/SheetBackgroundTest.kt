package com.notio.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet background (Android D265): the colour with its see-through, old documents staying white, the eyedropper's blending. */
class SheetBackgroundTest {
    @Test
    fun `an opaque background is the colour at full strength`() {
        assertEquals(0xFF336699.toInt(), SheetBackground(0x336699).argb)
        assertEquals(0xFFFFFFFF.toInt(), SheetBackground.DEFAULT.argb)
    }

    @Test
    fun `transparency 100 means no background, 50 half, 0 opaque`() {
        assertFalse(SheetBackground(0xFF0000, transparent = true, transparency = 100).visible)
        assertEquals(128, SheetBackground(0xFF0000, transparent = true, transparency = 50).argb ushr 24)
        assertEquals(255, SheetBackground(0xFF0000, transparent = true, transparency = 0).argb ushr 24)
        // The slider value is kept while "Transparent" is off, but does nothing.
        assertEquals(255, SheetBackground(0xFF0000, transparent = false, transparency = 100).argb ushr 24)
    }

    @Test
    fun `a document made before has a white opaque sheet and a new one keeps its background`() {
        val old = VectorDocument.decode("""{"formatVersion":1,"shapes":[]}""".toByteArray())!!
        assertEquals(SheetBackground.DEFAULT, old.background())
        val doc = VectorDocument(sheetColor = 0x00FF00, sheetTransparent = true, sheetTransparency = 30)
        val back = VectorDocument.decode(doc.encode())!!.background()
        assertEquals(SheetBackground(0x00FF00, true, 30), back)
        assertTrue(back.visible)
    }

    @Test
    fun `over blends a see-through colour on what is under it`() {
        assertEquals(0xFF123456.toInt(), SheetBackground.over(0xFF123456.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), SheetBackground.over(0, 0xFFFFFFFF.toInt()))
        val half = SheetBackground.over(0x80000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(255, half ushr 24)
        assertEquals(127, (half shr 16) and 0xFF)
    }

    @Test
    fun `the exported picture carries the sheet background, a fully see-through sheet stays transparent`() {
        // Android's bug of 0.9.70 checked here: a PNG with a see-through sheet shows black when pasted into most apps.
        fun corner(bg: SheetBackground): Int {
            val png = DrawingRenderer.flatten(null, emptyList(), 40, 30, bg)
            return android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)!!.getPixel(0, 0)
        }
        assertEquals(0xFFFFFFFF.toInt(), corner(SheetBackground.DEFAULT))
        assertEquals(0xFF336699.toInt(), corner(SheetBackground(0x336699)))
        assertEquals(0, corner(SheetBackground(0x336699, transparent = true, transparency = 100)) ushr 24)
    }
}
