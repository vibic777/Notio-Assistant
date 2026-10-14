package com.notebudget.app.feature.notes.rich

import com.notebudget.app.core.ui.QuickScroll
import com.notebudget.app.core.ui.QuickScrollAlways
import androidx.compose.ui.focus.focusRequester
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import com.notebudget.app.core.ui.tip.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.domain.model.RichAlign
import com.notebudget.app.domain.model.RichDocument
import com.notebudget.app.domain.model.RichEdit
import com.notebudget.app.domain.model.RichList
import com.notebudget.app.domain.model.RichStyle
import com.notebudget.app.feature.notes.ViewEditTabs
import com.notebudget.app.feature.notes.markdown.MarkdownEditor

/**
 * A TEXT note is Markdown (D214). A text note made "formatted" in 0.9.32 - 0.9.33 (before the two kinds were split) is moved to
 * the WORD kind when the app starts; until then, or if it comes back in an old backup, it still opens with the formatted editor.
 */
@Composable
fun TextNoteEditor(content: String, extras: String?, onEdit: (String, String?) -> Unit, modifier: Modifier = Modifier) {
    val rich = remember(content, extras) { RichDocument.decode(extras, content) }
    if (rich == null) MarkdownEditor(content, { onEdit(it, null) }, modifier)
    else WordNoteEditor(content, extras, onEdit, modifier)
}

/** A WORD note (D214): formatted text; a new one (nothing in `extras` yet) starts as plain text. */
@Composable
fun WordNoteEditor(content: String, extras: String?, onEdit: (String, String?) -> Unit, modifier: Modifier = Modifier) {
    val doc = remember(content, extras) { RichDocument.decode(extras, content) ?: RichDocument(content).normalized() }
    var viewing by rememberSaveable { mutableStateOf(true) }
    RichTextNote(doc, { onEdit(it.text, it.encode()) }, modifier, viewing) { viewing = it }
}

@Composable
private fun RichTextNote(
    doc: RichDocument,
    onChange: (RichDocument) -> Unit,
    modifier: Modifier,
    viewing: Boolean,
    onViewingChange: (Boolean) -> Unit,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ViewEditTabs(
            editing = !viewing, onChange = { onViewingChange(!it) }, modifier = Modifier.padding(horizontal = 0.dp),
            // Copy of the whole text (D362), in the View.
            extra = if (viewing && doc.text.isNotBlank() && !(com.notebudget.app.core.ui.LocalNoteHide.current?.masked == true)) ({ com.notebudget.app.core.ui.CopyIcon { doc.text } }) else null,
        )
        // The note hides its content (D363): the cover, until the eye is pressed.
        if (viewing && com.notebudget.app.core.ui.LocalNoteHide.current?.masked == true) com.notebudget.app.core.ui.HiddenContent(Modifier.fillMaxWidth().weight(1f))
        else if (viewing) RichTextView(doc) { line -> onChange(RichEdit.toggleChecked(doc, line)) }
        else RichTextEditor(doc, onChange, Modifier.weight(1f))
    }
}

/** The View: every line with its marker, indent, alignment and styles; check boxes can be ticked here. */
@Composable
private fun RichTextView(doc: RichDocument, onToggle: (Int) -> Unit) {
    val starts = remember(doc) { doc.lineStarts() }
    val numbers = remember(doc) { doc.numbers() }
    val styles = remember(doc) { doc.styles() }
    val viewScroll = rememberScrollState()
    // The search of the note (D256): the words are found line by line in what the View shows, and it scrolls to the current one.
    val search = com.notebudget.app.core.ui.LocalNoteSearch.current
    val pattern = search?.activePattern
    val lineTexts = remember(doc, starts) { starts.indices.map { i -> doc.text.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] - 1 else doc.text.length) } }
    val hits = remember(lineTexts, pattern) {
        if (pattern == null) emptyList() else lineTexts.flatMapIndexed { i, t -> com.notebudget.app.core.ui.TextSearch.hits(RichHitKey(i), t, pattern) }
    }
    LaunchedEffect(hits) { search?.report(hits) }
    val tops = remember { HashMap<Int, Int>() }
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx().toInt() }
    LaunchedEffect(search?.jumps, hits) {
        val key = search?.currentHit?.key as? RichHitKey ?: return@LaunchedEffect
        val top = tops[key.line] ?: return@LaunchedEffect
        viewScroll.animateScrollTo((top - margin).coerceIn(0, viewScroll.maxValue))
    }
    val current = search?.currentHit
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(viewScroll), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (doc.text.isBlank()) Text(stringResource(R.string.md_preview_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    val line = if (pattern == null) plain else com.notebudget.app.core.ui.TextSearch.mark(plain, pattern, RichHitKey(i), current)
                    Text(
                        if (line.isEmpty()) androidx.compose.ui.text.AnnotatedString(" ") else line,
                        textAlign = RichRender.align(para.align),
                        textDecoration = if (para.list == RichList.CHECK && para.checked) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f).padding(top = if (para.list == RichList.CHECK) 4.dp else 0.dp),
                    )
                    // Copy of one line of a list (D362): its words, without the marker.
                    if (para.list != RichList.NONE && end > starts[i]) com.notebudget.app.core.ui.CopyIcon(small = true) { doc.text.substring(starts[i], end) }
                }
            }
            com.notebudget.app.core.ui.NoteFooter()
        }
        QuickScroll(viewScroll)
    }
}

private enum class Panel { NONE, SIZE, COLOR, BACKGROUND }

/**
 * The Edit tab: the field with the styles shown as they will look, and the toolbar under it, just above the keyboard (so the
 * phone's copy / paste menu over the selection never covers it). With nothing selected, a style button sets the style of what
 * is typed next, as in a word processor.
 */
@Composable
private fun RichTextEditor(doc: RichDocument, onChange: (RichDocument) -> Unit, modifier: Modifier) {
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
    val search = com.notebudget.app.core.ui.LocalNoteSearch.current
    val pattern = search?.activePattern
    val rawHits = remember(doc.text, pattern) { com.notebudget.app.core.ui.TextSearch.hits(RichRawKey, doc.text, pattern) }
    LaunchedEffect(rawHits) { search?.report(rawHits) }
    LaunchedEffect(search?.jumps, rawHits) {
        val hit = search?.currentHit?.takeIf { it.key == RichRawKey } ?: return@LaunchedEffect
        val r = com.notebudget.app.core.ui.TextSearch.ranges(doc.text, pattern).getOrNull(hit.occurrence) ?: return@LaunchedEffect
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

    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    Column(modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().weight(1f)) {
            // The page follows the cursor: its line is kept second from the top (D321).
            com.notebudget.app.core.ui.CursorField(
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
                placeholder = { Text(stringResource(R.string.editor_text_hint)) },
                visualTransformation = transformation,
                modifier = Modifier.fillMaxSize().focusRequester(fieldFocus),
            )
            // A long text (D250): to its first or last line; the cursor goes there and the field follows it.
            QuickScrollAlways(
                long = field.text.length > LONG_TEXT || field.text.count { it == '\n' } > LONG_LINES,
                onTop = { field = field.copy(selection = TextRange(0)); runCatching { fieldFocus.requestFocus() } },
                onBottom = { field = field.copy(selection = TextRange(field.text.length)); runCatching { fieldFocus.requestFocus() } },
            )
        }
        when (panel) {
            Panel.SIZE -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RichStyle.SIZES.forEach { size ->
                    val chosen = (styleNow().size ?: 1f) == size
                    FilterChip(chosen, { restyle { it.copy(size = size.takeIf { s -> s != 1f }) }; panel = Panel.NONE }, { Text("${(size * 100).toInt()}%") })
                }
            }
            Panel.COLOR -> com.notebudget.app.feature.colors.ColorRow(R.string.rich_text_color, styleNow().color, allowNone = true, noneLabel = R.string.rich_color_auto) {
                c -> restyle { s -> s.copy(color = c) }
            }
            Panel.BACKGROUND -> com.notebudget.app.feature.colors.ColorRow(R.string.rich_background, styleNow().background, allowNone = true, noneLabel = R.string.rich_color_auto) {
                c -> restyle { s -> s.copy(background = c) }
            }
            Panel.NONE -> Unit
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Tool(R.string.md_bold, "B", has { it.bold }, weight = FontWeight.Bold) { toggle({ it.bold }) { s, on -> s.copy(bold = on) } }
            Tool(R.string.md_italic, "I", has { it.italic }, italic = true) { toggle({ it.italic }) { s, on -> s.copy(italic = on) } }
            Tool(R.string.rich_underline, "U", has { it.underline }, decoration = TextDecoration.Underline) { toggle({ it.underline }) { s, on -> s.copy(underline = on) } }
            Tool(R.string.md_strike, "S", has { it.strike }, decoration = TextDecoration.LineThrough) { toggle({ it.strike }) { s, on -> s.copy(strike = on) } }
            Tool(R.string.rich_superscript, "x²", has { it.script == 1 }) { toggle({ it.script == 1 }) { s, on -> s.copy(script = if (on) 1 else 0) } }
            Tool(R.string.rich_subscript, "x₂", has { it.script == -1 }) { toggle({ it.script == -1 }) { s, on -> s.copy(script = if (on) -1 else 0) } }
            Tool(R.string.rich_size, "A↕", panel == Panel.SIZE) { panel = if (panel == Panel.SIZE) Panel.NONE else Panel.SIZE }
            Tool(R.string.rich_text_color, "A", panel == Panel.COLOR, color = styleNow().color?.let { Color(it) }) { panel = if (panel == Panel.COLOR) Panel.NONE else Panel.COLOR }
            Tool(R.string.rich_background, "▒", panel == Panel.BACKGROUND, color = styleNow().background?.let { Color(it) }) { panel = if (panel == Panel.BACKGROUND) Panel.NONE else Panel.BACKGROUND }
            val para = current.normalized().paras.getOrNull(current.lineOf(start))
            Tool(R.string.md_bullets, "•", para?.list == RichList.BULLET) { onChange(RichEdit.toggleList(current, start, end, RichList.BULLET)) }
            Tool(R.string.md_numbered, "1.", para?.list == RichList.NUMBER) { onChange(RichEdit.toggleList(current, start, end, RichList.NUMBER)) }
            Tool(R.string.md_checklist, "☑", para?.list == RichList.CHECK) { onChange(RichEdit.toggleList(current, start, end, RichList.CHECK)) }
            Tool(R.string.rich_indent_less, "⇤", false) { onChange(RichEdit.indent(current, start, end, -1)) }
            Tool(R.string.rich_indent_more, "⇥", false) { onChange(RichEdit.indent(current, start, end, 1)) }
            IconTool(R.string.rich_align_left, alignIcon(RichAlign.START), para?.align == RichAlign.START) { onChange(RichEdit.lines(current, start, end) { it.copy(align = RichAlign.START) }) }
            IconTool(R.string.rich_align_center, alignIcon(RichAlign.CENTER), para?.align == RichAlign.CENTER) { onChange(RichEdit.lines(current, start, end) { it.copy(align = RichAlign.CENTER) }) }
            IconTool(R.string.rich_align_right, alignIcon(RichAlign.END), para?.align == RichAlign.END) { onChange(RichEdit.lines(current, start, end) { it.copy(align = RichAlign.END) }) }
            Tool(R.string.rich_clear, "✘", false) { typing = null; if (start != end) onChange(RichEdit.clear(current, start, end)) }
        }
    }
}

/**
 * Enter on an empty list item ends the list instead of making another empty item (as in a word processor): returns the offset
 * of that item's line, or `null` when this change is something else.
 */
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

@Composable
private fun Tool(
    @StringRes label: Int,
    glyph: String,
    active: Boolean,
    weight: FontWeight = FontWeight.Bold,
    italic: Boolean = false,
    decoration: TextDecoration? = null,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val description = stringResource(label)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .semantics { contentDescription = description }
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp)),
    ) {
        Text(
            glyph,
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = decoration,
            color = color ?: androidx.compose.ui.graphics.Color.Unspecified,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun IconTool(@StringRes label: Int, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp)),
    ) { Icon(icon, contentDescription = stringResource(label)) }
}

/** Four lines, pushed to the left, the middle or the right: the usual picture of text alignment. */
private fun alignIcon(align: RichAlign): ImageVector = ImageVector.Builder("Align$align", 24.dp, 24.dp, 24f, 24f).apply {
    val lengths = listOf(18f, 12f, 18f, 10f)
    lengths.forEachIndexed { i, len ->
        val y = 5f + i * 4.5f
        val x0 = when (align) {
            RichAlign.START -> 3f
            RichAlign.CENTER -> 12f - len / 2
            RichAlign.END -> 21f - len
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) { moveTo(x0, y); lineTo(x0 + len, y) }
    }
}.build()

/** From this length (or this many lines) the Edit field shows the quick scroll buttons (D250). */
private const val LONG_TEXT = 3_000
private const val LONG_LINES = 40
