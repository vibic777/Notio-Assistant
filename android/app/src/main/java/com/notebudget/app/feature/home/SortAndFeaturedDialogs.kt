package com.notebudget.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.FeaturedLayout
import com.notebudget.app.domain.model.FeaturedMetric
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.SortKey
import com.notebudget.app.domain.model.SortOption

private fun keyLabel(key: SortKey): Int = when (key) {
    SortKey.MANUAL -> R.string.sort_key_manual
    SortKey.UPDATED -> R.string.sort_key_updated
    SortKey.CREATED -> R.string.sort_key_created
    SortKey.NAME -> R.string.sort_key_name
    SortKey.COLOR -> R.string.sort_key_color
    SortKey.TAGS -> R.string.sort_key_tags
    SortKey.TYPE -> R.string.sort_key_type
    SortKey.COUNT -> R.string.sort_key_count
    SortKey.STATUS -> R.string.sort_key_status
    SortKey.PRIORITY -> R.string.sort_key_priority
    SortKey.DONE -> R.string.sort_key_completed
    SortKey.CREATED_DATE -> R.string.sort_key_created_date
    SortKey.CREATED_TIME -> R.string.sort_key_created_time
    SortKey.UPDATED_DATE -> R.string.sort_key_updated_date
    SortKey.UPDATED_TIME -> R.string.sort_key_updated_time
}

/** How the notes and the folders are ordered (D54, D57, D121); each has a key and a direction. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortDialog(
    folderSort: SortOption,
    noteSort: SortOption,
    onFolderSort: (SortOption) -> Unit,
    onNoteSort: (SortOption, Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** The folder on show is a folder of tasks (D338): its tasks are sorted by status and by priority too. */
    tasks: Boolean = false,
) {
    // The sort of the notes belongs to the folder that is shown; this box makes it the sort of every folder (D158).
    var applyAll by remember { mutableStateOf(false) }
    @Composable
    fun Group(title: Int, current: SortOption, keys: List<SortKey>, onChange: (SortOption) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEach { key ->
                    FilterChip(
                        selected = current.key == key,
                        // Choosing a key keeps the direction, except that the two dates start "newest first".
                        onClick = { onChange(SortOption(key, if (key == current.key) current.descending else key.isDate)) },
                        label = { Text(stringResource(keyLabel(key))) },
                    )
                }
            }
            // "My order" has no direction: the order is whatever was dragged.
            if (current.key != SortKey.MANUAL) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!current.descending, { onChange(current.copy(descending = false)) }, { Text(stringResource(R.string.sort_ascending)) })
                    FilterChip(current.descending, { onChange(current.copy(descending = true)) }, { Text(stringResource(R.string.sort_descending)) })
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.sort_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Group(R.string.sort_notes, noteSort, if (tasks) SortOption.TASK_KEYS else SortOption.NOTE_KEYS) { onNoteSort(it, applyAll) }
                Row(
                    Modifier.fillMaxWidth().selectable(selected = applyAll, onClick = { applyAll = !applyAll; if (applyAll) onNoteSort(noteSort, true) }, role = Role.Checkbox),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = applyAll, onCheckedChange = null)
                    Text(stringResource(R.string.sort_apply_all), Modifier.padding(start = 12.dp))
                }
                Group(R.string.sort_folders, folderSort, SortOption.FOLDER_KEYS, onFolderSort)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

/**
 * The featured note of a folder (D07, D08, D40, §6.4): any note of the folder, and for a budget which of sum, goal and
 * remainder the folder card shows, and how much (title only, compact, full).
 */
@Composable
fun FeaturedDialog(
    folder: Folder,
    notes: List<Note>,
    error: DomainError?,
    onConfirm: (noteId: String?, metricsMask: Int, layout: FeaturedLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(folder.featuredNoteId) }
    var mask by remember { mutableStateOf(if (folder.featuredNoteId == null) FeaturedMetric.maskOf(FeaturedMetric.SUM, FeaturedMetric.GOAL) else folder.featuredMetricsMask) }
    var layout by remember { mutableStateOf(folder.featuredLayout) }
    val isBudget = notes.firstOrNull { it.id == chosen }?.type == NoteType.BUDGET

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.featured_title, folder.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.featured_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceRow(stringResource(R.string.featured_none), chosen == null) { chosen = null }
                notes.forEach { note -> ChoiceRow(note.title, chosen == note.id) { chosen = note.id } }
                if (chosen != null) {
                    Text(stringResource(R.string.featured_layout), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(layout == FeaturedLayout.TITLE_ONLY, { layout = FeaturedLayout.TITLE_ONLY }, { Text(stringResource(R.string.featured_layout_title)) })
                        FilterChip(layout == FeaturedLayout.COMPACT, { layout = FeaturedLayout.COMPACT }, { Text(stringResource(R.string.featured_layout_compact)) })
                        FilterChip(layout == FeaturedLayout.FULL, { layout = FeaturedLayout.FULL }, { Text(stringResource(R.string.featured_layout_full)) })
                    }
                }
                if (isBudget) {
                    Text(stringResource(R.string.featured_metrics), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    MetricRow(R.string.featured_metric_sum, FeaturedMetric.SUM, mask) { mask = it }
                    MetricRow(R.string.featured_metric_goal, FeaturedMetric.GOAL, mask) { mask = it }
                    MetricRow(R.string.featured_metric_remainder, FeaturedMetric.REMAINDER, mask) { mask = it }
                } else if (chosen != null) {
                    Text(stringResource(R.string.featured_only_budget), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen, if (isBudget) mask else 0, layout) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun MetricRow(label: Int, metric: FeaturedMetric, mask: Int, onMask: (Int) -> Unit) {
    val on = mask and metric.bit != 0
    Row(
        Modifier.fillMaxWidth().selectable(selected = on, onClick = { onMask(if (on) mask and metric.bit.inv() else mask or metric.bit) }, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = null)
        Text(stringResource(label), Modifier.padding(start = 12.dp))
    }
}
