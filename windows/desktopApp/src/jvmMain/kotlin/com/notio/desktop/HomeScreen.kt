package com.notio.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What the ⋮ menu of the top bar can ask the window to do (file dialogs belong to the window). */
class HomeActions(
    val importBackup: () -> Unit,
    val exportBackup: () -> Unit,
    val openDataFolder: () -> Unit,
    val about: () -> Unit,
    val comingSoon: () -> Unit,
    /** Opens an editing dialog (EditHost). */
    val open: (EditDialog) -> Unit = {},
)

/** The top bar of the phone, standing in the window's title bar: the name and the icons Search, Reminders, Trash, ⋮, Settings. */
@Composable
fun HomeTopBar(s: Strings, state: UiState, controller: NotioController, chrome: WindowChrome, actions: HomeActions) {
    // The name of the app is a line of its own (the window's title bar here), the buttons a row under it (D236): the row then
    // has room for the calendar too.
    TitleBarRow(chrome) {
        Text(s.appName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp).weight(1f))
    }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        // While searching the icon is highlighted, and pressing it again ends the search (Android D113).
        IconButton(
            helpTopic = "search",
            onClick = controller::toggleSearch,
            colors = if (state.searchOpen) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
        ) { Icon(Icons.Filled.Search, contentDescription = s.search) }
        IconButton(onClick = controller::openCalendar, helpTopic = "reminders") {
            Icon(Icons.Filled.DateRange, contentDescription = controller.res.str("calendar_title"))
        }
        // A screen reader says the word, not the clock (Android D274 step 1).
        val remindersLabel = stringResource("reminders_title")
        // The alarm clock is a glyph, not an icon: it says its hint itself (D115, Android D334).
        IconButton(onClick = { controller.openReminders(null) }, modifier = Modifier.semantics { contentDescription = remindersLabel }, helpTopic = "reminders") {
            IconHint(remindersLabel)
            // Drawn as a character where the system has it; a bell of the app's own icons where it has not (D132).
            if (Glyphs.emojiShown) Text("⏰", style = MaterialTheme.typography.titleLarge) else Icon(AppIcons.Notifications, contentDescription = remindersLabel)
        }
        // The chat with the assistant (D145), while the assistant is turned on.
        if (controller.assistant.enabled) {
            IconButton(onClick = { controller.openCatalog("assistant_chat") }, helpTopic = "assistant") { Icon(AppIcons.Chat, contentDescription = stringResource("assistant_chat")) }
        }
        // The sync is at hand on the main screen (D110, Android D329), not only inside the settings.
        IconButton(onClick = controller::openSync, helpTopic = "sync") { Icon(AppIcons.Sync, contentDescription = stringResource("settings_cat_sync")) }
        IconButton(onClick = controller::openTrash, helpTopic = "trash") {
            BadgedBox(badge = { if (state.trash.isNotEmpty()) Badge { Text(state.trash.size.toString()) } }) {
                Icon(Icons.Filled.Delete, contentDescription = s.trash)
            }
        }
        var moreOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = s.more) }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                fun item(text: String, action: () -> Unit) = @Composable {
                    DropdownMenuItem(text = { Text(text) }, onClick = { moreOpen = false; action() })
                }
                item(s.newFolder) { actions.open(EditDialog.NewFolder) }()
                item(controller.res.str("sort_title")) { actions.open(EditDialog.Sort) }()
                // All reminders off or on again at once (Android D318); the Reminders page has the same switch.
                DropdownMenuItem(
                    text = { Text(controller.res.str(if (controller.remindersOn) "reminders_turn_all_off" else "reminders_turn_all_on")) },
                    leadingIcon = { Icon(if (controller.remindersOn) AppIcons.NotificationsOff else AppIcons.Notifications, contentDescription = null) },
                    onClick = { moreOpen = false; controller.chooseRemindersOn(!controller.remindersOn) },
                )
                // How the notes of the folder are shown (Android D300): here too, beside the sorting; the one in use is ticked.
                listOf(Triple("cards", "note_view_cards", AppIcons.ViewCards), Triple("list", "note_view_list", AppIcons.ViewList), Triple("grid", "note_view_grid", AppIcons.ViewGrid)).forEach { (view, label, icon) ->
                    DropdownMenuItem(
                        text = { Text(controller.res.str(label)) },
                        leadingIcon = { Icon(icon, contentDescription = null) },
                        trailingIcon = { if (controller.noteView == view) Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = { moreOpen = false; controller.chooseNoteView(view) },
                    )
                }
                state.selectedFolderId?.let { id -> item(s.newNote) { actions.open(EditDialog.NewNote(id)) }() }
                HorizontalDivider()
                item(controller.res.str("home_import_note")) {
                    chooseFile(controller.res.str("home_import_note"), save = false)?.let(controller::importNote)
                }()
                item(s.importBackup, actions.importBackup)()
                item(s.exportBackup, actions.exportBackup)()
                item(s.openDataFolder, actions.openDataFolder)()
                HorizontalDivider()
                item(s.about, actions.about)()
            }
        }
        IconButton(onClick = controller::openSettings) { Icon(Icons.Filled.Settings, contentDescription = s.settings) }
    }
}

/** The home screen of the phone (D50): folder cards in a strip on top, the notes of the open folder below, "New note". */
@Composable
fun HomeScreen(s: Strings, state: UiState, controller: NotioController, actions: HomeActions) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            // The search panel stands above the list, not in it (Android D204).
            if (state.searchOpen) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val side = ((maxWidth - 1000.dp) / 2).coerceAtLeast(0.dp)
                    SearchPanel(state, controller, Modifier.padding(start = 16.dp + side, end = 16.dp + side, top = 8.dp))
                }
            }
            // A folder of tasks: its tasks by status (D118).
            if (!state.searchOpen && (state.taskCounts.isNotEmpty() || state.taskFilter.isNotEmpty() || state.taskDone != null)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val side = ((maxWidth - 1000.dp) / 2).coerceAtLeast(0.dp)
                    TaskStateChips(state.taskFilter, controller::toggleTaskFilter, Modifier.padding(start = 16.dp + side, end = 16.dp + side, top = 8.dp), state.taskCounts, done = state.taskDone, onDone = controller::setTaskDone)
                }
            }
            val searching = controller.searchQuery(state).active
            val folderColors = remember(state.folders) { state.folders.associate { it.folder.id to it.folder.colorHex } }
            // Drag and drop (D141): while the mouse drags, the order is kept here; it is saved when the button is let go.
            val columnState = androidx.compose.foundation.lazy.rememberLazyListState()
            var noteOrder by remember { mutableStateOf<List<String>?>(null) }
            val shownNotes = remember(state.notes, noteOrder) { arranged(state.notes, noteOrder) { it.id } }
            LaunchedEffect(state.notes) { if (noteOrder != null && state.notes.map { it.id } == noteOrder) noteOrder = null }
            // The note just made (D257): once it is in the list, the list goes to it and its card is framed for a moment.
            val justCreated by controller.justCreated.collectAsState()
            LaunchedEffect(justCreated, shownNotes) {
                val id = justCreated ?: return@LaunchedEffect
                val index = shownNotes.indexOfFirst { it.id == id }
                if (index < 0) return@LaunchedEffect
                // The strip of folders is the first item of the list; the notes follow it.
                columnState.animateScrollToItem(1 + index)
                kotlinx.coroutines.delay(2_500)
                controller.justCreated.value = null
            }
            val noteDrag = rememberDragReorder(
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
                onDrop = { noteOrder?.let { controller.reorderNotes(it) } },
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // On a wide window the list keeps a readable width, like a tablet does.
                val side = ((maxWidth - 1000.dp) / 2).coerceAtLeast(0.dp)
                // The grid (Android D300): two cards in a row on a narrow window, up to four on a wide one.
                val gridColumns = ((maxWidth.coerceAtMost(1000.dp) - 32.dp) / 300.dp).toInt().coerceIn(2, 4)
                // The rows of the list, made when the notes, the view or the width change — not on every pass over the list.
                val perRow = if (controller.noteView == "grid") gridColumns else 1
                val noteRows = remember(shownNotes, perRow) { shownNotes.chunked(perRow) }
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = columnState,
                        contentPadding = PaddingValues(start = 16.dp + side, end = 16.dp + side, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "folders") {
                            // The folder strip's own background (D110).
                            Box {
                                BackgroundLayer(com.notio.shared.domain.BackgroundSlot.FOLDERS, Modifier.matchParentSize())
                                FolderStrip(s, state, controller, actions)
                            }
                        }
                        when {
                            // The first start (Android onboarding, D03, D47): nothing can be made before a folder exists.
                            state.loaded && state.folders.isEmpty() -> item(key = "no-folders") {
                                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource("onboarding_title"), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                                    Text(stringResource("onboarding_subtitle"), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                                    Button(onClick = { actions.open(EditDialog.NewFolder) }) { Text(stringResource("folder_create_button")) }
                                    Text(s.noFolders, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            searching && state.notes.isEmpty() -> item(key = "no-results") {
                                Text(s.nothingFound, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
                            }
                            state.loaded && state.notes.isEmpty() -> item(key = "empty") {
                                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(s.noNotes, style = MaterialTheme.typography.titleMedium)
                                    Text(s.emptyNotesHint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        // One note in a row, or as many as fit in the grid (Android D300); the grid has no grips and no colour headers.
                        val grid = controller.noteView == "grid"
                        val compactList = controller.noteView == "list"
                        items(noteRows.size, key = { noteRows[it].first().id }) { rowIndex ->
                            val row = noteRows[rowIndex]
                            androidx.compose.foundation.layout.Row(
                                if (noteDrag.draggedKey == row.first().id) noteDrag.itemModifier(row.first().id) else Modifier.animateItem(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                            row.forEachIndexed { column, note ->
                            val index = rowIndex * perRow + column
                            Column(Modifier.weight(1f)) {
                                // Sorted by colour: the name of the colour group stands over its first note (D196); none while dragging.
                                if (noteOrder == null && !grid) state.noteGroups[index]?.let { ColorGroupHeader(it, note.colorHex, Modifier.padding(top = if (index == 0) 0.dp else 8.dp)) }
                                NoteCard(
                                    s, note, folderColors[note.folderId], state.tags[note.id].orEmpty(), note.id in state.withReminder,
                                    state.pictures[note.id], onClick = { controller.openNote(note.id) },
                                    modifier = if (note.id == justCreated) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier,
                                    menu = { NoteMenu(s, note, controller, actions.open) },
                                    readerActions = noteMenuItems(s, note, controller, actions.open),
                                    compact = compactList,
                                    onCopy = { controller.copyNote(note.id) },
                                    selecting = controller.selection.active && !searching,
                                    checked = note.id in controller.selection.picked,
                                    onToggle = { controller.selection.toggle(note.id) },
                                    dragHandle = if (searching || controller.selection.active || grid) null else ({
                                        val label = stringResource("cd_drag_handle")
                                        Box(Modifier.size(40.dp).then(noteDrag.handleModifier(note.id)).semantics { contentDescription = label + " " + note.title }, contentAlignment = Alignment.Center) { MoveHandleIcon() }
                                    }),
                                )
                            }
                            }
                            repeat(perRow - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    // Above the New note button, as on the phone (D250).
                    QuickScroll(columnState, bottomPadding = 96.dp)
                }
            }
        }
        val folderId = state.selectedFolderId
        // The selection mode (D167) ends when the search opens or another folder is chosen; picks of notes gone are dropped.
        val selection = controller.selection
        LaunchedEffect(state.searchOpen, folderId) { if (selection.active && (state.searchOpen || folderId != controller.selectionFolderId)) selection.end() }
        LaunchedEffect(state.notes) { selection.retain(state.notes.map { it.id }) }
        var confirmDeleteMany by remember { mutableStateOf(false) }
        if (selection.active) {
            SelectionBar(
                count = selection.picked.size, total = state.notes.size,
                onSelectAll = { selection.selectAll(state.notes.map { it.id }) }, onClear = selection::clear,
                onDelete = { confirmDeleteMany = true }, onDone = selection::end,
                modifier = Modifier.align(Alignment.BottomCenter),
                hide = run {
                    val toHide = HideMany.targets(state.notes, selection.picked, hidden = true)
                    val toShow = HideMany.targets(state.notes, selection.picked, hidden = false)
                    HideMany(toHide.size, toShow.size, onHide = { controller.setNotesHidden(toHide, true) }, onShow = { controller.setNotesHidden(toShow, false) })
                },
            )
        }
        if (confirmDeleteMany) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDeleteMany = false },
                title = { Text(stringResource("home_delete_many_title")) },
                text = { Text(stringResource("home_delete_many_message", selection.picked.size)) },
                confirmButton = {
                    TextButton(onClick = { confirmDeleteMany = false; controller.trashNotes(selection.picked.toList()) }) {
                        Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteMany = false }) { Text(stringResource("action_cancel")) } },
            )
        }
        // The five icons at the bottom left (Android D300, as in FitEasy): cards / compact list / grid, sorting, time stamps.
        var stampsOpen by remember { mutableStateOf(false) }
        if ((folderId != null || state.searchOpen) && !selection.active) {
            androidx.compose.material3.Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 24.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), tonalElevation = 3.dp, shadowElevation = 3.dp,
            ) {
                androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconChoice(AppIcons.ViewCards, stringResource("note_view_cards"), controller.noteView == "cards", { controller.chooseNoteView("cards") })
                    IconChoice(AppIcons.ViewList, stringResource("note_view_list"), controller.noteView == "list", { controller.chooseNoteView("list") })
                    IconChoice(AppIcons.ViewGrid, stringResource("note_view_grid"), controller.noteView == "grid", { controller.chooseNoteView("grid") })
                    IconAction(AppIcons.Sort, stringResource("sort_title"), { actions.open(EditDialog.Sort) })
                    IconAction(AppIcons.Schedule, stringResource("settings_stamps"), { stampsOpen = true })
                }
            }
        }
        if (stampsOpen) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { stampsOpen = false },
                title = { Text(stringResource("settings_stamps")) },
                text = { Column { StampOptions() } },
                confirmButton = { TextButton(onClick = { stampsOpen = false }) { Text(stringResource("action_close")) } },
            )
        }
        if (folderId != null && !selection.active) {
            // An icon alone (Android D298): the mouse over it says "New note", a screen reader too.
            androidx.compose.foundation.layout.Box(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
                HoverTip(s.newNote) {
                    androidx.compose.material3.FloatingActionButton(onClick = { actions.open(EditDialog.NewNote(folderId)) }) {
                        Icon(Icons.Filled.Add, contentDescription = s.newNote)
                    }
                }
            }
        }
    }
}

/** The strip of folder cards (190 dp each, as on the phone); the mouse wheel scrolls it sideways, a card is dragged by its grip (D141). */
@Composable
private fun FolderStrip(s: Strings, state: UiState, controller: NotioController, actions: HomeActions) {
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    var folderOrder by remember { mutableStateOf<List<String>?>(null) }
    val shown = remember(state.folders, folderOrder) { arranged(state.folders, folderOrder) { it.folder.id } }
    LaunchedEffect(state.folders) { if (folderOrder != null && state.folders.map { it.folder.id } == folderOrder) folderOrder = null }
    val folderDrag = rememberDragReorder(
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
        onDrop = { folderOrder?.let { controller.reorderFolders(it) } },
    )
    val searching = controller.searchQuery(state).active
    val scope = rememberCoroutineScope()
    // The strip stays on the open folder (Android D163): when it opens off screen, the strip goes to it.
    LaunchedEffect(state.selectedFolderId, shown.size) {
        val index = shown.indexOfFirst { it.folder.id == state.selectedFolderId }
        if (index < 0 || folderDrag.draggedKey != null) return@LaunchedEffect
        val visible = rowState.layoutInfo.visibleItemsInfo
        val fully = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= rowState.layoutInfo.viewportEndOffset }
        if (!fully) runCatching { rowState.animateScrollToItem(index) }
    }
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth()
            .draggable(rememberDraggableState { d -> scope.launch { rowState.scrollBy(-d) } }, Orientation.Horizontal)
            .padding(vertical = 4.dp),
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(shown.size, key = { shown[it].folder.id }) { index ->
            val item = shown[index]
            Row(
                (if (folderDrag.draggedKey == item.folder.id) folderDrag.itemModifier(item.folder.id) else Modifier.animateItem()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (folderOrder == null) state.folderGroups[index]?.let { ColorGroupHeader(it, item.folder.colorHex, Modifier.padding(start = if (index == 0) 4.dp else 8.dp, end = 8.dp)) }
                FolderCard(
                    s, item.folder, item.noteCount, selected = item.folder.id == state.selectedFolderId && !state.searchOpen, featured = item.featured,
                    onClick = { controller.selectFolder(item.folder.id) }, modifier = Modifier.width(190.dp).widthIn(max = 190.dp),
                    menu = { FolderMenu(s, item, controller, actions.open) },
                    readerActions = folderMenuItems(s, item, controller, actions.open),
                    dragHandle = if (searching) null else ({
                        val label = stringResource("cd_drag_handle")
                        Box(Modifier.size(36.dp).then(folderDrag.handleModifier(item.folder.id)).semantics { contentDescription = label + " " + item.folder.name }, contentAlignment = Alignment.Center) { MoveHandleIcon() }
                    }),
                )
            }
        }
        item(key = "new-folder") {
            // An icon alone (Android D298): the mouse over it says "New folder".
            IconAction(AppIcons.NewFolder, s.newFolder, onClick = { actions.open(EditDialog.NewFolder) }, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** [items] in the order of [ids] while a drag is going on (the phone's `arranged`). */
private fun <T> arranged(items: List<T>, ids: List<String>?, id: (T) -> String): List<T> {
    if (ids == null) return items
    val byId = items.associateBy(id)
    return ids.mapNotNull { byId[it] } + items.filter { id(it) !in ids }
}
