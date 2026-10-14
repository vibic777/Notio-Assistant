package com.notio.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.notio.shared.domain.UndoHistory

/**
 * Undo / Redo of the text and the structure of a Markdown, formatted, list or schedule note (D197): the phone's
 * `NoteEditorViewModel` without the screen. Changes that come quickly one after another (typing a word) are one step; a pause of
 * [coalesceMillis] starts the next. The title is not part of it. [restore] puts a snapshot back into the editor and saves it.
 */
class NoteUndo(
    steps: Int = UndoHistory.DEFAULT_STEPS,
    private val now: () -> Long = System::currentTimeMillis,
    private val coalesceMillis: Long = 1_000,
) : UndoTarget {
    /** Text and structure together: what one undo step brings back. */
    data class Snapshot(val content: String, val extras: String?)

    private val history = UndoHistory<Snapshot>(steps)
    private var lastEditAt = Long.MIN_VALUE / 2

    /** Set by the note screen: shows [Snapshot] in the editor and saves it. */
    var restore: (Snapshot) -> Unit = {}

    /** What the editor shows now; kept by the note screen. */
    var current: Snapshot = Snapshot("", null)

    override var canUndo by mutableStateOf(false)
        private set
    override var canRedo by mutableStateOf(false)
        private set

    var steps: Int
        get() = history.limit
        set(value) { history.limit = value; sync() }

    /** A change from [current] to [next]: remembered for undo (unless it continues the step being typed). */
    fun change(next: Snapshot) {
        if (next == current) return
        val at = now()
        if (at - lastEditAt >= coalesceMillis) history.record(current)
        lastEditAt = at
        current = next
        sync()
    }

    override fun undo() {
        val previous = history.undo(current) ?: return
        back(previous)
    }

    override fun redo() {
        val next = history.redo(current) ?: return
        back(next)
    }

    override fun endStep() {
        lastEditAt = Long.MIN_VALUE / 2
    }

    private fun back(snapshot: Snapshot) {
        // The next change after an undo or redo is a step of its own, however soon it comes.
        lastEditAt = Long.MIN_VALUE / 2
        current = snapshot
        sync()
        restore(snapshot)
    }

    private fun sync() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }
}
