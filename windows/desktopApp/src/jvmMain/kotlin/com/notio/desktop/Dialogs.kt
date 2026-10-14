package com.notio.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.notio.shared.transfer.ImportMode
import com.notio.shared.transfer.ImportReport
import com.notio.shared.transfer.TransferException
import java.io.File

sealed interface DialogState {
    data class Message(val title: String, val text: String) : DialogState
    /** A question before something that cannot be taken back; [action] runs on Yes. */
    data class Confirm(val title: String, val text: String, val action: () -> Unit) : DialogState
    data class Import(val file: File, val protected: Boolean) : DialogState
    data object Export : DialogState

    companion object {
        fun fromImport(s: Strings, r: Result<ImportReport>): DialogState = r.fold(
            { rep ->
                Message(
                    s.done,
                    s.importDone(rep.stats.foldersAdded, rep.stats.notesAdded, rep.stats.notesUpdated, rep.stats.notesSkipped, rep.filesRestored, rep.filesSkipped),
                )
            },
            { e -> Message(s.error, (e as? TransferException)?.let { s.transferError(it.error) } ?: (e.message ?: e.toString())) },
        )
    }
}

@Composable
fun MessageDialog(s: Strings, title: String, text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(s.ok) } },
        title = { Text(title) },
        text = { Text(text) },
    )
}

@Composable
fun ConfirmDialog(s: Strings, title: String, text: String, onNo: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        confirmButton = { TextButton(onClick = onYes) { Text(s.delete) } },
        dismissButton = { TextButton(onClick = onNo) { Text(s.cancel) } },
        title = { Text(title) },
        text = { Text(text) },
    )
}

/** A password field with Show / Hide (Android D144). */
@Composable
private fun PasswordField(s: Strings, label: String, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) s.hide else s.show) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ImportDialog(s: Strings, fileName: String, protected: Boolean, onCancel: () -> Unit, onImport: (ImportMode, String?) -> Unit) {
    var mode by remember { mutableStateOf(ImportMode.MERGE) }
    var password by remember { mutableStateOf("") }
    // Replace deletes everything on this PC: it needs a second, explicit press (Android §1.3).
    var confirmReplace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(s.importTitle) },
        text = {
            Column {
                Text(fileName, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text(s.mode, style = MaterialTheme.typography.labelLarge)
                for ((m, label) in listOf(ImportMode.MERGE to s.modeMerge, ImportMode.NEW_IDS to s.modeNewIds, ImportMode.REPLACE to s.modeReplace)) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = mode == m, onClick = { mode = m; confirmReplace = false }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = { mode = m; confirmReplace = false })
                        Text(label, color = if (m == ImportMode.REPLACE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (confirmReplace) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.replaceWarning, color = MaterialTheme.colorScheme.error)
                }
                if (protected) {
                    Spacer(Modifier.height(12.dp))
                    Text(s.passwordHint, style = MaterialTheme.typography.bodySmall)
                    PasswordField(s, s.password, password) { password = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (mode == ImportMode.REPLACE && !confirmReplace) confirmReplace = true
                    else onImport(mode, password.takeIf { protected && it.isNotEmpty() })
                },
                enabled = !protected || password.isNotEmpty(),
            ) {
                Text(if (confirmReplace) s.replaceConfirm else s.importAction)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(s.cancel) } },
    )
}

@Composable
fun ExportDialog(s: Strings, onCancel: () -> Unit, onExport: (String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(s.exportBackup.removeSuffix("…")) },
        text = { PasswordField(s, s.exportPassword, password) { password = it } },
        confirmButton = { TextButton(onClick = { onExport(password.ifEmpty { null }) }) { Text(s.export) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(s.cancel) } },
    )
}
