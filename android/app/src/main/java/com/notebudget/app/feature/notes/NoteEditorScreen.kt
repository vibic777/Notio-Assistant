package com.notebudget.app.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.core.ui.has
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType

/**
 * Note screen: title in the top bar and the editor for the note's type. TEXT and LIST
 * autosave; BUDGET writes each change straight to the database. DRAWING has no editor yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(noteId: String, onBack: () -> Unit, onOpenReminders: (String) -> Unit = {}) {
    val viewModel: NoteEditorViewModel = appViewModel {
        NoteEditorViewModel(noteId, it.noteRepository, it.noteUseCases, undoSteps = it.settings.undoSteps, appScope = it.appScope)
    }
    // The editor of the note puts here what the Undo / Redo buttons of the top bar act on (D197).
    val undoSlot = remember { mutableStateOf<UndoTarget?>(null) }
    // The note as it is stored now, so the stamps at the bottom (D198) follow changes that the editors save themselves.
    val appContainer = rememberAppContainer()
    val liveFlow = remember(noteId) { appContainer.noteRepository.observeById(noteId) }
    val liveNote by liveFlow.collectAsStateWithLifecycle(initialValue = null)
    val footerStamps by appContainer.settings.footerStamps.collectAsStateWithLifecycle(
        initialValue = com.notebudget.app.domain.model.NoteFooterStamps(),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val container = rememberAppContainer()
    var showExport by remember { mutableStateOf(false) }
    // The content hidden until the eye is pressed (D363): the option is the note's, the eye works while the note is open.
    var contentShown by remember(noteId) { mutableStateOf(false) }
    val noteHide = (liveNote ?: state.note)?.takeIf { it.type in com.notebudget.app.core.ui.HIDEABLE_KINDS }?.let { n ->
        com.notebudget.app.core.ui.NoteHide(n.hidden, contentShown, { on -> contentShown = false; viewModel.setHidden(on) }, { contentShown = it })
    }
    // The bin in View of a task (D361): asked first, then the note goes to the trash and the screen goes back.
    var askDelete by remember { mutableStateOf(false) }
    val taskDelete = remember { { _: String -> askDelete = true } }
    // The search inside the note (D238): the bar is here, the editor finds and marks the words.
    val search = remember(noteId) { com.notebudget.app.core.ui.NoteSearch() }

    BackHandler { viewModel.saveNow { currentOnBack() } }
    // Back first closes an open search, then leaves the note.
    BackHandler(enabled = search.open) { search.close() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.saveNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val slot = state.note?.let { com.notebudget.app.domain.model.BackgroundSlot.forNote(it.type) }
    val hasOwnBackground = slot != null && com.notebudget.app.core.ui.LocalBackgrounds.current.has(slot)
    val footerNote = liveNote ?: state.note
    val footer: @Composable () -> Unit = {
        if (footerNote != null) com.notebudget.app.core.ui.NoteFooterStampLines(footerNote, footerStamps)
    }
    CompositionLocalProvider(
        LocalUndoSlot provides undoSlot,
        com.notebudget.app.core.ui.LocalNoteFooter provides footer,
        com.notebudget.app.core.ui.LocalNoteSearch provides search,
        // A task sets its reminder on its own Edit tab (D239).
        com.notebudget.app.feature.notes.task.LocalOpenReminders provides onOpenReminders,
        com.notebudget.app.feature.notes.task.LocalTaskDelete provides taskDelete,
        com.notebudget.app.core.ui.LocalNoteHide provides noteHide,
    ) {
    val noteToDelete = state.note
    if (askDelete && noteToDelete != null) {
        com.notebudget.app.feature.home.DeleteNoteDialog(
            noteToDelete,
            onConfirm = { askDelete = false; viewModel.deleteNote { currentOnBack() } },
            onDismiss = { askDelete = false },
        )
    }
    Box(Modifier.fillMaxSize()) {
    if (slot != null) com.notebudget.app.core.ui.BackgroundLayer(slot, Modifier.matchParentSize())
    Scaffold(
        containerColor = if (hasOwnBackground) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,
        topBar = {
            Column {
            TopAppBar(
                title = {
                    TextField(
                        value = state.title,
                        onValueChange = viewModel::editTitle,
                        singleLine = true,
                        enabled = state.note != null,
                        isError = state.error != null,
                        placeholder = { Text(stringResource(R.string.editor_title_hint)) },
                        supportingText = state.error?.let { { Text(stringResource(it.messageRes())) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                actions = {
                    undoSlot.value?.let { UndoRedoButtons(it) }
                    val note = state.note
                    if (note != null && note.type in SEARCHABLE) {
                        IconButton(onClick = { if (search.open) search.close() else search.open = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.note_search_open))
                        }
                    }
                    if (note != null && container.noteFileService.formatsFor(note).isNotEmpty()) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_note_action)) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.saveNow()
                                        showExport = true
                                    },
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveNow { currentOnBack() } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
            if (search.open) com.notebudget.app.core.ui.NoteSearchBar(search)
            }
        },
    ) { padding ->
        val note = state.note
        if (showExport && note != null) NoteExportDialog(note, onDismiss = { showExport = false })
        when {
            !state.loaded -> Box(Modifier.fillMaxSize().padding(padding))

            note == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.editor_note_missing))
            }

            else -> Column(Modifier.padding(padding)) {
                // When the note was made and last changed, in the format chosen in Settings (D138).
                com.notebudget.app.core.ui.StampLines(note.createdAt, note.updatedAt, Modifier.padding(horizontal = 16.dp))
                // Text, list and schedule keep their text in this screen's model, so its history is the one to undo (D197);
                // a budget and a drawing save themselves and bring their own.
                if (note.type == NoteType.TEXT || note.type == NoteType.WORD || note.type == NoteType.LIST || note.type == NoteType.SCHEDULE) ProvideUndoTarget(viewModel)
                NoteBody(
                    note, state.content, viewModel::editContent, state.extras, viewModel::editContentAndExtras,
                    Modifier.weight(1f),
                )
            }
        }
    }
    }
    }
}

@Composable
private fun NoteBody(
    note: Note,
    content: String,
    onContentChange: (String) -> Unit,
    extras: String?,
    onEdit: (String, String?) -> Unit,
    modifier: Modifier,
) {
    val registry = rememberAppContainer().noteTypes
    val editor: @Composable (Modifier) -> Unit = { m ->
        // The hints inside a note lead to the help of its kind (D332).
        com.notebudget.app.core.ui.tip.HelpTopic(com.notio.common.HelpMap.topicForNoteType(note.type.id)) {
            registry.definitionFor(note.type).Editor(note, content, onContentChange, m, extras, onEdit)
        }
    }
    // Text, list and budget are read as text: zoom = a bigger font (D118). Drawings have their own zoom of the sheet.
    if (note.type == NoteType.TEXT || note.type == NoteType.WORD || note.type == NoteType.LIST || note.type == NoteType.BUDGET || note.type == NoteType.SCHEDULE) {
        ZoomableText(modifier) { editor(Modifier) }
    } else {
        editor(modifier)
    }
}

/** The kinds that can be searched inside (D238): Markdown, list and budget. */
private val SEARCHABLE = setOf(
    NoteType.TEXT, NoteType.LIST, NoteType.BUDGET,
    // The formatted "Note" (D256).
    NoteType.WORD,
    // Recordings, photos and videos: the names and comments of their items (D248).
    NoteType.AUDIO, NoteType("PHOTO"), NoteType("VIDEO"), NoteType("SCREEN"),
)
