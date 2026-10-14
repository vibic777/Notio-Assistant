package com.notebudget.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The status of a task (D337), in the order of the work — the order a folder of tasks is sorted by. [icon] is the name of its
 * icon in `AppIcons`, [key] the name of its text. The three [ended] ones finish a task: it is not overdue and not open any more.
 */
enum class TaskState(val icon: String, val key: String, val ended: Boolean = false) {
    CREATED("Add", "task_state_created"),
    QUEUED("PlaylistAdd", "task_state_queued"),
    PLANNED("Event", "task_state_planned"),
    IN_PROGRESS("PlayCircle", "task_state_in_progress"),
    PAUSED("PauseCircle", "task_state_paused"),
    POSTPONED("MoreTime", "task_state_postponed"),
    WAITING("Schedule", "task_state_waiting"),
    REVIEW("Visibility", "task_state_review"),
    DONE("TaskAlt", "task_state_done", ended = true),
    CLOSED("Lock", "task_state_closed", ended = true),
    CANCELLED("Close", "task_state_cancelled", ended = true);

    companion object {
        fun of(name: String?): TaskState? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A task (D239): what is kept in `note.extras` next to its description (the note's `contentText`, searched like any text)
 * and its name (the note's title). [start] and [end] are epoch millis or `null` when not set.
 *
 * D337: [status] is the name of a [TaskState] and [priority] a number 0..[MAX_PRIORITY] or none. [done] is still written — true
 * for a status that ends the task — so that an older Notio, which knows only the tick, shows the task right; a task it saved has
 * no status and is read by its tick.
 *
 * D358: the tick "completed" is [ended] — the task has a status that ends it. Ticking makes it Done and keeps the status it
 * had in [before]; clearing the tick gives that status back (In work when there is none to give back).
 */
@Serializable
data class TaskDocument(
    val comment: String = "",
    val start: Long? = null,
    val end: Long? = null,
    val done: Boolean = false,
    val status: String? = null,
    val priority: Int? = null,
    val before: String? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    /** The status; a task without one (made before D337, or saved by an older Notio) is Done by its tick, otherwise In work. */
    val state: TaskState get() = TaskState.of(status) ?: if (done) TaskState.DONE else TaskState.IN_PROGRESS

    /** The tick "completed": Done, Closed and Cancelled are. */
    val ended: Boolean get() = state.ended

    /** With the status [state]; a task that ends remembers the status it left, one that is open again forgets it. */
    fun with(state: TaskState): TaskDocument = copy(
        status = state.name, done = state.ended,
        before = if (!state.ended) null else if (ended) before else this.state.name,
    )

    /** The tick set or cleared: Done, or back to the status it had before it ended. */
    fun ticked(on: Boolean): TaskDocument = when {
        on == ended -> this
        on -> with(TaskState.DONE)
        else -> with(TaskState.of(before)?.takeIf { !it.ended } ?: TaskState.IN_PROGRESS)
    }

    /** Not ended and its end has passed. */
    fun overdue(now: Long): Boolean = !done && end != null && end < now

    /** An end before the start cannot be saved. */
    val valid: Boolean get() = start == null || end == null || end >= start

    companion object {
        const val MAX_COMMENT = 1000
        const val MAX_PRIORITY = 65_535
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** What a new task starts with. */
        val NEW: TaskDocument = TaskDocument().with(TaskState.CREATED)

        /** The task of [extras]; an empty one for a new or unreadable note. */
        fun decode(extras: String?): TaskDocument =
            extras?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
                ?.let { it.copy(priority = it.priority?.coerceIn(0, MAX_PRIORITY)) } ?: TaskDocument()

        /** What a priority field holds after [typed] was typed or pasted into it: its digits alone, no more than [MAX_PRIORITY]. */
        fun priorityText(typed: String): String {
            val digits = typed.filter { it in '0'..'9' }.trimStart('0').take(6)
            return when {
                digits.isEmpty() -> if (typed.any { it == '0' }) "0" else ""
                digits.toInt() > MAX_PRIORITY -> MAX_PRIORITY.toString()
                else -> digits
            }
        }

        /** The priority of what a field holds; none for an empty one. */
        fun priorityOf(text: String): Int? = priorityText(text).toIntOrNull()
    }
}

/**
 * Which tasks a folder of tasks or a search shows (D338): the ones of the chosen statuses, all when none is chosen.
 * [done] (D358) narrows them to the completed ones (`true`) or to the ones still open (`false`); `null` asks nothing.
 */
object TaskFilter {
    fun matches(note: Note, states: Set<TaskState>, done: Boolean? = null): Boolean {
        if (states.isEmpty() && done == null) return true
        if (note.type != NoteType.TASK) return false
        val state = TaskDocument.decode(note.extras).state
        return (states.isEmpty() || state in states) && (done == null || state.ended == done)
    }

    /** How many of the tasks counted in [counts] are completed. */
    fun doneOf(counts: Map<TaskState, Int>): Int = counts.entries.sumOf { if (it.key.ended) it.value else 0 }

    /** How many of [notes] have each status; the statuses no task has are not in it. */
    fun counts(notes: List<Note>): Map<TaskState, Int> {
        val out = java.util.EnumMap<TaskState, Int>(TaskState::class.java)
        notes.forEach { if (it.type == NoteType.TASK) out.merge(TaskDocument.decode(it.extras).state, 1, Int::plus) }
        return out
    }
}

/** What the calendar needs of a task (D239). */
data class ScheduledTask(val noteId: String, val title: String, val start: Long, val end: Long, val done: Boolean, val colorArgb: Int?)
