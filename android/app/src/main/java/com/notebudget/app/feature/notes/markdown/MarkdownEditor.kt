package com.notebudget.app.feature.notes.markdown

import com.notebudget.app.core.ui.QuickScroll
import com.notebudget.app.core.ui.QuickScrollAlways
import androidx.compose.ui.focus.focusRequester
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.notebudget.app.R

/**
 * TEXT note editor (§6.8, D38): a plain multiline field with a Markdown toolbar, Enter that continues
 * lists, and a preview with tappable checkboxes. The text stays plain Markdown in `contentText`, so
 * notes written before the toolbar existed open unchanged.
 */
@Composable
fun MarkdownEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var field by remember { mutableStateOf(TextFieldValue(content)) }
    // Always the rendered View first (D108); Edit is the second tab.
    var preview by rememberSaveable { mutableStateOf(true) }

    // The text can arrive after the editor is shown (it is loaded from the database).
    LaunchedEffect(content) {
        if (content != field.text) {
            field = TextFieldValue(content, TextRange(minOf(field.selection.start, content.length)))
        }
    }

    fun apply(next: EditState) {
        field = TextFieldValue(next.text, TextRange(next.start, next.end))
        if (next.text != content) onContentChange(next.text)
    }

    fun state(v: TextFieldValue) = EditState(v.text, v.selection.min, v.selection.max)

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The same tabs row as every other note: View first, then Edit, with the zoom at its end (D108, D142).
        com.notebudget.app.feature.notes.ViewEditTabs(
            editing = !preview, onChange = { preview = !it }, modifier = Modifier.padding(horizontal = 0.dp),
            // Copy of the whole text (D362), in the View.
            extra = if (preview && field.text.isNotBlank() && !(com.notebudget.app.core.ui.LocalNoteHide.current?.masked == true)) ({ com.notebudget.app.core.ui.CopyIcon { field.text } }) else null,
        )
        if (preview && com.notebudget.app.core.ui.LocalNoteHide.current?.masked == true) {
            // The note hides its content (D363): the cover, until the eye is pressed.
            com.notebudget.app.core.ui.HiddenContent(Modifier.fillMaxWidth().weight(1f))
        } else if (preview) {
            // Moving and deleting a checklist line are undo steps of their own (D235), never merged with typing.
            val undo = com.notebudget.app.feature.notes.LocalUndoSlot.current
            fun step(next: String) {
                if (next == field.text) return
                undo?.value?.endStep()
                val at = minOf(field.selection.start, next.length)
                apply(EditState(next, at, at))
                undo?.value?.endStep()
            }
            Preview(
                field.text,
                onToggle = { line ->
                    val toggled = MarkdownEdit.toggleChecklistLine(field.text, line)
                    val at = minOf(field.selection.start, toggled.length)
                    apply(EditState(toggled, at, at))
                },
                onReorder = { order -> step(MarkdownEdit.reorderChecklist(field.text, order)) },
                onDelete = { line -> step(MarkdownEdit.deleteChecklistItem(field.text, line)) },
            )
        } else {
            // The toolbar is UNDER the field, just above the keyboard (D210): the phone's own copy / paste menu appears over the
            // selected words and used to cover the toolbar when it was at the top.
            Column(Modifier.weight(1f).imePadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The search of the note (D238) in Edit: the words are marked in the text, the current match is selected.
            val search = com.notebudget.app.core.ui.LocalNoteSearch.current
            val query = search?.activePattern
            val rawHits = remember(field.text, query) { com.notebudget.app.core.ui.TextSearch.hits(MdRawKey, field.text, query) }
            LaunchedEffect(rawHits) { search?.report(rawHits) }
            LaunchedEffect(search?.jumps, rawHits) {
                val hit = search?.currentHit?.takeIf { it.key == MdRawKey } ?: return@LaunchedEffect
                val r = com.notebudget.app.core.ui.TextSearch.ranges(field.text, query).getOrNull(hit.occurrence) ?: return@LaunchedEffect
                field = field.copy(selection = TextRange(r.first, r.last + 1))
            }
            val currentHit = search?.currentHit
            val transformation = remember(query, currentHit) { if (query == null) MarkdownHighlight else SearchHighlight(query, currentHit) }
            val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().weight(1f)) {
                // The page follows the cursor: its line is kept second from the top (D321).
                com.notebudget.app.core.ui.CursorField(
                    value = field,
                    onValueChange = { new ->
                        val continued = MarkdownEdit.continueList(state(field), state(new))
                        if (continued != null) apply(continued) else {
                            field = new
                            if (new.text != content) onContentChange(new.text)
                        }
                    },
                    placeholder = { Text(stringResource(R.string.editor_text_hint)) },
                    // Bold, italic, strikethrough and code are shown while typing; the text itself is unchanged.
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
            Toolbar { action -> apply(action(state(field))) }
            }
        }
    }
}

/**
 * Styles the text of the editor field as Markdown without changing a single character. The field asks again on every
 * recomposition, a move of the cursor included; the last answer is kept, so a long text is styled once per change (D200).
 */
private object MarkdownHighlight : VisualTransformation {
    private var lastText: String? = null
    private var last: TransformedText? = null

    override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText {
        val cached = last
        if (cached != null && lastText == text.text) return cached
        return TransformedText(MarkdownRender.highlight(text.text), OffsetMapping.Identity).also {
            lastText = text.text
            last = it
        }
    }
}

/** The key of the matches in the text of the Edit tab (D238): the whole text is one piece. */
/** From this length (or this many lines) the Edit field shows the quick scroll buttons (D250). */
private const val LONG_TEXT = 3_000
private const val LONG_LINES = 40

private object MdRawKey

/** A match in the View (D238): the block that starts on [line]. */
private data class MdHitKey(val line: Int)

/** The Markdown styles of the field and, over them, the matches of the note's search; the last answer is kept, as in [MarkdownHighlight]. */
private class SearchHighlight(private val query: com.notebudget.app.domain.model.TextPattern, private val current: com.notebudget.app.core.ui.SearchHit?) : VisualTransformation {
    private var lastText: String? = null
    private var last: TransformedText? = null

    override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText {
        val cached = last
        if (cached != null && lastText == text.text) return cached
        val marked = com.notebudget.app.core.ui.TextSearch.mark(MarkdownRender.highlight(text.text), query, MdRawKey, current)
        return TransformedText(marked, OffsetMapping.Identity).also { lastText = text.text; last = it }
    }

    override fun equals(other: Any?) = other is SearchHighlight && other.query == query && other.current == current
    override fun hashCode() = query.hashCode() * 31 + current.hashCode()
}

/** What a block of the View shows as text, for the search; `null` for a line. */
private fun shownText(b: MdBlock): String? = when (b) {
    is MdBlock.Heading -> MarkdownRender.inline(b.text).text
    is MdBlock.Check -> MarkdownRender.inline(b.text).text
    is MdBlock.Bullet -> MarkdownRender.inline(b.text).text
    is MdBlock.Numbered -> MarkdownRender.inline(b.text).text
    is MdBlock.Quote -> MarkdownRender.inline(b.text).text
    is MdBlock.Paragraph -> MarkdownRender.inline(b.text).text
    is MdBlock.Code -> b.text
    is MdBlock.Rule -> null
}

private class ToolbarAction(@StringRes val label: Int, val glyph: String, val run: (EditState) -> EditState)

private val ACTIONS = listOf(
    ToolbarAction(R.string.md_bold, "B") { MarkdownEdit.toggleInline(it, "**") },
    ToolbarAction(R.string.md_italic, "I") { MarkdownEdit.toggleInline(it, "*") },
    ToolbarAction(R.string.md_strike, "S") { MarkdownEdit.toggleInline(it, "~~") },
    ToolbarAction(R.string.md_heading, "H") { MarkdownEdit.heading(it) },
    ToolbarAction(R.string.md_bullets, "•") { MarkdownEdit.bullets(it) },
    ToolbarAction(R.string.md_numbered, "1.") { MarkdownEdit.numbered(it) },
    ToolbarAction(R.string.md_checklist, "☑") { MarkdownEdit.checklist(it) },
    ToolbarAction(R.string.md_quote, "❝") { MarkdownEdit.quote(it) },
    ToolbarAction(R.string.md_code, "</>") { MarkdownEdit.toggleInline(it, "`") },
    ToolbarAction(R.string.md_code_block, "{ }") { MarkdownEdit.codeBlock(it) },
    ToolbarAction(R.string.md_link, "🔗") { MarkdownEdit.link(it) },
    ToolbarAction(R.string.md_rule, "―") { MarkdownEdit.rule(it) },
)

@Composable
private fun Toolbar(onAction: ((EditState) -> EditState) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        ACTIONS.forEach { a ->
            val description = stringResource(a.label)
            TextButton(
                onClick = { onAction(a.run) },
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Text(a.glyph, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Where a dragged checklist line of the View ends up (D235). Pure, so it is unit-tested. */
internal object CheckDrag {
    /**
     * Moves [dragged] past its neighbours in [order] while it has been pulled ([offset] pixels from its place) beyond the middle
     * of the neighbour's slot (its height plus the [gap]); returns the new order and the offset left from the new place.
     * The threshold is half of the whole slot, so a swap never makes the opposite swap due right away (that ping-pong froze
     * the app in 0.9.46: the line jumped back and forth forever).
     */
    /**
     * How far the View scrolls in one frame while a line is held at [center]: towards an edge only when the line is within [edge]
     * of it AND the finger has pulled that way ([pulled] since the line was taken). A line taken near the top and pulled down
     * made the text run up before (user, 2026-09-27).
     */
    fun edgeScroll(center: Float, top: Float, bottom: Float, edge: Float, pulled: Float, speed: Float): Float = when {
        pulled < 0f && center < top + edge -> -speed
        pulled > 0f && center > bottom - edge -> speed
        else -> 0f
    }

    fun settle(order: List<Int>, dragged: Int, offset: Float, heights: Map<Int, Int>, gap: Float): Pair<List<Int>, Float> {
        var o = order
        var off = offset
        // Every pass moves the line one place in the direction of the pull; it cannot pass more places than there are.
        repeat(order.size) {
            val idx = o.indexOf(dragged)
            if (idx < 0) return o to off
            val neighbour = when {
                off > 0 && idx < o.lastIndex -> idx + 1
                off < 0 && idx > 0 -> idx - 1
                else -> return o to off
            }
            val slot = (heights[o[neighbour]] ?: return o to off) + gap
            if (kotlin.math.abs(off) <= slot / 2f) return o to off
            o = o.toMutableList().also { it.add(neighbour, it.removeAt(idx)) }
            off -= if (neighbour > idx) slot else -slot
        }
        return o to off
    }
}

/** What the checklist lines of the View can do (D235), and where the View is on the screen (for scrolling while dragging). */
private class CheckActions(
    val onToggle: (line: Int) -> Unit,
    val onReorder: (order: List<Int>) -> Unit,
    val onDelete: (MdBlock.Check, withSubLines: Boolean) -> Unit,
    val scroll: ScrollState,
) {
    var viewportTop = 0f
    var viewportBottom = 0f

    /** Where each block starts on the screen, by its first line: the search scrolls to it (D238). */
    val tops = HashMap<Int, Float>()
}

@Composable
private fun Preview(text: String, onToggle: (line: Int) -> Unit, onReorder: (List<Int>) -> Unit, onDelete: (line: Int) -> Unit) {
    val blocks = remember(text) { MarkdownRender.blocks(text) }
    // The groups of sibling lines are found once per text, not per line and per frame.
    val groups = remember(text) { MarkdownEdit.checklistGroups(MarkdownEdit.checklistItems(text)) }
    val scroll = rememberScrollState()
    var deleting by remember { mutableStateOf<Pair<MdBlock.Check, Boolean>?>(null) }
    val toggle by androidx.compose.runtime.rememberUpdatedState(onToggle)
    val reorder by androidx.compose.runtime.rememberUpdatedState(onReorder)
    val actionsNow = remember(scroll) { CheckActions({ toggle(it) }, { reorder(it) }, { b, sub -> deleting = b to sub }, scroll) }
    // The search of the note (D238): the words are found in what the View shows, and the View scrolls to the current one.
    val search = com.notebudget.app.core.ui.LocalNoteSearch.current
    val query = search?.activePattern
    val hits = remember(blocks, query) {
        if (query == null) emptyList()
        else blocks.flatMap { b -> shownText(b)?.let { com.notebudget.app.core.ui.TextSearch.hits(MdHitKey(b.line), it, query) }.orEmpty() }
    }
    LaunchedEffect(hits) { search?.report(hits) }
    val margin = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(search?.jumps, hits) {
        val key = search?.currentHit?.key as? MdHitKey ?: return@LaunchedEffect
        val top = actionsNow.tops[key.line] ?: return@LaunchedEffect
        val target = scroll.value + (top - actionsNow.viewportTop - margin)
        scroll.animateScrollTo(target.toInt().coerceIn(0, scroll.maxValue))
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { c -> c.boundsInRoot().let { actionsNow.viewportTop = it.top; actionsNow.viewportBottom = it.bottom } }
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (blocks.isEmpty()) {
                Text(stringResource(R.string.md_preview_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PreviewBlocks(blocks, groups, actionsNow)
            // "Created" / "changed" after the text, when Settings shows them for text notes (D198).
            com.notebudget.app.core.ui.NoteFooter()
        }
        QuickScroll(scroll)
    }
    // Deleting a line asks first (rule 7; user, 2026-09-27: "delete with a confirmation"); Undo still brings it back.
    deleting?.let { (b, sub) ->
        com.notebudget.app.core.ui.AppAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.md_check_delete_title)) },
            text = {
                val name = b.text.ifBlank { "…" }
                Text(stringResource(if (sub) R.string.md_check_delete_text_sub else R.string.md_check_delete_text, name))
            },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(b.line) }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * The blocks in text order. A run of checklist items of one level is drawn as a group whose items can be dragged among
 * themselves; each item carries the blocks of its sub-lines with it (D235).
 */
@Composable
private fun PreviewBlocks(blocks: List<MdBlock>, groups: Map<Int, List<ChecklistItem>>, actions: CheckActions) {
    var i = 0
    while (i < blocks.size) {
        val b = blocks[i]
        val group = if (b is MdBlock.Check) groups[b.line] else null
        if (group != null) {
            val end = group.last().end
            var j = i
            while (j < blocks.size && blocks[j].line < end) j++
            key("group", b.line) { CheckGroup(group, blocks.subList(i, j), groups, actions) }
            i = j
        } else {
            key("block", b.line) {
                androidx.compose.foundation.layout.Box(Modifier.onGloballyPositioned { actions.tops[b.line] = it.positionInRoot().y }) {
                    PreviewBlock(b, actions)
                }
            }
            i++
        }
    }
}

@Composable
private fun CheckGroup(group: List<ChecklistItem>, blocks: List<MdBlock>, groups: Map<Int, List<ChecklistItem>>, actions: CheckActions) {
    val lines = group.map { it.line }
    // Each item's own blocks (its line and its sub-lines), split once; a drag only changes the order they are shown in.
    val ownBlocks = remember(blocks, group) {
        group.associate { item -> item.line to blocks.filter { it.line >= item.line && it.line < item.end } }
    }
    // The drag state belongs to this text of the group, not to its line numbers: after a move the lines are numbered as
    // before (0, 1, 2 …) but hold other items, and an order kept from the last drag was put on the new text again — the
    // line went back or to a wrong place (user, 2026-09-27).
    val stateKey = blocks
    var order by remember(stateKey) { mutableStateOf(lines) }
    var dragged by remember(stateKey) { mutableStateOf<Int?>(null) }
    var offset by remember(stateKey) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val heights = remember(stateKey) { HashMap<Int, Int>() }
    val tops = remember(stateKey) { HashMap<Int, Float>() }
    val density = LocalDensity.current
    val gap = with(density) { 6.dp.toPx() }
    val edge = with(density) { 48.dp.toPx() }
    val speedPx = with(density) { 8.dp.toPx() }
    // How far the finger has gone since it took the line: the text scrolls only towards where the line is pulled.
    val pulled = remember(stateKey) { floatArrayOf(0f) }
    // The finger is followed in screen coordinates: the grip itself moves with the line (its offset, and a jump of a whole
    // line at every change of places), so the distances a gesture reports inside the grip were wrong, even backwards, and the
    // line ran off the other way (user, 2026-09-27: "I drag it before 255 and it lands before 253").
    val grips = remember(stateKey) { HashMap<Int, androidx.compose.ui.layout.LayoutCoordinates>() }
    val fingerY = remember(stateKey) { floatArrayOf(0f) }
    // Where the finger touched the grip: the drag starts only after a few pixels (the touch slop), and those count too.
    val downY = remember(stateKey) { floatArrayOf(0f) }

    // The dragged item changes places with a neighbour once it has passed the middle of that neighbour.
    fun swapIfNeeded() {
        val d = dragged ?: return
        val (nextOrder, nextOffset) = CheckDrag.settle(order, d, offset, heights, gap)
        if (nextOrder != order) order = nextOrder
        offset = nextOffset
    }

    fun finish() {
        if (dragged == null) return
        dragged = null
        offset = 0f
        val dropped = order
        // The text is the truth: the order shown goes back to it at once (the new text brings the moved line).
        order = lines
        if (dropped != lines) actions.onReorder(dropped)
    }

    // Near the top or the bottom of the View the text scrolls by itself, so an item can go further than the screen.
    androidx.compose.runtime.LaunchedEffect(dragged) {
        val d = dragged ?: return@LaunchedEffect
        while (true) {
            // Once per frame, and only while a line is held.
            androidx.compose.runtime.withFrameNanos { }
            val top = tops[d] ?: continue
            val center = top + offset + (heights[d] ?: 0) / 2f
            val speed = CheckDrag.edgeScroll(center, actions.viewportTop, actions.viewportBottom, edge, pulled[0], speedPx)
            if (speed != 0f) {
                offset += actions.scroll.scrollBy(speed)
                swapIfNeeded()
            }
        }
    }

    val handleLabel = stringResource(R.string.cd_drag_handle)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        order.forEach { line ->
            key(line) {
                val item = group.first { it.line == line }
                val own = ownBlocks.getValue(line)
                val isDragged = dragged == line
                Column(
                    Modifier
                        .onGloballyPositioned { tops[line] = it.boundsInRoot().top }
                        .onSizeChanged { heights[line] = it.height }
                        .then(
                            if (isDragged) {
                                Modifier.zIndex(1f).graphicsLayer { translationY = offset; shadowElevation = 12f }
                                    .background(MaterialTheme.colorScheme.surface)
                            } else Modifier,
                        ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val check = own.first() as MdBlock.Check
                    val handle = if (group.size < 2) null else Modifier
                        .onGloballyPositioned { grips[line] = it }
                        .pointerInput(stateKey, line) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                downY[0] = grips[line]?.takeIf { it.isAttached }?.localToRoot(down.position)?.y ?: 0f
                            }
                        }
                        .semantics { contentDescription = handleLabel }
                        .pointerInput(stateKey, line) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    dragged = line; offset = 0f; pulled[0] = 0f
                                    fingerY[0] = downY[0].takeIf { it != 0f } ?: grips[line]?.takeIf { it.isAttached }?.localToRoot(start)?.y ?: 0f
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val y = grips[line]?.takeIf { it.isAttached }?.localToRoot(change.position)?.y
                                    if (y != null) {
                                        val dy = y - fingerY[0]
                                        fingerY[0] = y
                                        offset += dy
                                        pulled[0] += dy
                                        swapIfNeeded()
                                    }
                                },
                                onDragEnd = { finish() },
                                onDragCancel = { finish() },
                            )
                        }
                    CheckRow(check, handle, withSubLines = item.end > item.line + 1, actions)
                    PreviewBlocks(own.drop(1), groups, actions)
                }
            }
        }
    }
}

/** A checklist line of the View: the grip (when it has neighbours to change places with), the box, the text, Delete. */
@Composable
private fun CheckRow(b: MdBlock.Check, handle: Modifier?, withSubLines: Boolean, actions: CheckActions) {
    androidx.compose.foundation.layout.Column {
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { actions.tops[b.line] = it.positionInRoot().y }
            .padding(start = (b.indent * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (handle != null) {
            androidx.compose.foundation.layout.Box(Modifier.size(36.dp).then(handle), contentAlignment = Alignment.Center) {
                com.notebudget.app.core.ui.MoveHandleIcon()
            }
        }
        Checkbox(checked = b.checked, onCheckedChange = { actions.onToggle(b.line) })
        Text(com.notebudget.app.core.ui.highlighted(MarkdownRender.inline(b.text), MdHitKey(b.line)), Modifier.weight(1f))
        // Copy of this line alone (D362): its words, without the marker.
        com.notebudget.app.core.ui.CopyIcon(small = true) { MarkdownRender.inline(b.text).text }
        IconButton(onClick = { actions.onDelete(b, withSubLines) }) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.md_check_delete_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    ListItemLine(b.indent)
    }
}

/**
 * A thin line under an item of a list in the View (D305): where one item ends and the next begins is seen at once, also when an
 * item runs over several lines.
 */
@Composable
private fun ListItemLine(indent: Int) {
    HorizontalDivider(Modifier.padding(start = (indent * 16).dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PreviewBlock(b: MdBlock, actions: CheckActions) {
    when (b) {
        is MdBlock.Heading -> Text(
            com.notebudget.app.core.ui.highlighted(MarkdownRender.inline(b.text), MdHitKey(b.line)),
            style = when (b.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                else -> MaterialTheme.typography.titleLarge
            },
            fontWeight = FontWeight.Bold,
        )
        // Every checklist line is drawn by CheckRow inside its group; this is only a safety net.
        is MdBlock.Check -> androidx.compose.foundation.layout.Column {
            Row(Modifier.padding(start = (b.indent * 16).dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = b.checked, onCheckedChange = { actions.onToggle(b.line) })
                Text(com.notebudget.app.core.ui.highlighted(MarkdownRender.inline(b.text), MdHitKey(b.line)))
            }
            ListItemLine(b.indent)
        }
        is MdBlock.Bullet -> androidx.compose.foundation.layout.Column {
            Row(Modifier.padding(start = (b.indent * 16).dp, top = 4.dp, bottom = 4.dp)) {
                Text("•", Modifier.width(20.dp))
                Text(com.notebudget.app.core.ui.highlighted(MarkdownRender.inline(b.text), MdHitKey(b.line)), Modifier.weight(1f))
                com.notebudget.app.core.ui.CopyIcon(small = true) { MarkdownRender.inline(b.text).text }
            }
            ListItemLine(b.indent)
        }
        is MdBlock.Numbered -> androidx.compose.foundation.layout.Column {
            Row(Modifier.padding(start = (b.indent * 16).dp, top = 4.dp, bottom = 4.dp)) {
                Text("${b.number}.", Modifier.width(28.dp))
                Text(com.notebudget.app.core.ui.highlighted(MarkdownRender.inline(b.text), MdHitKey(b.line)), Modifier.weight(1f))
                com.notebudget.app.core.ui.CopyIcon(small = true) { MarkdownRender.inline(b.text).text }
            }
            ListItemLine(b.indent)
        }
        // A quote was only a little greyer than the text around it (D209): a bar on its left and italics now.
        is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            androidx.compose.foundation.layout.Box(
                Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary),
            )
            Text(
                com.notebudget.app.core.ui.highlighted(MarkdownRender.inline(b.text), MdHitKey(b.line)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        is MdBlock.Code -> Text(
            com.notebudget.app.core.ui.highlighted(b.text, MdHitKey(b.line)),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        )
        is MdBlock.Rule -> HorizontalDivider()
        is MdBlock.Paragraph -> Text(com.notebudget.app.core.ui.highlighted(MarkdownRender.inline(b.text), MdHitKey(b.line)))
    }
}
