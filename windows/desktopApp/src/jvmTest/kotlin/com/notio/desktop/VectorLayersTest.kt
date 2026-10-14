package com.notio.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layers of a blueprint (D98). */
class VectorLayersTest {
    private var next = 0
    private fun editor(shapes: List<Shape> = emptyList(), layers: List<VectorLayer> = emptyList()) =
        VectorEditor(shapes, layers, newId = { "id${++next}" })

    private fun rect(id: String, layer: String?, l: Float = 0f, t: Float = 0f, r: Float = 100f, b: Float = 100f, fill: Int? = 0xFFFF0000.toInt()) =
        Shape(id, "RECT", listOf(Point(l, t), Point(r, b)), fillColor = fill, layerId = layer)

    private fun VectorEditor.draw(tool: VectorTool, from: Point, to: Point) {
        this.tool = tool
        pointerDown(from, 10f)
        pointerMove(to)
        pointerUp()
    }

    private fun VectorEditor.tap(at: Point) {
        pointerDown(at, 10f)
        pointerUp()
    }

    @Test
    fun `a document made before layers has one default layer that holds every shape`() {
        val e = editor(listOf(rect("a", null), rect("b", null)))
        assertEquals(listOf(VectorLayer.DEFAULT_ID), e.layers.map { it.id })
        assertEquals(listOf("a", "b"), e.drawOrder.map { it.id })
        val doc = VectorDocument(shapes = listOf(rect("a", null)))
        assertEquals(listOf(VectorLayer.DEFAULT_ID), doc.allLayers().map { it.id })
        assertEquals(listOf("a"), doc.drawableShapes().map { it.id })
    }

    @Test
    fun `new shapes go on the active layer, which is the top one after adding a layer`() {
        val e = editor()
        e.draw(VectorTool.RECT, Point(0f, 0f), Point(50f, 50f))
        assertEquals(VectorLayer.DEFAULT_ID, e.shapes.single().layerId)
        val top = e.addLayer("Walls")
        assertEquals(top, e.activeLayerId)
        e.draw(VectorTool.RECT, Point(100f, 100f), Point(160f, 160f))
        assertEquals(top, e.shapes.last().layerId)
        assertEquals(listOf(VectorLayer.DEFAULT_ID, top), e.layers.map { it.id })
    }

    @Test
    fun `layers are drawn bottom to top whatever the order the shapes were made in`() {
        val layers = listOf(VectorLayer("low"), VectorLayer("high"))
        val e = editor(listOf(rect("h1", "high"), rect("l1", "low"), rect("h2", "high"), rect("l2", "low")), layers)
        assertEquals(listOf("l1", "l2", "h1", "h2"), e.drawOrder.map { it.id })
        e.moveLayer("low", 1) // now "low" is above "high"
        assertEquals(listOf("h1", "h2", "l1", "l2"), e.drawOrder.map { it.id })
        e.moveLayer("low", 1) // already on top: nothing
        assertEquals(listOf("high", "low"), e.layers.map { it.id })
    }

    @Test
    fun `a layer goes to the very top or the very bottom in one step (D234)`() {
        val e = editor(layers = listOf(VectorLayer("a"), VectorLayer("b"), VectorLayer("c"), VectorLayer("d")))
        e.moveLayerToEdge("b", top = true)
        assertEquals(listOf("a", "c", "d", "b"), e.layers.map { it.id })
        e.moveLayerToEdge("d", top = false)
        assertEquals(listOf("d", "a", "c", "b"), e.layers.map { it.id })
        val version = e.documentVersion
        e.moveLayerToEdge("d", top = false) // already at the bottom: nothing
        e.moveLayerToEdge("missing", top = true)
        assertEquals(version, e.documentVersion)
        assertEquals(listOf("d", "a", "c", "b"), e.layers.map { it.id })
    }

    @Test
    fun `a hidden layer is not drawn, cannot be picked or filled, and takes no new shapes`() {
        val layers = listOf(VectorLayer("a"), VectorLayer("b"))
        val e = editor(listOf(rect("under", "a"), rect("over", "b")), layers)
        e.setLayerVisible("b", false)
        assertEquals(listOf("under"), e.drawOrder.map { it.id })
        // the shape of the hidden layer is on top of the visible one, but a touch reaches the visible one
        e.tool = VectorTool.SELECT
        e.tap(Point(50f, 50f))
        assertEquals("under", e.selectedId)
        e.selectNone()
        e.setActiveLayer("b")
        assertFalse(e.canDrawOnActiveLayer)
        e.draw(VectorTool.RECT, Point(200f, 200f), Point(300f, 300f))
        assertEquals(2, e.shapes.size)
        assertNull(e.draft)
        e.setLayerVisible("b", true)
        assertTrue(e.canDrawOnActiveLayer)
    }

    @Test
    fun `a locked layer is drawn but cannot be picked, changed or drawn on, and locking deselects`() {
        val layers = listOf(VectorLayer("a"), VectorLayer("b"))
        val e = editor(listOf(rect("x", "a")), layers)
        e.tool = VectorTool.SELECT
        e.tap(Point(50f, 50f))
        assertEquals("x", e.selectedId)
        e.setLayerLocked("a", true)
        assertNull("locking the layer of the selected shape deselects it", e.selectedId)
        assertEquals(listOf("x"), e.drawOrder.map { it.id })
        e.tap(Point(50f, 50f))
        assertNull(e.selectedId)
        e.tool = VectorTool.FILL
        e.setFillColor(0xFF00FF00.toInt())
        e.tap(Point(50f, 50f))
        assertEquals(0xFFFF0000.toInt(), e.shapes.single().fillColor) // unchanged
    }

    @Test
    fun `deleting a layer removes its shapes and the last layer cannot be deleted`() {
        val layers = listOf(VectorLayer("a"), VectorLayer("b"))
        val e = editor(listOf(rect("x", "a"), rect("y", "b"), rect("z", "b")), layers)
        e.setActiveLayer("b")
        e.deleteLayer("b")
        assertEquals(listOf("x"), e.shapes.map { it.id })
        assertEquals(listOf("a"), e.layers.map { it.id })
        assertEquals("the active layer falls back to a remaining one", "a", e.activeLayerId)
        assertFalse("nothing to undo: those shapes are gone for good", e.canUndo)
        e.deleteLayer("a")
        assertEquals(listOf("a"), e.layers.map { it.id })
        assertEquals(1, e.shapes.size)
    }

    @Test
    fun `a selected shape moves to another layer and that can be undone`() {
        val layers = listOf(VectorLayer("a"), VectorLayer("b"))
        val e = editor(listOf(rect("x", "a")), layers)
        e.tool = VectorTool.SELECT
        e.tap(Point(50f, 50f))
        e.moveSelectedToLayer("b")
        assertEquals("b", e.shapes.single().layerId)
        e.undo()
        assertEquals("a", e.shapes.single().layerId)
    }

    @Test
    fun `layer edits change the document version so they are saved, and names are trimmed`() {
        val e = editor()
        val before = e.documentVersion
        val id = e.addLayer("  Rooms ")
        e.renameLayer(id, " Big rooms ")
        e.setLayerLocked(id, true)
        assertTrue(e.documentVersion >= before + 3)
        assertEquals("Big rooms", e.layers.first { it.id == id }.name)
    }

    @Test
    fun `the layers and the layer of each shape survive the stored form and an older file still reads`() {
        val doc = VectorDocument(
            shapes = listOf(rect("a", "l1")),
            layers = listOf(VectorLayer("l0", "Base"), VectorLayer("l1", visible = false, locked = true)),
        )
        val back = VectorDocument.decode(doc.encode())!!
        assertEquals(doc, back)
        assertTrue(back.drawableShapes().isEmpty()) // the only shape is on a hidden layer
        val old = """{"formatVersion":1,"shapes":[{"id":"s","type":"RECT","points":[{"x":0,"y":0},{"x":9,"y":9}]}]}"""
        assertEquals(1, VectorDocument.decode(old.toByteArray())!!.drawableShapes().size)
        assertEquals("l1", VectorLayers.layerOf(back.shapes[0], back.layers))
        assertEquals("shapes of an unknown layer belong to the first layer", "l0", VectorLayers.layerOf(rect("q", "nope"), back.layers))
    }

    @Test
    fun `a second finger drops the shape being drawn and a move that was under way`() {
        val e = editor()
        e.tool = VectorTool.RECT
        e.pointerDown(Point(0f, 0f), 10f)
        e.pointerMove(Point(80f, 80f))
        e.pointerCancelDroppingDraft()
        assertNull(e.draft)
        assertTrue(e.shapes.isEmpty())

        val f = editor(listOf(rect("x", "base")))
        f.tool = VectorTool.SELECT
        f.pointerDown(Point(50f, 50f), 10f)
        f.pointerMove(Point(150f, 150f))
        f.pointerCancelDroppingDraft()
        assertEquals(Point(0f, 0f), f.shapes.single().points[0])
    }
}
