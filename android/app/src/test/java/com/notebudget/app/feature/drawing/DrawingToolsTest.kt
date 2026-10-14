package com.notebudget.app.feature.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shapes, text and eyedropper of drawings and blueprints (D212). */
class DrawingToolsTest {
    private var next = 0
    private fun editor() = VectorEditor(newId = { "id${++next}" }, maxUndo = 10)

    private fun VectorEditor.draw(tool: VectorTool, from: Point, to: Point) {
        this.tool = tool
        pointerDown(from, 4f)
        pointerMove(to)
        pointerUp()
    }

    @Test
    fun `polygons have the right corners and are hit on their edges`() {
        fun shape(type: ShapeType) = Shape("s", type.name, listOf(Point(0f, 0f), Point(100f, 100f)))
        assertEquals(5, shape(ShapeType.PENTAGON).polygon().size)
        assertEquals(6, shape(ShapeType.HEXAGON).polygon().size)
        assertEquals(10, shape(ShapeType.STAR).polygon().size)
        val rhombus = shape(ShapeType.RHOMBUS)
        assertEquals(listOf(Point(50f, 0f), Point(100f, 50f), Point(50f, 100f), Point(0f, 50f)), rhombus.polygon())
        assertTrue(rhombus.hit(75f, 25f, 3f))        // on an edge
        assertFalse(rhombus.hit(50f, 50f, 3f))       // the empty middle of an outline
        assertTrue(rhombus.contains(50f, 50f, 0f))   // but inside for the fill tool
        assertFalse(rhombus.contains(5f, 5f, 0f))    // a corner of the box is outside a rhombus
        assertTrue(shape(ShapeType.DOUBLE_ARROW).hit(50f, 50f, 2f))
    }

    @Test
    fun `the square and circle tools keep the sides equal`() {
        val e = editor()
        e.draw(VectorTool.SQUARE, Point(10f, 10f), Point(110f, 40f))
        val b = e.shapes.single().bounds()
        assertEquals("RECT", e.shapes.single().type)
        assertEquals(b.width, b.height, 0.01f)
        e.draw(VectorTool.CIRCLE, Point(200f, 200f), Point(150f, 280f))
        val c = e.shapes.last().bounds()
        assertEquals("ELLIPSE", e.shapes.last().type)
        assertEquals(80f, c.width, 0.01f)
        assertEquals(80f, c.height, 0.01f)
    }

    @Test
    fun `a text is added, picked, changed and undone, and it survives saving`() {
        val e = editor()
        val text = Shape("t", ShapeType.TEXT.name, listOf(Point(10f, 10f), Point(210f, 70f)), text = "Hello", textSize = 56f, textStyle = Shape.TEXT_BOLD)
        e.add(text)
        assertEquals(1, e.shapes.size)
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(100f, 40f), 4f)
        e.pointerUp()
        assertEquals("t", e.selected?.id)
        e.changeSelected { it.copy(text = "Hi") }
        assertEquals("Hi", e.shapes.single().text)
        e.undo()
        assertEquals("Hello", e.shapes.single().text)
        val doc = VectorDocument.decode(VectorDocument(shapes = e.shapes).encode())!!
        assertEquals(e.shapes, doc.shapes)
        // The tools that act by a tap drop the selection, so a picked colour never recolours a shape by accident.
        e.tool = VectorTool.EYEDROPPER
        assertNull(e.selected)
    }

    @Test
    fun `the eyedropper takes the fill inside a filled shape and the lines elsewhere`() {
        val e = editor()
        e.add(Shape("r", "RECT", listOf(Point(0f, 0f), Point(100f, 100f)), strokeColor = 0xFF0000FF.toInt(), fillColor = 0xFFFF0000.toInt()))
        e.add(Shape("l", "LINE", listOf(Point(200f, 0f), Point(200f, 100f)), strokeColor = 0xFF00FF00.toInt()))
        assertEquals(0xFFFF0000.toInt(), e.colorAt(Point(50f, 50f), 4f))
        assertEquals(0xFF00FF00.toInt(), e.colorAt(Point(201f, 50f), 4f))
        assertNull(e.colorAt(Point(500f, 500f), 4f))
    }

    @Test
    fun `raster shapes and texts are undoable operations like strokes`() {
        val h = RasterHistory(maxUndo = 3)
        val stamp = ShapeStamp(Shape("s", "STAR", listOf(Point(0f, 0f), Point(50f, 50f))))
        h.add(stamp)
        assertTrue(h.undo())
        assertEquals(stamp, h.redo()!!.op)
    }
}
