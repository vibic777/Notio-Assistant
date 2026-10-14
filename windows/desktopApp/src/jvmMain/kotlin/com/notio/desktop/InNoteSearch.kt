package com.notio.desktop

import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/** One match: the [occurrence]-th time the words are found in the text named [key] (a line, an item, a row of a budget). */
data class SearchHit(val key: Any, val occurrence: Int)

/**
 * The search inside the open note (D238). The note screen owns it and shows the search field, the counter and the arrows; the
 * editor of the note finds the matches in what it shows ([report]), marks them ([highlighted]) and scrolls to [currentHit].
 */
@Stable
class NoteSearch {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
        private set
    var current by mutableIntStateOf(0)
        private set

    /** Letters found whatever their case (on by default) and the words read as a regular expression (off): D246. */
    var ignoreCase by mutableStateOf(true)
        private set
    var regex by mutableStateOf(false)
        private set

    /** The words made into what finds them, while the search is open; see [com.notio.shared.domain.TextPattern]. */
    val pattern: com.notio.shared.domain.TextPattern? by androidx.compose.runtime.derivedStateOf {
        if (open) com.notio.shared.domain.TextPattern.of(query, com.notio.shared.domain.SearchOptions(ignoreCase, regex)) else null
    }

    /** Why a regular expression finds nothing: it cannot be read. */
    val error: com.notio.shared.domain.PatternError? get() = pattern?.error

    /** The matches in the order they are shown, as the editor last reported them. */
    var hits by mutableStateOf<List<SearchHit>>(emptyList())
        private set

    /** How often the person asked to go to a match: a new request scrolls even when it is the same match again. */
    var jumps by mutableIntStateOf(0)
        private set

    val active: Boolean get() = pattern?.regex != null

    /** The pattern while it can find something, else `null`. */
    val activePattern: com.notio.shared.domain.TextPattern? get() = pattern?.takeIf { it.regex != null }
    val currentHit: SearchHit? get() = if (active) hits.getOrNull(current) else null

    fun report(found: List<SearchHit>) {
        if (found == hits) return
        hits = found
        if (current >= found.size) current = 0
    }

    fun type(text: String) {
        query = text
        current = 0
        jumps++
    }

    fun useIgnoreCase(on: Boolean) {
        ignoreCase = on
        current = 0
        jumps++
    }

    fun useRegex(on: Boolean) {
        regex = on
        current = 0
        jumps++
    }

    fun next() {
        if (hits.isEmpty()) return
        current = (current + 1) % hits.size
        jumps++
    }

    /** Makes the [index]-th match the current one (a tap on a found item). */
    fun jumpTo(index: Int) {
        if (index !in hits.indices) return
        current = index
        jumps++
    }

    fun previous() {
        if (hits.isEmpty()) return
        current = (current - 1 + hits.size) % hits.size
        jumps++
    }

    /** Ctrl+F again while the bar is open puts the cursor back into its field (the desktop). */
    var focusRequests by mutableIntStateOf(0)

    fun close() {
        open = false
        query = ""
        current = 0
        hits = emptyList()
    }
}

/** The search of the open note; `null` outside a note screen (a card, a dialog, a test of an editor alone). */
val LocalNoteSearch = staticCompositionLocalOf<NoteSearch?> { null }

/** Finding and marking the words. Pure, so it is unit-tested. */
object TextSearch {
    val MATCH = Color(0x99FFE082)
    val CURRENT = Color(0xFFFFB300)

    /** Where [pattern] is in [text]; the matches do not overlap. No pattern finds nothing. */
    fun ranges(text: String, pattern: com.notio.shared.domain.TextPattern?): List<IntRange> = pattern?.ranges(text).orEmpty()

    /** Plain words, ignoring case (the default of the search). */
    fun ranges(text: String, query: String): List<IntRange> = ranges(text, com.notio.shared.domain.TextPattern.of(query))

    /** The hits of one text, in order. */
    fun hits(key: Any, text: String, pattern: com.notio.shared.domain.TextPattern?): List<SearchHit> =
        List(ranges(text, pattern).size) { SearchHit(key, it) }

    fun hits(key: Any, text: String, query: String): List<SearchHit> = hits(key, text, com.notio.shared.domain.TextPattern.of(query))

    fun mark(text: AnnotatedString, query: String, key: Any, current: SearchHit?): AnnotatedString =
        mark(text, com.notio.shared.domain.TextPattern.of(query), key, current)

    /** [text] with every match marked, and the [current] one (when it is in this text) marked stronger. Styles are kept. */
    fun mark(text: AnnotatedString, pattern: com.notio.shared.domain.TextPattern?, key: Any, current: SearchHit?): AnnotatedString {
        val ranges = ranges(text.text, pattern)
        if (ranges.isEmpty()) return text
        return buildAnnotatedString {
            append(text)
            ranges.forEachIndexed { i, r ->
                val strong = current != null && current.key == key && current.occurrence == i
                addStyle(SpanStyle(background = if (strong) CURRENT else MATCH, color = if (strong) Color.Black else Color.Unspecified), r.first, r.last + 1)
            }
        }
    }
}

/**
 * A piece of a long [text] around one match of [pattern] (D253): the [current] one when it is in this text ([key]), else the
 * first; every match in the piece marked, the current stronger; line breaks shown as spaces. `null` when nothing matches.
 */
fun TextSearch.snippet(text: String, pattern: com.notio.shared.domain.TextPattern?, key: Any, current: SearchHit?, before: Int = 40, after: Int = 80): AnnotatedString? {
    val ranges = ranges(text, pattern)
    if (ranges.isEmpty()) return null
    val chosen = if (current != null && current.key == key) current.occurrence.coerceIn(0, ranges.lastIndex) else 0
    val start = (ranges[chosen].first - before).coerceAtLeast(0)
    val end = (ranges[chosen].last + 1 + after).coerceAtMost(text.length)
    val lead = if (start > 0) "…" else ""
    return buildAnnotatedString {
        append(lead)
        append(text.substring(start, end).replace('\n', ' ').replace('\r', ' '))
        if (end < text.length) append("…")
        ranges.forEachIndexed { i, r ->
            if (r.last < start || r.first >= end) return@forEachIndexed
            val from = r.first.coerceAtLeast(start) - start + lead.length
            val to = (r.last + 1).coerceAtMost(end) - start + lead.length
            val strong = current != null && current.key == key && current.occurrence == i
            addStyle(SpanStyle(background = if (strong) TextSearch.CURRENT else TextSearch.MATCH, color = if (strong) Color.Black else Color.Unspecified), from, to)
        }
    }
}

/** [text] as it is, or with the matches of the note's search marked while the search is on. */
@Composable
fun highlighted(text: AnnotatedString, key: Any): AnnotatedString {
    val search = LocalNoteSearch.current ?: return text
    val pattern = search.activePattern ?: return text
    val current = search.currentHit
    return remember(text, pattern, key, current) { TextSearch.mark(text, pattern, key, current) }
}

@Composable
fun highlighted(text: String, key: Any): AnnotatedString = highlighted(remember(text) { AnnotatedString(text) }, key)

/** The bar under the title of a note while its search is open: the words, "3 / 7", the previous and the next match, close. */
@Composable
fun NoteSearchBar(search: NoteSearch, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier, hint: String? = null) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(search.focusRequests) { runCatching { focus.requestFocus() } }
    val count = search.hits.size
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = search.query,
            onValueChange = search::type,
            singleLine = true,
            placeholder = { Text(hint ?: stringResource("note_search_hint")) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search.next() }),
            // The keys of the computer: Enter / Shift+Enter the next / previous match, Alt+C and Alt+R the two ticks (like editors).
            modifier = androidx.compose.ui.Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == androidx.compose.ui.input.key.Key.Enter -> { if (e.isShiftPressed) search.previous() else search.next(); true }
                    e.isAltPressed && e.key == androidx.compose.ui.input.key.Key.C -> { search.useIgnoreCase(!search.ignoreCase); true }
                    e.isAltPressed && e.key == androidx.compose.ui.input.key.Key.R -> { search.useRegex(!search.regex); true }
                    else -> false
                }
            },
        )
        Text(
            when {
                search.query.isBlank() -> ""
                search.error != null -> ""
                count == 0 -> stringResource("note_search_none")
                else -> stringResource("note_search_count", search.current + 1, count)
            },
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            // "3 / 7" and "nothing found" are said by a screen reader when they change (Android D274 step 5).
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 6.dp)
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        )
        IconButton(onClick = search::previous, enabled = count > 0) {
            Icon(
                androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource("note_search_previous"),
            )
        }
        IconButton(onClick = search::next, enabled = count > 0) {
            Icon(
                androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource("note_search_next"),
            )
        }
        IconButton(onClick = search::close) {
            Icon(
                androidx.compose.material.icons.Icons.Filled.Close,
                contentDescription = stringResource("note_search_close"),
            )
        }
    }
    SearchOptionsRow(
        ignoreCase = search.ignoreCase,
        regex = search.regex,
        error = search.error,
        expression = search.query,
        onIgnoreCase = search::useIgnoreCase,
        onRegex = search::useRegex,
        modifier = androidx.compose.ui.Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
    )
    }
}

/**
 * The two ticks of a search (D246), shared by the search inside a note and the search of notes: letters in any case, and the
 * words as a regular expression; under them why an expression cannot be used.
 */
@Composable
fun SearchOptionsRow(
    ignoreCase: Boolean,
    regex: Boolean,
    error: com.notio.shared.domain.PatternError?,
    expression: String,
    onIgnoreCase: (Boolean) -> Unit,
    onRegex: (Boolean) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    androidx.compose.foundation.layout.Column(modifier) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(
                selected = ignoreCase,
                onClick = { onIgnoreCase(!ignoreCase) },
                label = { Text(stringResource("search_ignore_case")) },
            )
            androidx.compose.material3.FilterChip(
                selected = regex,
                onClick = { onRegex(!regex) },
                label = { Text(stringResource("search_regex")) },
            )
        }
        if (error != null) PatternErrorText(error, expression)
    }
}

/**
 * A mistake in a regular expression, told in the language of the app (D247): what is wrong and how to write it instead, the number
 * of the character, and the expression itself with that character marked.
 */
@Composable
fun PatternErrorText(error: com.notio.shared.domain.PatternError, expression: String) {
    val sign = error.sign.orEmpty()
    val what = when (error.problem) {
        com.notio.shared.domain.PatternProblem.NOTHING_TO_REPEAT -> stringResource("search_err_nothing_to_repeat", sign)
        com.notio.shared.domain.PatternProblem.UNCLOSED_GROUP -> stringResource("search_err_unclosed_group")
        com.notio.shared.domain.PatternProblem.UNMATCHED_CLOSE -> stringResource("search_err_unmatched_close")
        com.notio.shared.domain.PatternProblem.UNCLOSED_CLASS -> stringResource("search_err_unclosed_class")
        com.notio.shared.domain.PatternProblem.BAD_BRACE -> stringResource("search_err_bad_brace")
        com.notio.shared.domain.PatternProblem.TRAILING_BACKSLASH -> stringResource("search_err_trailing_backslash")
        com.notio.shared.domain.PatternProblem.BAD_RANGE -> stringResource("search_err_bad_range", sign)
        com.notio.shared.domain.PatternProblem.OTHER -> stringResource("search_err_other")
    }
    val color = androidx.compose.material3.MaterialTheme.colorScheme.error
    val message = if (error.index >= 0) stringResource("search_err_at", error.index + 1, what)
    else stringResource("search_regex_error", what)
    Text(message, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = color)
    if (error.index in expression.indices) {
        val marked = remember(expression, error.index, color) {
            buildAnnotatedString {
                append(expression)
                addStyle(SpanStyle(background = color, color = Color.White), error.index, error.index + 1)
            }
        }
        Text(
            marked,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A match in a note made of items — recordings, photos, videos (D248): the [field] (0 the name as shown, 1 the comment) of [id]. */
data class ItemHitKey(val id: String, val field: Int)

/** One item as the search reads it: its [id], the [title] as shown (a default name when it has none) and its [comment]. */
data class SearchableItem(val id: String, val title: String, val comment: String)

/** The matches in [items], in their order: the name of each, then its comment. */
fun itemHits(items: List<SearchableItem>, pattern: com.notio.shared.domain.TextPattern?): List<SearchHit> {
    if (pattern == null) return emptyList()
    return items.flatMap { item ->
        TextSearch.hits(ItemHitKey(item.id, 0), item.title, pattern) +
            (if (item.comment.isNotBlank()) TextSearch.hits(ItemHitKey(item.id, 1), item.comment, pattern) else emptyList())
    }
}

/** Finds the words of the note's search in [items], reports them and scrolls [listState] to the current one ([before] rows first). */
@Composable
fun ItemSearchEffects(items: List<SearchableItem>, listState: androidx.compose.foundation.lazy.LazyListState, before: Int = 0) {
    val search = LocalNoteSearch.current ?: return
    val pattern = search.activePattern
    val hits = remember(items, pattern) { itemHits(items, pattern) }
    androidx.compose.runtime.LaunchedEffect(hits) { search.report(hits) }
    androidx.compose.runtime.LaunchedEffect(search.jumps, hits) {
        val key = search.currentHit?.key as? ItemHitKey ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.id == key.id }
        if (index >= 0) listState.animateScrollToItem(before + index)
    }
}

/** The matches of [query] in the rows as shown, in their order: the name of each group and item, then its comment. */
fun budgetHits(rows: List<com.notio.shared.domain.BudgetRowItem>, query: com.notio.shared.domain.TextPattern?, showComments: Boolean): List<SearchHit> {
    if (query == null) return emptyList()
    val search = TextSearch
    return rows.filter { it.depth > 0 && it.part != com.notio.shared.domain.BudgetRowPart.TOTAL }.flatMap { row ->
        search.hits(com.notio.shared.domain.BudgetHitKey(row.node.id, 0), row.node.title, query) +
            com.notio.shared.domain.shownComment(row.node, showComments)?.let { search.hits(com.notio.shared.domain.BudgetHitKey(row.node.id, 1), it, query) }.orEmpty()
    }
}
