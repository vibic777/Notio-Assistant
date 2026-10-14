package com.notio.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorModelTest {
    private fun shape(type: String, vararg pts: Float, fill: Int? = null, width: Float = 6f) = Shape(
        id = "s", type = type,
        points = pts.toList().chunked(2).map { Point(it[0], it[1]) },
        strokeWidth = width, fillColor = fill,
    )

    // ---- hit testing (finger slack 10 px) ----

    @Test
    fun `a line is hit near its segment and not beyond its ends`() {
        val line = shape("LINE", 0f, 0f, 100f, 0f)
        assertTrue(line.hit(50f, 8f, 10f))
        assertFalse(line.hit(50f, 30f, 10f))
        assertFalse(line.hit(140f, 0f, 10f))
    }

    @Test
    fun `an empty rectangle is hit on its edge only, a filled one inside too`() {
        val empty = shape("RECT", 0f, 0f, 100f, 100f)
        assertTrue(empty.hit(0f, 50f, 10f))
        assertFalse(empty.hit(50f, 50f, 10f))
        assertTrue(empty.copy(fillColor = 0xFFFF0000.toInt()).hit(50f, 50f, 10f))
        assertFalse(empty.copy(fillColor = 0xFFFF0000.toInt()).hit(200f, 200f, 10f))
    }

    @Test
    fun `an ellipse is hit on its outline, filled inside, not outside`() {
        val e = shape("ELLIPSE", 0f, 0f, 200f, 100f)
        assertTrue(e.hit(200f, 50f, 10f))
        assertFalse(e.hit(100f, 50f, 10f))
        assertTrue(e.copy(fillColor = 1).hit(100f, 50f, 10f))
        assertFalse(e.copy(fillColor = 1).hit(0f, 0f, 10f))
    }

    @Test
    fun `a triangle is hit on its edges and, when filled, inside`() {
        val t = shape("TRIANGLE", 0f, 0f, 100f, 100f)
        assertTrue(t.hit(50f, 0f, 10f)) // apex
        assertFalse(t.hit(50f, 60f, 10f))
        assertTrue(t.copy(fillColor = 1).hit(50f, 60f, 10f))
        assertFalse(t.copy(fillColor = 1).hit(5f, 20f, 3f)) // the empty top-left corner of the box
    }

    @Test
    fun `a path is hit along its segments, filled paths inside too`() {
        val path = shape("PATH", 0f, 0f, 100f, 0f, 100f, 100f)
        assertTrue(path.hit(100f, 50f, 10f))
        assertFalse(path.hit(30f, 60f, 10f))
        assertTrue(path.copy(fillColor = 1).hit(70f, 30f, 10f)) // inside the closed triangle
    }

    @Test
    fun `a shape of an unknown kind or without points is never hit`() {
        assertFalse(shape("TRAPEZOID", 0f, 0f, 100f, 100f).hit(50f, 50f, 100f))
        assertFalse(Shape(id = "x", type = "LINE").hit(0f, 0f, 100f))
    }

    @Test
    fun `contains is true anywhere inside a closed shape even without a fill, false for lines`() {
        assertTrue(shape("RECT", 0f, 0f, 300f, 200f).contains(150f, 100f, 10f))
        assertFalse(shape("RECT", 0f, 0f, 300f, 200f).contains(400f, 100f, 10f))
        assertTrue(shape("ELLIPSE", 0f, 0f, 300f, 200f).contains(150f, 100f, 10f))
        assertFalse(shape("ELLIPSE", 0f, 0f, 300f, 200f).contains(5f, 5f, 10f)) // the corner of the box
        assertTrue(shape("TRIANGLE", 0f, 0f, 300f, 200f).contains(150f, 150f, 10f))
        assertFalse(shape("TRIANGLE", 0f, 0f, 300f, 200f).contains(10f, 20f, 3f))
        assertTrue(shape("PATH", 0f, 0f, 200f, 0f, 200f, 200f).contains(150f, 60f, 10f))
        assertFalse(shape("LINE", 0f, 0f, 300f, 0f).contains(150f, 0f, 10f))
        assertFalse(shape("ARROW", 0f, 0f, 300f, 0f).contains(150f, 0f, 10f))
        assertFalse(shape("TRAPEZOID", 0f, 0f, 300f, 200f).contains(150f, 100f, 10f))
    }

    @Test
    fun `a path of fewer than three points encloses nothing`() {
        assertFalse(shape("PATH", 0f, 0f, 100f, 100f).contains(50f, 50f, 10f))
        assertFalse(Shape(id = "x", type = "PATH").contains(0f, 0f, 10f))
    }

    @Test
    fun `area is the size of the bounding box`() {
        assertEquals(60_000f, shape("RECT", 0f, 0f, 300f, 200f).area(), 0f)
    }

    @Test
    fun `only closed kinds can be filled`() {
        assertFalse(shape("LINE", 0f, 0f, 1f, 1f).canFill)
        assertFalse(shape("ARROW", 0f, 0f, 1f, 1f).canFill)
        assertTrue(shape("RECT", 0f, 0f, 1f, 1f).canFill)
        assertTrue(shape("PATH", 0f, 0f, 1f, 1f).canFill)
        assertFalse(shape("TRAPEZOID", 0f, 0f, 1f, 1f).canFill)
    }

    // ---- geometry helpers ----

    @Test
    fun `translate moves every point and handle edit changes one`() {
        val s = shape("LINE", 0f, 0f, 10f, 10f)
        assertEquals(listOf(Point(5f, 7f), Point(15f, 17f)), s.translated(5f, 7f).points)
        assertEquals(listOf(Point(0f, 0f), Point(99f, 1f)), s.withPoint(1, Point(99f, 1f)).points)
        assertEquals(s, s.withPoint(5, Point(1f, 1f))) // out of range: unchanged
    }

    @Test
    fun `simplify drops points on a straight run and keeps corners`() {
        val straight = (0..10).map { Point(it * 10f, it * 5f) }
        assertEquals(listOf(straight.first(), straight.last()), simplify(straight, 1.5f))

        val corner = listOf(Point(0f, 0f), Point(50f, 0f), Point(100f, 0f), Point(100f, 50f), Point(100f, 100f))
        assertEquals(listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 100f)), simplify(corner, 1.5f))

        val two = listOf(Point(0f, 0f), Point(1f, 1f))
        assertEquals(two, simplify(two, 1.5f))
    }

    // ---- the stored file: D84 compatibility ----

    @Test
    fun `a document survives encode and decode`() {
        val doc = VectorDocument(shapes = listOf(shape("RECT", 1f, 2f, 3f, 4f, fill = 0xFF00FF00.toInt())))
        assertEquals(doc, VectorDocument.decode(doc.encode()))
    }

    @Test
    fun `text that is not a vector layer decodes to null and never throws`() {
        assertNull(VectorDocument.decode("not json".toByteArray()))
        assertNull(VectorDocument.decode(ByteArray(0)))
        assertNull(VectorDocument.decode("[1,2,3]".toByteArray()))
    }

    /**
     * Golden file of vector layer version 1. Never edit it to make a test pass: if it stops
     * decoding, files that users already have would stop loading (D84).
     */
    @Test
    fun `golden v1 vector layer still decodes`() {
        val golden = """
            {"formatVersion":1,"shapes":[
              {"id":"a1","type":"RECT","points":[{"x":10.0,"y":20.0},{"x":110.0,"y":220.0}],
               "strokeColor":-16777216,"strokeWidth":6.0,"fillColor":-65536},
              {"id":"a2","type":"LINE","points":[{"x":0.0,"y":0.0},{"x":5.0,"y":5.0}]}
            ]}
        """.trimIndent()
        val doc = VectorDocument.decode(golden.toByteArray())
        assertNotNull(doc)
        assertEquals(listOf("a1", "a2"), doc!!.shapes.map { it.id })
        assertEquals(-65536, doc.shapes[0].fillColor)
        // Fields that the old file did not have come from the defaults.
        assertEquals(6f, doc.shapes[1].strokeWidth, 0f)
        assertNull(doc.shapes[1].fillColor)
    }

    @Test
    fun `a file from a newer app keeps its unknown shape kinds and ignores unknown fields`() {
        val future = """
            {"formatVersion":9,"someNewThing":{"a":1},"shapes":[
              {"id":"n1","type":"TRAPEZOID","points":[{"x":1.0,"y":2.0}],"gradient":"sunset"},
              {"id":"n2","type":"LINE","points":[{"x":0.0,"y":0.0},{"x":1.0,"y":1.0}]}
            ]}
        """.trimIndent()
        val doc = VectorDocument.decode(future.toByteArray())
        assertNotNull(doc)
        assertEquals(2, doc!!.shapes.size)
        assertNull(doc.shapes[0].kind) // not drawn, not hit...
        // ...but saved again untouched instead of being dropped.
        val again = VectorDocument.decode(doc.encode())!!
        assertEquals("TRAPEZOID", again.shapes[0].type)
        assertEquals(listOf(Point(1f, 2f)), again.shapes[0].points)
    }
}
