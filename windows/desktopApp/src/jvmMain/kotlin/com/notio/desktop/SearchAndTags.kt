package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.notio.shared.data.NotioEditor
import com.notio.shared.domain.SearchScope
import com.notio.shared.domain.Tag

/**
 * The search of the main screen, the phone's SearchPanel (D109, D116): notes / folders / tags, a text field, and for notes the
 * tags a note must all have. It stands above the list, not in it (D204).
 */
@Composable
fun SearchPanel(state: UiState, controller: NotioController, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(state.query) }
    val scope = state.searchScope
    val query = controller.searchQuery(state)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(scope == SearchScope.NOTES, { controller.setSearchScope(SearchScope.NOTES) }, { Text(stringResource("search_scope_notes")) })
            FilterChip(scope == SearchScope.FOLDERS, { controller.setSearchScope(SearchScope.FOLDERS) }, { Text(stringResource("search_scope_folders")) })
            FilterChip(scope == SearchScope.TAGS, { controller.setSearchScope(SearchScope.TAGS) }, { Text(stringResource("search_scope_tags")) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = controller::toggleSearch) { Text(stringResource("search_close")) }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; controller.search(it) },
            singleLine = true,
            label = {
                Text(stringResource(when (scope) {
                    SearchScope.NOTES -> "search_hint_notes"
                    SearchScope.FOLDERS -> "search_hint_folders"
                    SearchScope.TAGS -> "search_hint_tags"
                }))
            },
            trailingIcon = if (text.isNotEmpty()) {
                { IconButton(onClick = { text = ""; controller.search("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource("search_clear")) } }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        // Letters in any case, and the words as a regular expression (D246); a wrong expression told clearly (D247).
        SearchOptionsRow(
            ignoreCase = query.ignoreCase,
            regex = query.regex,
            error = query.error,
            expression = query.text,
            onIgnoreCase = controller::setSearchIgnoreCase,
            onRegex = controller::setSearchRegex,
        )
        if (scope != SearchScope.FOLDERS) {
            if (state.allTags.isEmpty()) {
                Text(stringResource("search_no_tags"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // Many tags must not push the results off the screen: the chips scroll within a few lines.
                FlowRow(
                    Modifier.heightIn(max = 132.dp).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val patterns = remember(query) { query.patterns() }
                    state.allTags.filter { tag -> scope != SearchScope.TAGS || tag.id in state.searchTagIds || com.notio.shared.domain.NoteSearch.tagFits(tag.name, query, patterns) }.forEach { tag ->
                        FilterChip(tag.id in state.searchTagIds, { controller.toggleSearchTag(tag.id) }, { Text("#" + tag.name) })
                    }
                }
            }
        }
        // The statuses of the tasks (D118): chosen ones narrow the search to the tasks that have one of them.
        if (scope == SearchScope.NOTES && state.hasTasks) TaskStateChips(state.searchTaskStates, controller::toggleSearchTaskState, done = state.searchTaskDone, onDone = controller::setSearchTaskDone)
        if (query.active) {
            val found = if (scope == SearchScope.FOLDERS) state.folders.size else state.notes.size
            Text(stringResource("search_found", found), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The tags of one note (the phone's NoteTagsDialog, D109): tick the ones it should have, or type a new one. */
@Composable
fun NoteTagsDialog(allTags: List<Tag>, current: List<Tag>, onCreate: (String, (Tag) -> Unit) -> Unit, onConfirm: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(current.map { it.id }.toSet()) }
    var newName by remember { mutableStateOf("") }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("tags_dialog_title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it.take(NotioEditor.MAX_TAG + 1) }, singleLine = true,
                        label = { Text(stringResource("tags_new_hint")) }, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { val typed = newName; newName = ""; onCreate(typed) { tag -> selected = selected + tag.id } }, enabled = newName.isNotBlank()) {
                        Text(stringResource("tags_add"))
                    }
                }
                if (allTags.isEmpty()) Text(stringResource("tags_none_yet"), style = MaterialTheme.typography.bodySmall)
                allTags.forEach { tag ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(tag.id in selected, role = Role.Checkbox) {
                            selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = tag.id in selected, onCheckedChange = null)
                        Text("#" + tag.name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource("action_save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}
