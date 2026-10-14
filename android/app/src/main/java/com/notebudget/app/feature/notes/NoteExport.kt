package com.notebudget.app.feature.notes

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.data.transfer.ExportFormat
import com.notebudget.app.data.transfer.ExportedFile
import com.notebudget.app.domain.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Export note" (D23, D62): pick a format, then a place to save it (system file picker, SAF,
 * D26). The formats on offer come from the note kind's transfer handler.
 */
@Composable
fun NoteExportDialog(note: Note, onDismiss: () -> Unit) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val formats = remember(note.type) { container.noteFileService.formatsFor(note) }
    var chosen by remember { mutableStateOf<ExportFormat?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val format = chosen
        if (uri == null || format == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = runCatching {
                // Re-read the note: the editor may have saved since this dialog opened.
                val latest = container.noteRepository.getById(note.id) ?: note
                val file: ExportedFile = container.noteFileService.export(latest, format)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.bytes) }
                        ?: error("Cannot open $uri")
                }
            }.isSuccess
            Toast.makeText(
                context,
                context.getString(if (ok) R.string.export_done else R.string.export_failed),
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_note_title)) },
        text = {
            Column {
                formats.forEach { format ->
                    Text(
                        text = stringResource(format.labelRes),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chosen = format
                                saveLauncher.launch(fileNameFor(note, format))
                            }
                            .padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun fileNameFor(note: Note, format: ExportFormat): String =
    note.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifBlank { "note" }.take(80) +
        "." + format.extension
