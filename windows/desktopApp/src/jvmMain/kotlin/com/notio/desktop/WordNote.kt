package com.notio.desktop

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.material3.Surface
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.RichAlign
import com.notio.shared.domain.RichDocument
import com.notio.shared.domain.RichEdit
import com.notio.shared.domain.RichList
import com.notio.shared.domain.RichStyle

/** The document of a formatted note (D214); a new one (nothing in `extras` yet) starts as plain text. */
fun wordDocument(content: String?, extras: String?): RichDocument =
    RichDocument.decode(extras, content.orEmpty()) ?: RichDocument(content.orEmpty()).normalized()

/** The View of a formatted note (Android RichTextView): every line with its marker, indent, alignment and styles; check boxes tick here. */
@Composable
fun RichTextView(s: Strings, doc: RichDocument, onToggle: (Int) -> Unit, viewScroll: androidx.compose.foundation.ScrollState? = null) {
    val starts = remember(doc) { doc.lineStarts() }
    val numbers = remember(doc) { doc.numbers() }
    val styles = remember(doc) { doc.styles() }
    // The search of the note (D256): the words are found line by line in what the View shows, and it scrolls to the current one.
    val search = LocalNoteSearch.current
    val pattern = search?.activePattern
    val lineTexts = remember(doc, starts) { starts.indices.map { i -> doc.text.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] - 1 else doc.text.length) } }
    val hits = remember(lineTexts, pattern) {
        if (pattern == null) emptyList() else lineTexts.flatMapIndexed { i, t -> TextSearch.hits(RichHitKey(i), t, pattern) }
    }
    LaunchedEffect(hits) { search?.report(hits) }
    val tops = remember { HashMap<Int, Int>() }
    var columnTop by remember { mutableStateOf(0) }
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }
    LaunchedEffect(search?.jumps, hits) {
        val key = search?.currentHit?.key as? RichHitKey ?: return@LaunchedEffect
        val top = tops[key.line] ?: return@LaunchedEffect
        viewScroll?.let { it.animateScrollTo((columnTop + top - margin).coerceIn(0, it.maxValue)) }
    }
    val current = search?.currentHit
    Column(
        Modifier.fillMaxWidth().onGloballyPositioned { columnTop = it.positionInParent().y.toInt() },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (doc.text.isBlank()) Text(s.previewEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else for (i in starts.indices) {
            val para = doc.paras[i]
            val end = if (i + 1 < starts.size) starts[i + 1] - 1 else doc.text.length
            Row(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { tops[i] = it.positionInParent().y.toInt() }
                    .padding(start = (para.indent * RichRender.INDENT_SP).dp),
                verticalAlignment = Alignment.Top,
            ) {
                when (para.list) {
                    RichList.CHECK -> Checkbox(checked = para.checked, onCheckedChange = { onToggle(i) }, modifier = Modifier.size(32.dp))
                    RichList.NONE -> Unit
                    else -> Text(RichRender.marker(para, numbers[i]), fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                }
                val plain = RichRender.piece(doc, styles, starts[i], end)
                val line = if (pattern == null) plain else TextSearch.mark(plain, pattern, RichHitKey(i), current)
                Text(
                    if (line.isEmpty()) AnnotatedString(" ") else line,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = RichRender.align(para.align),
                    textDecoration = if (para.list == RichList.CHECK && para.checked) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f).padding(top = if (para.list == RichList.CHECK) 4.dp else 0.dp),
                )
                // Copy of one line of a list (D143, Android D362): its words, without the marker.
                if (para.list != RichList.NONE && end > starts[i]) CopyIcon(small = true) { doc.text.substring(starts[i], end) }
            }
        }
    }
}

private enum class Panel { NONE, SIZE, COLOR, BACKGROUND }

private fun hexOf(argb: Int?): String? = argb?.let { String.format("#%06X", it and 0xFFFFFF) }
private fun argbOf(hex: String?): Int? = hex?.removePrefix("#")?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }

/**
 * The Edit tab of a formatted note (Android RichTextEditor): the field shows the styles as they will look, the toolbar under it.
 * With nothing selected a style button sets the style of what is typed next, as in a word processor; Ctrl+B / Ctrl+I / Ctrl+U too.
 */
@Composable
fun RichTextEditor(s: Strings, doc: RichDocument, onChange: (RichDocument) -> Unit, modifier: Modifier = Modifier) {
    var field by remember { mutableStateOf(TextFieldValue(doc.text)) }
    var typing by remember { mutableStateOf<RichStyle?>(null) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    val current by rememberUpdatedState(doc)
    LaunchedEffect(doc.text) {
        if (doc.text != field.text) field = TextFieldValue(doc.text, TextRange(minOf(field.selection.start, doc.text.length)))
    }
    val start = field.selection.min
    val end = field.selection.max
    val markerColor = MaterialTheme.colorScheme.primary
    // The search of the note (D256) in Edit: the words are marked in the text, the current match is selected.
    val search = LocalNoteSearch.current
    val pattern = search?.activePattern
    val rawHits = remember(doc.text, pattern) { TextSearch.hits(RichRawKey, doc.text, pattern) }
    LaunchedEffect(rawHits) { search?.report(rawHits) }
    LaunchedEffect(search?.jumps, rawHits) {
        val hit = search?.currentHit?.takeIf { it.key == RichRawKey } ?: return@LaunchedEffect
        val r = TextSearch.ranges(doc.text, pattern).getOrNull(hit.occurrence) ?: return@LaunchedEffect
        if (field.text == doc.text) field = field.copy(selection = TextRange(r.first, r.last + 1))
    }
    val currentHit = search?.currentHit
    val transformation = remember(doc, markerColor, pattern, currentHit) { RichRender.Transformation(doc, markerColor, pattern, currentHit) }

    fun styleNow(): RichStyle = typing ?: current.styleAt(start)
    fun has(test: (RichStyle) -> Boolean) = if (start == end) test(styleNow()) else RichEdit.all(current, start, end, test)
    fun toggle(test: (RichStyle) -> Boolean, set: (RichStyle, Boolean) -> RichStyle) {
        if (start == end) typing = styleNow().let { set(it, !test(it)) }
        else onChange(RichEdit.toggle(current, start, end, test, set))
    }
    fun restyle(change: (RichStyle) -> RichStyle) {
        if (start == end) typing = change(styleNow()) else onChange(RichEdit.style(current, start, end, change))
    }
    val bold = { toggle({ it.bold }) { st, on -> st.copy(bold = on) } }
    val italic = { toggle({ it.italic }) { st, on -> st.copy(italic = on) } }
    val underline = { toggle({ it.underline }) { st, on -> st.copy(underline = on) } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Tool(s.mdBold + " (Ctrl+B)", "B", has { it.bold }, onClick = bold)
            Tool(s.mdItalic + " (Ctrl+I)", "I", has { it.italic }, italic = true, onClick = italic)
            Tool(s.richUnderline + " (Ctrl+U)", "U", has { it.underline }, decoration = TextDecoration.Underline, onClick = underline)
            Tool(s.mdStrike, "S", has { it.strike }, decoration = TextDecoration.LineThrough) { toggle({ it.strike }) { st, on -> st.copy(strike = on) } }
            Tool(s.richSuperscript, "x²", has { it.script == 1 }) { toggle({ it.script == 1 }) { st, on -> st.copy(script = if (on) 1 else 0) } }
            Tool(s.richSubscript, "x₂", has { it.script == -1 }) { toggle({ it.script == -1 }) { st, on -> st.copy(script = if (on) -1 else 0) } }
            Tool(s.richSize, "A↕", panel == Panel.SIZE) { panel = if (panel == Panel.SIZE) Panel.NONE else Panel.SIZE }
            Tool(s.richTextColor, "A", panel == Panel.COLOR, color = styleNow().color?.let { Color(it) }) { panel = if (panel == Panel.COLOR) Panel.NONE else Panel.COLOR }
            Tool(s.richBackground, "▒", panel == Panel.BACKGROUND, color = styleNow().background?.let { Color(it) }) { panel = if (panel == Panel.BACKGROUND) Panel.NONE else Panel.BACKGROUND }
            val para = current.normalized().paras.getOrNull(current.lineOf(start))
            Tool(s.mdBullets, "•", para?.list == RichList.BULLET) { onChange(RichEdit.toggleList(current, start, end, RichList.BULLET)) }
            Tool(s.mdNumbered, "1.", para?.list == RichList.NUMBER) { onChange(RichEdit.toggleList(current, start, end, RichList.NUMBER)) }
            Tool(s.mdChecklist, "☐", para?.list == RichList.CHECK) { onChange(RichEdit.toggleList(current, start, end, RichList.CHECK)) }
            Tool(s.richIndentLess, "⇤", false) { onChange(RichEdit.indent(current, start, end, -1)) }
            Tool(s.richIndentMore, "⇥", false) { onChange(RichEdit.indent(current, start, end, 1)) }
            Tool(s.richAlignLeft, "⯇", para?.align == RichAlign.START) { onChange(RichEdit.lines(current, start, end) { it.copy(align = RichAlign.START) }) }
            Tool(s.richAlignCenter, "≡", para?.align == RichAlign.CENTER) { onChange(RichEdit.lines(current, start, end) { it.copy(align = RichAlign.CENTER) }) }
            Tool(s.richAlignRight, "⯈", para?.align == RichAlign.END) { onChange(RichEdit.lines(current, start, end) { it.copy(align = RichAlign.END) }) }
            Tool(s.richClear, "✘", false) { typing = null; if (start != end) onChange(RichEdit.clear(current, start, end)) }
        }
        when (panel) {
            Panel.SIZE -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RichStyle.SIZES.forEach { size ->
                    val chosen = (styleNow().size ?: 1f) == size
                    FilterChip(chosen, { restyle { it.copy(size = size.takeIf { v -> v != 1f }) }; panel = Panel.NONE }, { Text("${(size * 100).toInt()}%") })
                }
            }
            Panel.COLOR -> ColorChooser(s, hexOf(styleNow().color), allowNone = true, noneLabel = s.richColorAuto) { c -> restyle { st -> st.copy(color = argbOf(c)) } }
            Panel.BACKGROUND -> ColorChooser(s, hexOf(styleNow().background), allowNone = true, noneLabel = s.richColorAuto) { c -> restyle { st -> st.copy(background = argbOf(c)) } }
            Panel.NONE -> Unit
        }
      val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
      androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().weight(1f)) {
        // The page follows the cursor: its line is kept second from the top (Android D321).
        CursorField(
            value = field,
            onValueChange = { new ->
                val d = current
                val endsList = endsEmptyListItem(d, new.text)
                when {
                    endsList != null -> onChange(RichEdit.lines(d, endsList, endsList) { it.copy(list = RichList.NONE, checked = false, indent = 0) })
                    new.text != d.text -> { onChange(RichEdit.changeText(d, new.text, typing)); field = new }
                    else -> {
                        if (new.selection != field.selection) typing = null
                        field = new
                    }
                }
            },
            placeholder = { Text(s.textHint) },
            visualTransformation = transformation,
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxSize().focusRequester(fieldFocus).onRichKeys(bold, italic, underline),
        )
        // A long text (D250): to its first or last line; the cursor goes there and the field follows it.
        QuickScrollAlways(
            long = field.text.length > LONG_TEXT || field.text.count { it == '\n' } > LONG_LINES,
            onTop = { field = field.copy(selection = TextRange(0)); runCatching { fieldFocus.requestFocus() } },
            onBottom = { field = field.copy(selection = TextRange(field.text.length)); runCatching { fieldFocus.requestFocus() } },
        )
      }
    }
}

/** Ctrl+B, Ctrl+I, Ctrl+U in the field, the keys of every word processor. */
private fun Modifier.onRichKeys(bold: () -> Unit, italic: () -> Unit, underline: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
    when (e.key) {
        Key.B -> { bold(); true }
        Key.I -> { italic(); true }
        Key.U -> { underline(); true }
        else -> false
    }
}

/** Enter on an empty list item ends the list instead of making another empty item: the offset of that item's line, else `null`. */
private fun endsEmptyListItem(doc: RichDocument, newText: String): Int? {
    val old = doc.text
    if (newText.length != old.length + 1) return null
    var at = 0
    while (at < old.length && old[at] == newText[at]) at++
    if (newText[at] != '\n' || newText.substring(at + 1) != old.substring(at)) return null
    val line = doc.lineOf(at)
    val lineStart = doc.lineStarts()[line]
    val lineEnd = old.indexOf('\n', lineStart).let { if (it < 0) old.length else it }
    val para = doc.normalized().paras[line]
    return if (para.list != RichList.NONE && lineStart == lineEnd) lineStart else null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tool(
    label: String,
    glyph: String,
    active: Boolean,
    italic: Boolean = false,
    decoration: TextDecoration? = null,
    color: Color? = null,
    onClick: () -> Unit,
) {
    // The name of a tool shows when the mouse rests on it (the phone reads it out instead).
    TooltipArea(tooltip = {
        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
            Text(label, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp)),
        ) {
            Text(
                glyph, fontWeight = FontWeight.Bold, fontStyle = if (italic) FontStyle.Italic else null, textDecoration = decoration,
                color = color ?: Color.Unspecified, style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
