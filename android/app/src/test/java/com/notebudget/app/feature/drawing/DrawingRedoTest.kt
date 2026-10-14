package com.notebudget.app.feature.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Redo and the undo limit of the two drawing layers (D197). */
class DrawingRedoTest {
    private fun fill(x: Int) = Fill(x, 0, 0)

    @Test
    fun `raster redo gives back the undone operations in order`() {
        val h = RasterHistory(maxUndo = 3)
        val a = fill(1)
        val b = fill(2)
        h.add(a)
        h.add(b)
        assertTrue(h.undo())
        assertTrue(h.undo())
        assertTrue(h.canRedo)
        assertSame(a, h.redo()!!.op)
        assertSame(b, h.redo()!!.op)
        assertNull(h.redo())
        assertEquals(listOf(a, b), h.ops)
    }

    @Test
    fun `a new raster operation drops the redo, a lower limit hands back what must be baked`() {
        val h = RasterHistory(maxUndo = 5)
        val ops = (1..5).map { fill(it) }
        ops.forEach { h.add(it) }
        h.undo()
        h.add(fill(9))
        assertFalse(h.canRedo)
        val baked = h.setLimit(2)
        assertEquals(ops.take(3), baked)
        assertEquals(2, h.ops.size)
    }

    @Test
    fun `vector redo brings a shape back, the limit follows the setting`() {
        var next = 0
        val e = VectorEditor(newId = { "id${++next}" }, maxUndo = 3)
        fun draw(x: Float) {
            e.tool = VectorTool.RECT
            e.pointerDown(Point(x, x), 1f)
            e.pointerMove(Point(x + 50, x + 50))
            e.pointerUp()
        }
        draw(0f)
        draw(100f)
        e.undo()
        assertEquals(1, e.shapes.size)
        assertTrue(e.canRedo)
        e.redo()
        assertEquals(2, e.shapes.size)
        assertFalse(e.canRedo)
        e.undoLimit = 1
        e.undo()
        assertFalse(e.canUndo)
        assertEquals(1, e.shapes.size)
    }
}
