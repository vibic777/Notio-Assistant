package com.notebudget.app.feature.budget

import android.content.res.Resources
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.ui.LocalDateTimeFormats
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.data.transfer.AndroidPdfWriter
import com.notebudget.app.data.transfer.PdfBlock
import com.notebudget.app.data.transfer.PdfStyle
import com.notebudget.app.data.transfer.PlainText
import com.notebudget.app.domain.model.BudgetLogEntry
import com.notebudget.app.domain.model.BudgetLogKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The lines of the log as words, in the language of the app (the log itself is stored as data, D166). */
object BudgetLogText {

    /** A number of the log as text: a sum or a goal in money, a quantity as it is; `null` = not set. */
    private fun value(res: Resources, kind: String, v: Long?, currency: String?, privacy: BudgetPrivacy): String = when {
        v == null -> res.getString(R.string.budget_log_not_set)
        kind == BudgetLogKind.QUANTITY -> v.toString()
        kind == BudgetLogKind.GOAL -> privacy.goal(v, currency)
        else -> privacy.sum(v, currency)
    }

    private fun group(res: Resources, name: String) = name.ifEmpty { res.getString(R.string.budget_total) }

    /** [currency] is the text the budget writes after its sums (D170), `null` = none. */
    fun of(res: Resources, e: BudgetLogEntry, currency: String? = null, privacy: BudgetPrivacy = BudgetPrivacy.NONE): String {
        val text = line(res, e, currency, privacy)
        // A comment given with a + / − / = change (D262) follows the line.
        val comment = if (e.kind == BudgetLogKind.SUM || e.kind == BudgetLogKind.GOAL) e.detail?.substringAfter('\n', "").orEmpty() else ""
        return if (comment.isBlank()) text else res.getString(R.string.budget_log_with_comment, text, comment)
    }

    private fun line(res: Resources, e: BudgetLogEntry, currency: String?, privacy: BudgetPrivacy): String = when (e.kind) {
        BudgetLogKind.ITEM_ADDED -> res.getString(R.string.budget_log_item_added, e.title, group(res, e.detail.orEmpty()))
        BudgetLogKind.GROUP_ADDED -> res.getString(R.string.budget_log_group_added, e.title, group(res, e.detail.orEmpty()))
        BudgetLogKind.SUM -> {
            val old = value(res, e.kind, e.oldValue, currency, privacy)
            val new = value(res, e.kind, e.newValue, currency, privacy)
            val delta = (e.newValue ?: 0L) - (e.oldValue ?: 0L)
            when (e.detail?.substringBefore('\n')) {
                "SET" -> res.getString(R.string.budget_log_sum_set, e.title, old, new)
                "ADD", "SUBTRACT" -> res.getString(
                    R.string.budget_log_sum_delta, e.title, old, new, privacy.sum(delta, currency).let { if (it == BudgetPrivacy.MASK || delta < 0) it else "+$it" },
                )
                else -> res.getString(R.string.budget_log_sum, e.title, old, new)
            }
        }
        BudgetLogKind.GOAL -> res.getString(R.string.budget_log_goal, e.title, value(res, e.kind, e.oldValue, currency, privacy), value(res, e.kind, e.newValue, currency, privacy))
        BudgetLogKind.QUANTITY -> res.getString(R.string.budget_log_quantity, e.title, value(res, e.kind, e.oldValue, currency, privacy), value(res, e.kind, e.newValue, currency, privacy))
        BudgetLogKind.RENAMED -> res.getString(R.string.budget_log_renamed, e.title, e.detail.orEmpty())
        BudgetLogKind.MOVED -> {
            val parts = e.detail.orEmpty().split('\n')
            res.getString(R.string.budget_log_moved, e.title, group(res, parts.getOrNull(0).orEmpty()), group(res, parts.getOrNull(1).orEmpty()))
        }
        BudgetLogKind.DELETED -> res.getString(
            // After a line break the line keeps what it deleted (D359).
            if (e.detail?.substringBefore('\n') == BudgetLogKind.TREE) R.string.budget_log_deleted_tree else R.string.budget_log_deleted, e.title,
        )
        BudgetLogKind.RESTORED -> res.getString(R.string.budget_log_restored, e.title)
        BudgetLogKind.UNDONE -> res.getString(R.string.budget_log_undone)
        BudgetLogKind.REDONE -> res.getString(R.string.budget_log_redone)
        BudgetLogKind.MERGED -> res.getString(R.string.budget_log_merged, e.title, (e.oldValue ?: 0L).toInt(), (e.newValue ?: 0L).toInt())
        BudgetLogKind.MERGED_INTO -> res.getString(R.string.budget_log_merged_into, e.title)
        BudgetLogKind.ITEM_MERGED -> res.getString(R.string.budget_log_item_merged, e.title, e.detail.orEmpty())
        BudgetLogKind.MERGE_UNDONE -> res.getString(R.string.budget_log_merge_undone, e.title)
        // D282: what an undo from the log did, by the kind of the line it undid.
        BudgetLogKind.OP_UNDONE -> when (e.detail?.substringAfter('\n', "")) {
            BudgetLogKind.SUM -> res.getString(R.string.budget_log_op_undone_value, e.title, value(res, BudgetLogKind.SUM, e.oldValue, currency, privacy), value(res, BudgetLogKind.SUM, e.newValue, currency, privacy))
            BudgetLogKind.GOAL -> res.getString(R.string.budget_log_op_undone_value, e.title, value(res, BudgetLogKind.GOAL, e.oldValue, currency, privacy), value(res, BudgetLogKind.GOAL, e.newValue, currency, privacy))
            BudgetLogKind.QUANTITY -> res.getString(R.string.budget_log_op_undone_value, e.title, value(res, BudgetLogKind.QUANTITY, e.oldValue, currency, privacy), value(res, BudgetLogKind.QUANTITY, e.newValue, currency, privacy))
            BudgetLogKind.RENAMED -> res.getString(R.string.budget_log_op_undone_rename, e.title)
            else -> res.getString(R.string.budget_log_op_undone_added, e.title)
        }
        else -> e.title
    }
}

/**
 * The log of one budget note (D166), newest line first: what was added, changed, moved or deleted. It can be cleared and
 * saved as a TXT or a PDF file. A change is undone from its line (D282), a deletion is put back from its line (D359).
 */
@Composable
fun BudgetLogView(noteId: String, onBack: () -> Unit, modifier: Modifier = Modifier, revealed: Boolean = false) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val formats = LocalDateTimeFormats.current
    val entries by remember(noteId) { container.budgetLogRepository.observe(noteId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var undoing by remember { mutableStateOf<BudgetLogEntry?>(null) }
    // D282: a line of the log to undo, asked first.
    var undoingLine by remember { mutableStateOf<BudgetLogEntry?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // The lines are written with the currency of the budget (D170).
    val note by remember(noteId) { container.noteRepository.observeById(noteId) }.collectAsStateWithLifecycle(initialValue = null)
    val currency = com.notebudget.app.domain.model.BudgetCurrency.labelOf(note?.extras)
    // Hidden amounts stay hidden in the log too, unless the eye of the note was pressed (D272); the PDF is not masked.
    val privacy = BudgetPrivacy.of(BudgetLayout.parse(note?.extras), revealed)
    BackHandler(onBack = onBack)

    /** The log as blocks of a document: a title, then a line per entry with its time. */
    suspend fun blocks(): Pair<String, List<PdfBlock>> {
        val title = container.noteRepository.getById(noteId)?.title.orEmpty()
        val head = context.getString(R.string.budget_log_export_title, title)
        val lines = container.budgetLogRepository.getByNote(noteId).map {
            PdfBlock(formats.format(it.at) + "   " + BudgetLogText.of(resources, it, currency))
        }
        return head to lines
    }

    fun save(uri: Uri?, pdf: Boolean) {
        if (uri == null) return
        scope.launch {
            val ok = runCatching {
                val (head, lines) = blocks()
                val bytes = withContext(Dispatchers.IO) {
                    if (pdf) AndroidPdfWriter().write(PlainText.withTitle(head, lines))
                    else (head + "\n\n" + lines.joinToString("\n") { it.text }).toByteArray(Charsets.UTF_8)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Cannot open $uri")
                }
            }.isSuccess
            Toast.makeText(context, context.getString(if (ok) R.string.budget_log_export_done else R.string.budget_log_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { save(it, pdf = false) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { save(it, pdf = true) }
    val fileBase = stringResource(R.string.budget_log_file_name)

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Text(
                stringResource(R.string.budget_log_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.budget_log_save_txt)) },
                        onClick = { menuOpen = false; txtLauncher.launch("$fileBase.txt") },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.budget_log_save_pdf)) },
                        onClick = { menuOpen = false; pdfLauncher.launch("$fileBase.pdf") },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.budget_log_clear)) },
                        onClick = { menuOpen = false; confirmClear = true },
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.budget_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(entries, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            formats.format(entry.at),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(BudgetLogText.of(resources, entry, currency, privacy), style = MaterialTheme.typography.bodyLarge)
                        // A merge can be undone from here while it has not been (D237).
                        if (entry.kind == com.notebudget.app.domain.model.BudgetLogKind.MERGED && !container.budgetMerge.undone(entry, entries)) {
                            TextButton(onClick = { undoing = entry }) { Text(stringResource(R.string.budget_merge_undo)) }
                        }
                        // Any change of an item can be undone the same way (D282), once; a line undone says so.
                        if (container.budgetEditor.canUndo(entry, entries)) {
                            TextButton(onClick = { undoingLine = entry }) { Text(stringResource(R.string.budget_log_undo)) }
                        } else if (entry.kind in com.notebudget.app.domain.model.BudgetLogKind.UNDOABLE && container.budgetEditor.undone(entry, entries)) {
                            Text(stringResource(R.string.budget_log_was_undone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // What was deleted is put back from here (D359), once; a line restored says so. Nothing is asked: a restore
                        // destroys nothing.
                        if (container.budgetEditor.canRestore(entry, entries)) {
                            TextButton(onClick = {
                                scope.launch {
                                    val result = container.budgetEditor.restoreDeleted(entry, container.budgetLogRepository.getByNote(noteId))
                                    if (result is com.notebudget.app.domain.model.DomainResult.Failure) {
                                        Toast.makeText(context, context.getString(R.string.budget_log_restore_failed), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) { Text(stringResource(R.string.budget_log_restore)) }
                        } else if (entry.kind == BudgetLogKind.DELETED && container.budgetEditor.restored(entry, entries)) {
                            Text(stringResource(R.string.budget_log_was_restored), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    undoing?.let { entry ->
        AlertDialog(
            onDismissRequest = { undoing = null },
            title = { Text(stringResource(R.string.budget_merge_undo)) },
            text = { Text(stringResource(R.string.budget_merge_undo_confirm, entry.title)) },
            confirmButton = {
                TextButton(onClick = {
                    undoing = null
                    scope.launch { container.budgetMerge.undo(entry) }
                }) { Text(stringResource(R.string.budget_merge_undo)) }
            },
            dismissButton = { TextButton(onClick = { undoing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    undoingLine?.let { entry ->
        val added = entry.kind == com.notebudget.app.domain.model.BudgetLogKind.ITEM_ADDED || entry.kind == com.notebudget.app.domain.model.BudgetLogKind.GROUP_ADDED
        AlertDialog(
            onDismissRequest = { undoingLine = null },
            title = { Text(stringResource(R.string.budget_log_undo_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(BudgetLogText.of(resources, entry, currency, privacy), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (added) R.string.budget_log_undo_added_message else R.string.budget_log_undo_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    undoingLine = null
                    scope.launch {
                        val result = container.budgetEditor.undoLogEntry(entry, container.budgetLogRepository.getByNote(noteId))
                        if (result is com.notebudget.app.domain.model.DomainResult.Failure) {
                            android.widget.Toast.makeText(context, context.getString(R.string.budget_log_undo_failed), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) {
                    Text(stringResource(R.string.budget_log_undo), color = if (added) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                }
            },
            dismissButton = { TextButton(onClick = { undoingLine = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.budget_log_clear_title)) },
            text = { Text(stringResource(R.string.budget_log_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { container.budgetLogRepository.clear(noteId) }
                }) { Text(stringResource(R.string.budget_log_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
