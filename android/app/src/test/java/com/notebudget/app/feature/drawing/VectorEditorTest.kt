package com.notebudget.app.feature.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorEditorTest {
    private var next = 0
    private fun editor(initial: List<Shape> = emptyList(), maxUndo: Int = 50) =
        VectorEditor(initial, newId = { "id${++next}" }, maxUndo = maxUndo)

    private fun VectorEditor.draw(tool: VectorTool, from: Point, to: Point) {
        this.tool = tool
        pointerDown(from, TOL)
        pointerMove(to)
        pointerUp()
    }

    private fun rect(id: String, l: Float, t: Float, r: Float, b: Float, fill: Int? = null) =
        Shape(id, "RECT", listOf(Point(l, t), Point(r, b)), fillColor = fill)

    private fun line(id: String, x1: Float, y1: Float, x2: Float, y2: Float) =
        Shape(id, "LINE", listOf(Point(x1, y1), Point(x2, y2)))

    // ---- drawing ----

    @Test
    fun `dragging with a shape tool adds one shape, leaves nothing selected, and can be undone`() {
        val e = editor()
        e.draw(VectorTool.RECT, Point(10f, 10f), Point(110f, 60f))
        assertEquals(1, e.shapes.size)
        assertEquals("RECT", e.shapes[0].type)
        assertEquals(listOf(Point(10f, 10f), Point(110f, 60f)), e.shapes[0].points)
        // Not selected: a selection frame that follows every new shape looks stuck.
        assertNull(e.selectedId)
        assertNull(e.draft)
        assertTrue(e.canUndo)
        assertEquals(1, e.documentVersion)

        e.undo()
        assertTrue(e.shapes.isEmpty())
        assertNull(e.selectedId)
    }

    @Test
    fun `the shape is only a draft until the finger lifts`() {
        val e = editor()
        e.tool = VectorTool.LINE
        e.pointerDown(Point(0f, 0f), TOL)
        e.pointerMove(Point(50f, 50f))
        assertNotNull(e.draft)
        assertTrue(e.shapes.isEmpty())
        assertEquals(0, e.documentVersion)
        assertTrue(e.pointerUp())
        assertNull(e.draft)
        assertEquals(1, e.shapes.size)
    }

    @Test
    fun `a tap or a tiny drag creates nothing`() {
        val e = editor()
        e.draw(VectorTool.ELLIPSE, Point(10f, 10f), Point(11f, 11f))
        assertTrue(e.shapes.isEmpty())
        assertFalse(e.canUndo)
        assertEquals(0, e.documentVersion)
    }

    @Test
    fun `a cancelled gesture drops the draft`() {
        val e = editor()
        e.tool = VectorTool.RECT
        e.pointerDown(Point(0f, 0f), TOL)
        e.pointerMove(Point(80f, 80f))
        e.pointerCancel()
        assertNull(e.draft)
        assertTrue(e.shapes.isEmpty())
    }

    @Test
    fun `a free-hand path is simplified when finished`() {
        val e = editor()
        e.tool = VectorTool.PATH
        e.pointerDown(Point(0f, 0f), TOL)
        for (x in 1..50) e.pointerMove(Point(x * 4f, 0f)) // a straight run of 50 points
        e.pointerUp()
        val path = e.shapes.single()
        assertEquals("PATH", path.type)
        assertEquals(2, path.points.size)
    }

    @Test
    fun `new shapes take the current colours and width, fill only on closed kinds`() {
        val e = editor()
        e.setStrokeColor(0xFF112233.toInt())
        e.setFillColor(0xFF445566.toInt())
        e.setStrokeWidth(12f)
        e.draw(VectorTool.RECT, Point(0f, 0f), Point(50f, 50f))
        e.draw(VectorTool.LINE, Point(0f, 0f), Point(50f, 50f))
        val (r, l) = e.shapes
        assertEquals(0xFF112233.toInt(), r.strokeColor)
        assertEquals(12f, r.strokeWidth, 0f)
        assertEquals(0xFF445566.toInt(), r.fillColor)
        assertNull(l.fillColor)
    }

    // ---- select, move, reshape ----

    @Test
    fun `select picks the topmost shape under the finger`() {
        val e = editor(listOf(rect("under", 0f, 0f, 100f, 100f, fill = 1), rect("over", 0f, 0f, 100f, 100f, fill = 1)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(50f, 50f), TOL)
        e.pointerUp()
        assertEquals("over", e.selectedId)
        e.pointerDown(Point(500f, 500f), TOL) // empty space deselects
        e.pointerUp()
        assertNull(e.selectedId)
    }

    @Test
    fun `dragging a selected shape moves it as one undo step`() {
        val e = editor(listOf(rect("r", 0f, 0f, 100f, 100f)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(0f, 50f), TOL)
        e.pointerMove(Point(10f, 60f))
        e.pointerMove(Point(20f, 70f))
        assertTrue(e.pointerUp())
        assertEquals(listOf(Point(20f, 20f), Point(120f, 120f)), e.shapes[0].points)
        assertEquals(1, e.documentVersion)

        e.undo()
        assertEquals(listOf(Point(0f, 0f), Point(100f, 100f)), e.shapes[0].points)
        assertFalse(e.canUndo)
    }

    @Test
    fun `selecting without moving changes nothing and leaves no undo step`() {
        val e = editor(listOf(rect("r", 0f, 0f, 100f, 100f)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(0f, 50f), TOL)
        assertFalse(e.pointerUp())
        assertFalse(e.canUndo)
        assertEquals(0, e.documentVersion)
    }

    @Test
    fun `dragging an end handle of the selected line moves that end only`() {
        val e = editor(listOf(line("l", 0f, 0f, 100f, 0f)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(50f, 0f), TOL); e.pointerUp() // select
        e.pointerDown(Point(100f, 0f), TOL)
        e.pointerMove(Point(100f, 80f))
        assertTrue(e.pointerUp())
        assertEquals(listOf(Point(0f, 0f), Point(100f, 80f)), e.shapes[0].points)
        e.undo()
        assertEquals(listOf(Point(0f, 0f), Point(100f, 0f)), e.shapes[0].points)
    }

    // ---- picking by touching the inside of an outline-only shape ----

    @Test
    fun `an outline-only shape is picked by touching its middle`() {
        val e = editor(listOf(rect("r", 0f, 0f, 300f, 200f)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(150f, 100f), TOL) // far from every edge
        assertEquals("r", e.selectedId)
        e.pointerUp()
        assertFalse(e.canUndo) // a tap changes nothing
    }

    @Test
    fun `a small shape stays pickable inside a bigger outline and the bigger one is picked elsewhere inside`() {
        val big = rect("big", 0f, 0f, 400f, 400f)
        val small = rect("small", 100f, 100f, 160f, 160f, fill = 1)
        val e = editor(listOf(small, big)) // the big outline is on top
        e.tool = VectorTool.SELECT

        e.pointerDown(Point(130f, 130f), TOL); e.pointerUp()
        assertEquals("small", e.selectedId) // hit on the small one itself, not swallowed by the big outline

        e.pointerDown(Point(300f, 300f), TOL); e.pointerUp()
        assertEquals("big", e.selectedId) // inside the big outline only
    }

    @Test
    fun `of several outlines the smallest one under the finger is picked`() {
        val e = editor(listOf(rect("outer", 0f, 0f, 500f, 500f), rect("inner", 100f, 100f, 300f, 300f)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(200f, 200f), TOL) // inside both, on neither edge
        assertEquals("inner", e.selectedId)
    }

    // ---- finger jitter must not move things ----

    @Test
    fun `a tap with a little finger jitter does not move the shape or make an undo step`() {
        val e = editor(listOf(rect("r", 0f, 0f, 100f, 100f, fill = 1)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(50f, 50f), TOL)
        e.pointerMove(Point(51f, 50f))
        e.pointerMove(Point(52f, 51f))
        assertFalse(e.pointerUp())
        assertEquals(listOf(Point(0f, 0f), Point(100f, 100f)), e.shapes[0].points)
        assertFalse(e.canUndo)
        assertEquals(0, e.documentVersion)
    }

    @Test
    fun `once the finger really moves the shape follows the whole distance without a jump`() {
        val e = editor(listOf(rect("r", 0f, 0f, 100f, 100f, fill = 1)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(50f, 50f), TOL)
        e.pointerMove(Point(52f, 50f)) // still jitter
        e.pointerMove(Point(80f, 70f)) // a real drag
        e.pointerUp()
        assertEquals(listOf(Point(30f, 20f), Point(130f, 120f)), e.shapes[0].points)
    }

    @Test
    fun `touching empty space clears the selection and a second tap on a shape selects it again`() {
        val e = editor(listOf(rect("a", 0f, 0f, 100f, 100f, fill = 1), rect("b", 300f, 0f, 400f, 100f, fill = 1)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(50f, 50f), TOL); e.pointerUp()
        assertEquals("a", e.selectedId)
        e.pointerDown(Point(350f, 50f), TOL); e.pointerUp() // straight to the other shape
        assertEquals("b", e.selectedId)
        e.pointerDown(Point(700f, 700f), TOL); e.pointerUp()
        assertNull(e.selectedId)
        e.pointerDown(Point(50f, 50f), TOL); e.pointerUp()
        assertEquals("a", e.selectedId)
    }

    // ---- fill tool ----

    @Test
    fun `the fill tool fills a closed shape and skips lines and empty space`() {
        val e = editor(listOf(rect("r", 0f, 0f, 100f, 100f, fill = 1), line("l", 200f, 0f, 300f, 0f)))
        e.setFillColor(0xFF00FF00.toInt())
        e.tool = VectorTool.FILL
        e.pointerDown(Point(50f, 50f), TOL); e.pointerUp()
        assertEquals(0xFF00FF00.toInt(), e.shapes[0].fillColor)
        val versionAfterRect = e.documentVersion

        e.pointerDown(Point(250f, 0f), TOL); e.pointerUp() // a line cannot be filled
        e.pointerDown(Point(900f, 900f), TOL); e.pointerUp() // nothing there
        assertNull(e.shapes[1].fillColor)
        assertEquals(versionAfterRect, e.documentVersion)
    }

    @Test
    fun `the fill tool paints an outline-only shape when touched inside it, not just on its edge`() {
        val e = editor(listOf(rect("r", 0f, 0f, 300f, 200f), Shape("o", "ELLIPSE", listOf(Point(400f, 0f), Point(700f, 200f)))))
        e.setFillColor(0xFF00FF00.toInt())
        e.tool = VectorTool.FILL
        e.pointerDown(Point(150f, 100f), TOL); e.pointerUp() // the middle of the rectangle
        e.pointerDown(Point(550f, 100f), TOL); e.pointerUp() // the middle of the ellipse
        assertEquals(0xFF00FF00.toInt(), e.shapes[0].fillColor)
        assertEquals(0xFF00FF00.toInt(), e.shapes[1].fillColor)
        assertNull(e.selectedId) // filling does not leave a selection behind
    }

    @Test
    fun `choosing the fill tool with no fill colour picks one, so the first tap paints something`() {
        val e = editor(listOf(rect("r", 0f, 0f, 300f, 200f)))
        assertNull(e.fillColor)
        e.tool = VectorTool.FILL
        assertEquals(VectorEditor.DEFAULT_FILL, e.fillColor)
        e.pointerDown(Point(150f, 100f), TOL); e.pointerUp()
        assertEquals(VectorEditor.DEFAULT_FILL, e.shapes[0].fillColor)
    }

    @Test
    fun `the fill tool changes the document at the touch down, so the view model can see there is something to save (D162)`() {
        val e = editor(listOf(rect("r", 0f, 0f, 300f, 200f)))
        e.tool = VectorTool.FILL
        val before = e.documentVersion
        e.pointerDown(Point(150f, 100f), TOL)
        assertEquals("the version moves on the DOWN, not on the lift", before + 1, e.documentVersion)
        assertEquals(false, e.pointerUp())
    }

    @Test
    fun `an explicit no-fill choice is respected and clears a fill instead of being replaced by the default`() {
        val e = editor(listOf(rect("r", 0f, 0f, 300f, 200f, fill = 0xFFFF0000.toInt())))
        e.tool = VectorTool.FILL
        e.setFillColor(null) // the user picks "no fill"
        assertNull(e.fillColor)
        e.pointerDown(Point(150f, 100f), TOL); e.pointerUp()
        assertNull(e.shapes[0].fillColor)
    }

    @Test
    fun `the fill tool fills the topmost closed shape under the finger`() {
        val e = editor(listOf(rect("under", 0f, 0f, 400f, 400f), rect("over", 100f, 100f, 300f, 300f)))
        e.setFillColor(0xFF0000FF.toInt())
        e.tool = VectorTool.FILL
        e.pointerDown(Point(200f, 200f), TOL); e.pointerUp()
        assertNull(e.shapes[0].fillColor)
        assertEquals(0xFF0000FF.toInt(), e.shapes[1].fillColor)
        e.pointerDown(Point(50f, 350f), TOL); e.pointerUp() // only inside the big one
        assertEquals(0xFF0000FF.toInt(), e.shapes[0].fillColor)
    }

    @Test
    fun `filling with the colour a shape already has is not an undo step`() {
        val red = 0xFFFF0000.toInt()
        val e = editor(listOf(rect("r", 0f, 0f, 100f, 100f, fill = red)))
        e.setFillColor(red)
        e.tool = VectorTool.FILL
        e.pointerDown(Point(50f, 50f), TOL); e.pointerUp()
        assertFalse(e.canUndo)
    }

    @Test
    fun `no fill colour clears the fill of a shape under the fill tool`() {
        val e = editor(listOf(rect("r", 0f, 0f, 100f, 100f, fill = 0xFFFF0000.toInt())))
        e.tool = VectorTool.FILL // choosing the tool picks a default colour...
        e.setFillColor(null) // ...and the user then chooses "no fill"
        e.pointerDown(Point(50f, 50f), TOL); e.pointerUp()
        assertNull(e.shapes[0].fillColor)
        assertTrue(e.canUndo)
    }

    // ---- selection actions ----

    @Test
    fun `delete removes the selected shape and undo brings it back`() {
        val e = editor(listOf(rect("a", 0f, 0f, 10f, 10f), rect("b", 20f, 20f, 30f, 30f)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(20f, 25f), TOL); e.pointerUp()
        e.deleteSelected()
        assertEquals(listOf("a"), e.shapes.map { it.id })
        assertNull(e.selectedId)
        e.undo()
        assertEquals(listOf("a", "b"), e.shapes.map { it.id })
    }

    @Test
    fun `duplicate adds an offset copy with a new id and selects it`() {
        val e = editor(listOf(rect("a", 0f, 0f, 10f, 10f)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(0f, 5f), TOL); e.pointerUp()
        e.duplicateSelected()
        assertEquals(2, e.shapes.size)
        val copy = e.shapes[1]
        assertTrue(copy.id != "a")
        assertEquals(copy.id, e.selectedId)
        assertEquals(listOf(Point(30f, 30f), Point(40f, 40f)), copy.points)
    }

    @Test
    fun `z-order actions move the selected shape and stop at the ends`() {
        fun order(e: VectorEditor) = e.shapes.map { it.id }
        val e = editor(listOf(rect("a", 0f, 0f, 10f, 10f, 1), rect("b", 100f, 0f, 110f, 10f, 1), rect("c", 200f, 0f, 210f, 10f, 1)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(105f, 5f), TOL); e.pointerUp() // select b
        e.bringForward(); assertEquals(listOf("a", "c", "b"), order(e))
        val version = e.documentVersion
        e.bringForward(); assertEquals(listOf("a", "c", "b"), order(e)) // already on top
        assertEquals(version, e.documentVersion)
        e.sendBackward(); assertEquals(listOf("a", "b", "c"), order(e))
        e.sendToBack(); assertEquals(listOf("b", "a", "c"), order(e))
        e.bringToFront(); assertEquals(listOf("a", "c", "b"), order(e))
        // Front / back are undo steps like every change of the shapes (D234).
        e.undo(); assertEquals(listOf("b", "a", "c"), order(e))
        e.redo(); assertEquals(listOf("a", "c", "b"), order(e))
    }

    // ---- style ----

    @Test
    fun `style changes restyle the selected shape only while the select tool is active`() {
        val e = editor(listOf(rect("r", 0f, 0f, 100f, 100f), line("l", 200f, 0f, 300f, 0f)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(0f, 50f), TOL); e.pointerUp()
        e.setStrokeColor(0xFF0000FF.toInt())
        e.setStrokeWidth(20f)
        e.setFillColor(0xFFFFFF00.toInt())
        assertEquals(0xFF0000FF.toInt(), e.shapes[0].strokeColor)
        assertEquals(20f, e.shapes[0].strokeWidth, 0f)
        assertEquals(0xFFFFFF00.toInt(), e.shapes[0].fillColor)
        assertEquals(6f, e.shapes[1].strokeWidth, 0f) // untouched

        e.tool = VectorTool.LINE // a drawing tool: only the defaults change
        val before = e.shapes
        e.setStrokeColor(0xFF00FF00.toInt())
        assertEquals(before, e.shapes)
    }

    @Test
    fun `a fill colour never reaches a line`() {
        val e = editor(listOf(line("l", 0f, 0f, 100f, 0f)))
        e.tool = VectorTool.SELECT
        e.pointerDown(Point(50f, 0f), TOL); e.pointerUp()
        e.setFillColor(0xFFFF0000.toInt())
        assertNull(e.shapes[0].fillColor)
    }

    // ---- undo depth and loading ----

    @Test
    fun `undo depth is bounded`() {
        val e = editor(maxUndo = 3)
        repeat(5) { e.draw(VectorTool.RECT, Point(it * 100f, 0f), Point(it * 100f + 50f, 50f)) }
        assertEquals(5, e.shapes.size)
        repeat(10) { e.undo() }
        assertEquals(2, e.shapes.size) // only the last 3 steps could be undone
        assertFalse(e.canUndo)
    }

    @Test
    fun `load replaces the document and clears undo and selection`() {
        val e = editor()
        e.draw(VectorTool.RECT, Point(0f, 0f), Point(50f, 50f))
        e.load(listOf(line("l", 0f, 0f, 1f, 1f)))
        assertEquals(listOf("l"), e.shapes.map { it.id })
        assertNull(e.selectedId)
        assertFalse(e.canUndo)
    }

    private companion object {
        const val TOL = 10f
    }
}
