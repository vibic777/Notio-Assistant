package com.notebudget.app.feature.home

import com.notebudget.app.core.ui.QuickScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.notebudget.app.domain.model.colorGroupStarts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.notebudget.app.core.ui.tip.ExtendedFloatingActionButton
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.draw.clip
import com.notebudget.app.core.ui.tip.TextButton
import com.notebudget.app.domain.model.SortKey
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.Scaffold
import com.notebudget.app.core.ui.NoticeHost
import com.notebudget.app.core.ui.showNotice
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.data.transfer.TransferException
import com.notebudget.app.feature.settings.messageRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.pluralStringResource
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.notebudget.app.feature.folders.FolderCard
import com.notebudget.app.feature.folders.FolderDraft
import com.notebudget.app.feature.notes.NoteActions
import com.notebudget.app.feature.notes.NoteCard

/** Test hook for the "new note" button. */
const val NEW_NOTE_FAB_TAG = "new-note-fab"

/**
 * Combined main screen (D50): folder cards on top, the notes of the selected folder below.
 * A tap on a note opens its editor ([onOpenNote]); the overflow menu covers pin, favourite,
 * bold title, move and delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenNote: (noteId: String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenReminders: (noteId: String?) -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    /** The Sync page (D329): an icon of the bar, beside the calendar and the reminders. */
    onOpenSync: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = appViewModel {
        HomeViewModel(
            it.folderRepository, it.noteRepository, it.folderUseCases, it.noteUseCases, it.tagRepository, it.tagUseCases,
            it.settings, it.budgetRepository, it.settings, it.reminderRepository, it.noteCloneService,
            computeDispatcher = kotlinx.coroutines.Dispatchers.Default,
        )
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val dialogError by viewModel.dialogError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentViewModel by rememberUpdatedState(viewModel)
    // "Select" in the menu of a folder (D167): check marks on the notes of that folder, Select all, Clear, Delete, Done.
    val selection = com.notebudget.app.core.ui.rememberSelectionState()
    var selectionFolderId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteMany by remember { mutableStateOf(false) }

    // The note just made (D257): the list goes to it and its card is framed for a moment, so it is seen and can be opened.
    var justCreated by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // In a job of its own: a newer message takes the place of the one shown, nothing waits for it (D360).
            launch {
                when (event) {
                    is HomeEvent.NoteCreated -> justCreated = event.noteId

                    is HomeEvent.Failed ->
                        snackbarHostState.showNotice(context.getString(event.error.messageRes()))

                    is HomeEvent.NoteTrashed -> {
                        val result = snackbarHostState.showNotice(
                            message = context.getString(R.string.note_moved_to_trash),
                            actionLabel = context.getString(R.string.action_undo),
                        )
                        if (result == SnackbarResult.ActionPerformed) currentViewModel.undoNoteDelete(event.noteId)
                    }

                    is HomeEvent.NoteCloned ->
                        snackbarHostState.showNotice(context.getString(R.string.note_cloned, event.title))

                    is HomeEvent.NotesTrashed -> {
                        val result = snackbarHostState.showNotice(
                            message = context.getString(R.string.home_notes_moved_to_trash, event.noteIds.size),
                            actionLabel = context.getString(R.string.action_undo),
                        )
                        if (result == SnackbarResult.ActionPerformed) currentViewModel.undoNotesDelete(event.noteIds)
                    }

                    is HomeEvent.NotesHidden -> {
                        val result = snackbarHostState.showNotice(
                            message = context.getString(if (event.hidden) R.string.privacy_hidden_many else R.string.privacy_shown_many, event.noteIds.size),
                            actionLabel = context.getString(R.string.action_undo),
                        )
                        if (result == SnackbarResult.ActionPerformed) currentViewModel.undoNotesHidden(event.noteIds, event.hidden)
                    }

                    is HomeEvent.FolderTrashed -> {
                        val result = snackbarHostState.showNotice(
                            message = context.getString(R.string.folder_moved_to_trash),
                            actionLabel = context.getString(R.string.action_undo),
                        )
                        if (result == SnackbarResult.ActionPerformed) currentViewModel.undoFolderDelete(event.folderId)
                    }
                }
            }
        }
    }

    val selectedFolder = state.folders.firstOrNull { it.folder.id == state.selectedFolderId }?.folder
    val shareScope = androidx.compose.runtime.rememberCoroutineScope()
    // Every card needs the colour of its folder: one map instead of a search of the folders per card (D200).
    val folderColors = remember(state.folders) { state.folders.associate { it.folder.id to it.folder.colorHex } }

    // The mode belongs to one folder: going to another folder, or searching, ends it; deleted notes drop out of the picks.
    LaunchedEffect(state.selectedFolderId, state.searching) {
        if (selection.active && (state.searching || state.selectedFolderId != selectionFolderId)) selection.end()
    }
    LaunchedEffect(state.notes) { selection.retain(state.notes.map { it.id }) }

    // Drag and drop (D141): while a finger drags, the order is kept here; it is saved when the finger lets go.
    val columnState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    var noteOrder by remember { mutableStateOf<List<String>?>(null) }
    var folderOrder by remember { mutableStateOf<List<String>?>(null) }
    val shownNotes = remember(state.notes, noteOrder) { arranged(state.notes, noteOrder) { it.id } }
    // Go to the note just made once it is in the list (the list comes from the database a moment later), then unframe it (D257).
    LaunchedEffect(justCreated, shownNotes) {
        val id = justCreated ?: return@LaunchedEffect
        val index = shownNotes.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        // The strip of folders is the first item of the list; the notes follow it.
        columnState.animateScrollToItem(1 + index)
        kotlinx.coroutines.delay(2_500)
        justCreated = null
    }
    val shownFolders = remember(state.folders, folderOrder) { arranged(state.folders, folderOrder) { it.folder.id } }
    LaunchedEffect(state.notes) { if (noteOrder != null && state.notes.map { it.id } == noteOrder) noteOrder = null }
    LaunchedEffect(state.folders) { if (folderOrder != null && state.folders.map { it.folder.id } == folderOrder) folderOrder = null }
    val noteDrag = com.notebudget.app.core.ui.rememberDragReorder(
        columnState, horizontal = false,
        onSwap = { a, b ->
            val ids = (noteOrder ?: state.notes.map { it.id }).toMutableList()
            val from = ids.indexOf(a as String)
            val to = ids.indexOf(b as String)
            if (from >= 0 && to >= 0) { ids.add(to, ids.removeAt(from)); noteOrder = ids }
        },
        canSwap = { a, b ->
            val x = state.notes.firstOrNull { it.id == a }
            val y = state.notes.firstOrNull { it.id == b }
            x != null && y != null && x.isPinned == y.isPinned
        },
        onDrop = { noteOrder?.let { viewModel.reorderNotes(it) } },
    )
    val folderDrag = com.notebudget.app.core.ui.rememberDragReorder(
        rowState, horizontal = true,
        onSwap = { a, b ->
            val ids = (folderOrder ?: state.folders.map { it.folder.id }).toMutableList()
            val from = ids.indexOf(a as String)
            val to = ids.indexOf(b as String)
            if (from >= 0 && to >= 0) { ids.add(to, ids.removeAt(from)); folderOrder = ids }
        },
        canSwap = { a, b ->
            val x = state.folders.firstOrNull { it.folder.id == a }
            val y = state.folders.firstOrNull { it.folder.id == b }
            x != null && y != null && x.folder.isPinned == y.folder.isPinned
        },
        onDrop = { folderOrder?.let { viewModel.reorderFolders(it) } },
    )
    val dragLabel = stringResource(R.string.cd_drag_handle)
    // Sorted by colour: where the headers of the colour groups go (D196); none while a finger drags a card.
    val noteGroups = remember(shownNotes, state.noteSort.key, noteOrder, state.searching) {
        if (state.noteSort.key == SortKey.COLOR && noteOrder == null && !state.searching) colorGroupStarts(shownNotes, { it.isPinned }, { it.colorHex })
        else emptyMap()
    }
    val folderGroups = remember(shownFolders, state.folderSort.key, folderOrder, state.searching) {
        if (state.folderSort.key == SortKey.COLOR && folderOrder == null && !state.searching) {
            colorGroupStarts(shownFolders, { it.folder.isPinned }, { it.folder.colorHex })
        } else emptyMap()
    }

    // The strip of folders stays on the folder that is open (D163): when the screen opens, and when the open folder changes for
    // another reason than a tap on it (restored from the last session, a new folder), the strip scrolls so that folder is in
    // view, roughly in the middle. It never jumps to the end by itself.
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.LaunchedEffect(state.selectedFolderId, state.folders.size) {
        val id = state.selectedFolderId ?: return@LaunchedEffect
        val index = state.folders.indexOfFirst { it.folder.id == id }
        if (index < 0) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { rowState.layoutInfo.viewportSize.width }.first { it > 0 }
        val info = rowState.layoutInfo
        // The folder list (and so the strip's real item count) can have changed while the wait above was suspended —
        // folders still loading in, a reorder — and `index`, valid when captured, can now be stale. `scrollToItem`
        // does not clamp an out-of-range index, it throws (user, 2026-09-22: a crash while the strip was still
        // populating and they started scrolling it — caught here instead of anywhere in Compose's own scroll code).
        if (index >= info.totalItemsCount) return@LaunchedEffect
        val at = info.visibleItemsInfo.firstOrNull { it.index == index }
        val inView = at != null && at.offset >= 0 && at.offset + at.size <= info.viewportEndOffset
        if (!inView) {
            val itemPx = with(density) { 190.dp.toPx() }
            val middle = ((info.viewportSize.width - itemPx) / 2f).toInt().coerceAtLeast(0)
            // A belt-and-braces catch too: this is a scroll-position convenience, not something that should ever be
            // allowed to crash the app, even if some other race gets past the check above (e.g. a concurrent manual
            // drag on the very same list state).
            runCatching { rowState.scrollToItem(index, -middle) }
        }
    }

    val trashContainer = rememberAppContainer()
    val trashCount by remember(trashContainer) {
        combine(
            trashContainer.noteRepository.observeTrashed(), trashContainer.folderRepository.observeTrashed(),
            trashContainer.mediaTrashRepository.observeAll(),
        ) { notes, folders, media -> notes.size + folders.size + media.size }
    }.collectAsStateWithLifecycle(initialValue = 0)

    // Import a single note file (Markdown, PNG/JPEG, audio or a NoteBudget JSON) into the selected folder.
    val container = rememberAppContainer()
    // How the notes are shown (D300): cards, a compact list or a grid; chosen with the icons at the bottom left.
    val noteView by container.settings.noteView.collectAsStateWithLifecycle(com.notebudget.app.core.datastore.SettingsDataStore.NOTE_VIEW_CARDS)
    val viewScope = androidx.compose.runtime.rememberCoroutineScope()
    // All reminders on or off, from the menu (D318).
    remember { com.notebudget.app.core.alarm.RemindersSwitch.isOn(context) }
    val remindersOn by com.notebudget.app.core.alarm.RemindersSwitch.enabled.collectAsStateWithLifecycle()
    // The rows of the list, made when the notes or the view change — not on every pass over the list (the pass of 2026-10-05:
    // it was made again, a small list for every note, each time the list looked at a drag, a selection or a frame).
    val noteRows = remember(shownNotes, noteView) {
        shownNotes.chunked(if (noteView == com.notebudget.app.core.datastore.SettingsDataStore.NOTE_VIEW_GRID) 2 else 1)
    }
    var stampsOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val folderId = selectedFolder?.id
        if (uri == null || folderId == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "note"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open $uri")
                    container.noteFileService.import(name, bytes, folderId)
                }
            }
            result.onSuccess {
                snackbarHostState.showNotice(context.getString(R.string.import_note_done, it.title))
            }.onFailure { e ->
                val res = (e as? TransferException)?.error?.messageRes() ?: R.string.transfer_error_generic
                snackbarHostState.showNotice(context.getString(res))
            }
        }
    }
    fun importNote() = importLauncher.launch(arrayOf("*/*"))

    // The system Back button ends a search first, like the cancel button does (D113).
    androidx.activity.compose.BackHandler(enabled = state.searchOpen) { viewModel.toggleSearch() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // The name of the app is a line of its own above the buttons (D236): the row then has room for the calendar too.
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // While searching the icon is highlighted, and tapping it again ends the search (D113).
                    IconButton(
                        helpTopic = "search",
                        onClick = viewModel::toggleSearch,
                        colors = if (state.searchOpen) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(if (state.searchOpen) R.string.search_close else R.string.search_open),
                        )
                    }
                    IconButton(onClick = onOpenCalendar, helpTopic = "reminders") {
                        Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.calendar_title))
                    }
                    val remindersLabel = stringResource(R.string.action_reminders)
                    IconButton(
                        onClick = { onOpenReminders(null) },
                        modifier = Modifier.semantics { contentDescription = remindersLabel },
                        helpTopic = "reminders",
                    ) {
                        // The alarm clock is a glyph, not an icon: it says its hint itself (D334).
                        com.notebudget.app.core.ui.tip.IconHint(remindersLabel)
                        Text("\u23F0", style = MaterialTheme.typography.titleLarge)
                    }
                    // The sync is at hand on the main screen (D329), not only inside the settings.
                    IconButton(onClick = onOpenSync, helpTopic = "sync") {
                        Icon(com.notebudget.app.core.ui.AppIcons.Sync, contentDescription = stringResource(R.string.settings_cat_sync))
                    }
                    IconButton(onClick = onOpenTrash, helpTopic = "trash") {
                        // The number of things in the trash is shown on the bin, so it is easy to find and to trust.
                        val label = if (trashCount == 0) stringResource(R.string.trash_title)
                        else pluralStringResource(R.plurals.home_trash_count, trashCount, trashCount)
                        BadgedBox(badge = { if (trashCount > 0) Badge { Text(trashCount.toString()) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = label)
                        }
                    }
                    // Sorting and importing are used less often: they share one menu, so the bar fits a small phone.
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_more_actions))
                        }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            // The same as the buttons on the screen, so they are at hand in one place (D161).
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_add_folder)) },
                                onClick = { moreOpen = false; viewModel.showDialog(HomeDialog.NewFolder) },
                            )
                            if (selectedFolder != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.home_new_note)) },
                                    onClick = { moreOpen = false; viewModel.showDialog(HomeDialog.NewNote) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_title)) },
                                onClick = { moreOpen = false; viewModel.showDialog(HomeDialog.Sort) },
                            )
                            // All reminders off or on again at once (D318); the Reminders page has the same switch.
                            DropdownMenuItem(
                                text = { Text(stringResource(if (remindersOn) R.string.reminders_turn_all_off else R.string.reminders_turn_all_on)) },
                                leadingIcon = { Icon(if (remindersOn) com.notebudget.app.core.ui.AppIcons.NotificationsOff else com.notebudget.app.core.ui.AppIcons.Notifications, contentDescription = null) },
                                onClick = {
                                    moreOpen = false
                                    val on = !remindersOn
                                    viewScope.launch {
                                        com.notebudget.app.core.alarm.RemindersSwitch.change(context, container, on)
                                        android.widget.Toast.makeText(context, context.getString(if (on) R.string.reminders_all_on else R.string.reminders_all_off_hint), android.widget.Toast.LENGTH_LONG).show()
                                    }
                                },
                            )
                            // How the notes of the folder are shown (D300): here too, beside the sorting; the one in use is ticked.
                            val store = com.notebudget.app.core.datastore.SettingsDataStore
                            listOf(
                                Triple(store.NOTE_VIEW_CARDS, R.string.note_view_cards, com.notebudget.app.core.ui.AppIcons.ViewCards),
                                Triple(store.NOTE_VIEW_LIST, R.string.note_view_list, com.notebudget.app.core.ui.AppIcons.ViewList),
                                Triple(store.NOTE_VIEW_GRID, R.string.note_view_grid, com.notebudget.app.core.ui.AppIcons.ViewGrid),
                            ).forEach { (view, label, icon) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = { Icon(icon, contentDescription = null) },
                                    trailingIcon = { if (noteView == view) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_selected)) },
                                    onClick = { moreOpen = false; viewScope.launch { container.settings.setNoteView(view) } },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_stamps)) },
                                leadingIcon = { Icon(com.notebudget.app.core.ui.AppIcons.Schedule, contentDescription = null) },
                                onClick = { moreOpen = false; stampsOpen = true },
                            )
                            if (selectedFolder != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.home_import_note)) },
                                    onClick = { moreOpen = false; importNote() },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onOpenSettings, helpTopic = "settings") {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings))
                    }
                }
            }
            }
        },
        snackbarHost = { NoticeHost(snackbarHostState) },
        bottomBar = {
            if (selection.active) {
                com.notebudget.app.core.ui.SelectionBar(
                    count = selection.picked.size, total = state.notes.size,
                    onSelectAll = { selection.selectAll(state.notes.map { it.id }) }, onClear = selection::clear,
                    onDelete = { confirmDeleteMany = true }, onDone = selection::end,
                    hide = run {
                        val toHide = com.notebudget.app.core.ui.HideMany.targets(state.notes, selection.picked, hidden = true)
                        val toShow = com.notebudget.app.core.ui.HideMany.targets(state.notes, selection.picked, hidden = false)
                        com.notebudget.app.core.ui.HideMany(
                            toHide.size, toShow.size,
                            onHide = { viewModel.setNotesHidden(toHide, hidden = true) },
                            onShow = { viewModel.setNotesHidden(toShow, hidden = false) },
                        )
                    },
                )
            }
        },
        floatingActionButton = {
            if (selectedFolder != null && !selection.active) {
                // An icon alone (D298); a long press says "New note".
                com.notebudget.app.core.ui.IconFab(
                    Icons.Filled.Add, stringResource(R.string.home_new_note),
                    onClick = { viewModel.showDialog(HomeDialog.NewNote) },
                    modifier = Modifier.testTag(NEW_NOTE_FAB_TAG),
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
        // The search panel stands ABOVE the list, not in it (D204): a focused text field inside a lazy list that is rebuilt
        // on every typed letter closed the app on a phone with 100+ notes; here it also stays in view while the results scroll.
        if (state.searchOpen) {
            SearchPanel(
                state = state,
                onScope = viewModel::setSearchScope,
                onText = viewModel::setSearchText,
                onToggleTag = viewModel::toggleSearchTag,
                onClose = viewModel::toggleSearch,
                onIgnoreCase = viewModel::setSearchIgnoreCase,
                onRegex = viewModel::setSearchRegex,
                onToggleTaskState = viewModel::toggleSearchTaskState,
                onTaskDone = viewModel::setSearchTaskDone,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        // A folder of tasks: its tasks by status (D338). Above the list, as the search is.
        if (!state.searchOpen && (state.taskCounts.isNotEmpty() || (state.tasksFolder && (state.taskFilter.isNotEmpty() || state.taskDone != null)))) {
            com.notebudget.app.feature.notes.task.TaskStateChips(
                state.taskFilter, viewModel::toggleTaskFilter, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), state.taskCounts,
                done = state.taskDone, onDone = viewModel::setTaskDone,
            )
        }
        // The status and the priority of a task are set on its card (D337).
        val taskChange = remember(viewModel) { { id: String, task: com.notebudget.app.domain.model.TaskDocument -> viewModel.setTask(id, task) } }
        // The bin on the card of a task (D361) asks as "Delete" of the card's menu does.
        val taskDelete = remember(viewModel) { { id: String -> viewModel.showDialog(HomeDialog.DeleteNote(id)) } }
        androidx.compose.runtime.CompositionLocalProvider(
            com.notebudget.app.feature.notes.task.LocalTaskChange provides taskChange,
            com.notebudget.app.feature.notes.task.LocalTaskDelete provides taskDelete,
        ) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = columnState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "folders") {
                    Box(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) {
                        com.notebudget.app.core.ui.BackgroundLayer(com.notebudget.app.domain.model.BackgroundSlot.FOLDERS, Modifier.matchParentSize())
                    LazyRow(state = rowState, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        itemsIndexed(shownFolders, key = { _, item -> item.folder.id }) { index, item ->
                            androidx.compose.foundation.layout.Row(
                                if (folderDrag.draggedKey == item.folder.id) folderDrag.itemModifier(item.folder.id) else Modifier.animateItem(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                            // Sorted by colour: the name of the colour group stands in front of its first folder (D196).
                            folderGroups[index]?.let { ColorGroupHeader(it, item.folder.colorHex, Modifier.padding(start = if (index == 0) 4.dp else 8.dp)) }
                            FolderCard(
                                name = item.folder.name,
                                colorHex = item.folder.colorHex,
                                styleId = item.folder.styleId,
                                icon = item.folder.icon,
                                noteCount = item.noteCount,
                                selected = item.folder.id == state.selectedFolderId,
                                onClick = { viewModel.selectFolder(item.folder.id) },
                                modifier = Modifier.width(190.dp),
                                pinned = item.folder.isPinned,
                                comment = if (com.notebudget.app.core.ui.LocalCommentDisplay.current.folders) com.notebudget.app.domain.model.Comment.short(item.folder.comment) else null,
                                createdAt = item.folder.createdAt,
                                updatedAt = item.folder.updatedAt,
                                dragHandle = if (state.searching) null else ({
                                    Box(
                                        Modifier.size(36.dp).then(folderDrag.handleModifier(item.folder.id))
                                            .semantics { contentDescription = dragLabel },
                                        contentAlignment = Alignment.Center,
                                    ) { com.notebudget.app.core.ui.MoveHandleIcon() }
                                }),
                                menu = {
                                    val manual = state.folderSort.key == SortKey.MANUAL && !state.searching
                                    val at = state.folders.indexOfFirst { it.folder.id == item.folder.id }
                                    FolderMenu(
                                        pinned = item.folder.isPinned,
                                        onTogglePinned = { viewModel.toggleFolderPinned(item.folder.id) },
                                        canDelete = state.folders.size > 1,
                                        onEdit = { viewModel.showDialog(HomeDialog.EditFolder(item.folder.id)) },
                                        onDelete = { viewModel.showDialog(HomeDialog.DeleteFolder(item.folder.id)) },
                                        onComment = { viewModel.showDialog(HomeDialog.FolderComment(item.folder.id)) },
                                        onSelect = {
                                            // The mode works inside one folder: the one whose menu it was chosen in becomes the open one.
                                            selectionFolderId = item.folder.id
                                            viewModel.selectFolder(item.folder.id)
                                            selection.begin()
                                        },
                                        onFeatured = { viewModel.showDialog(HomeDialog.Featured(item.folder.id)) },
                                        onMoveEarlier = if (manual && at > 0) ({ viewModel.moveFolder(item.folder.id, -1) }) else null,
                                        onMoveLater = if (manual && at in 0 until state.folders.lastIndex) ({ viewModel.moveFolder(item.folder.id, 1) }) else null,
                                    )
                                },
                            )
                            }
                        }
                        item(key = "add-folder") {
                            // An icon alone (D298); a long press says "Add folder".
                            com.notebudget.app.core.ui.IconAction(
                                com.notebudget.app.core.ui.AppIcons.NewFolder, stringResource(R.string.home_add_folder),
                                onClick = { viewModel.showDialog(HomeDialog.NewFolder) },
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    }
                }

                // "Nothing found" for a note search with no notes, and for a folder search with no folders (D201): the screen used to
                // go blank without a word in the second case.
                val folderSearchEmpty = state.searching && state.query.scope == com.notebudget.app.domain.usecase.SearchScope.FOLDERS && state.folders.isEmpty()
                if (folderSearchEmpty || (state.searching && state.notes.isEmpty() && state.query.scope != com.notebudget.app.domain.usecase.SearchScope.FOLDERS)) {
                    item(key = "no-results") {
                        Text(
                            stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                } else if (selectedFolder != null && state.notes.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.home_empty_notes), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.home_empty_notes_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                if (selectedFolder != null || state.searching) {
                    // Sorted by colour: a header over every colour group (D196); not while a finger drags a card.
                    // One note in a row, or two in the grid (D300); the grid has no grips and no colour headers.
                    val grid = noteView == com.notebudget.app.core.datastore.SettingsDataStore.NOTE_VIEW_GRID
                    val compactList = noteView == com.notebudget.app.core.datastore.SettingsDataStore.NOTE_VIEW_LIST
                    itemsIndexed(noteRows, key = { _, row -> row.first().id }) { rowIndex, row ->
                        Row(
                            if (noteDrag.draggedKey == row.first().id) noteDrag.itemModifier(row.first().id) else Modifier.animateItem(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                        row.forEachIndexed { column, note ->
                        val index = rowIndex * (if (grid) 2 else 1) + column
                        Column(
                            Modifier.weight(1f)
                                .then(
                                    if (note.id == justCreated) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                                    else Modifier,
                                ),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                        if (!grid) noteGroups[index]?.let { ColorGroupHeader(it, note.colorHex, Modifier.padding(top = if (index == 0) 0.dp else 8.dp)) }
                        NoteCard(
                            compact = compactList,
                            hasReminder = note.id in state.notesWithReminder,
                            selecting = selection.active && !state.searching,
                            checked = note.id in selection.picked,
                            onToggle = { selection.toggle(note.id) },
                            dragHandle = if (state.searching || selection.active || grid) null else ({
                                Box(
                                    Modifier.size(40.dp).then(noteDrag.handleModifier(note.id))
                                        .semantics { contentDescription = dragLabel },
                                    contentAlignment = Alignment.Center,
                                ) { com.notebudget.app.core.ui.MoveHandleIcon() }
                            }),
                            note = note,
                            // In a search the notes come from every folder, each in the colour of its own folder.
                            folderColorHex = folderColors[note.folderId] ?: selectedFolder?.colorHex ?: "#3366FF",
                            tags = state.tagsByNote[note.id].orEmpty(),
                            onClick = { onOpenNote(note.id) },
                            actions = NoteActions(
                                onTogglePinned = { viewModel.togglePinned(note) },
                                onToggleBoldTitle = { viewModel.toggleBoldTitle(note) },
                                onColor = { viewModel.showDialog(HomeDialog.NoteColor(note.id)) },
                                onTags = { viewModel.showDialog(HomeDialog.NoteTags(note.id)) },
                                onReminder = { onOpenReminders(note.id) },
                                onMove = { viewModel.showDialog(HomeDialog.MoveNote(note.id)) },
                                onDelete = { viewModel.showDialog(HomeDialog.DeleteNote(note.id)) },
                                onComment = { viewModel.showDialog(HomeDialog.NoteComment(note.id)) },
                                onClone = { viewModel.cloneNote(note.id, context.getString(R.string.note_clone_suffix)) },
                                onShare = { shareScope.launch { com.notebudget.app.feature.notes.NoteShare.share(context, container, note) } },
                                onCopy = {
                                    shareScope.launch {
                                        if (com.notebudget.app.feature.notes.NoteShare.copy(context, container, note)) com.notebudget.app.core.ui.sayCopied(context)
                                        else android.widget.Toast.makeText(context, context.getString(R.string.share_nothing), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                            ),
                        )
                        }
                        }
                        if (grid && row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            // Above the "New note" button (D250).
            QuickScroll(columnState, bottomPadding = innerPadding.calculateBottomPadding() + 88.dp)
            // The five icons at the bottom left (D300): cards / compact list / grid, sorting, time stamps.
            if ((selectedFolder != null || state.searching) && !selection.active) {
                NoteViewBar(
                    view = noteView,
                    onView = { chosen -> viewScope.launch { container.settings.setNoteView(chosen) } },
                    onSort = { viewModel.showDialog(HomeDialog.Sort) },
                    onStamps = { stampsOpen = true },
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp),
                )
            }
        }
        }
        }
    }

    if (confirmDeleteMany) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteMany = false },
            title = { Text(stringResource(R.string.home_delete_many_title)) },
            text = { Text(stringResource(R.string.home_delete_many_message, selection.picked.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteNotes(selection.picked.toList())
                    confirmDeleteMany = false
                    selection.end()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteMany = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (stampsOpen) {
        com.notebudget.app.core.ui.AppAlertDialog(
            onDismissRequest = { stampsOpen = false },
            title = { Text(stringResource(R.string.settings_stamps)) },
            text = { Column(Modifier) { com.notebudget.app.feature.settings.StampOptions() } },
            confirmButton = { com.notebudget.app.core.ui.tip.TextButton(onClick = { stampsOpen = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    when (val current = dialog) {
        null -> Unit

        HomeDialog.Sort -> SortDialog(
            tasks = state.tasksFolder,
            folderSort = state.folderSort,
            noteSort = state.noteSort,
            onFolderSort = viewModel::setFolderSort,
            onNoteSort = { option, all -> viewModel.setNoteSort(option, all) },
            onDismiss = viewModel::dismissDialog,
        )

        is HomeDialog.Featured -> state.folders.firstOrNull { it.folder.id == current.folderId }?.let { item ->
            FeaturedDialog(
                folder = item.folder,
                notes = state.notesByFolder[item.folder.id].orEmpty().sortedBy { it.title.lowercase() },
                error = dialogError,
                onConfirm = { noteId, mask, layout -> viewModel.setFeatured(item.folder.id, noteId, mask, layout) },
                onDismiss = viewModel::dismissDialog,
            )
        }

        HomeDialog.NewFolder -> FolderDialog(
            title = stringResource(R.string.dialog_new_folder_title),
            confirmLabel = stringResource(R.string.action_create),
            initial = FolderDraft(name = ""),
            error = dialogError,
            onDraftEdited = viewModel::clearDialogError,
            onConfirm = viewModel::createFolder,
            onDismiss = viewModel::dismissDialog,
            showKind = true,
            existingNames = remember(state.folders) { state.folders.map { it.folder.name } },
        )

        is HomeDialog.EditFolder -> state.folders.firstOrNull { it.folder.id == current.folderId }?.let { item ->
            FolderDialog(
                title = stringResource(R.string.dialog_edit_folder_title),
                confirmLabel = stringResource(R.string.action_save),
                initial = FolderDraft(
                    name = item.folder.name,
                    colorHex = item.folder.colorHex,
                    styleId = item.folder.styleId,
                    icon = item.folder.icon.orEmpty(),
                ),
                error = dialogError,
                onDraftEdited = viewModel::clearDialogError,
                onConfirm = { viewModel.updateFolder(current.folderId, it) },
                onDismiss = viewModel::dismissDialog,
            )
        }

        is HomeDialog.DeleteFolder -> state.folders.firstOrNull { it.folder.id == current.folderId }?.let { item ->
            DeleteFolderDialog(
                folder = item.folder,
                noteCount = item.noteCount,
                otherFolders = state.folders.map { it.folder }.filter { it.id != current.folderId },
                onConfirm = { mode, target -> viewModel.deleteFolder(current.folderId, mode, target) },
                onDismiss = viewModel::dismissDialog,
            )
        }

        HomeDialog.NewNote -> NewNoteDialog(
            error = dialogError,
            onDraftEdited = viewModel::clearDialogError,
            onConfirm = viewModel::createNote,
            onDismiss = viewModel::dismissDialog,
            tasksOnly = state.folders.firstOrNull { it.folder.id == state.selectedFolderId }?.folder?.kind == com.notebudget.app.domain.model.FolderKind.TASKS,
            existingTitles = remember(state.notesByFolder, state.selectedFolderId) {
                state.notesByFolder[state.selectedFolderId].orEmpty().map { it.title }
            },
        )

        is HomeDialog.MoveNote -> state.notes.firstOrNull { it.id == current.noteId }?.let { note ->
            MoveNoteDialog(
                note = note,
                otherFolders = state.folders.map { it.folder }.filter { it.id != note.folderId },
                onMove = { viewModel.moveNote(note.id, it) },
                onDismiss = viewModel::dismissDialog,
            )
        }

        is HomeDialog.NoteTags -> state.notes.firstOrNull { it.id == current.noteId }?.let { note ->
            NoteTagsDialog(
                allTags = state.tags,
                current = state.tagsByNote[note.id].orEmpty(),
                onCreate = viewModel::createTag,
                onConfirm = { viewModel.setNoteTags(note.id, it) },
                onDismiss = viewModel::dismissDialog,
            )
        }

        is HomeDialog.NoteColor -> state.notes.firstOrNull { it.id == current.noteId }?.let { note ->
            NoteColorDialog(
                note = note,
                onPick = { viewModel.setNoteColor(note, it) },
                onDismiss = viewModel::dismissDialog,
            )
        }

        is HomeDialog.NoteComment -> state.notes.firstOrNull { it.id == current.noteId }?.let { note ->
            CommentDialog(
                title = stringResource(R.string.comment_title_note),
                initial = note.comment.orEmpty(),
                onSave = { viewModel.setNoteComment(note.id, it) },
                onDismiss = viewModel::dismissDialog,
            )
        }

        is HomeDialog.FolderComment -> state.folders.firstOrNull { it.folder.id == current.folderId }?.let { item ->
            CommentDialog(
                title = stringResource(R.string.comment_title_folder),
                initial = item.folder.comment.orEmpty(),
                onSave = { viewModel.setFolderComment(item.folder.id, it) },
                onDismiss = viewModel::dismissDialog,
            )
        }

        is HomeDialog.DeleteNote -> state.notes.firstOrNull { it.id == current.noteId }?.let { note ->
            DeleteNoteDialog(
                note = note,
                onConfirm = { viewModel.deleteNote(note.id) },
                onDismiss = viewModel::dismissDialog,
            )
        }
    }
}

/** [items] in the order of [ids] while a drag is going on; anything not in [ids] follows in its own order. */
private fun <T> arranged(items: List<T>, ids: List<String>?, id: (T) -> String): List<T> {
    if (ids == null) return items
    val byId = items.associateBy(id)
    val ordered = ids.mapNotNull { byId[it] }
    return ordered + items.filter { id(it) !in ids }
}

@Composable
private fun FolderMenu(
    pinned: Boolean,
    onTogglePinned: () -> Unit,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onComment: () -> Unit,
    onSelect: () -> Unit,
    onFeatured: () -> Unit,
    onMoveEarlier: (() -> Unit)?,
    onMoveLater: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit)) },
                onClick = { open = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_comment)) },
                onClick = { open = false; onComment() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.media_select)) },
                onClick = { open = false; onSelect() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.action_unpin else R.string.action_pin)) },
                onClick = { open = false; onTogglePinned() },
            )
            onMoveEarlier?.let { move ->
                DropdownMenuItem(text = { Text(stringResource(R.string.folder_move_earlier)) }, onClick = { open = false; move() })
            }
            onMoveLater?.let { move ->
                DropdownMenuItem(text = { Text(stringResource(R.string.folder_move_later)) }, onClick = { open = false; move() })
            }
            // The last folder can never be deleted (INV-2), so the item is disabled there.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                enabled = canDelete,
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/**
 * The five icons at the bottom left of the notes (D300, as in FitEasy): how they are shown — cards, a compact list, a grid — then
 * sorting and the time stamps. A long press names each one.
 */
@Composable
private fun NoteViewBar(view: String, onView: (String) -> Unit, onSort: () -> Unit, onStamps: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val store = com.notebudget.app.core.datastore.SettingsDataStore
            com.notebudget.app.core.ui.IconChoice(com.notebudget.app.core.ui.AppIcons.ViewCards, stringResource(R.string.note_view_cards), view == store.NOTE_VIEW_CARDS, { onView(store.NOTE_VIEW_CARDS) })
            com.notebudget.app.core.ui.IconChoice(com.notebudget.app.core.ui.AppIcons.ViewList, stringResource(R.string.note_view_list), view == store.NOTE_VIEW_LIST, { onView(store.NOTE_VIEW_LIST) })
            com.notebudget.app.core.ui.IconChoice(com.notebudget.app.core.ui.AppIcons.ViewGrid, stringResource(R.string.note_view_grid), view == store.NOTE_VIEW_GRID, { onView(store.NOTE_VIEW_GRID) })
            com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Sort, stringResource(R.string.sort_title), onSort)
            com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Schedule, stringResource(R.string.settings_stamps), onStamps)
        }
    }
}
