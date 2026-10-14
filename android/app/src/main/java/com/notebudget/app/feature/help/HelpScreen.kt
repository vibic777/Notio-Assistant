package com.notebudget.app.feature.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notebudget.app.R
import com.notebudget.app.core.ui.AppIcons
import com.notebudget.app.core.ui.tip.HelpLink
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import com.notebudget.app.core.ui.tip.stringResource
import com.notio.common.HelpMap

/**
 * The text of a key of [HelpMap], in the language of the app, through [HELP_STRINGS] (D336): a string asked for by its name
 * alone is taken out of the release build. `HelpMapTest` checks that every name of the help is in the table.
 */
@Composable
internal fun named(key: String): String {
    val id = HELP_STRINGS[key] ?: return key
    return androidx.compose.ui.res.stringResource(id)
}

/**
 * The help (§6.11, D332): a tab for every category, under it a tab for every section of the category; a section is its text and
 * the icons that are met there — each drawn as in the app, with its name. [topic] is the section (or category) it opens at:
 * a pressed hint names its own ([HelpLink]).
 */
@Composable
fun HelpContent(topic: String?, modifier: Modifier = Modifier) {
    val start = remember(topic) { HelpMap.find(topic) }
    var category by rememberSaveable(topic) { mutableIntStateOf(start.first) }
    var section by rememberSaveable(topic) { mutableIntStateOf(start.second) }
    val shown = HelpMap.CATEGORIES[category.coerceIn(0, HelpMap.CATEGORIES.lastIndex)]
    // The search of the help (D335): what is typed is looked for in every section; a place found opens its section.
    var query by rememberSaveable(topic) { mutableStateOf("") }
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val hits = remember(query, configuration) {
        fun words(key: String): String = HELP_STRINGS[key]?.let { context.getString(it) }.orEmpty()
        HelpMap.search(query, ::words)
    }
    Column(modifier) {
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it.take(60) }, singleLine = true,
            placeholder = { Text(named("help_search_hint")) },
            leadingIcon = { androidx.compose.material3.Icon(AppIcons.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) null else ({
                IconButton(onClick = { query = "" }) { Icon(AppIcons.Close, contentDescription = named("search_clear")) }
            }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (query.trim().length >= 2) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hits.isEmpty()) Text(named("search_no_results"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                hits.forEach { hit ->
                    Column(
                        Modifier.fillMaxWidth().clickable { category = hit.category; section = hit.section; query = "" }.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            named(HelpMap.CATEGORIES[hit.category].titleKey) + " › " + named(hit.titleKey),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        )
                        Text(snippet(hit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.HorizontalDivider()
                }
            }
            return@Column
        }
        ScrollableTabRow(selectedTabIndex = category, edgePadding = 8.dp) {
            HelpMap.CATEGORIES.forEachIndexed { index, item ->
                Tab(selected = index == category, onClick = { category = index; section = 0 }, text = { Text(named(item.titleKey)) })
            }
        }
        if (shown.sections.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = section.coerceIn(0, shown.sections.lastIndex), edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                shown.sections.forEachIndexed { index, item ->
                    Tab(selected = index == section, onClick = { section = index }, text = { Text(named(item.titleKey), style = MaterialTheme.typography.labelMedium) })
                }
            }
        }
        // Every section starts at its top.
        key(category, section) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (shown.sections.isEmpty()) {
                    Text(named(shown.titleKey), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    HelpMap.GLOSSARY_KEYS.forEach { Text(named(it), style = MaterialTheme.typography.bodyMedium) }
                } else {
                    val part = shown.sections[section.coerceIn(0, shown.sections.lastIndex)]
                    Text(named(part.titleKey), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(named(part.bodyKey), style = MaterialTheme.typography.bodyMedium)
                    if (part.items.isNotEmpty()) {
                        Text(named("help_icons_title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
                        part.items.forEach { item ->
                            val icon = AppIcons.byName(item.icon) ?: return@forEach
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(named(item.label).trimEnd('…', '.', ' '), style = MaterialTheme.typography.bodyMedium)
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

/** The help as a page of the app (Settings -> Help). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit, topic: String? = null) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding -> HelpContent(topic, Modifier.padding(padding)) }
}

/**
 * The help over whatever is on the screen (D332): a hint that was pressed opens it at its section. It is a window of its own,
 * above the page and above a dialog that may be open, so closing it leaves everything as it was — nothing typed is lost.
 */
@Composable
fun HelpOverlay() {
    if (!HelpLink.open) return
    Dialog(onDismissRequest = HelpLink::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) { HelpScreen(onBack = HelpLink::close, topic = HelpLink.topic) }
        }
    }
}
