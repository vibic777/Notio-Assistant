package com.notebudget.app.feature.drawing

import com.notebudget.app.data.drawing.DrawingStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterHistoryTest {
    private fun stroke(n: Int) = Stroke(DrawingTool.PENCIL, 0, n.toFloat(), listOf(Stroke.Point(0f, 0f)))

    @Test
    fun `undo removes the latest stroke first`() {
        val h = RasterHistory()
        val a = stroke(1); val b = stroke(2)
        h.add(a); h.add(b)
        assertTrue(h.undo())
        assertEquals(listOf(a), h.ops)
        assertTrue(h.undo())
        assertFalse(h.canUndo)
        assertFalse(h.undo())
    }

    @Test
    fun `strokes beyond the undo depth are handed back to be baked into the image`() {
        val h = RasterHistory(maxUndo = 2)
        val a = stroke(1)
        assertNull(h.add(a))
        assertNull(h.add(stroke(2)))
        assertSame(a, h.add(stroke(3)))
        assertEquals(2, h.ops.size)
    }

    @Test
    fun `file store writes atomically, replaces, and refuses ids that escape the folder`() = runBlocking {
        val dir = Files.createTempDirectory("store").toFile().also { it.deleteOnExit() }
        val store = DrawingStore(File(dir, "d"))
        assertNull(store.read("n1"))
        store.write("n1", byteArrayOf(1, 2))
        store.write("n1", byteArrayOf(3, 4, 5))
        assertArrayEquals(byteArrayOf(3, 4, 5), store.read("n1"))
        assertEquals(listOf("n1.png"), File(dir, "d").list()!!.toList()) // no temp file left

        store.write("../escape", byteArrayOf(9))
        assertNull(store.read("../escape"))
        assertFalse(File(dir, "escape.png").exists())

        store.delete("n1")
        assertNull(store.read("n1"))
    }
}
