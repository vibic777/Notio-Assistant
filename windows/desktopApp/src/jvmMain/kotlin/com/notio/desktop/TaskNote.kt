package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.Note
import com.notio.shared.domain.TaskDocument
import com.notio.shared.domain.TaskState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Opens the reminders of a note, and the reminders of the open note (the note screen provides them; `null` elsewhere). */
val LocalOpenReminders = staticCompositionLocalOf<((String) -> Unit)?> { null }
val LocalNoteReminders = staticCompositionLocalOf<List<com.notio.shared.domain.Reminder>> { emptyList() }

/** Changes the task of a note from its card (D117; the main screen provides it, `null` elsewhere: the card only shows). */
val LocalTaskChange = staticCompositionLocalOf<((String, TaskDocument) -> Unit)?> { null }

/** Asks to delete the note of a task, by its id (D142, Android D361): the bin on its card and in View. `null` - no bin. */
val LocalTaskDelete = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** How the tests find the bin of a task. */
const val TASK_DELETE = "task_delete"

/** How the tests find the button of the status, the lines of its list and the field of the priority. */
const val TASK_STATE_BUTTON = "task-state"
const val TASK_STATE_ITEM = "task-state-item-"
const val TASK_PRIORITY_FIELD = "task-priority"
const val TASK_STATE_CHIP = "task-chip-"
const val TASK_DONE_BOX = "task-done"
const val TASK_DONE_CHIP = "task-done-chip-"

@Composable
fun TaskStateIcon(state: TaskState, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    androidx.compose.material3.Icon(AppIcons.byName(state.icon) ?: AppIcons.TaskAlt, contentDescription = null, modifier = modifier.size(20.dp), tint = tint)
}

/** The section of the help the hints of a task lead to (D122). */
private const val TASK_HELP = "tasks"

/**
 * The status of a task: its icon alone (D122), its word is the hint of the icon — and a press on the hint opens the help, as with
 * every hint. "Overdue" stands in red beside it when the end of the task has passed. With [onPick] the icon is a button: a press
 * opens the list of all statuses, each with its icon and its word.
 */
@Composable
fun TaskStateButton(task: TaskDocument, onPick: ((TaskState) -> Unit)?, now: Long = System.currentTimeMillis()) {
    var open by remember { mutableStateOf(false) }
    val state = task.state
    val color = when {
        state == TaskState.DONE -> MaterialTheme.colorScheme.primary
        state.ended -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val word = stringResource(state.key)
    val choose = stringResource("task_state")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            HoverTip(word, topic = TASK_HELP) {
                Box(
                    Modifier.clip(MaterialTheme.shapes.small)
                        .then(
                            if (onPick == null) Modifier
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                                .clickable(onClickLabel = choose, role = Role.DropdownList) { open = true },
                        )
                        .padding(6.dp)
                        .semantics { contentDescription = word }
                        .testTag(TASK_STATE_BUTTON),
                    contentAlignment = Alignment.Center,
                ) { TaskStateIcon(state, color) }
            }
            if (onPick != null) DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                TaskState.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(stringResource(s.key), fontWeight = if (s == state) FontWeight.Bold else null) },
                        onClick = { open = false; if (s != state) onPick(s) },
                        leadingIcon = { TaskStateIcon(s, if (s == state) MaterialTheme.colorScheme.primary else LocalContentColor.current) },
                        modifier = Modifier.testTag(TASK_STATE_ITEM + s.name),
                    )
                }
            }
        }
        if (task.overdue(now)) Text(stringResource("task_overdue"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * The priority of a task (D117, D122): a small field for the number and nothing else — digits alone, 0..65535, empty for none.
 * What it is says its hint. With [onLeave] the number is handed on when the field is left or Done is pressed (the card of a task:
 * a list sorted by priority must not move under the fingers that type), else at every change.
 */
@Composable
fun TaskPriorityField(priority: Int?, onChange: (Int?) -> Unit, modifier: Modifier = Modifier, onLeave: Boolean = false) {
    var text by remember(priority) { mutableStateOf(priority?.toString().orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val label = stringResource("task_priority")
    fun commit() { text.toIntOrNull().let { if (it != priority) onChange(it) } }
    HoverTip(label, topic = TASK_HELP) {
        BasicTextField(
            value = text,
            onValueChange = { typed ->
                text = TaskDocument.priorityText(typed)
                if (!onLeave) commit()
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (onLeave) commit(); focus.clearFocus() }),
            modifier = modifier.width(64.dp)
                .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .onFocusChanged { now -> if (focused && !now.isFocused && onLeave) commit(); focused = now.isFocused }
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .semantics { contentDescription = label }
                .testTag(TASK_PRIORITY_FIELD),
        )
    }
}

/**
 * The tick "completed" of a task (D138, Android D358): on its card, in View and in Edit. Set — the task is Done; cleared — it has the status
 * it had before it ended. A task that is Closed or Cancelled is ticked too. Without [onTask] it is only shown.
 */
@Composable
fun TaskDoneBox(task: TaskDocument, onTask: ((TaskDocument) -> Unit)?) {
    val word = stringResource("task_completed")
    HoverTip(word, topic = TASK_HELP) {
        androidx.compose.material3.Checkbox(
            checked = task.ended,
            onCheckedChange = onTask?.let { change -> { on: Boolean -> change(task.ticked(on)) } },
            enabled = onTask != null,
            modifier = Modifier.size(32.dp).semantics { contentDescription = word }.testTag(TASK_DONE_BOX),
        )
    }
}

/**
 * The tick, the status and the priority of a task in one line: in View, in Edit and on its card. Without [onTask] they are only shown.
 * With [onDelete] the line ends with the bin (D142): on the card and in View. With [copy] the icon of two sheets copies that text (D143).
 */
@Composable
internal fun TaskStateLine(task: TaskDocument, onTask: ((TaskDocument) -> Unit)?, card: Boolean = false, onDelete: (() -> Unit)? = null, copy: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskDoneBox(task, onTask)
        TaskStateButton(task, onTask?.let { change -> { s: TaskState -> change(task.with(s)) } })
        if (onTask != null) TaskPriorityField(task.priority, { onTask(task.copy(priority = it)) }, onLeave = card)
        else task.priority?.let { number ->
            HoverTip(stringResource("task_priority"), topic = TASK_HELP) { Text(number.toString(), style = MaterialTheme.typography.labelLarge) }
        }
        // Copy and the bin stand at the end of the line: a click in the middle of the card still opens the task.
        if (copy != null || onDelete != null) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        if (copy != null) CopyIcon(small = true) { copy }
        if (onDelete != null) {
            IconButton(onClick = onDelete, helpTopic = TASK_HELP, modifier = Modifier.testTag(TASK_DELETE)) {
                Icon(AppIcons.Delete, contentDescription = stringResource("action_delete"), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** The status and the priority on the card of a task (D117): both are set there, the note is not opened by that. */
@Composable
internal fun TaskCardLine(task: TaskDocument, onTask: ((TaskDocument) -> Unit)?, onDelete: (() -> Unit)? = null, copy: String? = null) =
    TaskStateLine(task, onTask, card = true, onDelete = onDelete, copy = copy)

/** A task as text (D143, Android D362): its name, status, dates, comment and description, each on a line of its own. */
@Composable
internal fun taskCopyText(title: String, task: TaskDocument, content: String): String = listOfNotNull(
    title.takeIf { it.isNotBlank() }, stringResource(task.state.key), taskSpan(task), task.comment.takeIf { it.isNotBlank() }, content.takeIf { it.isNotBlank() },
).joinToString("\n")

/**
 * The statuses as chips (D118): the filter above a folder of tasks ([counts]: only the statuses its tasks have, each with how
 * many) and the one of the search (no counts: every status). A chip is the icon of its status (D122), the word is its hint.
 * The chosen ones are always shown, so that they can be let go. Before them stand the two chips of the tick (D138), icons as
 * well (D139): an empty box "Not completed" and a ticked box "Completed".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskStateChips(
    chosen: Set<TaskState>, onToggle: (TaskState) -> Unit, modifier: Modifier = Modifier, counts: Map<TaskState, Int>? = null,
    done: Boolean? = null, onDone: ((Boolean?) -> Unit)? = null,
) {
    val shown = TaskState.entries.filter { counts == null || it in chosen || (counts[it] ?: 0) > 0 }
    // Completed or not (D138, Android D358): two chips before the statuses; a press on the chosen one lets it go. A box, empty or
    // ticked, and the number of its tasks; the words are its hint (D139, Android D359: the words took too much room).
    val total = counts?.values?.sum()
    val doneChips = onDone != null && (total == null || total > 0 || done != null)
    if (shown.isEmpty() && !doneChips) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (doneChips && onDone != null) {
            val completed = counts?.let { com.notio.shared.domain.TaskFilter.doneOf(it) }
            listOf(false to total?.minus(completed ?: 0), true to completed).forEach { (value, count) ->
                val word = if (value) stringResource("task_filter_done") else stringResource("task_filter_open")
                HoverTip(word, topic = TASK_HELP) {
                    FilterChip(
                        selected = done == value,
                        onClick = { onDone(if (done == value) null else value) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                androidx.compose.material3.Icon(if (value) AppIcons.CheckBox else AppIcons.CheckBoxBlank, contentDescription = null, modifier = Modifier.size(20.dp))
                                if (count != null) Text(count.toString())
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = word }.testTag(TASK_DONE_CHIP + value),
                    )
                }
            }
        }
        shown.forEach { s ->
            val word = stringResource(s.key)
            HoverTip(word, topic = TASK_HELP) {
                FilterChip(
                    selected = s in chosen,
                    onClick = { onToggle(s) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TaskStateIcon(s)
                            if (counts != null) Text((counts[s] ?: 0).toString())
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = word }.testTag(TASK_STATE_CHIP + s.name),
                )
            }
        }
    }
}

/** "12 Sep 2026, 09:00 – 15 Sep 2026, 18:00", or only what is set. */
@Composable
fun taskSpan(task: TaskDocument): String? {
    val formats = LocalDateTimeFormats.current
    val start = task.start?.let { formats.format(it) }
    val end = task.end?.let { formats.format(it) }
    return when {
        start != null && end != null -> "$start – $end"
        start != null -> stringResource("task_from", start)
        end != null -> stringResource("task_until", end)
        else -> null
    }
}

@Composable
internal fun TaskView(task: TaskDocument, content: String, noteId: String, onTask: ((TaskDocument) -> Unit)? = null, title: String = "") {
    val delete = LocalTaskDelete.current?.takeIf { onTask != null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskStateLine(task, onTask, onDelete = delete?.let { ask -> { ask(noteId) } }, copy = taskCopyText(title, task, content))
        taskSpan(task)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (task.comment.isNotBlank()) Text(task.comment, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Reminders(noteId)
        Text(stringResource("task_description"), style = MaterialTheme.typography.labelLarge)
        if (content.isBlank()) Text(stringResource("task_no_description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(content, style = MaterialTheme.typography.bodyLarge)
        NoteFooter()
    }
}

/** The reminders of the task and the way to add or change them (set here, not through the note menu). */
@Composable
private fun Reminders(noteId: String) {
    val reminders = LocalNoteReminders.current
    val formats = LocalDateTimeFormats.current
    val open = LocalOpenReminders.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (reminders.isEmpty()) stringResource("task_no_reminder")
            else Glyphs.bell + " " + reminders.sortedBy { it.triggerAt }.joinToString(", ") { formats.format(it.triggerAt) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (open != null) TextButton(onClick = { open(noteId) }) { Text(stringResource("task_reminder")) }
    }
}

@Composable
internal fun TaskEdit(task: TaskDocument, content: String, onChange: (TaskDocument, String) -> Unit, noteId: String) {
    var comment by remember { mutableStateOf(task.comment) }
    var description by remember { mutableStateOf(content) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" or "end"
    var endBeforeStart by remember { mutableStateOf(false) }

    fun save(t: TaskDocument, text: String = description) {
        if (!t.valid) { endBeforeStart = true; return }
        endBeforeStart = false
        onChange(t, text)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskStateLine(task, { save(it) })
        OutlinedTextField(
            value = comment,
            onValueChange = { v -> comment = v.take(TaskDocument.MAX_COMMENT); save(task.copy(comment = comment)) },
            label = { Text(stringResource("task_comment")) },
            modifier = Modifier.fillMaxWidth(),
        )
        DateLine("task_start", task.start, onPick = { picking = "start" }, onClear = { save(task.copy(start = null)) })
        DateLine("task_end", task.end, onPick = { picking = "end" }, onClear = { save(task.copy(end = null)) })
        if (endBeforeStart) Text(stringResource("task_end_before_start"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Reminders(noteId)
        OutlinedTextField(
            value = description,
            onValueChange = { v -> description = v; save(task, v) },
            label = { Text(stringResource("task_description")) },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    picking?.let { which ->
        DateTimePicker(
            initial = if (which == "start") task.start else task.end ?: task.start,
            onPicked = { at -> picking = null; save(if (which == "start") task.copy(start = at) else task.copy(end = at)) },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun DateLine(label: String, at: Long?, onPick: () -> Unit, onClear: () -> Unit) {
    val formats = LocalDateTimeFormats.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.3f))
        OutlinedButton(onClick = onPick, modifier = Modifier.weight(0.7f)) { Text(at?.let { formats.format(it) } ?: stringResource("task_not_set")) }
        if (at != null) {
            val clear = stringResource("search_clear")
            TextButton(onClick = onClear, modifier = Modifier.semantics { contentDescription = clear }) { Text("✕") }
        }
    }
}

/** A day, then a time; [onPicked] gets the moment in this time zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePicker(initial: Long?, onPicked: (Long) -> Unit, onDismiss: () -> Unit) {
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val zone = ZoneId.systemDefault()
    val chosen = day
    if (chosen == null) {
        val start = initial?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    day = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: start
                }) { Text(stringResource("action_save")) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
        ) { DatePicker(state = state) }
    } else {
        val time = initial?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onPicked(LocalDateTime.of(chosen, LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant().toEpochMilli())
                }) { Text(stringResource("action_save")) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
        )
    }
}
