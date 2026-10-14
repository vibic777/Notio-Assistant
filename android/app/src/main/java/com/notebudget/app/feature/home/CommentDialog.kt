package com.notebudget.app.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.notebudget.app.core.ui.tip.stringResource
import com.notebudget.app.R
import com.notebudget.app.domain.model.Comment

/** The comment of a note or a folder (D177): a few words for the person; the card shows the first 16 characters. Blank clears it. */
@Composable
fun CommentDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(Comment.MAX_LENGTH) },
                    label = { Text(stringResource(R.string.comment_label)) },
                    minLines = 2,
                    maxLines = 6,
                    supportingText = { Text(stringResource(R.string.comment_counter, text.length, Comment.MAX_LENGTH)) },
                )
                Text(
                    stringResource(R.string.comment_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
