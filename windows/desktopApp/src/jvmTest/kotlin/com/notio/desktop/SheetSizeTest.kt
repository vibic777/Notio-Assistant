package com.notio.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The size of a drawing / blueprint sheet (D260). */
class SheetSizeTest {

    @Test
    fun `mm and cm are turned into pixels at 150 per inch and back`() {
        // A4: 210 x 297 mm.
        assertEquals(1240, SheetUnit.MM.toPixels(210.0))
        assertEquals(1754, SheetUnit.MM.toPixels(297.0))
        assertEquals(1240, SheetUnit.CM.toPixels(21.0))
        assertEquals(210.0, SheetUnit.MM.fromPixels(1240), 0.1)
        assertEquals(21.0, SheetUnit.CM.fromPixels(1240), 0.05)
        assertEquals(1080, SheetUnit.PX.toPixels(1080.0))
        assertEquals(SheetUnit.PX, SheetUnit.of("inch"))
        assertEquals(SheetUnit.CM, SheetUnit.of("cm"))
    }

    @Test
    fun `too small and too large sizes are refused`() {
        assertNull(SheetSize.problem(1920, 1080))
        assertNull(SheetSize.problem(64, 4096))
        assertEquals(SheetSizeProblem.TOO_SMALL, SheetSize.problem(63, 500))
        assertEquals(SheetSizeProblem.TOO_LARGE, SheetSize.problem(4097, 100))
        assertEquals(SheetSizeProblem.TOO_LARGE, SheetSize.problem(3000, 3000))
        assertEquals(SheetSize.DEFAULT_WIDTH to SheetSize.DEFAULT_HEIGHT, SheetSize.safe(0, 0))
        assertEquals(1080 to 1920, SheetSize.safe(1080, 1920))
        // The ready ratios are all within the limits.
        SheetSize.PRESETS.forEach { assertNull(it.label, SheetSize.problem(it.width, it.height)) }
    }

    @Test
    fun `a document made before D260 has the old 3 by 4 sheet, a new one keeps its size`() {
        val old = VectorDocument.decode("""{"formatVersion":1,"shapes":[]}""".toByteArray())!!
        assertEquals(1080 to 1440, old.sheet())
        val wide = VectorDocument(sheetWidth = 1920, sheetHeight = 1080, sheetUnit = "mm")
        val back = VectorDocument.decode(wide.encode())!!
        assertEquals(1920 to 1080, back.sheet())
        assertEquals("mm", back.sheetUnit)
    }

    @Test
    fun `the numbers of the dialog accept a comma and show no needless zero`() {
        assertEquals(21.5, parseSize(" 21,5 ")!!, 0.0)
        assertNull(parseSize("abc"))
        assertNull(parseSize("-3"))
        assertEquals("1080", formatSize(1080.0))
        assertEquals("21.5", formatSize(21.5))
    }
}
