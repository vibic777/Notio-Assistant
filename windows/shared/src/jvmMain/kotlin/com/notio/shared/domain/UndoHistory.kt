package com.notio.shared.domain

/**
 * Undo and redo of one editor (D197): the states before the last changes, and the states that were undone. [limit] is how
 * many steps back are kept (Settings -> Notes -> "Undo steps"); the oldest is forgotten when there are more. A new change
 * after an undo drops what could have been redone, as in every editor.
 *
 * Pure Kotlin and not thread-safe: an editor uses it from its main thread only.
 */
class UndoHistory<T>(limit: Int = DEFAULT_STEPS) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()

    var limit: Int = limit.coerceIn(MIN_STEPS, MAX_STEPS)
        set(value) {
            field = value.coerceIn(MIN_STEPS, MAX_STEPS)
            trim(undo)
            trim(redo)
        }

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Remembers [before], the state just before a change, as one step. */
    fun record(before: T) {
        undo.addLast(before)
        trim(undo)
        redo.clear()
    }

    /** The state to go back to, or `null` when there is none; [current] becomes the step that redo gives back. */
    fun undo(current: T): T? {
        val previous = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        trim(redo)
        return previous
    }

    /** The state that was undone last, or `null`; [current] becomes an undo step again. */
    fun redo(current: T): T? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        trim(undo)
        return next
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    private fun trim(stack: ArrayDeque<T>) {
        while (stack.size > limit) stack.removeFirst()
    }

    companion object {
        const val DEFAULT_STEPS = 3
        const val MIN_STEPS = 1
        const val MAX_STEPS = 50

        /** What Settings offers. */
        val CHOICES = listOf(1, 3, 5, 10, 20, 50)
    }
}
