package com.notebudget.app.feature.home

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.domain.usecase.SearchScope

/**
 * The search of the main screen (D109): a switch between looking for notes and for folders, a text field, and - for
 * notes - the tags to filter by (a note must have all the chosen tags).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchPanel(
    state: HomeUiState,
    onScope: (SearchScope) -> Unit,
    onText: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onClose: () -> Unit,
    onIgnoreCase: (Boolean) -> Unit = {},
    onRegex: (Boolean) -> Unit = {},
    onToggleTaskState: (com.notebudget.app.domain.model.TaskState) -> Unit = {},
    onTaskDone: (Boolean?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The field keeps its own text while typing; the view model gets every change.
    var text by remember { mutableStateOf(state.query.text) }
    val scope = state.query.scope

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(scope == SearchScope.NOTES, { onScope(SearchScope.NOTES) }, { Text(stringResource(R.string.search_scope_notes)) })
            FilterChip(scope == SearchScope.FOLDERS, { onScope(SearchScope.FOLDERS) }, { Text(stringResource(R.string.search_scope_folders)) })
            FilterChip(scope == SearchScope.TAGS, { onScope(SearchScope.TAGS) }, { Text(stringResource(R.string.search_scope_tags)) })
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            com.notebudget.app.core.ui.tip.TextButton(onClick = onClose) { Text(stringResource(R.string.search_close)) }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onText(it) },
            singleLine = true,
            label = {
                Text(
                    stringResource(
                        when (scope) {
                            SearchScope.NOTES -> R.string.search_hint_notes
                            SearchScope.FOLDERS -> R.string.search_hint_folders
                            SearchScope.TAGS -> R.string.search_hint_tags
                        },
                    ),
                )
            },
            trailingIcon = if (text.isNotEmpty()) {
                {
                    IconButton(onClick = { text = ""; onText("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        // Letters in any case, and the words as a regular expression (D246).
        com.notebudget.app.core.ui.SearchOptionsRow(
            ignoreCase = state.query.ignoreCase,
            regex = state.query.regex,
            error = state.query.error,
            expression = state.query.text,
            onIgnoreCase = onIgnoreCase,
            onRegex = onRegex,
        )
        if (scope != SearchScope.FOLDERS) {
            if (state.tags.isEmpty()) {
                Text(
                    stringResource(R.string.search_no_tags),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Many tags must not push the results off the screen: the chips scroll within a few lines.
                FlowRow(
                    Modifier.heightIn(max = 132.dp).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // In a tag search the typed words also narrow the chips (a chosen tag always stays).
                    val patterns = androidx.compose.runtime.remember(state.query) { state.query.patterns() }
                    state.tags.filter { tag -> scope != SearchScope.TAGS || tag.id in state.query.tagIds || com.notebudget.app.domain.usecase.NoteSearch.tagFits(tag.name, state.query, patterns) }.forEach { tag ->
                        FilterChip(
                            selected = tag.id in state.query.tagIds,
                            onClick = { onToggleTag(tag.id) },
                            label = { Text("#" + tag.name) },
                        )
                    }
                }
            }
        }
        // The statuses of the tasks (D338): chosen ones narrow the search to the tasks that have one of them.
        if (scope == SearchScope.NOTES && state.hasTasks) com.notebudget.app.feature.notes.task.TaskStateChips(state.query.taskStates, onToggleTaskState, done = state.query.taskDone, onDone = onTaskDone)
        if (state.searching) {
            val found = if (scope == SearchScope.FOLDERS) state.folders.size else state.notes.size
            Text(stringResource(R.string.search_found, found), style = MaterialTheme.typography.labelLarge)
        }
    }
}
