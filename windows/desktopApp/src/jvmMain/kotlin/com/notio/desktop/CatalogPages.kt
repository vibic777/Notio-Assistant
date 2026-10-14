package com.notio.desktop

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.BuiltInCurrency
import com.notio.shared.domain.BuiltInUnit
import com.notio.shared.domain.CustomCurrency
import com.notio.shared.domain.CustomUnit
import com.notio.shared.domain.DomainError
import com.notio.shared.domain.Tag

/**
 * The lists of Settings -> Notes and export (Android feature/tags, feature/units, feature/currencies; AGENT.md D30): the tags with
 * how many notes have each (rename, delete after a confirmation), the units and the currencies — the built-in ones listed, the
 * person's own ones added and deleted by the phone's rules.
 */
@Composable
fun CatalogScreen(kind: String, controller: NotioController) {
    // Read again after every change made here.
    var version by remember { mutableIntStateOf(0) }
    val changed: () -> Unit = { version++ }
    when (kind) {
        "tags" -> TagsPage(controller, version, changed)
        "units" -> UnitsPage(controller, version, changed)
        else -> CurrenciesPage(controller, version, changed)
    }
}

fun catalogTitle(kind: String): String = when (kind) {
    "tags" -> "tags_title"
    "units" -> "units_title"
    else -> "currencies_title"
}

@Composable
private fun CatalogFrame(hint: String, addLabel: String?, onAdd: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Page { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = 900.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "hint") { Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                content()
            }
            if (addLabel != null) ExtendedFloatingActionButton(
                onClick = onAdd,
                // The words for a screen reader (the text slot of this button is not read, found by ScreenReaderTest).
                icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(addLabel)) },
                text = { Text(stringResource(addLabel)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() })
}

// ---- tags (D109) ----

@Composable
private fun TagsPage(controller: NotioController, version: Int, changed: () -> Unit) {
    val rows = remember(version, controller.state.value.allTags) { controller.tagRows() }
    var renaming by remember { mutableStateOf<Tag?>(null) }
    var deleting by remember { mutableStateOf<Pair<Tag, Int>?>(null) }
    // Android D271: a tag can be made here too, before any note has it.
    var adding by remember { mutableStateOf(false) }
    CatalogFrame("tags_hint", stringResource("tags_add_tag"), { adding = true }) {
        if (rows.isEmpty()) item(key = "empty") { Text(stringResource("tags_none_yet_manage"), modifier = Modifier.padding(top = 16.dp)) }
        items(rows, key = { it.first.id }) { (tag, count) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#" + tag.name, style = MaterialTheme.typography.bodyLarge)
                    Text(pluralStringResource("tags_used_by", count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Android D278: rename as a pencil beside the cross, its name on hover.
                IconAction(AppIcons.Edit, stringResource("tags_rename"), { renaming = tag })
                IconButton(onClick = { deleting = tag to count }) { Icon(Icons.Filled.Close, contentDescription = stringResource("tags_delete")) }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        com.notio.desktop.EditorDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource("tags_add_tag")) },
            text = { OutlinedTextField(name, { name = it.take(41) }, singleLine = true, label = { Text(stringResource("tags_new_hint")) }) },
            confirmButton = {
                TextButton(onClick = {
                    controller.addTag(name) { tag -> controller.say(controller.res.str("tags_created", tag.name)); adding = false; changed() }
                }, enabled = name.isNotBlank()) { Text(stringResource("tags_add_confirm")) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource("action_cancel")) } },
        )
    }
    renaming?.let { tag ->
        var name by remember(tag.id) { mutableStateOf(tag.name) }
        com.notio.desktop.EditorDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource("tags_rename_title")) },
            text = { OutlinedTextField(name, { name = it.take(41) }, singleLine = true, label = { Text(stringResource("tags_new_hint")) }) },
            confirmButton = {
                TextButton(onClick = { controller.renameTag(tag.id, name); controller.say(controller.res.str("tags_renamed", name.trim())); renaming = null; changed() }, enabled = name.isNotBlank()) {
                    Text(stringResource("action_save"))
                }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource("action_cancel")) } },
        )
    }
    deleting?.let { (tag, count) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource("tags_delete_title")) },
            text = { Text(pluralStringResource("tags_delete_message", count, tag.name, count)) },
            confirmButton = {
                TextButton(onClick = { controller.deleteTag(tag.id); controller.say(controller.res.str("tags_deleted", tag.name)); deleting = null; changed() }) {
                    Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource("action_cancel")) } },
        )
    }
}

// ---- units (D92) ----

@Composable
private fun UnitsPage(controller: NotioController, version: Int, changed: () -> Unit) {
    val custom = remember(version) { controller.customUnits() }
    val res = LocalRes.current
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomUnit?>(null) }
    var renaming by remember { mutableStateOf<CustomUnit?>(null) }
    CatalogFrame("units_hint", "units_add", { adding = true }) {
        item(key = "custom-title") { SectionTitle("units_custom_section") }
        if (custom.isEmpty()) item(key = "custom-empty") { Text(stringResource("units_none"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(custom, key = { it.id }) { unit ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(unit.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                // Android D277: a unit of one's own can be renamed, as a tag.
                IconAction(AppIcons.Edit, stringResource("tags_rename"), { renaming = unit })
                IconButton(onClick = { deleting = unit }) { Icon(Icons.Filled.Close, contentDescription = stringResource("units_delete")) }
            }
        }
        item(key = "builtin-title") { SectionTitle("units_built_in_section") }
        items(BuiltInUnit.ALL, key = { "b-$it" }) { id ->
            val symbol = UnitLabels.symbolRes(id)?.let(res::str).orEmpty()
            val name = UnitLabels.nameRes(id)?.let(res::str).orEmpty()
            Text("$symbol - $name", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
    if (adding) AddNameDialog("units_add_title", "units_name_label", CustomUnit.MAX_NAME_LENGTH, onDismiss = { adding = false }) { name ->
        controller.addUnit(name).also { if (it == null) { adding = false; changed() } }
    }
    renaming?.let { unit ->
        AddNameDialog("units_rename_title", "units_name_label", CustomUnit.MAX_NAME_LENGTH, onDismiss = { renaming = null }, initial = unit.name, confirm = "action_save") { name ->
            controller.renameUnit(unit.id, name).also { if (it == null) { renaming = null; changed() } }
        }
    }
    deleting?.let { unit ->
        ConfirmDelete("units_delete_title", stringResource("units_delete_message", unit.name), { deleting = null }) {
            controller.deleteUnit(unit.id); deleting = null; changed()
        }
    }
}

// ---- currencies (D170) ----

@Composable
private fun CurrenciesPage(controller: NotioController, version: Int, changed: () -> Unit) {
    val custom = remember(version) { controller.customCurrencies() }
    val res = LocalRes.current
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomCurrency?>(null) }
    var renaming by remember { mutableStateOf<CustomCurrency?>(null) }
    CatalogFrame("currencies_hint", "currencies_add", { adding = true }) {
        item(key = "custom-title") { SectionTitle("currencies_custom_section") }
        if (custom.isEmpty()) item(key = "custom-empty") { Text(stringResource("currencies_none"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(custom, key = { it.id }) { currency ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(currency.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                // Android D277: a currency of one's own can be renamed; the budgets that use it follow.
                IconAction(AppIcons.Edit, stringResource("tags_rename"), { renaming = currency })
                IconButton(onClick = { deleting = currency }) { Icon(Icons.Filled.Close, contentDescription = stringResource("currencies_delete")) }
            }
        }
        item(key = "builtin-title") { SectionTitle("currencies_built_in_section") }
        items(BuiltInCurrency.ALL, key = { "b-$it" }) { id ->
            val name = runCatching { java.util.Currency.getInstance(id).getDisplayName(java.util.Locale.forLanguageTag(res.language)) }.getOrNull() ?: id
            Text(BuiltInCurrency.symbolOf(id) + " - " + name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
    if (adding) AddNameDialog("currencies_add_title", "currencies_name_label", CustomCurrency.MAX_NAME_LENGTH, onDismiss = { adding = false }) { name ->
        controller.addCurrency(name).also { if (it == null) { adding = false; changed() } }
    }
    renaming?.let { currency ->
        AddNameDialog("currencies_rename_title", "currencies_name_label", CustomCurrency.MAX_NAME_LENGTH, onDismiss = { renaming = null }, initial = currency.name, confirm = "action_save") { name ->
            controller.renameCurrency(currency.id, name) { renaming = null; changed() }
            null
        }
    }
    deleting?.let { currency ->
        ConfirmDelete("currencies_delete_title", stringResource("currencies_delete_message", currency.name), { deleting = null }) {
            controller.deleteCurrency(currency.id); deleting = null; changed()
        }
    }
}

/** A name to add; the error of the last try is shown under the field and the dialog stays open (the phone's behaviour). */
@Composable
private fun AddNameDialog(title: String, label: String, max: Int, onDismiss: () -> Unit, initial: String = "", confirm: String = "action_create", onAdd: (String) -> DomainError?) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<DomainError?>(null) }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                name, { name = it.take(max); error = null }, singleLine = true, label = { Text(stringResource(label)) },
                isError = error != null, supportingText = error?.let { e -> { Text(stringResource(e.messageRes())) } },
            )
        },
        confirmButton = { TextButton(onClick = { error = onAdd(name) }, enabled = name.isNotBlank()) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

@Composable
private fun ConfirmDelete(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}
