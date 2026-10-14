package com.notebudget.app.domain.model

/**
 * The tasks for the quiet notification in the shade (D266): how many there are, how many are still open (not Done) and how
 * many of those are past their end.
 */
data class TaskSummary(val count: Int, val active: Int, val overdue: Int) {
    companion object {
        /** `null` when there is no task at all. [notes] are the notes outside the trash. */
        fun of(notes: List<Note>, now: Long): TaskSummary? = ofExtras(notes.filter { it.type == NoteType.TASK }.map { it.extras }, now)

        /** The same from the settings of the task notes alone (D331): counting them does not need their texts. */
        fun ofExtras(extras: List<String?>, now: Long): TaskSummary? {
            val tasks = extras.map { TaskDocument.decode(it) }
            if (tasks.isEmpty()) return null
            return TaskSummary(tasks.size, tasks.count { !it.done }, tasks.count { it.overdue(now) })
        }
    }
}
