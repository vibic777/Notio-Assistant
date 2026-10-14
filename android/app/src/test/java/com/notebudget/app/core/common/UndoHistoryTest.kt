package com.notebudget.app.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoHistoryTest {

    @Test
    fun `undo goes back step by step and redo comes forward again`() {
        val h = UndoHistory<String>(limit = 3)
        h.record("a") // a -> b
        h.record("b") // b -> c
        assertEquals("b", h.undo("c"))
        assertEquals("a", h.undo("b"))
        assertNull(h.undo("a"))
        assertTrue(h.canRedo)
        assertEquals("b", h.redo("a"))
        assertEquals("c", h.redo("b"))
        assertNull(h.redo("c"))
    }

    @Test
    fun `only the chosen number of steps is kept, the oldest is forgotten`() {
        val h = UndoHistory<Int>(limit = 3)
        (1..5).forEach { h.record(it) }
        assertEquals(5, h.undo(6))
        assertEquals(4, h.undo(5))
        assertEquals(3, h.undo(4))
        assertNull(h.undo(3))
    }

    @Test
    fun `a new change after an undo drops what could have been redone`() {
        val h = UndoHistory<String>()
        h.record("a")
        h.undo("b")
        assertTrue(h.canRedo)
        h.record("a")
        assertFalse(h.canRedo)
    }

    @Test
    fun `a smaller limit trims both sides and the limit stays in range`() {
        val h = UndoHistory<Int>(limit = 10)
        (1..8).forEach { h.record(it) }
        h.limit = 2
        assertEquals(8, h.undo(9))
        assertEquals(7, h.undo(8))
        assertNull(h.undo(7))
        h.limit = 0
        assertEquals(UndoHistory.MIN_STEPS, h.limit)
        h.limit = 1000
        assertEquals(UndoHistory.MAX_STEPS, h.limit)
    }
}
