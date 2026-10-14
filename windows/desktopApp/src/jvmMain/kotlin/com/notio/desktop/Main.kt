package com.notio.desktop

import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.notio.desktop.theme.NotioTheme
import com.notio.desktop.theme.isDark
import com.notio.shared.AppInfo
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    if (SelfTest.runIfAsked()) return
    Glyphs.warmUp()
    AutoStart.startedBySystem = AutoStart.ARG in args
    val paths = com.notio.shared.data.AppPaths.default()
    // Before anything is written there (D134): on Linux the folder of the data is for its person alone.
    paths.keepPrivate()
    // One Notio per data folder (D45): a second start shows the window of the first and ends.
    if (SingleInstance.enabled(paths.root) && !SingleInstance.claim(paths.root)) {
        // A start that ends here leaves a line in the log, and says so when the running Notio does not answer (D95): before,
        // it ended without a word and Notio "did not open".
        if (SingleInstance.otherAnswered) Launcher.log("INFO", "a second start: the running Notio was asked to show its window")
        else Launcher.notAnswering()
        return
    }
    CrashGuard.install(paths)
    // Before anything of Compose: how the window is drawn (D83).
    Rendering.configure(paths.root)
    // A freeze of the window is written to the app log with where it happened (D72).
    FreezeWatch.install()
    // A moved portable folder: its start-with-Windows value is pointed here again (D61), off the start path.
    Thread(AutoStart::repair, "notio-autostart").apply { isDaemon = true }.start()
    notioApp()
}

private fun notioApp() = application {
    val controller = remember {
        NotioController().also {
            // Not for a person (D125: the app has no demo data any more): `-Dnotio.loadDemo=true` fills an empty data folder with
            // the sample notes at start. Only the build uses it, to train the start-up cache on a window with notes in it.
            if (System.getProperty("notio.loadDemo") == "true") it.loadDemo {}
        }
    }
    val s = controller.strings
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // The window can go and Notio stay in the tray, so reminders still ring (D25).
    // Started with Windows (D61): straight into the tray when so chosen, the window comes from the tray icon.
    // A desktop without a tray (GNOME as it comes, WSL; D129) has nowhere to hide a window and bring it back from: there
    // "into the tray" is "minimised", Notio stays in the list of windows and goes on running.
    val trayThere = DesktopNotice.trayThere
    var windowVisible by remember { mutableStateOf(!(AutoStart.startedBySystem && controller.autoStartInTray && trayThere)) }
    val trayState = androidx.compose.ui.window.rememberTrayState()
    TrayHolder.state = trayState
    // A recording still running is stopped and saved before Notio ends (the user, 2026-09-28).
    val quit = { controller.finishRecordingsThen { controller.close(); SingleInstance.release(); exitApplication() } }
    /** The window back from the tray, in front (the tray, a second start of Notio, D45), at once on AWT (D70). */
    var mainWindow by remember { mutableStateOf<androidx.compose.ui.awt.ComposeWindow?>(null) }
    fun showWindow() { windowVisible = true; mainWindow?.let(WindowRestore::show) }
    /** Out of sight and still running: into the tray, or minimised where there is no tray. */
    fun hideWindow() {
        if (trayThere) windowVisible = false
        else mainWindow?.let { w -> w.extendedState = w.extendedState or java.awt.Frame.ICONIFIED }
    }
    // Started by the system "into the tray" on a desktop without one: minimised as soon as the window is there.
    androidx.compose.runtime.LaunchedEffect(mainWindow) {
        if (mainWindow != null && !trayThere && AutoStart.startedBySystem && controller.autoStartInTray) hideWindow()
    }
    SingleInstance.onShow = { showWindow() }
    // The installer asks before it replaces the files (D72): ended as by Exit, a running recording saved first.
    SingleInstance.onQuit = { com.notio.shared.AppLog.i("app", "asked to end by the installer"); quit() }
    // Notio started again by itself (the database is converted at the start, D46): the same program with the same arguments.
    controller.restartApp = {
        val self = ProcessHandle.current().info()
        val command = self.command().orElse(null)
        controller.close()
        SingleInstance.release()
        if (command != null) runCatching { ProcessBuilder(listOf(command) + self.arguments().orElse(emptyArray()).toList()).start() }
            .onFailure { com.notio.shared.AppLog.critical("app", "Notio could not start itself again", it) }
        exitApplication()
    }
    // Read through a derived state: the whole app level is composed again only when the text itself changes, not on every change of the data.
    val uiState = controller.state.collectAsState()
    val trayText by remember { androidx.compose.runtime.derivedStateOf { trayTooltip(controller, uiState.value) } }
    Tray(
        icon = remember { appIcon() } ?: androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color(0xFF1E5FD8)),
        state = trayState,
        // The phone's quiet status notification (Android D266): how many reminders are on and when the next rings, the tasks.
        tooltip = trayText,
        // A click on a reminder's notification lands here too: the calendar on that day with the reminder framed (Android D267).
        onAction = { showWindow(); controller.takeNotificationTarget()?.let { (at, id) -> controller.openCalendarAt(at, id) } },
        // The right click on the tray icon (DESKTOP-ONLY, D45): open, a new folder, a new note in the chosen folder, reminders, help, exit.
        menu = {
            // While a reminder's melody plays, Stop comes first (Android D293; the Windows balloon of the tray has no buttons).
            if (controller.reminders.sounding.collectAsState().value != null) {
                Item(Glyphs.alarm + " " + controller.res.str("reminder_stop_sound"), onClick = controller.reminders::silence)
                Separator()
            }
            Item(controller.res.str("app_name"), onClick = { showWindow() })
            Separator()
            Item(s.newFolder, onClick = { showWindow(); controller.requestDialog(EditDialog.NewFolder) })
            Item(s.newNote, onClick = {
                showWindow()
                val folder = controller.state.value.selectedFolderId ?: controller.state.value.folders.firstOrNull()?.folder?.id
                controller.requestDialog(if (folder != null) EditDialog.NewNote(folder) else EditDialog.NewFolder)
            })
            Item(controller.res.str("reminders_title"), onClick = { showWindow(); controller.openReminders(null) })
            // All reminders off or on again, without opening the window (Android D318).
            Item(controller.res.str(if (controller.remindersOn) "reminders_turn_all_off" else "reminders_turn_all_on"), onClick = { controller.chooseRemindersOn(!controller.remindersOn) })
            Item(controller.res.str("help_title"), onClick = { showWindow(); controller.openHelp() })
            // Stop of a screen recording also here, the floating Stop may be off (D249 on the desktop).
            if (controller.screenRecording.recording) Item(controller.res.str("rec_action_stop"), onClick = controller::stopScreenRecording)
            else if (controller.recordingKeeper.active != null) Item(controller.res.str("rec_action_stop"), onClick = controller.recordingKeeper::requestStop)
            Separator()
            Item(s.exit, onClick = quit)
        },
    )
    // "Always ready for a sync" (D104): the port opens with the app; a question of another device brings the window and the Sync
    // page, where it is answered.
    LaunchedEffect(controller) { controller.startSyncIfAlways() }
    // The assistant's Telegram bot listens from the start of Notio, also when the window stays in the tray (D148).
    LaunchedEffect(controller) { controller.assistant }
    if (controller.syncAlways) {
        val syncPrompt by controller.syncEngine.prompt.collectAsState()
        LaunchedEffect(syncPrompt) {
            if (syncPrompt != null) {
                showWindow()
                if (controller.state.value.screen != Screen.Sync) controller.openSync()
            }
        }
    }
    // One left click on the tray icon opens the window too (D70); AWT itself answers only a double click.
    LaunchedEffect(Unit) { repeat(20) { if (WindowRestore.openOnClick { showWindow() }) return@LaunchedEffect; kotlinx.coroutines.delay(250) } }
    AlarmWindows(controller) { noteId -> showWindow(); controller.openNote(noteId) }
    // While the screen is recorded (D147, D36): a small Stop window over all others, the phone's floating Stop button.
    // The floating Stop can be turned off (Settings -> Recordings, Android D249); Stop stays in the note and the tray.
    val floatingStop by controller.appSettings.floatingStopButton.collectAsState()
    // A voice or camera recording shows it too while the window is minimised or in the tray (Android D255).
    val keptRecording = controller.recordingKeeper.active
    val screenRecording = controller.screenRecording.recording
    if (floatingStop && (screenRecording || (keptRecording != null && (controller.windowAway || !windowVisible)))) {
        androidx.compose.ui.window.Window(
            onCloseRequest = { if (screenRecording) controller.stopScreenRecording() else controller.recordingKeeper.requestStop() },
            title = controller.res.str("rec_notification_title"), alwaysOnTop = true, resizable = false,
            state = androidx.compose.ui.window.rememberWindowState(size = DpSize(300.dp, 150.dp), position = androidx.compose.ui.window.WindowPosition(Alignment.TopEnd)),
        ) {
            // Never in the recording itself (Windows WDA_EXCLUDEFROMCAPTURE; the phone's worry of D249: the button seen or stopping the capture).
            LaunchedEffect(window) { setExcludedFromCapture(window, true) }
            NotioTheme(controller.state.collectAsState().value.theme) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        var seconds by remember { mutableStateOf(0L) }
                        val since = if (screenRecording) controller.screenRecording.startedAt else keptRecording?.startedAt ?: System.currentTimeMillis()
                        LaunchedEffect(since) { while (true) { seconds = (System.currentTimeMillis() - since) / 1000; kotlinx.coroutines.delay(500) } }
                        Text(controller.res.str("rec_recording") + "  " + formatDuration((seconds * 1000).toInt()), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { if (screenRecording) controller.stopScreenRecording() else controller.recordingKeeper.requestStop() }) {
                            Text(controller.res.str("rec_action_stop"))
                        }
                    }
                }
            }
        }
    }
    Window(
        visible = windowVisible,
        onCloseRequest = {
            // "Close" can put the window into the tray (DESKTOP-ONLY, D45); Exit of the tray menu ends Notio then.
            if (controller.closeToTray) {
                hideWindow()
                if (!controller.closeToTrayTold && trayThere) {
                    controller.closeToTrayTold = true
                    DesktopNotice.send(trayState, SystemName.app, controller.res.str("desktop_close_to_tray_notice"))
                }
            } else if (controller.keepInTray && controller.reminders.hasActive()) {
                hideWindow()
                if (trayThere) runCatching { trayState.sendNotification(androidx.compose.ui.window.Notification(SystemName.app, controller.res.str("tray_still_running").takeIf { it != "tray_still_running" } ?: s.trayStillRunning)) }
            } else quit()
        },
        title = SystemName.app,
        icon = remember { appIcon() },
        state = rememberWindowState(size = DpSize(1100.dp, 820.dp)),
        // Home / End and Ctrl+Home / Ctrl+End that no text field took: to the beginning / the end of the list on the screen (D250).
        onKeyEvent = { e ->
            e.type == KeyEventType.KeyDown && dialog == null && when (e.key) {
                Key.MoveHome -> ScrollKeys.go(top = true)
                Key.MoveEnd -> ScrollKeys.go(top = false)
                else -> false
            }
        },
        // Esc goes back, like the phone's Back button.
        onPreviewKeyEvent = { e ->
            if (dialog == null && e.type == KeyEventType.KeyDown && controller.keyHook(KeyPress(e.key, e.isCtrlPressed, e.isShiftPressed))) true
            else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && controller.state.value.screen != Screen.Home && dialog == null) {
                controller.backByPerson(); true
            } else false
        },
    ) {
        val chrome = rememberWindowChrome()
        androidx.compose.runtime.SideEffect { mainWindow = window }
        // How the window is drawn (DirectX, OpenGL or by the processor): a slow way explains a slow window (D72).
        LaunchedEffect(window) { com.notio.shared.AppLog.i("window", "drawn through " + window.renderApi) }
        // The app lock counts the time the window is away: minimised or hidden in the tray (the phone's ON_STOP / ON_START).
        androidx.compose.runtime.DisposableEffect(window) {
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowIconified(e: java.awt.event.WindowEvent?) { controller.windowAway = true; controller.lockGate.onLeft() }
                override fun windowDeiconified(e: java.awt.event.WindowEvent?) { controller.windowAway = false; controller.lockGate.onReturned() }
            }
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener) }
        }
        LaunchedEffect(windowVisible) {
            if (windowVisible) controller.lockGate.onReturned()
            else {
                controller.lockGate.onLeft()
                // Hours in the tray hold nothing that is not shown (D70): the pictures go, and the memory they held goes back to Windows.
                PictureCache.clear()
                Thread({ System.gc() }, "notio-trim").apply { isDaemon = true }.start()
            }
        }
        // While the lock is on, the window is kept out of screenshots and screen recordings of other apps (the phone's FLAG_SECURE, D124).
        val lockOn by controller.lockGate.enabled.collectAsState()
        LaunchedEffect(lockOn) { setExcludedFromCapture(window, lockOn) }
        // The screen recorder steps the window aside while it records, and brings it back after (D36).
        controller.stepAside = { away -> window.isMinimized = away; if (!away) window.toFront() }
        val scope = rememberCoroutineScope()
        fun chooseFile(title: String, save: Boolean, name: String? = null): File? {
            val fd = FileDialog(window, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
            if (name != null) fd.file = name
            fd.isVisible = true
            return fd.file?.let { File(fd.directory, it) }
        }
        val actions = HomeActions(
            importBackup = { chooseFile(s.importBackup, save = false)?.let { dialog = DialogState.Import(it, controller.isProtected(it)) } },
            // With the backup password of Settings on, the export is protected with it without asking (Android D134).
            exportBackup = {
                val saved = controller.backupPasswords.exportPassword()
                if (saved == null) dialog = DialogState.Export
                else chooseFile(s.exportBackup, save = true, name = controller.suggestedExportName(true))?.let { target ->
                    controller.exportBackup(target, saved) { r ->
                        dialog = r.fold(
                            { DialogState.Message(s.done, s.exportDone(target.path, it.filesWritten, it.filesSkipped)) },
                            { DialogState.Message(s.error, it.message ?: it.toString()) },
                        )
                    }
                }
            },
            openDataFolder = { runCatching { controller.paths.root.mkdirs(); Desktop.getDesktop().open(controller.paths.root) } },
            about = { dialog = DialogState.Message(AppInfo.NAME, "${AppInfo.SHOWN}\n${s.developer}\n\n${s.aboutText}") },
            comingSoon = { scope.launch { snackbar.showNotice(s.comingSoon) } },
        )

        NotioApp(controller, chrome, actions, snackbar)

        val state by controller.state.collectAsState()
        NotioTheme(state.theme) {
            when (val d = dialog) {
                null -> Unit
                is DialogState.Message -> MessageDialog(s, d.title, d.text) { dialog = null }
                is DialogState.Confirm -> ConfirmDialog(s, d.title, d.text, onNo = { dialog = null }) { dialog = null; d.action() }
                is DialogState.Import -> ImportDialog(s, d.file.name, d.protected, onCancel = { dialog = null }) { mode, password ->
                    dialog = null
                    controller.importBackup(d.file, mode, password) { r -> dialog = DialogState.fromImport(s, r) }
                }
                DialogState.Export -> ExportDialog(s, onCancel = { dialog = null }) { password ->
                    dialog = null
                    val target = chooseFile(s.exportBackup, save = true, name = controller.suggestedExportName(!password.isNullOrEmpty()))
                    if (target != null) {
                        controller.exportBackup(target, password) { r ->
                            dialog = r.fold(
                                { DialogState.Message(s.done, s.exportDone(target.path, it.filesWritten, it.filesSkipped)) },
                                { DialogState.Message(s.error, it.message ?: it.toString()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The app icon (the Android launcher icon on its blue square, D14). */
private fun appIcon(): androidx.compose.ui.graphics.painter.Painter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("notio.png")!!.use { it.readBytes() }
    androidx.compose.ui.graphics.painter.BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

/** The whole window content: the top bar of the current screen (in the title bar) and the screen, as on the phone. */
@Composable
fun NotioApp(controller: NotioController, chrome: WindowChrome, actions: HomeActions, snackbar: SnackbarHostState) {
    val state by controller.state.collectAsState()
    val s = controller.strings
    var editDialog by remember { mutableStateOf<EditDialog?>(null) }
    // A dialog asked for by the tray menu (D45).
    val requested by controller.requestedDialog.collectAsState()
    LaunchedEffect(requested) { requested?.let { editDialog = it; controller.requestedDialog.value = null } }
    // The editor of the open note puts its Undo / Redo here; the arrows stand in the top bar (the phone's LocalUndoSlot, D197).
    val undoSlot = remember { mutableStateOf<UndoTarget?>(null) }
    // The search inside the open note (Android D238, D246 - D248): the bar under the top bar, the editor finds and marks the words.
    val openNoteId = (state.screen as? Screen.NoteView)?.id
    // One search per screen: the open note, the trash (D253) or the calendar (D252).
    val searchKey: String? = openNoteId ?: when (state.screen) { Screen.Trash -> "trash"; Screen.Calendar -> "calendar"; else -> null }
    val noteSearch = remember(searchKey) { NoteSearch() }
    val trashFilled = state.trash.isNotEmpty() || state.trashFolders.isNotEmpty() || state.mediaTrash.isNotEmpty()
    val searchable = (openNoteId != null && state.detail?.note?.type in SEARCHABLE_KINDS) ||
        (state.screen == Screen.Trash && trashFilled) || state.screen == Screen.Calendar
    val searchHint: String? = when (state.screen) {
        Screen.Trash -> controller.res.str("trash_search")
        Screen.Calendar -> controller.res.str("calendar_search")
        else -> null
    }
    // The keys of the computer: Ctrl+F opens the search (of the note, or of the notes on the main screen), F3 / Shift+F3 go to the
    // next / previous match, Esc closes an open search before it goes back.
    androidx.compose.runtime.SideEffect {
        controller.keyHook = { e ->
            when {
                e.ctrl && e.key == Key.F && searchable -> { noteSearch.open = true; noteSearch.focusRequests++; true }
                e.ctrl && e.key == Key.F && controller.state.value.screen == Screen.Home -> {
                    if (!controller.state.value.searchOpen) controller.toggleSearch(); true
                }
                e.key == Key.F3 && noteSearch.open -> { if (e.shift) noteSearch.previous() else noteSearch.next(); true }
                e.key == Key.Escape && noteSearch.open -> { noteSearch.close(); true }
                else -> false
            }
        }
    }
    val acts = remember(actions) {
        HomeActions(actions.importBackup, actions.exportBackup, actions.openDataFolder, actions.about, actions.comingSoon) { editDialog = it }
    }
    // Messages of the controller (a refused edit, "Moved to the trash" with Undo) as the phone's snackbar.
    LaunchedEffect(controller) {
        controller.messages.collect { m ->
            // In a job of its own: a newer message takes the place of the one shown, nothing waits for it (Android D360).
            launch {
                val r = snackbar.showNotice(m.text, actionLabel = m.undo?.let { s.undo })
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) m.undo?.invoke()
            }
        }
    }
    NotioTheme(state.theme) {
      androidx.compose.runtime.CompositionLocalProvider(
        LocalRes provides controller.res, LocalUnits provides remember(state.detail) { controller.units() }, LocalUndoSlot provides undoSlot, LocalNoteSearch provides noteSearch,
        LocalController provides controller,
        LocalDateTimeFormats provides state.display.formats, LocalTaskChange provides controller::setTask, LocalTaskDelete provides controller::askDeleteNote, LocalCopyText provides controller::copyText, LocalPreviewLines provides state.display.previewLines, LocalCardTint provides state.display.cardTint, LocalBudgetRoots provides state.budgetRoots,
        LocalMediaPreview provides state.display.mediaPreview, LocalDrawingPreview provides state.display.drawingPreview,
        LocalCommentDisplay provides state.display.comments, LocalStampDisplay provides state.display.stamps,
        LocalNoteFooter provides { state.detail?.note?.let { NoteFooterStampLines(it, state.display.footer) } },
      ) {
        val dark = state.theme.isDark()
        LaunchedEffect(dark) { chrome.setDarkControls(dark) }
        // The app lock (D124, D37): nothing of the notes is composed while it is locked.
        val locked by controller.lockGate.locked.collectAsState()
        if (locked) {
            androidx.compose.runtime.CompositionLocalProvider(LocalAppLock provides controller.appLock, LocalLockGate provides controller.lockGate) {
                Column(Modifier.fillMaxSize()) {
                    TitleBarRow(chrome) { Text(s.appName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp).weight(1f)) }
                    Box(Modifier.weight(1f)) { LockScreen(controller.lockGate, controller.appLock, Unit) }
                }
            }
            return@CompositionLocalProvider
        }
        // Settings -> Backgrounds (D110): the app's own colour or picture behind every screen; the page itself is then clear.
        val pageColor = MaterialTheme.colorScheme.background
        androidx.compose.runtime.CompositionLocalProvider(
            LocalBackgrounds provides state.display.backgrounds, LocalBackgroundImages provides controller.backgroundImages, LocalPageColor provides pageColor,
        ) {
        Box(Modifier.fillMaxSize()) {
        BackgroundLayer(com.notio.shared.domain.BackgroundSlot.APP, Modifier.matchParentSize())
        Surface(Modifier.fillMaxSize(), color = if (state.display.backgrounds.has(com.notio.shared.domain.BackgroundSlot.APP)) androidx.compose.ui.graphics.Color.Transparent else pageColor) {
            Column(Modifier.fillMaxSize()) {
                when (val screen = state.screen) {
                    Screen.Home -> HomeTopBar(s, state, controller, chrome, acts)
                    is Screen.NoteView -> BackTopBar(s, state.detail?.note?.title ?: "", chrome, controller::backByPerson) {
                        undoSlot.value?.let { UndoRedoButtons(it) }
                        if (searchable) IconButton(onClick = { if (noteSearch.open) noteSearch.close() else noteSearch.open = true }) {
                            Icon(Icons.Filled.Search, contentDescription = controller.res.str("note_search_open"))
                        }
                        state.detail?.note?.let { n ->
                            if (n.deletedAt == null) NoteMenu(s, n, controller) { editDialog = it } else TrashNoteMenu(s, n, controller) { editDialog = it }
                        }
                    }
                    Screen.Trash -> BackTopBar(s, s.trash, chrome, controller::backByPerson) {
                        if (searchable) IconButton(onClick = { if (noteSearch.open) noteSearch.close() else noteSearch.open = true }) {
                            Icon(Icons.Filled.Search, contentDescription = controller.res.str("trash_search"))
                        }
                    }
                    Screen.Settings -> BackTopBar(s, SettingsSection.byId(state.settingsSection)?.let { controller.res.str(it.title) } ?: s.settings, chrome, controller::backByPerson)
                    Screen.Help -> BackTopBar(s, controller.res.str("help_title"), chrome, controller::backByPerson)
                    is Screen.Catalog -> BackTopBar(s, controller.res.str(when (screen.kind) { "backgrounds" -> "bg_title"; "lock" -> "lock_settings_title"; "assistant" -> "assistant_title"; "assistant_chat" -> "assistant_chat"; else -> catalogTitle(screen.kind) }), chrome, controller::backByPerson)
                    Screen.Sync -> BackTopBar(s, controller.res.str("settings_sync"), chrome, controller::backByPerson)
                    is Screen.Reminders -> BackTopBar(s, controller.res.str("reminders_title"), chrome, controller::backByPerson)
                    Screen.Calendar -> BackTopBar(s, controller.res.str("calendar_title"), chrome, controller::backByPerson) {
                        IconButton(onClick = { if (noteSearch.open) noteSearch.close() else noteSearch.open = true }) {
                            Icon(Icons.Filled.Search, contentDescription = controller.res.str("calendar_search"))
                        }
                        CalendarTodayButton(controller.calendar)
                    }
                }
                if (searchable && noteSearch.open) NoteSearchBar(noteSearch, Modifier.fillMaxWidth(), hint = searchHint)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val screen = state.screen) {
                        Screen.Home -> HomeScreen(s, state, controller, acts)
                        is Screen.NoteView -> NoteScreen(s, state.detail, screen.edit, controller, actions.comingSoon)
                        Screen.Trash -> TrashScreen(s, state, controller) { editDialog = it }
                        Screen.Settings -> SettingsScreen(s, state, controller, acts)
                        Screen.Sync -> SyncScreen(controller)
                        Screen.Help -> HelpScreen()
                        is Screen.Catalog -> when (screen.kind) {
                            "backgrounds" -> BackgroundsPage(controller)
                            "assistant" -> AssistantPage(controller)
                            "assistant_chat" -> AssistantChatPage(controller)
                            "lock" -> androidx.compose.runtime.CompositionLocalProvider(LocalAppLock provides controller.appLock, LocalLockGate provides controller.lockGate) {
                                LockSettingsScreen(onBack = controller::backByPerson)
                            }
                            else -> CatalogScreen(screen.kind, controller)
                        }
                        is Screen.Reminders -> RemindersScreen(screen.noteId, controller)
                        Screen.Calendar -> CalendarScreen(controller.calendar, { id -> controller.openNote(id, from = Screen.Calendar) }, marked = controller.calendarMarked)
                    }
                    NoticeHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                }
                EditDialogHost(s, state, controller, editDialog) { editDialog = null }
                PreviousCrashDialog()
                // A pressed hint opens the help over whatever is shown (D113, Android D332); the screen says what it is about.
                androidx.compose.runtime.SideEffect { HelpLink.around = HelpLink.topicOf(state.screen, state.detail?.note?.type?.id) }
                HelpOverlay()
            }
        }
        }
        }
      }
    }
}

/** The kinds that can be searched inside (Android D238, D248): Markdown, list, budget, and the names and comments of recordings, photos and videos. */
private val SEARCHABLE_KINDS = setOf(
    com.notio.shared.domain.NoteType.TEXT, com.notio.shared.domain.NoteType.LIST, com.notio.shared.domain.NoteType.BUDGET,
    // The formatted "Note" (D256).
    com.notio.shared.domain.NoteType.WORD,
    com.notio.shared.domain.NoteType.AUDIO, com.notio.shared.domain.NoteType("PHOTO"), com.notio.shared.domain.NoteType("VIDEO"),
    com.notio.shared.domain.NoteType("SCREEN"),
)

/** "Notio", then "Active: 2 · next: …" and / or "Tasks: 5 · open: 2 · overdue: 1" (Android D266). */
private fun trayTooltip(controller: NotioController, state: UiState): String = listOfNotNull(
    AppInfo.NAME,
    state.statusReminders?.let { controller.res.str("status_text", it.count, Format.dateTime(it.nextAt)) },
    state.statusTasks?.let {
        controller.res.str("status_tasks", it.count, it.active) +
            (if (it.overdue > 0) " · " + controller.res.str("status_tasks_overdue", it.overdue) else "")
    },
).joinToString("\n")
