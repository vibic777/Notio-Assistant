package com.notio.desktop

import com.notio.desktop.NoteUndo.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The cases of the phone's `NoteEditorUndoTest` (D197, D235) on the desktop's [NoteUndo]. */
class NoteUndoTest {
    private var clock = 10_000L
    private val restored = mutableListOf<Snapshot>()

    private fun undo(steps: Int = 3) = NoteUndo(steps, now = { clock }).also {
        it.current = Snapshot("", null)
        it.restore = { s -> restored += s }
    }

    private fun NoteUndo.type(text: String, extras: String? = null) = change(Snapshot(text, extras))

    @Test
    fun quickTypingIsOneStepAPauseStartsTheNext() {
        val u = undo()
        u.type("H"); clock += 200; u.type("He"); clock += 200; u.type("Hel")
        clock += 1_500
        u.type("Hel lo")
        u.undo(); assertEquals("Hel", u.current.content)
        u.undo(); assertEquals("", u.current.content)
        assertFalse(u.canUndo)
        u.redo(); assertEquals("Hel", u.current.content)
        assertEquals(listOf("Hel", "", "Hel"), restored.map { it.content })
    }

    @Test
    fun aLineMovedOrDeletedInTheViewIsAStepOfItsOwnEvenRightAfterTyping() {
        val u = undo()
        u.type("- [ ] a\n- [ ] b")
        clock += 100
        u.endStep(); u.type("- [ ] b\n- [ ] a"); u.endStep()
        clock += 100
        u.type("- [ ] b\n- [ ] a!")
        u.undo(); assertEquals("- [ ] b\n- [ ] a", u.current.content)
        u.undo(); assertEquals("- [ ] a\n- [ ] b", u.current.content)
    }

    @Test
    fun undoOfAListBringsTheTextAndTheStructureBackTogether() {
        val u = undo()
        u.type("milk", "{\"v\":1}")
        clock += 2_000
        u.type("milk, bread", "{\"v\":2}")
        u.undo()
        assertEquals(Snapshot("milk", "{\"v\":1}"), restored.last())
    }

    @Test
    fun theNumberOfStepsFollowsTheSetting() {
        val u = undo(steps = 3)
        repeat(6) { clock += 2_000; u.type("v$it") }
        u.steps = 2
        u.undo(); u.undo()
        assertFalse(u.canUndo)
        assertEquals("v3", u.current.content)
    }

    @Test
    fun aChangeRightAfterAnUndoIsAStepOfItsOwn() {
        val u = undo()
        u.type("a"); clock += 2_000; u.type("ab")
        u.undo()
        clock += 10
        u.type("ax")
        assertTrue(u.canUndo)
        assertFalse(u.canRedo)
        u.undo(); assertEquals("a", u.current.content)
    }
}
