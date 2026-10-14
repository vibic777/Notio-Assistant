package com.notebudget.app.feature.currencies

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
import com.notebudget.app.domain.model.BuiltInCurrency
import com.notebudget.app.domain.model.CustomCurrency

/**
 * Currencies of the budget notes (D170): the built-in ones (cannot be deleted) and the ones the person added. A budget offers
 * all of them and chooses one for the whole note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrenciesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CurrenciesViewModel = appViewModel { CurrenciesViewModel(it.currencyRepository, it.currencyUseCases) },
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
                        is CurrenciesEvent.Added -> context.getString(R.string.currencies_added, event.name)
                        is CurrenciesEvent.Renamed -> context.getString(R.string.currencies_renamed, event.name)
                        is CurrenciesEvent.Deleted -> context.getString(R.string.currencies_deleted, event.name)
                        is CurrenciesEvent.Failed -> context.getString(event.error.messageRes())
                    },
                )
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.currencies_title)) },
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
                text = { Text(stringResource(R.string.currencies_add)) },
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
                    stringResource(R.string.currencies_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "custom-title") { SectionTitle(R.string.currencies_custom_section) }
            if (custom.isEmpty()) {
                item(key = "custom-empty") {
                    Text(stringResource(R.string.currencies_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(custom, key = { it.id }) { currency ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(currency.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    // D277: a currency of one's own can be renamed; the budgets that use it follow.
                    com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Edit, stringResource(R.string.tags_rename), { viewModel.askRename(currency) })
                    IconButton(onClick = { viewModel.askDelete(currency) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.currencies_delete))
                    }
                }
            }
            item(key = "builtin-title") { SectionTitle(R.string.currencies_built_in_section) }
            items(BuiltInCurrency.ALL, key = { "b-$it" }) { id ->
                Text(
                    CurrencyLabels.fullLabel(context, id, custom),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        CurrenciesDialog.Add -> AddCurrencyDialog(
            onAdd = { viewModel.add(it, CurrencyLabels.reservedNames(context)) },
            onDismiss = viewModel::dismissDialog,
        )
        is CurrenciesDialog.Rename -> AddCurrencyDialog(
            onAdd = { viewModel.rename(d.currency, it, CurrencyLabels.reservedNames(context)) },
            onDismiss = viewModel::dismissDialog,
            initial = d.currency.name,
            titleRes = R.string.currencies_rename_title,
            confirmRes = R.string.action_save,
        )
        is CurrenciesDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(stringResource(R.string.currencies_delete_title)) },
            text = { Text(stringResource(R.string.currencies_delete_message, d.currency.name)) },
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
private fun AddCurrencyDialog(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    @StringRes titleRes: Int = R.string.currencies_add_title,
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
                    onValueChange = { name = it.take(CustomCurrency.MAX_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.currencies_name_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank()) { Text(stringResource(confirmRes)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
