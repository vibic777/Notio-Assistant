package com.notebudget.app.feature.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.notebudget.app.core.ui.NoticeHost
import com.notebudget.app.core.ui.showNotice
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.domain.usecase.TagUseCases

/** Settings → Manage tags (D109). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TagsViewModel = appViewModel { TagsViewModel(it.tagRepository, it.tagUseCases) },
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // In a job of its own: a newer message takes the place of the one shown, nothing waits for it (D360).
            launch {
                snackbar.showNotice(
                    when (event) {
                        is TagsEvent.Created -> context.getString(R.string.tags_created, event.name)
                        is TagsEvent.Renamed -> context.getString(R.string.tags_renamed, event.name)
                        is TagsEvent.Deleted -> context.getString(R.string.tags_deleted, event.name)
                        is TagsEvent.Failed -> context.getString(event.error.messageRes())
                    },
                )
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tags_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { NoticeHost(snackbar) },
        // D271: a tag can be made here too, before any note has it.
        floatingActionButton = {
            com.notebudget.app.core.ui.tip.ExtendedFloatingActionButton(onClick = viewModel::askCreate) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.tags_add_tag), Modifier.padding(start = 8.dp))
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                // Room for the button, so the last tag is never under it.
                bottom = innerPadding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.tags_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rows.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.tags_none_yet_manage), modifier = Modifier.padding(top = 16.dp)) }
            }
            items(rows, key = { it.tag.id }) { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("#" + row.tag.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            pluralStringResource(R.plurals.tags_used_by, row.noteCount, row.noteCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // D278: rename as a pencil beside the cross, a long press says which.
                    com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Edit, stringResource(R.string.tags_rename), { viewModel.askRename(row.tag) })
                    IconButton(onClick = { viewModel.askDelete(row) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.tags_delete))
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        TagsDialog.Create -> {
            var name by remember { mutableStateOf("") }
            com.notebudget.app.core.ui.EditorDialog(
                onDismissRequest = viewModel::dismissDialog,
                title = { Text(stringResource(R.string.tags_add_tag)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(TagUseCases.MAX_NAME_LENGTH + 1) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.tags_new_hint)) },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.create(name) }, enabled = name.isNotBlank()) {
                        Text(stringResource(R.string.tags_add_confirm))
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        is TagsDialog.Rename -> {
            var name by remember(d.tag.id) { mutableStateOf(d.tag.name) }
            com.notebudget.app.core.ui.EditorDialog(
                onDismissRequest = viewModel::dismissDialog,
                title = { Text(stringResource(R.string.tags_rename_title)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(TagUseCases.MAX_NAME_LENGTH + 1) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.tags_new_hint)) },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.rename(d.tag, name) }, enabled = name.isNotBlank()) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        is TagsDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.tags_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.tags_delete_message, d.row.noteCount, d.row.tag.name, d.row.noteCount)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
