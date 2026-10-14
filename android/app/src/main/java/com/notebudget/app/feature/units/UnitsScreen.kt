package com.notebudget.app.feature.units

import androidx.annotation.StringRes
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
import com.notebudget.app.core.ui.tip.ExtendedFloatingActionButton
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
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.domain.model.BuiltInUnit
import com.notebudget.app.domain.model.CustomUnit

/**
 * Units of measurement (D92): the built-in units (cannot be deleted) and the ones the user added.
 * Lists offer all of them for the quantity of an item and for a "Total by unit".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UnitsViewModel = appViewModel { UnitsViewModel(it.unitRepository, it.unitUseCases) },
) {
    val custom by viewModel.custom.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // In a job of its own: a newer message takes the place of the one shown, nothing waits for it (D360).
            launch {
                snackbar.showNotice(
                    when (event) {
                        is UnitsEvent.Added -> context.getString(R.string.units_added, event.name)
                        is UnitsEvent.Renamed -> context.getString(R.string.units_renamed, event.name)
                        is UnitsEvent.Deleted -> context.getString(R.string.units_deleted, event.name)
                        is UnitsEvent.Failed -> context.getString(event.error.messageRes())
                    },
                )
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.units_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { NoticeHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::askAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.units_add)) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.units_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "custom-title") { SectionTitle(R.string.units_custom_section) }
            if (custom.isEmpty()) {
                item(key = "custom-empty") {
                    Text(stringResource(R.string.units_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(custom, key = { it.id }) { unit ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(unit.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    // D277: a unit of one's own can be renamed, as a tag.
                    com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Edit, stringResource(R.string.tags_rename), { viewModel.askRename(unit) })
                    IconButton(onClick = { viewModel.askDelete(unit) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.units_delete))
                    }
                }
            }
            item(key = "builtin-title") { SectionTitle(R.string.units_built_in_section) }
            items(BuiltInUnit.ALL, key = { "b-$it" }) { id ->
                val symbol = UnitLabels.symbolRes(id)?.let { stringResource(it) }.orEmpty()
                val name = UnitLabels.nameRes(id)?.let { stringResource(it) }.orEmpty()
                Text("$symbol - $name", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        UnitsDialog.Add -> AddUnitDialog(
            onAdd = { viewModel.add(it, UnitLabels.builtInNames(context)) },
            onDismiss = viewModel::dismissDialog,
        )
        is UnitsDialog.Rename -> AddUnitDialog(
            onAdd = { viewModel.rename(d.unit, it, UnitLabels.builtInNames(context)) },
            onDismiss = viewModel::dismissDialog,
            initial = d.unit.name,
            titleRes = R.string.units_rename_title,
            confirmRes = R.string.action_save,
        )
        is UnitsDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.units_delete_title)) },
            text = { Text(stringResource(R.string.units_delete_message, d.unit.name)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(@StringRes textRes: Int) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AddUnitDialog(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    @StringRes titleRes: Int = R.string.units_add_title,
    @StringRes confirmRes: Int = R.string.action_create,
) {
    var name by remember { mutableStateOf(initial) }
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CustomUnit.MAX_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.units_name_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank()) { Text(stringResource(confirmRes)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
