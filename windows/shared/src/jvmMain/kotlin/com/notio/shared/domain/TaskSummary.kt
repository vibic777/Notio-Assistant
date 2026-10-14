package com.notio.shared.domain

/**
 * The tasks for the quiet notification in the shade (Android D266): how many there are, how many are still open (not Done) and how
 * many of those are past their end.
 */
data class TaskSummary(val count: Int, val active: Int, val overdue: Int) {
    companion object {
        /** `null` when there is no task at all. [notes] are the notes outside the trash. */
        fun of(notes: List<Note>, now: Long): TaskSummary? {
            val tasks = notes.filter { it.type == NoteType.TASK }.map { TaskDocument.decode(it.extras) }
            if (tasks.isEmpty()) return null
            return TaskSummary(tasks.size, tasks.count { !it.done }, tasks.count { it.overdue(now) })
        }
    }
}
