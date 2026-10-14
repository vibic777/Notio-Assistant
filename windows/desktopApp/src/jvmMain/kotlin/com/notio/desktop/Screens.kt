@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.notio.desktop

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.foundation.Image
import kotlinx.coroutines.delay
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.notio.desktop.theme.themeSwatch
import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.ColorTheme
import com.notio.shared.domain.FontSize
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.ThemeMode
import java.awt.Desktop
import java.io.File
import kotlin.math.roundToInt

/** A top bar with the back arrow, standing in the window's title bar; [actions] at its end (a ⋮ menu). */
@Composable
fun BackTopBar(s: Strings, title: String, chrome: WindowChrome, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TitleBarRow(chrome) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
        }
        // A heading for a screen reader (Android D274 step 3): the reader can jump from heading to heading.
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f).semantics { heading() })
        actions()
    }
}

/** A page of readable width in the middle of a wide window. */
@Composable
internal fun Page(content: @Composable (PaddingValues) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = ((maxWidth - 1000.dp) / 2).coerceAtLeast(0.dp)
        content(PaddingValues(start = 16.dp + side, end = 16.dp + side, top = 8.dp, bottom = 24.dp))
    }
}

// ---- a note ----


/**
 * The note screen of the phone: stamps, the View / Edit chips with the zoom at their end (D108, D142), then the View or the
 * editor. The editor saves by itself a moment after typing stops, on the switch to View and when the screen is left, like the
 * phone's `saveNow`. The text field has the desktop's own Ctrl+Z undo.
 */
@Composable
fun NoteScreen(s: Strings, detail: NoteDetail?, startEditing: Boolean, controller: NotioController, onComingSoon: () -> Unit) {
    if (detail == null) return
    // The background of this kind of note (D110), behind the whole note screen.
    val slot = com.notio.shared.domain.BackgroundSlot.forNote(detail.note.type)
    // The content hidden until the eye is pressed (D144, Android D363): the option is the note's, the eye works while the note is open.
    var contentShown by remember(detail.note.id) { mutableStateOf(false) }
    val hide = detail.note.takeIf { it.type in HIDEABLE_KINDS }?.let { n ->
        NoteHide(n.hidden, contentShown, { on -> contentShown = false; if (n.deletedAt == null) controller.setNoteHidden(n.id, on) }, { contentShown = it })
    }
    CompositionLocalProvider(LocalNoteHide provides hide) {
        Box(Modifier.fillMaxSize()) {
            if (slot != null) BackgroundLayer(slot, Modifier.matchParentSize())
            NoteScreenContent(s, detail, startEditing, controller, onComingSoon)
        }
    }
}

@Composable
private fun NoteScreenContent(s: Strings, detail: NoteDetail?, startEditing: Boolean, controller: NotioController, onComingSoon: () -> Unit) {
    val budget = detail?.note?.type == NoteType.BUDGET
    // Android D272: hidden sums / goals show as stars until the eye is pressed; opened again, the note hides them again.
    var revealed by remember(detail?.note?.id) { mutableStateOf(false) }
    val budgetHides = budget && remember(detail?.note?.extras) { com.notio.shared.domain.BudgetPrivacy.of(com.notio.shared.domain.BudgetLayout.parse(detail?.note?.extras)).hidesAnything }
    if (detail == null) return
    val note = detail.note
    // A photo, camera-video or screen-recording note (D147, D36).
    com.notio.shared.domain.MediaKind.of(note.type)?.let { kind ->
        Page { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                StampLines(note.createdAt, note.updatedAt)
                MediaNoteScreen(note.id, kind, controller, Modifier.weight(1f))
            }
        }
        return
    }
    // An audio note: the phone's list of recordings with the microphone and the player of this computer (D112, D35).
    if (note.type == NoteType.AUDIO) {
        Page { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                StampLines(note.createdAt, note.updatedAt)
                AudioScreen(note.id, controller, Modifier.weight(1f))
            }
        }
        return
    }
    // A drawing and a blueprint have their own View / Edit tabs and the zoom of the sheet (the phone's DrawingScreen).
    if (note.type == NoteType.DRAWING || note.type == NoteType.BLUEPRINT) {
        Page { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                StampLines(note.createdAt, note.updatedAt)
                DrawingScreen(note.id, if (note.type == NoteType.BLUEPRINT) DrawingKind.BLUEPRINT else DrawingKind.DRAWING, controller, Modifier.weight(1f))
            }
        }
        return
    }
    val canEdit = note.type in EDITABLE_KINDS && note.deletedAt == null
    var editing by remember(note.id) { mutableStateOf(startEditing && canEdit) }
    var zoom by remember(note.id) { mutableFloatStateOf(1f) }
    var title by remember(note.id) { mutableStateOf(note.title) }
    var text by remember(note.id) { mutableStateOf(TextFieldValue(note.contentText.orEmpty())) }
    val word = note.type == NoteType.WORD
    var wordDoc by remember(note.id) { mutableStateOf(wordDocument(note.contentText, note.extras)) }
    val latest by rememberUpdatedState(Triple(note.id, title, if (word) wordDoc else text.text))
    fun saveNow() = latest.let { (id, t, c) ->
        when {
            c is com.notio.shared.domain.RichDocument -> controller.saveNoteRich(id, t, c.text, c.encode())
            // A list and a budget save their content themselves: only the title here, never an old copy of the text.
            note.type == NoteType.LIST || note.type == NoteType.BUDGET || note.type == NoteType.SCHEDULE -> controller.saveTitle(id, t)
            else -> controller.saveNoteText(id, t, c as String)
        }
    }
    // Autosave a moment after the last key, and whatever is left when the screen goes away.
    LaunchedEffect(note.id, title, text.text, wordDoc) {
        if (!editing) return@LaunchedEffect
        delay(800)
        saveNow()
    }
    // Exit asks the open note to save first (Android D294).
    DisposableEffect(note.id) {
        val save: () -> Unit = { saveNow() }
        controller.addUnsaved(save)
        onDispose { controller.removeUnsaved(save); saveNow() }
    }

    // Undo / Redo of the text and the structure (D197): the arrows of the top bar, Ctrl+Z / Ctrl+Y in the text.
    val undoKind = canEdit && (note.type == NoteType.TEXT || word || note.type == NoteType.LIST || note.type == NoteType.SCHEDULE || note.type == NoteType.TASK)
    val undo = remember(note.id) { NoteUndo(controller.appSettings.undoSteps.value) }
    val undoSteps by controller.appSettings.undoSteps.collectAsState()
    LaunchedEffect(undo, undoSteps) { undo.steps = undoSteps }
    val stored = NoteUndo.Snapshot(note.contentText.orEmpty(), note.extras)
    // What the editor shows now; a list and a schedule take theirs from the saved note.
    undo.current = when {
        word -> NoteUndo.Snapshot(wordDoc.text, wordDoc.encode())
        note.type == NoteType.TEXT -> NoteUndo.Snapshot(text.text, null)
        else -> stored
    }
    undo.restore = { snap ->
        when {
            word -> wordDocument(snap.content, snap.extras).let { wordDoc = it; controller.saveNoteRich(note.id, title, it.text, it.encode()) }
            note.type == NoteType.TEXT -> {
                text = TextFieldValue(snap.content, TextRange(minOf(text.selection.start, snap.content.length)))
                controller.saveNoteText(note.id, title, snap.content)
            }
            else -> controller.saveNoteContent(note.id, snap.content, snap.extras)
        }
    }
    if (undoKind) ProvideUndoTarget(undo)
    /** A change of a Markdown text: one undo step for quick typing, then shown (saved by the autosave, or at once from the View). */
    fun editText(next: TextFieldValue, saveAtOnce: Boolean = false) {
        undo.change(NoteUndo.Snapshot(next.text, null))
        text = next
        if (saveAtOnce) controller.saveNoteText(note.id, title, next.text)
    }
    /** A list's or a schedule's own save, remembered for undo first. */
    val saveBody: (String, String?) -> Unit = { t, e ->
        if (note.deletedAt == null) {
            undo.change(NoteUndo.Snapshot(t, e))
            controller.saveNoteContent(note.id, t, e)
        }
    }

    Page { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad)
                // Ctrl + wheel zooms the note like the + / − buttons (the phone's pinch, D118; AGENT.md D34).
                .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll, androidx.compose.ui.input.pointer.PointerEventPass.Initial) { event ->
                    if (!event.keyboardModifiers.isCtrlPressed) return@onPointerEvent
                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (dy != 0f) zoom = if (dy < 0) Zoom.plus(zoom) else Zoom.minus(zoom)
                    event.changes.forEach { it.consume() }
                },
        ) {
            StampLines(note.createdAt, note.updatedAt)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The tabs give way in a narrow window, never the buttons after them (Android D270: the + of the zoom was cut off).
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Icons since Android D276: a book for View, a pencil for Edit; their names on hover and for a screen reader.
                    IconTab(ViewIcon, s.view, selected = !editing) { if (editing) { saveNow(); editing = false } }
                    IconTab(EditIcon, s.edit, selected = editing) { if (canEdit) editing = true else onComingSoon() }
                }
                // The eye of a note that can hide its content (D144, Android D363).
                val masked = LocalNoteHide.current?.masked == true
                HideEye(editing)
                // Copy of the whole text (D143, Android D362), in the View of a note, an MD note and a list.
                val whole = when {
                    editing || masked -> null
                    note.type == NoteType.TEXT -> if (canEdit) text.text else note.contentText.orEmpty()
                    word -> wordDoc.text
                    note.type == NoteType.LIST -> note.contentText.orEmpty()
                    else -> null
                }
                if (!whole.isNullOrBlank()) CopyIcon { whole }
                // A budget with hidden sums / goals (Android D272): the eye shows them until the note is closed.
                if (budgetHides) {
                    val label = stringResource(if (revealed) "budget_hide_amounts" else "budget_show_amounts")
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(if (revealed) EyeOffIcon else EyeIcon, contentDescription = label)
                    }
                }
                // Words for a screen reader (Android D270, D274 step 1): "−", "%" and "+" alone say nothing.
                val zoomOut = stringResource("zoom_out_cd")
                val zoomLevel = stringResource("zoom_level_cd", (zoom * 100).roundToInt())
                val zoomIn = stringResource("zoom_in_cd")
                HoverTip(zoomOut, topic = "zoom") { OutlinedButton(onClick = { zoom = Zoom.minus(zoom) }, enabled = zoom > Zoom.MIN, modifier = Modifier.semantics { contentDescription = zoomOut }) { Text("−", maxLines = 1) } }
                OutlinedButton(onClick = { zoom = 1f }, modifier = Modifier.semantics { contentDescription = zoomLevel }) { Text(s.zoomNormal + " " + (zoom * 100).roundToInt() + "%", maxLines = 1, softWrap = false) }
                HoverTip(zoomIn, topic = "zoom") { OutlinedButton(onClick = { zoom = Zoom.plus(zoom) }, enabled = zoom < Zoom.MAX, modifier = Modifier.semantics { contentDescription = zoomIn }) { Text("+", maxLines = 1) } }
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * zoom)) {
                if (!editing && LocalNoteHide.current?.masked == true) {
                    // The note hides its content: the cover, until the eye is pressed.
                    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                        NoteMeta(s, detail)
                        HiddenContent(Modifier.fillMaxWidth().weight(1f))
                    }
                } else if (budget) {
                    // A budget saves itself row by row; its title is edited here in Edit (the phone has it in the top bar).
                    // The budget is its own scrolling list (so its rows can be dragged, D176); the title / meta and the stamps are its first and last rows.
                    BudgetScreen(
                        s, controller, note.id, note.title, editing, Modifier.fillMaxSize().padding(top = 8.dp), revealed = revealed,
                        header = {
                            if (editing) OutlinedTextField(
                                value = title, onValueChange = { title = it }, label = { Text(s.titleHint) }, singleLine = true,
                                isError = title.isBlank(), modifier = Modifier.fillMaxWidth(),
                            ) else NoteMeta(s, detail)
                        },
                        footer = { if (!editing) NoteFooter() },
                    )
                } else if (note.type == NoteType.LIST && canEdit || note.type == NoteType.LIST && note.deletedAt != null) {
                    // The list of the phone (D92, D101, D165, D178): items with quantities, units, prices, totals by unit.
                    Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) OutlinedTextField(
                            value = title, onValueChange = { title = it }, label = { Text(s.titleHint) }, singleLine = true,
                            isError = title.isBlank(), modifier = Modifier.fillMaxWidth(),
                        ) else NoteMeta(s, detail)
                        ListNote(
                            note.contentText.orEmpty(), note.extras,
                            saveBody,
                            editing, Modifier.fillMaxWidth().weight(1f),
                        )
                        if (!editing) NoteFooter()
                    }
                } else if (note.type == NoteType.TASK) {
                    // A task (Android D239): its state, start and end, comment, reminders and description; the name is the title.
                    Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) OutlinedTextField(
                            value = title, onValueChange = { title = it }, label = { Text(s.titleHint) }, singleLine = true,
                            isError = title.isBlank(), modifier = Modifier.fillMaxWidth(),
                        ) else NoteMeta(s, detail)
                        val task = remember(note.extras) { com.notio.shared.domain.TaskDocument.decode(note.extras) }
                        CompositionLocalProvider(
                            LocalNoteReminders provides detail.reminders,
                            LocalOpenReminders provides { id: String -> controller.openReminders(id) },
                        ) {
                            // TaskEdit scrolls by itself: a scroll around it gave it no height to fill, and the window crashed (D136).
                            if (editing) Box(Modifier.fillMaxWidth().weight(1f)) {
                                TaskEdit(task, note.contentText.orEmpty(), onChange = { t, text -> saveBody(text, t.encode()) }, noteId = note.id)
                            } else Box(Modifier.fillMaxWidth().weight(1f)) {
                                TaskView(task, note.contentText.orEmpty(), note.id, onTask = if (note.deletedAt == null) ({ t -> saveBody(note.contentText.orEmpty(), t.encode()) }) else null, title = note.title)
                            }
                        }
                    }
                } else if (note.type == NoteType.SCHEDULE) {
                    // The schedule of the phone (D155, D182): times of day, calculators of the time between, sorting.
                    Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) OutlinedTextField(
                            value = title, onValueChange = { title = it }, label = { Text(s.titleHint) }, singleLine = true,
                            isError = title.isBlank(), modifier = Modifier.fillMaxWidth(),
                        ) else NoteMeta(s, detail)
                        ScheduleNote(
                            note.contentText.orEmpty(), note.extras,
                            saveBody,
                            editing, Modifier.fillMaxWidth().weight(1f),
                        )
                        if (!editing) NoteFooter()
                    }
                } else if (word) {
                    Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (editing) {
                            OutlinedTextField(
                                value = title, onValueChange = { title = it }, label = { Text(s.titleHint) }, singleLine = true,
                                isError = title.isBlank(), modifier = Modifier.fillMaxWidth(),
                            )
                            RichTextEditor(s, wordDoc, { next -> wordDoc = next.normalized(); undo.change(NoteUndo.Snapshot(wordDoc.text, wordDoc.encode())) }, Modifier.fillMaxWidth().weight(1f))
                        } else {
                            val viewScroll = rememberScrollState()
                            Box(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxSize().verticalScroll(viewScroll)) {
                                    NoteMeta(s, detail)
                                    // Ticking a check box in the View is an edit too (Android: RichEdit.toggleChecked).
                                    RichTextView(s, wordDoc, viewScroll = viewScroll, onToggle = { line ->
                                        val next = com.notio.shared.domain.RichEdit.toggleChecked(wordDoc, line).normalized()
                                        undo.change(NoteUndo.Snapshot(next.text, next.encode()))
                                        wordDoc = next
                                        controller.saveNoteRich(note.id, title, next.text, next.encode())
                                    })
                                    NoteFooter()
                                }
                                // To the beginning / the end of a long text (D250).
                                QuickScroll(viewScroll)
                            }
                        }
                    }
                } else if (editing) {
                    Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = title, onValueChange = { title = it }, label = { Text(s.titleHint) }, singleLine = true,
                            isError = title.isBlank(), modifier = Modifier.fillMaxWidth(),
                        )
                        MarkdownEditField(text, { editText(it) }, undo.takeIf { undoKind }, Modifier.fillMaxWidth().weight(1f))
                    }
                } else if (note.type == NoteType.TEXT) {
                    // The phone's View of a Markdown note (D235): ticks, and checklist lines moved and deleted, are edits saved at once.
                    // While editing is open the View shows the draft, so nothing jumps back after a save.
                    MarkdownPreview(
                        if (canEdit) text.text else note.contentText.orEmpty(),
                        onChange = { next -> if (canEdit) editText(TextFieldValue(next, TextRange(minOf(text.selection.start, next.length))), saveAtOnce = true) },
                        undo = undo.takeIf { undoKind },
                        header = { Column(Modifier.padding(top = 8.dp)) { NoteMeta(s, detail) } },
                    )
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        NoteMeta(s, detail)
                        when (note.type) {
                            NoteType.DRAWING, NoteType.BLUEPRINT, NoteType("PHOTO") -> {
                                val pictures = detail.files.filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
                                if (pictures.isEmpty()) Muted(s.noFiles)
                                pictures.forEach { Picture(it) }
                                if (!note.contentText.isNullOrBlank()) PlainText(note.contentText!!)
                            }
                            NoteType.AUDIO, NoteType("VIDEO"), NoteType("SCREEN") -> {
                                if (!note.contentText.isNullOrBlank()) PlainText(note.contentText!!)
                                FileList(s, detail.files)
                            }
                            // While editing is open the View shows the draft, so nothing jumps back after a save.
                            NoteType.LIST -> MarkdownView(note.contentText.orEmpty())
                            else -> PlainText(note.contentText.orEmpty())
                        }
                        NoteFooter()
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteMeta(s: Strings, detail: NoteDetail) {
    val note = detail.note
    val lines = listOfNotNull(
        note.deletedAt?.let { "${Glyphs.trash} ${s.inTrashSince} ${Format.dateTime(it)}" },
        detail.tags.takeIf { it.isNotEmpty() }?.joinToString("  ") { "#" + it.name },
    ) + detail.reminders.map { "${Glyphs.bell} ${s.reminder}: ${Format.dateTime(it.triggerAt)}" } + listOfNotNull(note.comment?.let { "${Glyphs.comment} $it" })
    lines.forEach { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
    if (lines.isNotEmpty()) Spacer(Modifier.height(12.dp))
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PlainText(text: String) {
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

/** The View of a Markdown or list note: styled lines, check boxes, bullets and numbers (D206, D208). */
@Composable
private fun MarkdownView(text: String) {
    val link = MaterialTheme.colorScheme.primary
    val code = MaterialTheme.colorScheme.surfaceVariant
    // Parsed once per text and theme, not at every recomposition (the whole-code pass).
    val lines = remember(text, link, code) { Markdown.lines(text, link, code) }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (line in lines) {
                val start = (line.level * 20).dp
                when (line.kind) {
                    MdLine.Kind.HEADING -> Text(
                        line.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp),
                        style = when (line.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                    )
                    MdLine.Kind.CHECK -> Row(Modifier.padding(start = start), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = line.checked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
                        Text(
                            line.text, style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (line.checked) TextDecoration.LineThrough else null,
                            color = if (line.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    MdLine.Kind.BULLET -> Row(Modifier.padding(start = start)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(20.dp))
                        Text(line.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    MdLine.Kind.NUMBERED -> Row(Modifier.padding(start = start)) {
                        Text(line.number, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(28.dp))
                        Text(line.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    MdLine.Kind.QUOTE -> Row(Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.width(4.dp).height(24.dp).background(MaterialTheme.colorScheme.primary))
                        Text(line.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                    MdLine.Kind.RULE -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    MdLine.Kind.BLANK -> Spacer(Modifier.height(12.dp))
                    MdLine.Kind.PLAIN -> Text(line.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun Picture(file: File) {
    val bitmap = rememberPicture(file, NOTE_PICTURE_PX) ?: return
    Card(Modifier.padding(bottom = 12.dp)) {
        Image(bitmap, contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp))
    }
}

@Composable
private fun FileList(s: Strings, files: List<File>) {
    Text(s.files, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (files.isEmpty()) Muted(s.noFiles)
    files.forEach { f ->
        Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleSmall)
                    Muted("${f.length() / 1024} KB")
                }
                TextButton(onClick = { runCatching { Desktop.getDesktop().open(f) } }) { Text(s.open) }
            }
        }
    }
}

// ---- the trash ----

@Composable
fun TrashScreen(s: Strings, state: UiState, controller: NotioController, open: (EditDialog) -> Unit) {
    Page { pad ->
        if (state.trash.isEmpty() && state.trashFolders.isEmpty() && state.mediaTrash.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad).padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.emptyTrash, style = MaterialTheme.typography.titleMedium)
                Text(s.trashHint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            return@Page
        }
        val colors = remember(state.folders, state.trashFolders) { (state.folders + state.trashFolders).associate { it.folder.id to it.folder.colorHex } }
        // The search of the trash (D253): names of folders, notes and items, the text of notes, the notes gone with a folder, comments —
        // in the order of the list (folders, notes, items).
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val searchable = remember(state.trashFolders, state.trash, state.mediaTrash) {
            state.trashFolders.map { SearchableItem("f-" + it.folder.id, it.folder.name, it.noteTitles) } +
                state.trash.map { SearchableItem("n-" + it.id, it.title, it.contentText.orEmpty()) } +
                state.mediaTrash.map { SearchableItem("m-" + it.media.id, it.media.title, mediaCommentOf(it.media.itemJson)) }
        }
        ItemSearchEffects(searchable, listState, before = 1)
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "top") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Muted(s.trashHint)
                    Spacer(Modifier.weight(1f))
                    val count = state.trash.size + state.trashFolders.sumOf { 1 + it.noteCount } + state.mediaTrash.size
                    TextButton(onClick = { open(EditDialog.EmptyTrash(count)) }) { Text(s.emptyTrashAction, color = MaterialTheme.colorScheme.error) }
                }
            }
            items(state.trashFolders, key = { "f-" + it.folder.id }) { item ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = parseColorOrDefault(item.folder.colorHex).copy(alpha = 0.18f).compositeOver(MaterialTheme.colorScheme.surface))) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.trashFolderLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                item.folder.icon?.let { Text("$it ", style = MaterialTheme.typography.titleMedium) }
                                Text(highlighted(item.folder.name, ItemHitKey("f-" + item.folder.id, 0)), style = MaterialTheme.typography.titleMedium)
                            }
                            FoundDetail("f-" + item.folder.id, item.noteTitles)
                            Muted(s.notesCount(item.noteCount) + "  ·  " + s.inTrashSince + " " + Format.dateTime(item.folder.deletedAt ?: 0))
                        }
                        // Android D278: restore and delete for good as icons (their names on hover); deleting still asks first.
                        IconAction(AppIcons.RestoreFromTrash, s.restore, { controller.restoreFolder(item.folder.id) })
                        IconAction(AppIcons.DeleteForever, s.deleteForever, { open(EditDialog.PurgeFolder(item.folder, item.noteCount)) }, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            items(state.trash, key = { it.id }) { note ->
                Column {
                    NoteCard(
                        s, note, colors[note.folderId], state.tags[note.id].orEmpty(), false, null, onClick = { controller.openNote(note.id) },
                        menu = { TrashNoteMenu(s, note, controller, open) },
                        titleSearchKey = ItemHitKey("n-" + note.id, 0),
                    )
                    FoundDetail("n-" + note.id, note.contentText.orEmpty())
                }
            }
            items(state.mediaTrash, key = { "m-" + it.media.id }) { row -> MediaTrashCard(row, controller) }
        }
    }
}

/** While the search of the trash finds something in the text of a row: that piece, the words marked (D253). */
@Composable
private fun FoundDetail(key: String, detail: String) {
    val search = LocalNoteSearch.current ?: return
    val pattern = search.activePattern ?: return
    if (detail.isEmpty()) return
    val current = search.currentHit
    val piece = remember(detail, pattern, current) { TextSearch.snippet(detail, pattern, ItemHitKey(key, 1), current) } ?: return
    Text(piece, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
}

/** The comment in the JSON of a trashed media item, or "" (D253). */
internal fun mediaCommentOf(itemJson: String): String = runCatching {
    val element = kotlinx.serialization.json.Json.parseToJsonElement(itemJson)
    ((element as? kotlinx.serialization.json.JsonObject)?.get("comment") as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
}.getOrDefault("")

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/**
 * An item of the media trash (Android `MediaTrashCard`, D156): its name, kind and size, the note it came from and whether that note
 * is still there; put back (while its note and its file are there), export the file, delete for good.
 */
@Composable
private fun MediaTrashCard(row: MediaTrashRow, controller: NotioController) {
    val kindLabel = stringResource(
        when (row.media.kind) {
            "PHOTO" -> "note_type_photo"
            "VIDEO" -> "note_type_video"
            "SCREEN" -> "note_type_screen"
            else -> "note_type_audio"
        },
    )
    val size = row.media.sizeBytes.let { if (it >= 1024 * 1024) "%.1f MB".format(it / 1048576.0) else "${(it + 1023) / 1024} KB" }
    val where = when (row.noteState) {
        "ACTIVE" -> stringResource("trash_media_from_note", row.media.noteTitle)
        "IN_TRASH" -> stringResource("trash_media_note_in_trash", row.media.noteTitle)
        else -> stringResource("trash_media_note_gone", row.media.noteTitle)
    }
    var confirm by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(highlighted(row.media.title.ifBlank { kindLabel }, ItemHitKey("m-" + row.media.id, 0)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            FoundDetail("m-" + row.media.id, mediaCommentOf(row.media.itemJson))
            Muted("$kindLabel · $size")
            Muted(where)
            if (!row.fileExists) Text(stringResource("trash_media_file_missing"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Muted(trashDeletedLine(row.media.deletedAt, controller.appSettings.current.value.trashRetentionDays ?: 0))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Android D278: restore, save to a file and delete for good as icons (their names on hover); deleting still asks first.
                IconAction(AppIcons.RestoreFromTrash, stringResource("trash_restore"), { controller.restoreMedia(row.media.id) }, enabled = row.noteState == "ACTIVE" && row.fileExists)
                IconAction(AppIcons.FileDownload, stringResource("trash_export"), enabled = row.fileExists, onClick = {
                    val file = controller.mediaFile(row.media) ?: return@IconAction
                    val naming = com.notio.shared.domain.ExportNaming.decode(controller.appSettings.current.value.exportNaming)
                    val name = com.notio.shared.domain.ExportNaming.safe(
                        naming.baseName(row.media.noteTitle, row.media.title.ifBlank { kindLabel }, row.media.deletedAt, row.media.deletedAt, System.currentTimeMillis()),
                    ) + "." + file.extension.ifBlank { "bin" }
                    chooseFile(controller.res.str("trash_export"), save = true, name = name)?.let { target ->
                        val ok = runCatching { file.copyTo(target, overwrite = true) }.isSuccess
                        controller.say(controller.res.str(if (ok) "export_done" else "export_failed"))
                    }
                })
                IconAction(AppIcons.DeleteForever, stringResource("trash_delete_forever"), { confirm = true }, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource("trash_purge_media_title")) },
            text = { Text(stringResource("trash_purge_media_body", row.media.title.ifBlank { kindLabel })) },
            confirmButton = { TextButton(onClick = { confirm = false; controller.purgeMedia(row.media.id) }) { Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource("action_cancel")) } },
        )
    }
}

/** "Deleted on <date>" and, when the trash empties itself, how many days are left (Android `deletedLine`). */
@Composable
fun trashDeletedLine(deletedAt: Long, retentionDays: Int): String {
    val deleted = stringResource("trash_deleted_on", Format.date(deletedAt))
    val left = com.notio.shared.domain.TrashRetention.daysLeft(deletedAt, System.currentTimeMillis(), retentionDays) ?: return deleted
    return deleted + " · " + if (left == 0) stringResource("trash_expiring_soon") else pluralStringResource("trash_days_left", left, left)
}
