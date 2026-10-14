package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The bar of a multi-note (audio, photo, video, screen recording) while the person picks items with check marks (D146):
 * how many are picked, Select all, Clear selection, Delete the picked ones, and Done to leave the mode.
 */
@Composable
fun SelectionBar(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    hide: HideMany? = null,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource("media_selected_count", count, total), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text(stringResource("action_done")) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Android D278: icons, their names on hover; Delete still asks first.
                IconAction(AppIcons.SelectAll, stringResource("media_select_all"), onSelectAll, enabled = count < total)
                IconAction(AppIcons.Deselect, stringResource("media_clear_selection"), onClear, enabled = count > 0)
                IconAction(AppIcons.Delete, stringResource("media_delete_selected", count), onDelete, enabled = count > 0, tint = MaterialTheme.colorScheme.error)
                // Windows D156 (Android D364): the content of the picked notes hidden or no longer hidden; the number is how many change.
                if (hide != null) {
                    IconAction(AppIcons.VisibilityOff, stringResource("privacy_hide_selected", hide.toHide), hide.onHide, enabled = hide.toHide > 0, topic = HIDE_HELP)
                    IconAction(AppIcons.Visibility, stringResource("privacy_show_selected", hide.toShow), hide.onShow, enabled = hide.toShow > 0, topic = HIDE_HELP)
                }
            }
        }
    }
}

/**
 * The two eyes of the selection bar of a folder (Windows D156, Android D364): [toHide] picked notes would be hidden by [onHide], [toShow] would stop
 * being hidden by [onShow]; only notes of the kinds that can hide their content are counted.
 */
class HideMany(val toHide: Int, val toShow: Int, val onHide: () -> Unit, val onShow: () -> Unit) {
    companion object {
        /** The ids of the [picked] notes that a press of the eye setting [hidden] changes: of the kinds that hide, not so yet. */
        fun targets(notes: List<com.notio.shared.domain.Note>, picked: Collection<String>, hidden: Boolean): List<String> =
            notes.filter { it.id in picked && it.type in HIDEABLE_KINDS && it.hidden != hidden }.map { it.id }
    }
}

/** "Delete N items?" with the two answers; nothing is deleted before the person says yes. */
@Composable
fun ConfirmDeleteMany(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("media_delete_many_title")) },
        text = { Text(stringResource("media_delete_many_message", count)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

/**
 * The state of a selection mode: on or off and the picked ids. [toggle] picks or unpicks one; leaving the mode forgets
 * the picks. Kept apart from the screen so it can be tested.
 */
class SelectionState {
    var active by mutableStateOf(false)
        private set
    var picked by mutableStateOf(emptySet<String>())
        private set

    fun begin(first: String? = null) {
        active = true
        picked = if (first != null) setOf(first) else emptySet()
    }

    fun end() {
        active = false
        picked = emptySet()
    }

    fun toggle(id: String) {
        picked = if (id in picked) picked - id else picked + id
    }

    fun selectAll(ids: Collection<String>) {
        picked = ids.toSet()
    }

    fun clear() {
        picked = emptySet()
    }

    /** After items were deleted or removed some other way: forget picks of items that are gone. */
    fun retain(existing: Collection<String>) {
        val keep = picked.filter { it in existing }.toSet()
        if (keep != picked) picked = keep
    }
}

@Composable
fun rememberSelectionState(): SelectionState = remember { SelectionState() }
