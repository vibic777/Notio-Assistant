package com.notebudget.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.FolderDeleteMode
import com.notebudget.app.feature.folders.FolderDraft
import com.notebudget.app.feature.folders.FolderEditor
import com.notebudget.app.feature.folders.rememberFolderDraft

@Composable
fun FolderDialog(
    title: String,
    confirmLabel: String,
    initial: FolderDraft,
    error: DomainError?,
    onDraftEdited: () -> Unit,
    onConfirm: (FolderDraft) -> Unit,
    onDismiss: () -> Unit,
    showKind: Boolean = false,
    /**
     * The names of the folders there are, for a NEW folder (D275): its name is ready, "Folder <n>" or "Tasks <n>" with the smallest
     * number none of them has, so Create works at once; `null` when a folder is edited.
     */
    existingNames: Collection<String>? = null,
) {
    val draftState = rememberFolderDraft(initial)
    // The ready name follows the kind until the person types a name of their own (as the ready note title of D264).
    var ownName by rememberSaveable { mutableStateOf(false) }
    val readyBase = stringResource(if (draftState.value.kind == "TASKS") R.string.folder_ready_tasks else R.string.folder_ready_notes)
    val readyName = remember(readyBase, existingNames) { existingNames?.let { ReadyNoteTitle.next(readyBase, it) } }
    val draft = if (readyName != null && !ownName) draftState.value.copy(name = readyName) else draftState.value
    com.notebudget.app.core.ui.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FolderEditor(
                    draft = draft,
                    onDraftChange = {
                        onDraftEdited()
                        if (it.name != draft.name) ownName = true
                        // Another kind with an emptied name: the ready name of that kind comes back.
                        if (it.kind != draft.kind && it.name.isBlank()) ownName = false
                        draftState.value = it
                    },
                    error = error,
                    showKind = showKind,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = draft.name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NewNoteDialog(
    error: DomainError?,
    onDraftEdited: () -> Unit,
    onConfirm: (title: String, type: NoteType) -> Unit,
    onDismiss: () -> Unit,
    /** In a folder of tasks "New" makes a task (D239): no list of kinds. */
    tasksOnly: Boolean = false,
    /** The titles of the notes of this folder, so the ready name "<kind> <n>" takes a number none of them has (D264). */
    existingTitles: Collection<String> = emptyList(),
) {
    var typeId by rememberSaveable { mutableStateOf(if (tasksOnly) NoteType.TASK.id else NoteType.TEXT.id) }
    val registry = rememberAppContainer().noteTypes
    val kindLabel = stringResource(registry.definitionFor(NoteType(typeId)).labelRes)
    val readyTitle = remember(kindLabel, existingTitles) { ReadyNoteTitle.next(kindLabel, existingTitles) }
    // The ready name follows the kind until the person types a name of their own; an emptied field takes it again with the next kind.
    var ownTitle by rememberSaveable { mutableStateOf(false) }
    var typedTitle by rememberSaveable { mutableStateOf("") }
    val title = if (ownTitle) typedTitle else readyTitle
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (tasksOnly) R.string.dialog_new_task_title else R.string.dialog_new_note_title)) },
        text = {
            // Six kinds and a title do not fit a small screen or a large font: the dialog scrolls, so no row is cut off.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        onDraftEdited()
                        typedTitle = it
                        ownTitle = true
                    },
                    label = { Text(stringResource(R.string.note_title_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(stringResource(it.messageRes())) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!tasksOnly) Text(stringResource(R.string.note_type_label), style = MaterialTheme.typography.labelLarge)
                // The kinds as icons (D298): a long press names one, and the chosen one is named under them.
                if (!tasksOnly) Column {
                    // The kinds are listed by name in the order of the language in use (D159): a language switch re-sorts them.
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val definitions = registry.creatable
                    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                    val ordered = remember(definitions, locale) {
                        val collator = java.text.Collator.getInstance(locale)
                        definitions.sortedWith { a, b -> collator.compare(context.getString(a.labelRes), context.getString(b.labelRes)) }
                    }
                    androidx.compose.foundation.layout.FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ordered.forEach { definition ->
                            com.notebudget.app.core.ui.IconChoice(
                                icon = com.notebudget.app.core.ui.AppIcons.forKind(definition.type.id),
                                label = stringResource(definition.labelRes),
                                selected = typeId == definition.type.id,
                                onClick = {
                                    typeId = definition.type.id
                                    if (typedTitle.isBlank()) ownTitle = false
                                },
                            )
                        }
                    }
                    Text(kindLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, NoteType(typeId)) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The ready name of a new note (D264): the kind and the smallest number from 1 that no note of the folder is called, e.g. "Audio 3". */
object ReadyNoteTitle {
    fun next(kind: String, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { it.trim().lowercase() }
        var n = 1
        while ("$kind $n".lowercase() in taken) n++
        return "$kind $n"
    }
}

@Composable
fun DeleteNoteDialog(note: Note, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    com.notebudget.app.core.ui.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_note_title)) },
        text = { Text(stringResource(R.string.delete_note_message, note.title)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun MoveNoteDialog(
    note: Note,
    otherFolders: List<Folder>,
    onMove: (targetFolderId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var targetId by rememberSaveable { mutableStateOf(otherFolders.firstOrNull()?.id) }
    com.notebudget.app.core.ui.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.move_note_featured_hint), style = MaterialTheme.typography.bodySmall)
                Column(
                    Modifier
                        .selectableGroup()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    otherFolders.forEach { folder ->
                        RadioRow(label = folder.name, selected = targetId == folder.id, onClick = { targetId = folder.id })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { targetId?.let(onMove) }, enabled = targetId != null && note.folderId != targetId) {
                Text(stringResource(R.string.action_move_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Folder deletion (§1.3, D20/D48/D74): explains what is deleted and what happens to the notes.
 * With notes inside the user chooses to move them or to trash them with the folder.
 */
@Composable
fun DeleteFolderDialog(
    folder: Folder,
    noteCount: Int,
    otherFolders: List<Folder>,
    onConfirm: (mode: FolderDeleteMode, targetFolderId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var modeName by rememberSaveable { mutableStateOf(FolderDeleteMode.MOVE_NOTES.name) }
    var targetId by rememberSaveable { mutableStateOf(otherFolders.firstOrNull()?.id) }
    val mode = FolderDeleteMode.valueOf(modeName)

    com.notebudget.app.core.ui.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_folder_title, folder.name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (noteCount == 0) {
                    Text(stringResource(R.string.delete_folder_message_empty))
                } else {
                    Text(pluralStringResource(R.plurals.notes_count, noteCount, noteCount), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.delete_folder_message_notes))
                    Column(Modifier.selectableGroup()) {
                        RadioRow(
                            label = stringResource(R.string.delete_folder_option_move),
                            selected = mode == FolderDeleteMode.MOVE_NOTES,
                            onClick = { modeName = FolderDeleteMode.MOVE_NOTES.name },
                        )
                        RadioRow(
                            label = stringResource(R.string.delete_folder_option_trash),
                            selected = mode == FolderDeleteMode.DELETE_NOTES,
                            onClick = { modeName = FolderDeleteMode.DELETE_NOTES.name },
                        )
                    }
                    if (mode == FolderDeleteMode.MOVE_NOTES) {
                        Text(stringResource(R.string.delete_folder_target_label), style = MaterialTheme.typography.labelLarge)
                        Column(Modifier.selectableGroup()) {
                            otherFolders.forEach { other ->
                                RadioRow(label = other.name, selected = targetId == other.id, onClick = { targetId = other.id })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(if (noteCount == 0) FolderDeleteMode.MOVE_NOTES else mode, targetId) },
                enabled = noteCount == 0 || mode == FolderDeleteMode.DELETE_NOTES || targetId != null,
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Colour of one note: a ready-made colour, any RGB colour, or none (the note takes its folder colour). */
@Composable
fun NoteColorDialog(note: Note, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    com.notebudget.app.core.ui.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_color_title)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.note_color_hint), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                com.notebudget.app.feature.colors.ColorRow(
                    label = R.string.action_note_color,
                    selected = note.colorHex?.let { com.notebudget.app.core.common.ArgbColor.parseHex(it) },
                    allowNone = true,
                    noneLabel = R.string.note_color_none,
                    onPick = onPick,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The tags of one note (D109): tick the ones it should have, or type a new one; any number can be ticked. */
@Composable
fun NoteTagsDialog(
    allTags: List<com.notebudget.app.domain.model.Tag>,
    current: List<com.notebudget.app.domain.model.Tag>,
    onCreate: (String, (com.notebudget.app.domain.model.Tag) -> Unit) -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(current.map { it.id }.toSet()) }
    var newName by remember { mutableStateOf("") }
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(com.notebudget.app.domain.usecase.TagUseCases.MAX_NAME_LENGTH + 1) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.tags_new_hint)) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val typed = newName
                            newName = ""
                            onCreate(typed) { tag -> selected = selected + tag.id }
                        },
                        enabled = newName.isNotBlank(),
                    ) { Text(stringResource(R.string.tags_add)) }
                }
                if (allTags.isEmpty()) {
                    Text(stringResource(R.string.tags_none_yet), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                allTags.forEach { tag ->
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .androidx_selectable(tag.id in selected) {
                                selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                            },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = tag.id in selected, onCheckedChange = null)
                        Text("#" + tag.name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun Modifier.androidx_selectable(checked: Boolean, onToggle: () -> Unit): Modifier =
    this.then(Modifier.toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { onToggle() }))
