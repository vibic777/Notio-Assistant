package com.notebudget.app.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.notebudget.app.R

/** One way to sort: a stable [id] (the name of the key) and the words for it. */
class SortChoice(val id: String, @StringRes val label: Int)

/** The id of the "own order" choice: no sort by value. */
const val SORT_MANUAL = "MANUAL"

/** The text of the button that opens the sort dialog: `Sort: Name ↑`, `Sort: My order`. */
@Composable
fun sortButtonText(choices: List<SortChoice>, currentId: String, descending: Boolean): String {
    val choice = choices.firstOrNull { it.id == currentId } ?: choices.first()
    val arrow = if (choice.id == SORT_MANUAL) "" else if (descending) " ↓" else " ↑"
    return stringResource(R.string.sort_button, stringResource(choice.label) + arrow)
}

/**
 * Sorting the rows of a budget or of a list (D178): pick a key and, for a sort by value, the direction. "My order" keeps the rows as
 * the person arranged them (and lets him drag them); every other choice only changes how they are shown.
 */
@Composable
fun ItemSortDialog(
    choices: List<SortChoice>,
    currentId: String,
    descending: Boolean,
    onApply: (id: String, descending: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(currentId) }
    var desc by remember { mutableStateOf(descending) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = choice.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == choice.id, onClick = { picked = choice.id })
                        Text(stringResource(choice.label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (picked != SORT_MANUAL) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !desc, onClick = { desc = false }, label = { Text("↑ " + stringResource(R.string.sort_ascending)) })
                        FilterChip(selected = desc, onClick = { desc = true }, label = { Text("↓ " + stringResource(R.string.sort_descending)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(picked, desc && picked != SORT_MANUAL) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
