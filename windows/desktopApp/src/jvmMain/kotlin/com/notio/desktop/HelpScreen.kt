package com.notio.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notio.common.HelpMap

/**
 * Where a pressed hint goes (D113, Android D332: "while the hint of a button is shown, a press on it goes to the part of the
 * help it belongs to"). A hint only says which section; the help itself is shown over the window by [HelpOverlay].
 */
object HelpLink {
    /** The section (or category) of [HelpMap] the help opens at; `null` is its beginning. */
    var topic: String? by mutableStateOf(null)
        private set

    var open: Boolean by mutableStateOf(false)
        private set

    /**
     * The section the screen on show is about, told by the window. A button whose words do not say where it belongs (an icon
     * button has a hint, not a key) is explained by the help of its screen. Read at a press only.
     */
    @Volatile var around: String? = null

    fun open(topic: String?) {
        this.topic = topic ?: around
        open = true
    }

    fun close() {
        open = false
    }

    /** The section of the help for a screen of the window. */
    fun topicOf(screen: Screen, noteType: String?): String = when (screen) {
        Screen.Home -> "start"
        is Screen.NoteView -> HelpMap.topicForNoteType(noteType)
        Screen.Trash -> "trash"
        Screen.Settings -> "settings"
        Screen.Sync -> "sync"
        Screen.Help -> "hints"
        is Screen.Catalog -> when (screen.kind) { "backgrounds" -> "backgrounds"; "lock" -> "lock"; "assistant", "assistant_chat" -> "assistant"; else -> "settings" }
        is Screen.Reminders, Screen.Calendar -> "reminders"
    }
}

/**
 * The help (Android feature/help, §6.11, D332): a tab for every category, under it a tab for every section of the category; a
 * section is its text and the icons that are met there — each drawn as in the app, with its name. [topic] is the section (or
 * category) it opens at: a pressed hint names its own.
 */
@Composable
fun HelpScreen(topic: String? = null) {
    val start = remember(topic) { HelpMap.find(topic) }
    var category by remember(topic) { mutableIntStateOf(start.first) }
    var section by remember(topic) { mutableIntStateOf(start.second) }
    val shown = HelpMap.CATEGORIES[category.coerceIn(0, HelpMap.CATEGORIES.lastIndex)]
    // The search of the help (D116, Android D335): what is typed is looked for in every section; a place found opens its section.
    var query by remember(topic) { mutableStateOf("") }
    val res = LocalRes.current
    val hits = remember(query, res) { HelpMap.search(query) { key -> if (res.has(key)) res.str(key) else "" } }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it.take(60) }, singleLine = true,
            placeholder = { Text(stringResource("help_search_hint")) },
            leadingIcon = { androidx.compose.material3.Icon(AppIcons.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) null else ({
                IconButton(onClick = { query = "" }) { Icon(AppIcons.Close, contentDescription = stringResource("search_clear")) }
            }),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 600.dp).fillMaxWidth(),
        )
        if (query.trim().length >= 2) {
            Page { pad ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hits.isEmpty()) Text(stringResource("search_no_results"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    hits.forEach { hit ->
                        Column(
                            Modifier.fillMaxWidth().clickable { category = hit.category; section = hit.section; query = "" }.padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(HelpMap.CATEGORIES[hit.category].titleKey) + " › " + stringResource(hit.titleKey),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            )
                            Text(snippet(hit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.HorizontalDivider()
                    }
                }
            }
            return@Column
        }
        ScrollableTabRow(selectedTabIndex = category, edgePadding = 8.dp) {
            HelpMap.CATEGORIES.forEachIndexed { index, item ->
                Tab(selected = index == category, onClick = { category = index; section = 0 }, text = { Text(stringResource(item.titleKey)) })
            }
        }
        if (shown.sections.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = section.coerceIn(0, shown.sections.lastIndex), edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                shown.sections.forEachIndexed { index, item ->
                    Tab(selected = index == section, onClick = { section = index }, text = { Text(stringResource(item.titleKey), style = MaterialTheme.typography.labelMedium) })
                }
            }
        }
        // Every section starts at its top.
        key(category, section) {
            Page { pad ->
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), contentAlignment = Alignment.TopStart) {
                    Column(Modifier.padding(vertical = 16.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (shown.sections.isEmpty()) {
                            Text(stringResource(shown.titleKey), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            HelpMap.GLOSSARY_KEYS.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                        } else {
                            val part = shown.sections[section.coerceIn(0, shown.sections.lastIndex)]
                            Text(stringResource(part.titleKey), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            Text(stringResource(part.bodyKey), style = MaterialTheme.typography.bodyMedium)
                            if (part.items.isNotEmpty()) {
                                Text(stringResource("help_icons_title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
                                part.items.forEach { item ->
                                    val icon = AppIcons.byName(item.icon) ?: return@forEach
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(stringResource(item.label).trimEnd('…', '.', ' '), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The piece of text of a place found, the found words in bold. */
private fun snippet(hit: HelpMap.Hit): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(hit.snippet)
    if (hit.from >= 0 && hit.to <= hit.snippet.length) {
        addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), hit.from, hit.to)
    }
}

/**
 * The help over whatever is in the window (D113): a hint that was pressed opens it at its section. It lies above the page and
 * above a dialog that may be open, so closing it leaves everything as it was — nothing typed is lost.
 */
@Composable
fun HelpOverlay() {
    if (!HelpLink.open) return
    Dialog(onDismissRequest = HelpLink::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = HelpLink::close) { Icon(AppIcons.ArrowBack, contentDescription = stringResource("action_back")) }
                    Text(stringResource("help_title"), style = MaterialTheme.typography.titleLarge)
                }
                HelpScreen(HelpLink.topic)
            }
        }
    }
}
