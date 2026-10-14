package com.notio.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notio.shared.data.EditError
import com.notio.shared.data.FolderDeleteMode
import com.notio.shared.domain.Comment
import com.notio.shared.domain.FeaturedLayout
import com.notio.shared.domain.Folder
import com.notio.shared.domain.FolderStyleId
import com.notio.shared.domain.NoteType

/** The ready-made colours of the Android app (feature/colors PALETTE) plus the default folder blue. */
val FOLDER_PALETTE: List<String> = listOf(
    "#3366FF", "#000000", "#616161", "#D32F2F", "#F57C00", "#FBC02D", "#388E3C", "#1976D2", "#7B1FA2", "#6D4C41", "#FFFFFF",
)

/** A row of colour swatches, optionally with "none", and a #RRGGBB field for any colour (the phone's RGB button). */
@Composable
fun ColorChooser(s: Strings, selected: String?, allowNone: Boolean, noneLabel: String = "", onPick: (String?) -> Unit) {
    var hex by remember(selected) { mutableStateOf(selected.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allowNone) FilterChip(selected == null, { onPick(null) }, { Text(noneLabel) })
            val list = if (selected != null && FOLDER_PALETTE.none { it.equals(selected, true) }) FOLDER_PALETTE + selected else FOLDER_PALETTE
            list.forEach { c ->
                val color = parseColorOrDefault(c)
                val chosen = c.equals(selected, ignoreCase = true)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(color)
                        .border(BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), CircleShape)
                        .clickable { onPick(c.uppercase()) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) Icon(Icons.Filled.Check, contentDescription = c, tint = if (color.luminance() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { v ->
                hex = v
                val norm = (if (v.startsWith("#")) v else "#$v").uppercase()
                if (Regex("^#[0-9A-F]{6}$").matches(norm)) onPick(norm)
            },
            label = { Text("RGB  #RRGGBB") }, singleLine = true, modifier = Modifier.width(200.dp),
        )
    }
}

/** Create or change a folder: name, icon or emoji, colour, style and a live preview (Android FolderEditor, D47). */
@Composable
fun FolderEditorDialog(
    s: Strings,
    initial: Folder?,
    onCancel: () -> Unit,
    /** Offer the kind of the folder, notes or tasks (D239): only when a folder is made. */
    showKind: Boolean = false,
    /**
     * The names of the folders there are, for a NEW folder (Android D275): its name is ready, "Folder <n>" or "Tasks <n>" with the
     * smallest number none of them has, so Create works at once; `null` when a folder is edited.
     */
    existingNames: Collection<String>? = null,
    onSave: (name: String, color: String, style: String, icon: String?, kind: com.notio.shared.domain.FolderKind, showError: (EditError) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var folderKind by remember { mutableStateOf(initial?.kind ?: com.notio.shared.domain.FolderKind.NOTES) }
    var icon by remember { mutableStateOf(initial?.icon.orEmpty()) }
    var color by remember { mutableStateOf(initial?.colorHex ?: FOLDER_PALETTE.first()) }
    var style by remember { mutableStateOf(initial?.styleId ?: FolderStyleId.SOFT.name) }
    var error by remember { mutableStateOf<EditError?>(null) }
    // The ready name follows the kind until a name of one's own is typed (as the ready note title of D264).
    var ownName by remember { mutableStateOf(false) }
    val readyBase = stringResource(if (folderKind == com.notio.shared.domain.FolderKind.TASKS) "folder_ready_tasks" else "folder_ready_notes")
    val readyName = remember(readyBase, existingNames) { existingNames?.let { ReadyNoteTitle.next(readyBase, it) } }
    val shownName = if (readyName != null && !ownName) readyName else name
    com.notio.desktop.EditorDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initial == null) s.newFolder else s.editAction) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = shownName, onValueChange = { name = it; ownName = true; error = null }, label = { Text(s.folderName) }, singleLine = true,
                    isError = error != null, supportingText = error?.let { { Text(s.editError(it)) } }, modifier = Modifier.fillMaxWidth(),
                )
                if (showKind) {
                    // The two kinds as icons (Android D298): the mouse over one names it, and the chosen one is named beside them.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val tasks = folderKind == com.notio.shared.domain.FolderKind.TASKS
                        // Another kind with an emptied name: the ready name of that kind comes back.
                        IconChoice(AppIcons.Folder, stringResource("folder_kind_notes"), !tasks, { folderKind = com.notio.shared.domain.FolderKind.NOTES; if (name.isBlank()) ownName = false })
                        IconChoice(AppIcons.TaskAlt, stringResource("folder_kind_tasks"), tasks, {
                            // A folder of tasks gets a tick as its icon unless one was chosen.
                            folderKind = com.notio.shared.domain.FolderKind.TASKS
                            if (name.isBlank()) ownName = false
                            if (icon.isBlank()) icon = "\u2705"
                        })
                        Text(
                            stringResource(if (tasks) "folder_kind_tasks" else "folder_kind_notes"),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = icon, onValueChange = { icon = it.take(8) }, label = { Text(s.folderIcon) },
                    supportingText = { Text(s.folderIconHint) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(s.color, style = MaterialTheme.typography.labelLarge)
                ColorChooser(s, color, allowNone = false) { it?.let { c -> color = c } }
                Text(s.style, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderStyleId.entries.forEach { st -> FilterChip(style == st.name, { style = st.name }, { Text(s.styleName(st.name)) }) }
                }
                Text(s.preview, style = MaterialTheme.typography.labelLarge)
                val preview = (initial ?: Folder("preview", "", color, style, null, 0, null, 0, FeaturedLayout.COMPACT, 0, 0))
                    .copy(name = shownName.ifBlank { s.folderName }, colorHex = color, styleId = style, icon = icon.ifBlank { null }, isPinned = false, comment = null)
                FolderCard(s, preview, 0, selected = false, onClick = {}, modifier = Modifier.width(190.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(shownName, color, style, icon.ifBlank { null }, folderKind) { error = it } }) { Text(if (initial == null) s.create else s.save) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(s.cancel) } },
    )
}

/** The ready name of a new note (Android D264): the kind and the smallest number from 1 that no note of the folder is called, e.g. "Audio 3". */
object ReadyNoteTitle {
    fun next(kind: String, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { it.trim().lowercase() }
        var n = 1
        while ("$kind $n".lowercase() in taken) n++
        return "$kind $n"
    }
}

/** The kinds a new note can have; the ones without a desktop editor yet are shown but cannot be chosen. */
val EDITABLE_KINDS = listOf(NoteType.WORD, NoteType.TEXT, NoteType.LIST, NoteType.BUDGET, NoteType.SCHEDULE, NoteType.DRAWING, NoteType.BLUEPRINT, NoteType.AUDIO, NoteType("PHOTO"), NoteType("VIDEO"), NoteType("SCREEN"), NoteType.TASK)

val ALL_KINDS = listOf(
    NoteType.WORD, NoteType.TEXT, NoteType.LIST, NoteType.BUDGET, NoteType.SCHEDULE, NoteType.DRAWING, NoteType.BLUEPRINT,
    NoteType.AUDIO, NoteType("PHOTO"), NoteType("VIDEO"), NoteType("SCREEN"),
)

@Composable
fun NewNoteDialog(
    s: Strings, onCancel: () -> Unit,
    /** In a folder of tasks "New" makes a task (D239): no list of kinds. */
    tasksOnly: Boolean = false,
    /** The titles of the notes of this folder, so the ready name "<kind> <n>" takes a number none of them has (D264). */
    existingTitles: Collection<String> = emptyList(),
    onCreate: (title: String, type: NoteType, showError: (EditError) -> Unit) -> Unit,
) {
    var kind by remember { mutableStateOf(if (tasksOnly) NoteType.TASK else NoteType.TEXT) }
    val readyTitle = remember(kind, existingTitles) { ReadyNoteTitle.next(s.kind(kind), existingTitles) }
    // The ready name follows the kind until the person types a name of their own; an emptied field takes it again with the next kind.
    var ownTitle by remember { mutableStateOf(false) }
    var typedTitle by remember { mutableStateOf("") }
    val title = if (ownTitle) typedTitle else readyTitle
    var error by remember { mutableStateOf<EditError?>(null) }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onCancel,
        title = { Text(if (tasksOnly) stringResource("dialog_new_task_title") else s.newNote) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title, onValueChange = { typedTitle = it; ownTitle = true; error = null }, label = { Text(s.titleHint) }, singleLine = true,
                    isError = error != null, supportingText = error?.let { { Text(s.editError(it)) } }, modifier = Modifier.fillMaxWidth(),
                )
                if (!tasksOnly) Text(s.newNoteKind, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                // The kinds as icons (Android D298): the mouse over one names it, and the chosen one is named under them.
                if (!tasksOnly) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ALL_KINDS.forEach { k ->
                            val enabled = k in EDITABLE_KINDS
                            IconChoice(
                                AppIcons.forKind(k.id), if (enabled) s.kind(k) else s.kind(k) + " · " + s.kindLater, kind == k,
                                onClick = { kind = k; if (typedTitle.isBlank()) ownTitle = false }, enabled = enabled,
                            )
                        }
                    }
                    Text(s.kind(kind), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title, kind) { error = it } }) { Text(s.create) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(s.cancel) } },
    )
}

@Composable
fun CommentDialog(s: Strings, title: String, initial: String?, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial.orEmpty()) }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(Comment.MAX_LENGTH) }, label = { Text(s.comment) },
                supportingText = { Text(s.commentHint + "  ${text.length} / ${Comment.MAX_LENGTH}") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(s.cancel) } },
    )
}

@Composable
fun NoteColorDialog(s: Strings, current: String?, onCancel: () -> Unit, onPick: (String?) -> Unit) {
    var color by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(s.noteColorTitle) },
        text = { ColorChooser(s, color, allowNone = true, noneLabel = s.noteColorNone) { color = it } },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(s.cancel) } },
    )
}

/** Choose a folder (moving a note, or where a note goes back to from the trash). */
@Composable
fun FolderPickDialog(s: Strings, title: String, hint: String, folders: List<Folder>, current: String?, onCancel: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                folders.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = f.id != current) { onPick(f.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(parseColorOrDefault(f.colorHex)))
                        Text(listOfNotNull(f.icon, f.name).joinToString(" "), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                        if (f.id == current) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(s.cancel) } },
    )
}

/** A deletion always says what goes, where to, and whether it can be undone (Android §1.3). */
@Composable
fun ConfirmDialog(s: Strings, title: String, text: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(s.cancel) } },
    )
}

@Composable
fun DeleteFolderDialog(s: Strings, folder: Folder, noteCount: Int, others: List<Folder>, onCancel: () -> Unit, onDelete: (FolderDeleteMode, String?) -> Unit) {
    var mode by remember { mutableStateOf(if (others.isNotEmpty()) FolderDeleteMode.MOVE_NOTES else FolderDeleteMode.DELETE_NOTES) }
    var target by remember { mutableStateOf(others.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(s.deleteFolderTitle(folder.name)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (noteCount == 0) {
                    Text(s.deleteFolderEmpty)
                } else {
                    Text(s.deleteFolderNotes)
                    listOf(FolderDeleteMode.MOVE_NOTES to s.deleteFolderMove, FolderDeleteMode.DELETE_NOTES to s.deleteFolderTrash).forEach { (m, label) ->
                        Row(Modifier.fillMaxWidth().selectable(mode == m, onClick = { mode = m }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(mode == m, onClick = { mode = m })
                            Text(label)
                        }
                    }
                    if (mode == FolderDeleteMode.MOVE_NOTES) {
                        Text(s.deleteFolderTarget, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        others.forEach { f ->
                            Row(Modifier.fillMaxWidth().selectable(target == f.id, onClick = { target = f.id }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(target == f.id, onClick = { target = f.id })
                                Text(f.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDelete(if (noteCount == 0) FolderDeleteMode.DELETE_NOTES else mode, target) }) {
                Text(s.delete, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(s.cancel) } },
    )
}
