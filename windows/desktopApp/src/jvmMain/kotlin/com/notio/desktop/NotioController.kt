package com.notio.desktop

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.notio.desktop.theme.DEFAULT_CUSTOM_THEME
import com.notio.desktop.theme.ThemeChoice
import com.notio.shared.data.AppPaths
import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.BudgetSession
import com.notio.shared.data.toEntry
import com.notio.shared.data.EditError
import com.notio.shared.data.EditException
import com.notio.shared.data.FolderDeleteMode
import com.notio.shared.data.NotioEditor
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.SettingsStore
import com.notio.shared.domain.BudgetNode
import com.notio.shared.domain.ColorTheme
import com.notio.shared.domain.Folder
import com.notio.shared.domain.FontSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.notio.shared.AppInfo
import com.notio.shared.domain.Note
import com.notio.shared.domain.MediaDocument
import com.notio.shared.domain.MediaItem
import com.notio.shared.domain.MediaKind
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.Reminder
import com.notio.shared.domain.Tag
import com.notio.shared.domain.ThemeMode
import com.notio.shared.transfer.BackupExportReport
import com.notio.shared.transfer.BackupService
import com.notio.shared.transfer.BudgetLogRecord
import com.notio.shared.transfer.DemoBackup
import com.notio.shared.transfer.ImportMode
import com.notio.shared.transfer.ImportReport
import com.notio.shared.transfer.SettingsRecord
import java.io.File
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screens, as on the phone: the home screen, a note, the trash, the settings. */
sealed interface Screen {
    data object Home : Screen
    /** [edit]: opens on the Edit tab (a new note); [from]: where Back goes (the calendar), else the usual place. */
    data class NoteView(val id: String, val edit: Boolean = false, val from: Screen? = null) : Screen
    data object Trash : Screen
    data object Settings : Screen
    data object Sync : Screen
    /** Help and glossary (Android §6.11). */
    data object Help : Screen
    /** A list of Settings -> Notes: "tags", "units" or "currencies". */
    data class Catalog(val kind: String) : Screen
    /** The reminders of all notes, or of one ([noteId]). */
    data class Reminders(val noteId: String?) : Screen
    /** The month calendar of the main screen (Android D236). */
    data object Calendar : Screen
}

/** The featured note on a folder card (D121, §6.4): its title and, for a budget, the chosen totals. */
data class FeaturedInfo(
    val noteId: String, val title: String, val isBudget: Boolean, val layout: com.notio.shared.domain.FeaturedLayout,
    val sumMinor: Long?, val goalMinor: Long?, val remainderMinor: Long?,
)

/** A folder card: the folder, how many notes it holds, its featured note. */
data class FolderItem(
    val folder: Folder,
    val noteCount: Int,
    val featured: FeaturedInfo? = null,
    /** In the trash: the titles of the notes that went with it, one a line — the search of the trash finds them too (D253). */
    val noteTitles: String = "",
)

/** A short message at the bottom of the window, optionally with an Undo (Android snackbar). */
data class UiMessage(val text: String, val undo: (() -> Unit)? = null)

/** Everything the open note shows. */
data class NoteDetail(
    val note: Note,
    val folder: Folder?,
    val tags: List<Tag>,
    val reminders: List<Reminder>,
    val nodes: List<BudgetNode>,
    val log: List<BudgetLogRecord>,
    val files: List<File>,
)

data class UiState(
    val screen: Screen = Screen.Home,
    val folders: List<FolderItem> = emptyList(),
    val selectedFolderId: String? = null,
    val searchOpen: Boolean = false,
    val query: String = "",
    /** The search of the phone (D109, D116): notes, folders or tags, and the tags a note must have. */
    val searchScope: com.notio.shared.domain.SearchScope = com.notio.shared.domain.SearchScope.NOTES,
    val searchTagIds: Set<String> = emptySet(),
    /** Search ticks (Android D246): letters in any case (on), the words as a regular expression (off). */
    val searchIgnoreCase: Boolean = true,
    val searchRegex: Boolean = false,
    /** The statuses chosen in the search and above a folder of tasks (D118), and what the tasks of the folder on show have. */
    val searchTaskStates: Set<com.notio.shared.domain.TaskState> = emptySet(),
    val taskFilter: Set<com.notio.shared.domain.TaskState> = emptySet(),
    /** The tick in the search and in the filter above a folder of tasks (D138): completed, still open, or `null` — both. */
    val searchTaskDone: Boolean? = null,
    val taskDone: Boolean? = null,
    val taskCounts: Map<com.notio.shared.domain.TaskState, Int> = emptyMap(),
    val hasTasks: Boolean = false,
    val allTags: List<Tag> = emptyList(),
    /** The reminders screen (D122): every reminder with its note's title, and the notes a reminder can go on. */
    val reminderRows: List<ReminderRow> = emptyList(),
    val reminderNotes: List<Note> = emptyList(),
    /** How folders and the notes of the open folder are ordered (D137, D158). */
    val folderSort: com.notio.shared.domain.SortOption = com.notio.shared.domain.SortOption.FOLDERS_DEFAULT,
    val noteSort: com.notio.shared.domain.SortOption = com.notio.shared.domain.SortOption.NOTES_DEFAULT,
    /** Sorted by colour: where the header of a colour group goes, by position in the list (D196). */
    val noteGroups: Map<Int, com.notio.shared.domain.ColorFamily> = emptyMap(),
    val folderGroups: Map<Int, com.notio.shared.domain.ColorFamily> = emptyMap(),
    val notes: List<Note> = emptyList(),
    val tags: Map<String, List<Tag>> = emptyMap(),
    val withReminder: Set<String> = emptySet(),
    /** The picture on the card of a drawing or photo note (Android D160). */
    val pictures: Map<String, File> = emptyMap(),
    val trash: List<Note> = emptyList(),
    /** Recordings and photos taken out of their notes (D156), newest first, with where they came from. */
    val mediaTrash: List<MediaTrashRow> = emptyList(),
    /** What the tray icon's tooltip says (the phone's quiet status notification, Android D266): reminders on and the tasks. */
    val statusReminders: com.notio.shared.domain.ReminderSummary? = null,
    val statusTasks: com.notio.shared.domain.TaskSummary? = null,
    /** The top of each shown budget, for the total on its card (Android D159, missed here until 0.9.19). */
    val budgetRoots: Map<String, com.notio.shared.domain.BudgetNode> = emptyMap(),
    /** Folders in the trash, with how many notes went there with them. */
    val trashFolders: List<FolderItem> = emptyList(),
    val detail: NoteDetail? = null,
    val busy: Boolean = false,
    val theme: ThemeChoice = ThemeChoice(),
    val loaded: Boolean = false,
    /** The open page of the settings (Android D145); `null` = the menu of pages. */
    val settingsSection: String? = null,
    /** What the cards and notes show, from the settings (D138, D157, D159, D160, D177, D198). */
    val display: DisplayPrefs = DisplayPrefs(),
)

/** A row of the media trash: the item, whether its note is still there (active, in the trash, gone), whether its file is. */
data class MediaTrashRow(val media: com.notio.shared.transfer.MediaTrashRecord, val noteState: String, val fileExists: Boolean)

/** The settings that change what cards and notes show; read from the backup's settings. */
data class DisplayPrefs(
    val formats: com.notio.shared.domain.DateTimeFormats = com.notio.shared.domain.DateTimeFormats(),
    val stamps: com.notio.shared.domain.StampDisplay = com.notio.shared.domain.StampDisplay(),
    val comments: com.notio.shared.domain.CommentDisplay = com.notio.shared.domain.CommentDisplay(),
    val footer: com.notio.shared.domain.NoteFooterStamps = com.notio.shared.domain.NoteFooterStamps(),
    val previewLines: Int = 2,
    /** How strongly cards show their colour, 0..1 (Android D273). */
    val cardTint: Float = com.notio.shared.domain.CardTint.DEFAULT / 100f,
    val mediaPreview: com.notio.shared.domain.MediaPreviewMode = com.notio.shared.domain.MediaPreviewMode.FIRST,
    val drawingPreview: Boolean = true,
    /** Backgrounds of the app, the folder strip and the kinds of note (D110); a picture whose file is gone is none. */
    val backgrounds: Map<com.notio.shared.domain.BackgroundSlot, com.notio.shared.domain.BackgroundSpec> = emptyMap(),
) {
    companion object {
        fun of(s: SettingsRecord, images: BackgroundImages? = null) = DisplayPrefs(
            formats = com.notio.shared.domain.DateTimeFormats.decode(s.dateTimeFormats),
            stamps = com.notio.shared.domain.StampDisplay.decode(s.stampDisplay),
            comments = com.notio.shared.domain.CommentDisplay.decode(s.commentDisplay),
            footer = com.notio.shared.domain.NoteFooterStamps.decode(s.footerStamps),
            previewLines = (s.previewLines ?: 2).coerceIn(0, 2),
            cardTint = (s.cardTint ?: com.notio.shared.domain.CardTint.DEFAULT).coerceIn(com.notio.shared.domain.CardTint.MIN, com.notio.shared.domain.CardTint.MAX) / 100f,
            mediaPreview = runCatching { com.notio.shared.domain.MediaPreviewMode.valueOf(s.mediaPreview ?: "") }.getOrDefault(com.notio.shared.domain.MediaPreviewMode.FIRST),
            drawingPreview = s.drawingPreview ?: true,
            backgrounds = s.backgrounds.orEmpty().mapNotNull { (k, v) ->
                val slot = runCatching { com.notio.shared.domain.BackgroundSlot.valueOf(k) }.getOrNull() ?: return@mapNotNull null
                val spec = com.notio.shared.domain.BackgroundSpec.decode(v)
                slot to if (spec is com.notio.shared.domain.BackgroundSpec.Image && images?.exists(slot) != true) com.notio.shared.domain.BackgroundSpec.None else spec
            }.toMap(),
        )
    }
}

/**
 * The state holder of the window (MVVM, Android §1.2: no business logic in composables). Database work runs off the UI thread;
 * the composables only read [state] and call the functions here.
 */
class NotioController(val paths: AppPaths = AppPaths.default()) {
    /** Small window state (the last folder, Android D161), kept in the data folder so the portable build leaves no trace. */
    private val uiFile = File(paths.root, "ui.properties")
    private val ui = Properties().apply { runCatching { uiFile.inputStream().use { load(it) } } }

    /**
     * Whether all reminders are on when Notio starts (Android D318). Never chosen on this computer (D109, Android D328): a new
     * installation — no database yet — starts with them off; one that had Notio before, where they were on without a choice,
     * keeps them on. Decided here, before the database is made, and written down at once, so the next start reads the same.
     */
    private val remindersOnAtStart: Boolean = when (ui.getProperty(REMINDERS_ON)) {
        "true" -> true
        "false" -> false
        else -> paths.database.exists().also { on ->
            ui.setProperty(REMINDERS_ON, on.toString())
            runCatching { paths.root.mkdirs() }
            saveUiProps()
        }
    }

    /**
     * A job of the window that fails is written to the log and the others go on. Work that was still reading when Notio ended
     * finds the database closed: that is the end of the app, not an error to show (D129: on Linux the timing let such a read
     * reach the handler of the thread, which is the crash window).
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            if (!closed) com.notio.shared.AppLog.e("app", "a job of the window failed", e)
        },
    )
    /** The database passphrase (DPAPI) and a change planned for the next start (D46). */
    private val dbKeys = com.notio.shared.data.DatabaseKeyStore(File(paths.root, "database-key.json"), com.notio.shared.sync.SecretBox.forThisSystem())
    // A planned encryption, decryption or import of a database file happens here, before the file is opened (D46).
    private val db = com.notio.shared.data.DatabaseStartup.open(paths, dbKeys)

    /** What went wrong with the last planned change of the database file, and which change it was (shown once in Settings -> Data). */
    val lastDatabaseError: Pair<String, String>? = dbKeys.takeLastError()
    private val files = AttachmentStore(paths.attachments)
    private val settings = SettingsStore(paths.settings)
    private val backup = BackupService(db, files, settings)
    private val editor = NotioEditor(db, files)
    private val noteFiles = com.notio.shared.transfer.NoteFileService(
        db, files, AppInfo.VERSION,
        flatten = { png, vector ->
            val document = vector?.let { VectorDocument.decode(it) }
            val shapes = document?.drawableShapes().orEmpty()
            // The sheet's own size (Android D260).
            val (w, h) = document?.sheet() ?: (DrawingRenderer.WIDTH to DrawingRenderer.HEIGHT)
            // The sheet's background goes into the picture (Android D265): a transparent PNG shows black in most apps it is pasted in.
            val background = document?.background() ?: SheetBackground.DEFAULT
            if (png == null && shapes.isEmpty()) null else withContext(Dispatchers.Default) { DrawingRenderer.useFonts(); DrawingRenderer.flatten(png, shapes, w, h, background) }
        },
        toJpeg = { withContext(Dispatchers.Default) { DrawingRenderer.pngToJpeg(it) } },
        vectorIsValid = { VectorDocument.decode(it) != null },
        naming = { com.notio.shared.domain.ExportNaming.decode(settings.read().exportNaming) },
    )

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    private val _state = MutableStateFlow(
        UiState(selectedFolderId = ui.getProperty(LAST_FOLDER), theme = themeOf(settings.read()), display = DisplayPrefs.of(settings.read(), BackgroundImages(File(paths.root, "backgrounds")))),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The Android texts in the chosen language, else the system's (D17); changing the language redraws the window at once. */
    var res: AndroidRes by androidx.compose.runtime.mutableStateOf(AndroidRes.forLanguage(settings.read().languageTag))
        private set

    /** The settings pages' view of the settings (the phone's SettingsDataStore, D29). */
    val appSettings = AppSettings(settings, ui::getProperty, { k, v -> ui.setProperty(k, v); saveUi() }) { next ->
        _state.update { it.copy(theme = themeOf(next), display = DisplayPrefs.of(next, backgroundImages)) }
        Format.formats = com.notio.shared.domain.DateTimeFormats.decode(next.dateTimeFormats)
        if (next.languageTag != language) {
            language = next.languageTag
            res = AndroidRes.forLanguage(next.languageTag)
            strings = stringsFor(next.languageTag)
        }
        purgeExpiredTrash()
    }
    private var language: String? = settings.read().languageTag

    private fun saveUi() {
        saveUiProps()
    }

    /** Removes what has been in the trash longer than the chosen days (Android D65, D140); at start and when the choice changes. */
    fun purgeExpiredTrash() {
        val days = settings.read().trashRetentionDays ?: com.notio.shared.domain.TrashRetention.DEFAULT_DAYS
        if (days <= com.notio.shared.domain.TrashRetention.NEVER) return
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                // Recordings and photos in the media trash expire the same way (Android MediaTrashUseCases.purgeExpired).
                val cutoff = com.notio.shared.domain.TrashRetention.cutoff(System.currentTimeMillis(), days) ?: Long.MIN_VALUE
                val (old, keep) = db.mediaTrash().partition { it.deletedAt <= cutoff }
                old.forEach { mediaFile(it)?.delete() }
                if (old.isNotEmpty()) db.replaceMediaTrash(keep)
                old.size + runCatching { editor.purgeExpired(days) }.getOrDefault(0)
            }
            if (removed > 0) {
                com.notio.shared.AppLog.i("trash", "removed $removed expired items")
                refresh()
            }
        }
    }

    /** The pictures of the backgrounds (D110). */
    val backgroundImages = BackgroundImages(File(paths.root, "backgrounds"))

    fun setBackground(slot: com.notio.shared.domain.BackgroundSlot, spec: com.notio.shared.domain.BackgroundSpec) {
        if (spec !is com.notio.shared.domain.BackgroundSpec.Image) backgroundImages.delete(slot)
        appSettings.update { s ->
            val map = s.backgrounds.orEmpty().toMutableMap()
            if (spec == com.notio.shared.domain.BackgroundSpec.None) map.remove(slot.name) else map[slot.name] = spec.encode()
            s.copy(backgrounds = map.takeIf { it.isNotEmpty() })
        }
    }

    fun setBackgroundImage(slot: com.notio.shared.domain.BackgroundSlot, file: File) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { backgroundImages.import(slot, file) }
            if (ok) setBackground(slot, com.notio.shared.domain.BackgroundSpec.Image(System.currentTimeMillis())) else say(res.str("bg_error"))
        }
    }

    /**
     * The app lock (Android D124, D129, D185, D191, D192; AGENT.md D37): the phone's rules in `shared/.../lock`, the hashes in
     * `lock.json`, the authenticator key and the mail password sealed with DPAPI. It is asked for at start and after the window was
     * away (minimised or in the tray) longer than the chosen time.
     */
    val appLock = com.notio.shared.lock.AppLockUseCases(
        store = com.notio.shared.lock.FileLockStore(File(paths.root, "lock.json")),
        secrets = com.notio.shared.sync.SecretBox.forThisSystem(),
        mailer = com.notio.shared.lock.SmtpClient(),
        elapsed = com.notio.shared.lock.BootClock::millis,
        smtp = com.notio.shared.lock.FileSmtpStore(File(paths.root, "smtp.json"), com.notio.shared.sync.SecretBox.forThisSystem()),
        wiper = com.notio.shared.lock.AppDataWiper {
            // The person chose "erase after too many wrong tries" (Android D-lockout): the data folder goes, then Notio ends.
            com.notio.shared.AppLog.fatal("lock", "too many wrong tries: the data is erased as chosen")
            runCatching { db.close() }
            paths.root.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            kotlin.system.exitProcess(0)
        },
    )
    val lockGate = com.notio.shared.lock.AppLockGate(appLock).also { kotlinx.coroutines.runBlocking { it.start() } }

    /** The password of exported backups, sealed with DPAPI (Android D134). */
    val backupPasswords = BackupPasswords(File(paths.root, "backup-password.json"))

    // ---- the lists of Settings -> Notes: tags, units, currencies (Android D92, D109, D170) ----

    fun openCatalog(kind: String) = _state.update { it.copy(screen = Screen.Catalog(kind)) }

    /** The assistant (D145 - D148, the branch `assistant`): its settings, chat, access of notes, journal and bot, for the window. */
    private val assistantLazy = lazy {
        val bridge = AssistantSettingsBridge(appSettings)
        AssistantDesk(
            db, editor, scope, ui::getProperty, { k, v -> ui.setProperty(k, v); saveUiProps() }, com.notio.shared.sync.SecretBox.forThisSystem(),
            object : com.notio.shared.assistant.AssistantHost {
                override fun folderSort(folderId: String): String? = perFolderSorts()[folderId]
                override fun setFolderSort(folderId: String, sort: String?) {
                    val next = if (sort == null) perFolderSorts() - folderId else perFolderSorts() + (folderId to sort)
                    ui.setProperty(FOLDER_SORTS, kotlinx.serialization.json.Json.encodeToString(next))
                    saveUiProps()
                }
                override fun openNote(noteId: String) { scope.launch { this@NotioController.openNote(noteId) } }
                override fun openFolder(folderId: String) { scope.launch { selectFolder(folderId); _state.update { it.copy(screen = Screen.Home) } } }
                override fun reminders(noteId: String): List<Pair<String, Long>> =
                    db.remindersOf(noteId).filter { it.isEnabled && it.status == com.notio.shared.domain.ReminderStatus.SCHEDULED }.map { it.id to it.triggerAt }
                override fun addReminder(noteId: String, at: Long): String? = kotlinx.coroutines.runBlocking {
                    (this@NotioController.reminders.useCases.create(noteId, com.notio.shared.domain.ReminderDraft(triggerAt = at)) as? com.notio.shared.domain.DomainResult.Success)?.value?.id
                }
                override fun removeReminder(reminderId: String) { kotlinx.coroutines.runBlocking { this@NotioController.reminders.useCases.delete(reminderId) } }
                override fun settings(appearance: Boolean) = bridge.list(appearance)
                override fun setting(name: String) = bridge.get(name)
                override fun setSetting(name: String, value: String) = bridge.set(name, value)
                override fun locked(): Boolean = lockGate.locked.value
            },
            words = { name, arg -> if (arg.isEmpty()) res.str(name) else res.str(name, arg) },
        ) { scope.launch { refresh() } }
    }
    val assistant: AssistantDesk by assistantLazy

    /** Every tag with how many notes have it. */
    fun tagRows(): List<Pair<Tag, Int>> {
        val byNote = db.tagAssignments()
        return db.tags().map { tag -> tag to byNote.values.count { list -> list.any { it.id == tag.id } } }
    }

    fun customUnits(): List<com.notio.shared.domain.CustomUnit> = settings.read().customUnits.orEmpty()

    fun customCurrencies(): List<com.notio.shared.domain.CustomCurrency> = settings.read().customCurrencies.orEmpty()

    /** Adds a unit; `null` when done, else the error to show (the dialog stays open). */
    fun addUnit(name: String): com.notio.shared.domain.DomainError? =
        when (val r = com.notio.shared.domain.Catalogs.addUnit(customUnits(), name, UnitLabels.builtInNames(res))) {
            is com.notio.shared.domain.DomainResult.Success -> {
                appSettings.update { it.copy(customUnits = customUnits() + r.value) }
                say(res.str("units_added", r.value.name)); null
            }
            is com.notio.shared.domain.DomainResult.Failure -> r.error
        }

    /** Renames a unit of one's own (Android D277); `null` when done, else the error to show (the dialog stays open). */
    fun renameUnit(id: String, name: String): com.notio.shared.domain.DomainError? =
        when (val r = com.notio.shared.domain.Catalogs.renameUnit(customUnits(), id, name, UnitLabels.builtInNames(res))) {
            is com.notio.shared.domain.DomainResult.Success -> {
                appSettings.update { it.copy(customUnits = r.value) }
                say(res.str("units_renamed", name.trim())); null
            }
            is com.notio.shared.domain.DomainResult.Failure -> r.error
        }

    fun deleteUnit(id: String) {
        val unit = customUnits().firstOrNull { it.id == id }
        val error = com.notio.shared.domain.Catalogs.unitDeleteError(customUnits(), id, db.notes())
        if (error != null) { say(res.str(error.messageRes())); return }
        appSettings.update { s -> s.copy(customUnits = customUnits().filterNot { it.id == id }) }
        say(res.str("units_deleted", unit?.name.orEmpty()))
    }

    /** The names no currency of one's own may take: the built-in ones, their symbols and their names in the language of the app. */
    private fun reservedCurrencyNames() = com.notio.shared.domain.BuiltInCurrency.ALL.flatMap {
        listOfNotNull(com.notio.shared.domain.BuiltInCurrency.symbolOf(it), it, runCatching { java.util.Currency.getInstance(it).getDisplayName(java.util.Locale.forLanguageTag(res.language)) }.getOrNull())
    }

    /**
     * Renames a currency of one's own (Android D277). A budget keeps the text of its currency in itself (so it reads right on another
     * device), so every budget that uses it — trashed ones too — gets the new text; the time the note was changed stays.
     */
    fun renameCurrency(id: String, name: String, done: () -> Unit) {
        when (val r = com.notio.shared.domain.Catalogs.renameCurrency(customCurrencies(), id, name, reservedCurrencyNames())) {
            is com.notio.shared.domain.DomainResult.Failure -> say(res.str(r.error.messageRes()))
            is com.notio.shared.domain.DomainResult.Success -> {
                val clean = name.trim()
                appSettings.update { it.copy(customCurrencies = r.value) }
                edit({
                    db.transaction {
                        db.notes().filter { it.type == NoteType.BUDGET && com.notio.shared.domain.BudgetCurrency.idOf(it.extras) == id }
                            .forEach { note -> com.notio.shared.domain.BudgetCurrency.withLabel(note.extras, clean)?.let { db.upsertNote(note.copy(extras = it)) } }
                    }
                }) { say(res.str("currencies_renamed", clean)); refresh(); done() }
            }
        }
    }

    fun addCurrency(name: String): com.notio.shared.domain.DomainError? {
        val reserved = reservedCurrencyNames()
        return when (val r = com.notio.shared.domain.Catalogs.addCurrency(customCurrencies(), name, reserved)) {
            is com.notio.shared.domain.DomainResult.Success -> {
                appSettings.update { it.copy(customCurrencies = customCurrencies() + r.value) }
                say(res.str("currencies_added", r.value.name)); null
            }
            is com.notio.shared.domain.DomainResult.Failure -> r.error
        }
    }

    fun deleteCurrency(id: String) {
        val currency = customCurrencies().firstOrNull { it.id == id }
        val error = com.notio.shared.domain.Catalogs.currencyDeleteError(customCurrencies(), id, db.notes())
        if (error != null) { say(res.str(error.messageRes())); return }
        appSettings.update { s -> s.copy(customCurrencies = customCurrencies().filterNot { it.id == id }) }
        say(res.str("currencies_deleted", currency?.name.orEmpty()))
    }

    fun openHelp() = _state.update { it.copy(screen = Screen.Help) }

    fun openSettingsSection(id: String?) = _state.update { it.copy(screen = Screen.Settings, settingsSection = id) }

    /** Sync with the phones and the other computers (Android D226, AGENT.md D09); it listens only while its page is open. */
    val syncStore = com.notio.shared.sync.SyncStore(File(paths.root, "sync.json"))
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncEngineLazy = lazy {
        com.notio.shared.sync.SyncEngine(
            syncStore, com.notio.shared.sync.JmdnsDiscovery { syncStore.log(it) },
            com.notio.shared.sync.DesktopSyncData(db, backup) { refresh() },
            File(paths.root, "tmp-sync"), syncScope,
        )
    }
    val syncEngine: com.notio.shared.sync.SyncEngine by syncEngineLazy

    fun openSync() = _state.update { it.copy(screen = Screen.Sync) }

    /**
     * "Always ready for a sync" (D104, Android D323): off by default, kept on this computer. On: Notio listens for a sync all the
     * time it runs — the window open on any page or in the tray — so a paired phone can sync with the computer whenever it needs.
     */
    var syncAlways: Boolean by androidx.compose.runtime.mutableStateOf(ui.getProperty(SYNC_ALWAYS) == "true")
        private set

    fun chooseSyncAlways(on: Boolean) {
        syncAlways = on
        ui.setProperty(SYNC_ALWAYS, on.toString())
        saveUiProps()
        com.notio.shared.AppLog.i("sync", "always ready for a sync: " + if (on) "on" else "off")
        // The Sync page listens by itself while it is open; elsewhere the port follows the switch.
        if (on) syncEngine.startListening() else if (_state.value.screen != Screen.Sync) syncEngine.stopListening()
    }

    /** At the start of the app: with "always ready" on, the port opens without the Sync page. */
    fun startSyncIfAlways() {
        if (syncAlways && !closed) runCatching { syncEngine.startListening() }
    }

    /** Reminders (D122, D130, D150): the phone's rules, rung by this process (ReminderService). */
    val reminders = ReminderService(db).also { service ->
        service.saving = ui.getProperty("economy") == "true"
        service.enabled = remindersOnAtStart
        service.onChanged = { refresh() }
        // A reminder that rings is passed on to Telegram when its note says so (D148).
        service.onRing = { noteId, title -> assistant.remindToTelegram(noteId, title) }
        service.start()
    }

    fun noteComment(noteId: String): String? = db.note(noteId)?.comment

    /** Keep running in the tray when the window closes while reminders are armed (AGENT.md D25; on unless turned off). */
    var keepInTray: Boolean
        get() = ui.getProperty(KEEP_IN_TRAY) != "false"
        set(value) {
            ui.setProperty(KEEP_IN_TRAY, value.toString())
            saveUiProps()
        }

    /** The Close button of the window puts it into the tray (DESKTOP-ONLY, AGENT.md D45; off unless turned on). */
    var closeToTray: Boolean
        get() = ui.getProperty(CLOSE_TO_TRAY) == "true"
        set(value) { ui.setProperty(CLOSE_TO_TRAY, value.toString()); saveUiProps() }

    /** The tray said once that Notio goes on there; not said again. */
    var closeToTrayTold: Boolean
        get() = ui.getProperty(CLOSE_TO_TRAY_TOLD) == "true"
        set(value) { ui.setProperty(CLOSE_TO_TRAY_TOLD, value.toString()); saveUiProps() }

    /** Only one Notio on this data folder (DESKTOP-ONLY, D45; on unless turned off); takes effect at the next start. */
    var singleInstance: Boolean
        get() = SingleInstance.isEnabled(ui)
        set(value) { SingleInstance.setEnabled(ui, value); saveUiProps() }

    /** Started with Windows, Notio stays in the tray until the icon is clicked (DESKTOP-ONLY, D61; on unless turned off). */
    var autoStartInTray: Boolean
        get() = ui.getProperty(AUTO_START_IN_TRAY) != "false"
        set(value) { ui.setProperty(AUTO_START_IN_TRAY, value.toString()); saveUiProps() }

    /**
     * The settings of this computer (`ui.properties`) are written beside the file first and then put in its place in one step
     * (the pass of 2026-10-05): written straight into the file, a power cut or a kill in the middle left it empty and every
     * switch of the window — the tray, one Notio at a time, the saver, the view of the notes — went back to its default.
     */
    private fun saveUiProps() {
        runCatching {
            val bytes = java.io.ByteArrayOutputStream().also { ui.store(it, null) }.toByteArray()
            com.notio.shared.data.SafeFile.write(uiFile, bytes)
        }.onFailure { com.notio.shared.AppLog.e("app", "the settings of this computer could not be saved", it) }
    }

    /** A dialog asked for from outside the window (the tray menu, D45); the window opens it and clears it. */
    val requestedDialog = MutableStateFlow<EditDialog?>(null)

    fun requestDialog(dialog: EditDialog) { requestedDialog.value = dialog }

    /** The question "Delete the note?" for a note known by its id (D142: the bin of a task on its card and in View). */
    fun askDeleteNote(id: String) {
        val note = _state.value.notes.firstOrNull { it.id == id } ?: _state.value.detail?.note?.takeIf { it.id == id } ?: return
        requestDialog(EditDialog.DeleteNote(note))
    }

    // ---- database encryption (Android D184; AGENT.md D46) ----

    val databaseEncrypted: Boolean get() = dbKeys.enabled

    fun databasePassphrase(): String? = dbKeys.passphrase()

    /** Set by the window content: the keys it handles before the window's own (Ctrl+F, F3, Esc of a search); `true` when handled. */
    var keyHook: (KeyPress) -> Boolean = { false }

    /** Set by the window: ends Notio and starts it again (the database is converted at the start). */
    var restartApp: () -> Unit = {}

    /** Turns the encryption on or off: planned for the start, then Notio restarts. */
    fun changeDatabaseEncryption(on: Boolean) {
        if (on) dbKeys.planEncrypt(com.notio.shared.data.DatabaseCipher.newPassphrase()) else dbKeys.planDecrypt()
        com.notio.shared.AppLog.i("database", if (on) "encryption planned" else "decryption planned")
        restartApp()
    }

    /** The database file as it is (plain or encrypted) into [target]; `false` when it could not be written. */
    fun exportDatabaseFile(target: File): Boolean = runCatching { target.outputStream().use { db.copyFileTo(it) } }
        .onFailure { com.notio.shared.AppLog.e("database", "the database file could not be exported", it) }.isSuccess

    /** Checks [chosen] for import ("password", "newer", "invalid" or `null` when it is fine), see [DatabaseStartup.stageImport]. */
    fun checkDatabaseImport(chosen: File, password: String?): String? = com.notio.shared.data.DatabaseStartup.stageImport(paths, chosen, password)

    /** Replaces the database with the checked file at the next start, which comes at once. */
    fun importDatabaseFile(password: String?) {
        val staged = com.notio.shared.data.DatabaseStartup.stagedImport(paths)
        val encrypted = com.notio.shared.data.DatabaseCipher.isEncrypted(staged)
        dbKeys.planImport(staged, password?.takeIf { encrypted && it.isNotEmpty() })
        restartApp()
    }

    fun openReminders(noteId: String?) = _state.update { it.copy(screen = Screen.Reminders(noteId)) }

    private fun reminderEdit(block: suspend () -> com.notio.shared.domain.DomainResult<*>, onDone: () -> Unit = {}) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            refresh()
            if (result is com.notio.shared.domain.DomainResult.Failure) say(res.str(result.error.messageRes())) else onDone()
        }
    }

    fun createReminder(noteId: String, draft: com.notio.shared.domain.ReminderDraft, onDone: () -> Unit) =
        reminderEdit({ reminders.useCases.create(noteId, draft) }, onDone)

    fun updateReminder(id: String, draft: com.notio.shared.domain.ReminderDraft, onDone: () -> Unit) =
        reminderEdit({ reminders.useCases.update(id, draft) }, onDone)

    fun cancelReminder(id: String) = reminderEdit({ reminders.useCases.cancel(id) })

    /** The on / off switch of a reminder (Android D268). */
    fun setReminderEnabled(id: String, enabled: Boolean) = reminderEdit({ reminders.useCases.setEnabled(id, enabled) })

    fun deleteReminder(id: String) = reminderEdit({ reminders.useCases.delete(id) })

    fun confirmReminder(id: String) = reminderEdit({ reminders.useCases.onConfirmed(id) })

    /** The language of the phone's backup when there is one, else the system's. */
    var strings: Strings by androidx.compose.runtime.mutableStateOf(stringsFor(settings.read().languageTag))
        private set

    private fun stringsFor(tag: String?) = Strings(AndroidRes.forLanguage(tag))

    init {
        Format.formats = com.notio.shared.domain.DateTimeFormats.decode(settings.read().dateTimeFormats)
        refresh()
        purgeExpiredTrash()
        // The merges whose time passed while Notio was closed happen now (the phone: at the next start, D237).
        scope.launch {
            val done = withContext(Dispatchers.IO) { runCatching { budgetMerge.runDue() }.getOrDefault(0) }
            if (done > 0) refresh()
        }
    }

    // ---- budget merges (Android D237) ----

    /** Merges an income / expense into its account (D237). */
    val budgetMerge by lazy { com.notio.shared.data.BudgetMergeService(db, editor) }

    fun mergeNow(noteId: String): com.notio.shared.data.MergeOutcome =
        runCatching { budgetMerge.merge(noteId) }.onFailure { com.notio.shared.AppLog.e("budget", "a merge failed", it) }
            .getOrDefault(com.notio.shared.data.MergeOutcome.NotMergeable)

    fun undoMerge(e: com.notio.shared.transfer.BudgetLogRecord): Boolean = runCatching {
        budgetMerge.undo(e.toEntry())
    }.onFailure { com.notio.shared.AppLog.e("budget", "a merge could not be undone", it) }.getOrDefault(false)

    private var mergeTimer: kotlinx.coroutines.Job? = null
    private var mergeTimerAt: Long? = null

    /**
     * One timer for the next merge while Notio runs (the phone's exact alarm): set from the notes each refresh already has, changed only
     * when that time changes; nothing runs while no merge is planned.
     */
    private fun armMerges(next: Long?) {
        if (next == mergeTimerAt && mergeTimer?.isActive == true) return
        mergeTimer?.cancel()
        mergeTimerAt = next
        mergeTimer = next?.let { at ->
            scope.launch {
                // In steps by the wall clock: a wait does not count the time the computer sleeps (the code check of 2026-10-01).
                while (true) {
                    val left = at - System.currentTimeMillis()
                    if (left <= 0) break
                    kotlinx.coroutines.delay(minOf(left, 300_000L))
                }
                val done = withContext(Dispatchers.IO) { runCatching { budgetMerge.runDue() }.getOrDefault(0) }
                mergeTimerAt = null
                if (done > 0) com.notio.shared.AppLog.i("budget", "$done merge(s) at their time")
                refresh()
            }
        }
    }

    // ---- navigation ----

    fun openNote(id: String, edit: Boolean = false, from: Screen? = null) {
        _state.update { it.copy(screen = Screen.NoteView(id, edit, from), detail = null) }
        scope.launch {
            val detail = withContext(Dispatchers.IO) { loadDetail(id) }
            _state.update { it.copy(detail = detail) }
        }
    }

    fun openTrash() = _state.update { it.copy(screen = Screen.Trash) }

    /** The calendar (D236): its month and chosen day stay while the app runs, as on the phone while its screen is kept. */
    val calendar by lazy { CalendarModel(state, scope) }

    fun openCalendar() {
        calendarMarked = ""
        _state.update { it.copy(screen = Screen.Calendar) }
    }

    /** The reminder framed in the calendar's day list (Android D267): the one whose notification was clicked. */
    var calendarMarked: String by androidx.compose.runtime.mutableStateOf("")
        private set

    /** The calendar on the day of [at], the reminder [reminderId] framed there (a click on its notification, Android D267). */
    fun openCalendarAt(at: Long, reminderId: String) {
        calendar.showDayOf(at)
        calendarMarked = reminderId
        _state.update { it.copy(screen = Screen.Calendar) }
    }

    /**
     * The reminder of the last notification: a click on a Windows notification reaches only the tray icon's action, so the next
     * action of the icon within [NOTIFICATION_CLICK_MS] opens the calendar on it, once (Android D267).
     */
    private var notified: Pair<Long, String>? = null

    /** A reminder's notification went out now (it rang now: the day the calendar shows). */
    fun reminderNotified(reminderId: String) { notified = System.currentTimeMillis() to reminderId }

    /** The calendar target of a notification clicked just now, if any; taken once. */
    fun takeNotificationTarget(): Pair<Long, String>? {
        val n = notified ?: return null
        notified = null
        return n.takeIf { System.currentTimeMillis() - it.first <= NOTIFICATION_CLICK_MS }
    }

    fun openSettings() = _state.update { it.copy(screen = Screen.Settings, settingsSection = null) }

    /** When the person's Back last acted: the second Back of a double click must not leave the next screen too (Android D285). */
    private var lastBackAt = 0L

    /** Back from the window (the arrow of the top bar, Esc, the mouse's Back button); the app's own steps back use [back]. */
    fun backByPerson() {
        val now = System.nanoTime()
        if (lastBackAt != 0L && now - lastBackAt < DOUBLE_BACK_NANOS) return
        lastBackAt = now
        back()
    }

    fun back() = _state.update {
        it.copy(
            screen = when {
                it.screen is Screen.NoteView && it.detail?.note?.deletedAt != null -> Screen.Trash
                it.screen is Screen.NoteView && (it.screen as Screen.NoteView).from != null -> (it.screen as Screen.NoteView).from!!
                // The chat with the assistant is opened from the main screen and goes back there.
                it.screen is Screen.Catalog && (it.screen as Screen.Catalog).kind == "assistant_chat" -> Screen.Home
                it.screen == Screen.Sync || it.screen == Screen.Help || it.screen is Screen.Catalog -> Screen.Settings
                it.screen == Screen.Settings && it.settingsSection != null -> Screen.Settings
                it.screen is Screen.Reminders && (it.screen as Screen.Reminders).noteId != null -> Screen.NoteView((it.screen as Screen.Reminders).noteId!!)
                else -> Screen.Home
            },
            detail = null,
            settingsSection = if (it.screen == Screen.Settings) null else it.settingsSection,
        )
    }

    /** The data folder: files of the app's own that are read before the database opens (how the window is drawn, D83). */
    val dataDir: java.io.File get() = paths.root

    /**
     * The battery saver (Android D308, as in FitEasy): kept on this computer. While it is on the reminders' clock is looked at
     * less often and the decorative movement is off.
     */
    var powerSaving: Boolean by androidx.compose.runtime.mutableStateOf(ui.getProperty(POWER_SAVING) == "true")
        private set

    fun choosePowerSaving(on: Boolean) {
        powerSaving = on
        reminders.saving = on
        ui.setProperty(POWER_SAVING, on.toString())
        saveUiProps()
        com.notio.shared.AppLog.i("power", "battery saver " + if (on) "on" else "off")
    }

    /** All reminders on or off at once (Android D318): kept on this computer; the menus and the Reminders page change it. */
    var remindersOn: Boolean by androidx.compose.runtime.mutableStateOf(remindersOnAtStart)
        private set

    fun chooseRemindersOn(on: Boolean) {
        remindersOn = on
        ui.setProperty(REMINDERS_ON, on.toString())
        saveUiProps()
        reminders.turn(on)
        say(res.str(if (on) "reminders_all_on" else "reminders_all_off_hint"))
    }

    /** How the notes of a folder are shown (Android D300): "cards", "list" (one line each) or "grid". Kept on this computer. */
    var noteView: String by androidx.compose.runtime.mutableStateOf(ui.getProperty(NOTE_VIEW) ?: "cards")
        private set

    fun chooseNoteView(view: String) {
        noteView = view
        ui.setProperty(NOTE_VIEW, view)
        saveUiProps()
    }

    /** Opens a folder; the app opens on the last one next time (Android D161). */
    fun selectFolder(id: String) {
        ui.setProperty(LAST_FOLDER, id)
        saveUiProps()
        _state.update { it.copy(selectedFolderId = id) }
        refresh()
    }

    fun toggleSearch() {
        _state.update { it.copy(searchOpen = !it.searchOpen, query = "", searchTagIds = emptySet(), searchTaskStates = emptySet(), searchTaskDone = null, searchScope = com.notio.shared.domain.SearchScope.NOTES) }
        refresh()
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        refresh()
    }

    fun setSearchScope(scope: com.notio.shared.domain.SearchScope) {
        _state.update { it.copy(searchScope = scope) }
        refresh()
    }

    fun toggleSearchTag(id: String) {
        _state.update { it.copy(searchTagIds = if (id in it.searchTagIds) it.searchTagIds - id else it.searchTagIds + id) }
        refresh()
    }

    /** A search is on when it has words or chosen tags (Android `SearchQuery.active`). */
    fun searchQuery(s: UiState = _state.value) = com.notio.shared.domain.SearchQuery(
        s.searchScope, if (s.searchOpen) s.query else "", if (s.searchOpen) s.searchTagIds else emptySet(), s.searchIgnoreCase, s.searchRegex,
        if (s.searchOpen) s.searchTaskStates else emptySet(),
        if (s.searchOpen) s.searchTaskDone else null,
    )

    /** A status of the search (D118): chosen statuses narrow it to the tasks that have one of them. */
    fun toggleSearchTaskState(state: com.notio.shared.domain.TaskState) {
        _state.update { it.copy(searchTaskStates = if (state in it.searchTaskStates) it.searchTaskStates - state else it.searchTaskStates + state) }
        refresh()
    }

    /** The tick in the search (D138): the completed tasks, the ones still open, or `null` — it asks nothing. */
    fun setSearchTaskDone(done: Boolean?) {
        _state.update { it.copy(searchTaskDone = done) }
        refresh()
    }

    /** The tick in the filter above a folder of tasks (D138). */
    fun setTaskDone(done: Boolean?) {
        _state.update { it.copy(taskDone = done) }
        refresh()
    }

    /** A status of the filter above a folder of tasks (D118); none chosen shows every task. */
    fun toggleTaskFilter(state: com.notio.shared.domain.TaskState) {
        _state.update { it.copy(taskFilter = if (state in it.taskFilter) it.taskFilter - state else it.taskFilter + state) }
        refresh()
    }

    /** The status or the priority of a task changed on its card (D117). */
    fun setTask(id: String, task: com.notio.shared.domain.TaskDocument) {
        val note = _state.value.notes.firstOrNull { it.id == id && it.type == NoteType.TASK } ?: return
        if (note.extras == task.encode()) return
        edit({ editor.saveNote(note.copy(extras = task.encode()), withExtras = true) })
    }

    fun setSearchIgnoreCase(on: Boolean) { _state.update { it.copy(searchIgnoreCase = on) }; refresh() }

    fun setSearchRegex(on: Boolean) { _state.update { it.copy(searchRegex = on) }; refresh() }

    // ---- data ----

    /** Every refresh has a number; a result that comes back after a newer refresh started is dropped (fast clicks). */
    private var generation = 0

    fun refresh() {
        val mine = ++generation
        scope.launch {
            val s = _state.value
            // A read that fails (the database closing as the app ends, a damaged row) is logged; the screen keeps what it shows.
            val loaded = withContext(Dispatchers.IO) {
                runCatching { load(s.selectedFolderId, searchQuery(s), folderSort(), noteSortFor(s.selectedFolderId), s.taskFilter, s.taskDone) }
                    .onFailure { if (!closed) com.notio.shared.AppLog.e("ui", "the notes could not be read", it) }.getOrNull()
            } ?: return@launch
            if (mine != generation) return@launch
            _state.update {
                it.copy(
                    folders = loaded.folders, selectedFolderId = loaded.selected, notes = loaded.notes, tags = loaded.tags,
                    withReminder = loaded.withReminder, pictures = loaded.pictures, trash = loaded.trash, trashFolders = loaded.trashFolders, loaded = true,
                    mediaTrash = loaded.mediaTrash, statusReminders = loaded.statusReminders, statusTasks = loaded.statusTasks, budgetRoots = loaded.budgetRoots,
                    allTags = loaded.allTags, folderSort = loaded.folderSort, noteSort = loaded.noteSort,
                    reminderRows = loaded.reminderRows, reminderNotes = loaded.reminderNotes,
                    noteGroups = loaded.noteGroups, folderGroups = loaded.folderGroups,
                    taskCounts = loaded.taskCounts, hasTasks = loaded.hasTasks,
                )
            }
            armMerges(loaded.nextMergeAt)
        }
    }

    private class Loaded(
        val folders: List<FolderItem>, val selected: String?, val notes: List<Note>, val tags: Map<String, List<Tag>>,
        val withReminder: Set<String>, val trash: List<Note>, val pictures: Map<String, File>, val trashFolders: List<FolderItem>,
        val allTags: List<Tag>,
        val folderSort: com.notio.shared.domain.SortOption,
        val noteSort: com.notio.shared.domain.SortOption,
        val noteGroups: Map<Int, com.notio.shared.domain.ColorFamily>,
        val folderGroups: Map<Int, com.notio.shared.domain.ColorFamily>,
        val reminderRows: List<ReminderRow>,
        val reminderNotes: List<Note>,
        val mediaTrash: List<MediaTrashRow>,
        val nextMergeAt: Long?,
        val statusReminders: com.notio.shared.domain.ReminderSummary?,
        val statusTasks: com.notio.shared.domain.TaskSummary?,
        val budgetRoots: Map<String, com.notio.shared.domain.BudgetNode>,
        val taskCounts: Map<com.notio.shared.domain.TaskState, Int>,
        val hasTasks: Boolean,
    )

    private fun load(selected: String?, query: com.notio.shared.domain.SearchQuery, folderSort: com.notio.shared.domain.SortOption, noteSortOf: com.notio.shared.domain.SortOption, taskFilter: Set<com.notio.shared.domain.TaskState> = emptySet(), taskDone: Boolean? = null): Loaded {
        val all = db.folders()
        val active = all.filter { it.deletedAt == null }
        val activeIds = active.mapTo(HashSet()) { it.id }
        // A note is in the trash when it or its folder is (Android D29, D81).
        val (live, trashed) = db.notes().partition { it.deletedAt == null && it.folderId in activeIds }
        val counts = live.groupingBy { it.folderId }.eachCount()
        val tagsByNote = db.tagAssignments()
        val searchingFolders = query.active && query.scope == com.notio.shared.domain.SearchScope.FOLDERS
        val nodesByNote = HashMap<String, List<com.notio.shared.domain.BudgetNode>>()
        fun featuredOf(f: Folder): FeaturedInfo? {
            val n = f.featuredNoteId?.let { id -> live.firstOrNull { it.id == id } } ?: return null
            val root = if (n.type == com.notio.shared.domain.NoteType.BUDGET) nodesByNote.getOrPut(n.id) { db.budgetNodesOf(n.id) }.firstOrNull { it.parentId == null } else null
            fun shows(m: com.notio.shared.domain.FeaturedMetric) = root != null && f.shows(m)
            return FeaturedInfo(
                n.id, n.title, root != null, f.featuredLayout,
                root?.cachedSumMinor?.takeIf { shows(com.notio.shared.domain.FeaturedMetric.SUM) },
                root?.cachedGoalMinor?.takeIf { shows(com.notio.shared.domain.FeaturedMetric.GOAL) },
                root?.cachedRemainderMinor?.takeIf { shows(com.notio.shared.domain.FeaturedMetric.REMAINDER) },
            )
        }
        val folders = com.notio.shared.domain.Sorting.folders(
            if (searchingFolders) com.notio.shared.domain.NoteSearch.filterFolders(active, query) else active, folderSort, counts,
        ).map { FolderItem(it, counts[it.id] ?: 0, featuredOf(it)) }
        val folderId = selected?.takeIf { it in activeIds } ?: folders.firstOrNull()?.folder?.id
        // A folder of tasks shows the tasks of the chosen statuses (D118); the chips count all of its tasks.
        val inFolder = live.filter { it.folderId == folderId }
        val tasksFolder = active.firstOrNull { it.id == folderId }?.kind == com.notio.shared.domain.FolderKind.TASKS
        val taskCounts = if (tasksFolder && !query.active) com.notio.shared.domain.TaskFilter.counts(inFolder) else emptyMap()
        val shown = when {
            searchingFolders -> emptyList()
            query.active -> com.notio.shared.domain.NoteSearch.filterNotes(live, tagsByNote, query)
            tasksFolder -> inFolder.filter { com.notio.shared.domain.TaskFilter.matches(it, taskFilter, taskDone) }
            else -> inFolder
        }
        val noteSort = noteSortOf
        val notes = com.notio.shared.domain.Sorting.notes(shown, noteSort, tagsByNote)
        val noteGroups = if (noteSort.key == com.notio.shared.domain.SortKey.COLOR && !query.active) {
            com.notio.shared.domain.colorGroupStarts(notes, { it.isPinned }, { it.colorHex })
        } else emptyMap()
        val folderGroups = if (folderSort.key == com.notio.shared.domain.SortKey.COLOR && !query.active) {
            com.notio.shared.domain.colorGroupStarts(folders, { it.folder.isPinned }, { it.folder.colorHex })
        } else emptyMap()
        val now = System.currentTimeMillis()
        val waiting = db.reminders().filter { it.isEnabled && (it.triggerAt > now || it.snoozedUntil > now) }.mapTo(HashSet()) { it.noteId }
        val pictures = notes.filter { it.type.id in PICTURE_KINDS }.mapNotNull { n ->
            files.list(n.id).firstOrNull { it.extension.lowercase() in setOf("png", "jpg", "jpeg") }?.let { n.id to it }
        }.toMap()
        // A note trashed together with its folder is shown inside the folder's entry, not on its own (Android trash screen).
        val trashedFolders = all.filter { it.deletedAt != null }
        val withFolder = trashed.filter { n -> trashedFolders.any { it.id == n.folderId && it.deletedAt == n.deletedAt } }.mapTo(HashSet()) { it.id }
        val trashFolders = trashedFolders.sortedByDescending { it.deletedAt }
            .map { f ->
                val gone = trashed.filter { it.id in withFolder && it.folderId == f.id }
                FolderItem(f, gone.size, noteTitles = gone.joinToString("\n") { it.title })
            }
        val single = trashed.filter { it.id !in withFolder }.sortedByDescending { it.deletedAt ?: 0 }
        // The reminders screen: upcoming first (soonest first), then the finished ones (latest first); none of a trashed note.
        val titles = live.associate { it.id to it.title }
        // Switched-off reminders stay among the upcoming ones (Android D268), so they can be switched back on where they were.
        val (activeRows, doneRows) = db.reminders().mapNotNull { r -> titles[r.noteId]?.let { ReminderRow(r, it) } }.partition { it.isPending }
        val reminderRows = activeRows.sortedBy { it.reminder.triggerAt } + doneRows.sortedByDescending { it.reminder.triggerAt }
        return Loaded(
            folders, folderId, notes, tagsByNote, waiting, single, pictures, trashFolders, db.tags(), folderSort, noteSort, noteGroups, folderGroups,
            reminderRows, live.sortedBy { it.title.lowercase() },
            db.mediaTrash().sortedByDescending { it.deletedAt }.map { m ->
                val note = db.note(m.noteId)
                val state = when {
                    note == null -> "GONE"
                    note.deletedAt != null || note.folderId !in activeIds -> "IN_TRASH"
                    else -> "ACTIVE"
                }
                MediaTrashRow(m, state, mediaFile(m) != null)
            },
            nextMergeAt(live),
            com.notio.shared.domain.ReminderSummary.of(reminderRows.filter { it.isActive }.map { it.reminder }),
            com.notio.shared.domain.TaskSummary.of(live, now),
            notes.filter { it.type == NoteType.BUDGET }.mapNotNull { n -> (nodesByNote[n.id] ?: db.budgetNodesOf(n.id)).firstOrNull { it.parentId == null }?.let { n.id to it } }.toMap(),
            taskCounts, live.any { it.type == NoteType.TASK },
        )
    }

    /** The time of the next budget merge (D53): the layouts are parsed here, off the UI thread (the whole-code pass). */
    private fun nextMergeAt(notes: List<Note>): Long? = notes.asSequence().filter { it.type == NoteType.BUDGET }.mapNotNull { n ->
        val layout = com.notio.shared.domain.BudgetLayout.parse(n.extras)
        layout.mergeAt?.takeIf { layout.budgetKind != com.notio.shared.domain.BudgetKind.ACCOUNT && layout.mergeTarget != null }
    }.minOrNull()

    private fun loadDetail(noteId: String): NoteDetail? {
        val note = db.note(noteId) ?: return null
        return NoteDetail(
            note = note,
            folder = db.folders().firstOrNull { it.id == note.folderId },
            tags = db.tagsOf(noteId),
            reminders = db.remindersOf(noteId),
            nodes = db.budgetNodesOf(noteId),
            log = db.budgetLogOf(noteId),
            files = files.list(noteId),
        )
    }


    // ---- editing (the Android rules, NotioEditor) ----

    /** Runs an edit off the UI thread; a refused edit comes back as its [EditError], anything else as a message. */
    /**
     * The changes of the data run one at a time, in the order they were asked for (Android D294): a save started first never lands
     * after a later one of the same note.
     */
    private val writes = Dispatchers.IO.limitedParallelism(1)

    /**
     * A change of the data. Counted in [PendingSaves] from the moment it is asked for, so Exit waits until it is written (Android
     * D294: a save that has started is always finished).
     */
    private fun <T> edit(block: () -> T, onError: (EditError) -> Unit = { e -> say(strings.editError(e)) }, onDone: (T) -> Unit = {}) {
        PendingSaves.begin()
        scope.launch {
            val result = try { withContext(writes) { runCatching(block) } } finally { PendingSaves.end() }
            refresh()
            _state.value.detail?.let { d -> reloadDetail(d.note.id) }
            result.fold(onDone) { e -> if (e is EditException) onError(e.error) else say(e.message ?: e.toString()) }
        }
    }

    /**
     * A change of the data made by a screen itself (a budget's session): like [edit], it runs on the app's scope — so it is finished
     * when its screen goes away in the middle of it — in the order asked for, and Exit waits for it (Android D294). [done] gets
     * what [block] returned; a failure after Notio began to close is not an error.
     */
    fun <T> write(block: () -> T, done: (T) -> Unit) {
        PendingSaves.begin()
        scope.launch {
            val result = try { withContext(writes) { runCatching(block) } } finally { PendingSaves.end() }
            result.onSuccess(done).onFailure { e -> if (!closed) { com.notio.shared.AppLog.e("app", "a change could not be written", e); say(e.message ?: e.toString()) } }
        }
    }

    private fun reloadDetail(id: String) {
        scope.launch {
            val detail = withContext(Dispatchers.IO) { loadDetail(id) }
            _state.update { if (it.detail?.note?.id == id) it.copy(detail = detail) else it }
        }
    }

    fun say(text: String, undo: (() -> Unit)? = null) {
        _messages.tryEmit(UiMessage(text, undo))
    }

    fun createFolder(
        name: String, color: String, style: String, icon: String?, onError: (EditError) -> Unit,
        kind: com.notio.shared.domain.FolderKind = com.notio.shared.domain.FolderKind.NOTES,
        onDone: () -> Unit,
    ) = edit({ editor.createFolder(name, color, style, icon, kind) }, onError) { selectFolder(it.id); onDone() }

    fun updateFolder(id: String, name: String, color: String, style: String, icon: String?, onError: (EditError) -> Unit, onDone: () -> Unit) =
        edit({ editor.updateFolder(id, name, color, style, icon) }, onError) { onDone() }

    fun setFolderPinned(id: String, pinned: Boolean) = edit({ editor.setFolderPinned(id, pinned) })

    fun setFolderComment(id: String, text: String) = edit({ editor.setFolderComment(id, text) })

    fun deleteFolder(id: String, mode: FolderDeleteMode, target: String?) = edit({ editor.deleteFolder(id, mode, target) }) {
        say(strings.movedToTrash, undo = { edit({ editor.restoreFolder(id) }) })
    }

    /** Creates a note and opens it on the Edit tab. */
    fun createNote(folderId: String, title: String, type: NoteType, onError: (EditError) -> Unit, onDone: () -> Unit) =
        edit({ editor.createNote(folderId, title, type, contentText = "") }, onError) {
            onDone()
            // As on the phone (D257): the note stays in the list, which goes to it and frames it for a moment; a click opens it.
            justCreated.value = it.id
        }

    /** The titles of the live notes of [folderId] (the ready name of a new note, D264). */
    fun noteTitlesOf(folderId: String): List<String> = runCatching { db.notesOf(folderId).filter { it.deletedAt == null }.map { it.title } }.getOrDefault(emptyList())

    /** The note just made (Android D257): the main screen scrolls to it and frames its card for a moment, then clears this. */
    val justCreated = MutableStateFlow<String?>(null)

    /** Saves the title and text of the note being edited; nothing when nothing changed (autosave, Android `saveNow`). */
    fun saveNoteText(id: String, title: String, text: String) {
        val current = _state.value.detail?.note?.takeIf { it.id == id } ?: return
        if (title.isBlank() || (current.title == title.trim() && current.contentText.orEmpty() == text)) return
        // An untouched text stays as stored (a budget has none: `null` must not become "").
        edit({ editor.saveNote(current.copy(title = title, contentText = if (text == current.contentText.orEmpty()) current.contentText else text)) })
    }

    /** Saves a formatted note (D214): its plain text in `contentText` (search, cards) and the styles in `extras`. */
    fun saveNoteRich(id: String, title: String, text: String, extras: String) {
        val current = _state.value.detail?.note?.takeIf { it.id == id } ?: return
        if (title.isBlank() || (current.title == title.trim() && current.contentText.orEmpty() == text && current.extras == extras)) return
        edit({ editor.saveNote(current.copy(title = title, contentText = text, extras = extras), withExtras = true) })
    }

    /** Saves only the title (a list or a budget keeps its content by itself). */
    fun saveTitle(id: String, title: String) {
        val current = _state.value.detail?.note?.takeIf { it.id == id } ?: return
        if (title.isBlank() || current.title == title.trim()) return
        edit({ editor.saveNote(current.copy(title = title)) })
    }

    /** Saves the text and the type's JSON of a note at once (a list's structure, D92), the title as it is. */
    fun saveNoteContent(id: String, text: String, extras: String?) {
        val current = _state.value.detail?.note?.takeIf { it.id == id } ?: return
        if (current.contentText.orEmpty() == text && current.extras == extras) return
        // Shown at once, so the next change of the list starts from this one.
        _state.update { s -> s.detail?.let { d -> s.copy(detail = d.copy(note = d.note.copy(contentText = text, extras = extras))) } ?: s }
        edit({ editor.saveNote(current.copy(contentText = text, extras = extras), withExtras = true) })
    }

    /** The units the person added, kept in the backup's settings like on the phone (D92), and the adoption of a list's own. */
    fun units(): UnitsSource = UnitsSource(settings.read().customUnits.orEmpty()) { names, reserved ->
        val current = settings.read().customUnits.orEmpty()
        val added = names.filter { (id, name) ->
            val clean = name.trim()
            !com.notio.shared.domain.BuiltInUnit.isBuiltIn(id) && current.none { it.id == id } && clean.isNotEmpty() &&
                clean.length <= com.notio.shared.domain.CustomUnit.MAX_NAME_LENGTH &&
                current.none { it.name.equals(clean, true) } && reserved.none { it.equals(clean, true) }
        }.map { (id, name) -> com.notio.shared.domain.CustomUnit(id, name.trim()) }
        if (added.isNotEmpty()) settings.write(settings.read().copy(customUnits = current + added))
    }

    private fun withNote(id: String, change: (Note) -> Note) = edit({
        val note = db.note(id) ?: throw EditException(EditError.NOTE_NOT_FOUND)
        editor.saveNote(change(note))
    })

    fun togglePinned(id: String) = withNote(id) { it.copy(isPinned = !it.isPinned) }

    fun toggleBoldTitle(id: String) = withNote(id) { it.copy(isTitleBold = !it.isTitleBold) }

    fun setNoteColor(id: String, color: String?) = withNote(id) { it.copy(colorHex = color) }

    fun setNoteComment(id: String, text: String) = edit({ editor.setNoteComment(id, text) })

    /** The option "hide the content" of a note (D144, Android D363), from the crossed eye in Edit. */
    fun setNoteHidden(id: String, hidden: Boolean) = edit({ editor.setNoteHidden(id, hidden) })

    fun moveNote(id: String, folderId: String) = edit({ editor.moveNote(id, folderId) })

    /** To the trash, with Undo (Android: the snackbar after a deletion). */
    fun trashNote(id: String) = edit({ editor.trashNote(id) }) {
        if ((_state.value.screen as? Screen.NoteView)?.id == id) back()
        say(strings.movedToTrash, undo = { edit({ editor.restoreNote(id) }) })
    }

    fun restoreNote(id: String, target: String? = null) = edit({ editor.restoreNote(id, target) }) { say(strings.restored) }

    fun restoreFolder(id: String) = edit({ editor.restoreFolder(id) }) { say(strings.restored) }

    fun purgeNote(id: String) = edit({ editor.purgeNote(id) }) { if (_state.value.screen is Screen.NoteView) back() }

    fun purgeFolder(id: String) = edit({ editor.purgeFolder(id) })

    fun emptyTrash() = edit({
        // The media trash goes too (Android: emptyAll).
        db.mediaTrash().forEach { row -> mediaFile(row)?.delete() }
        db.replaceMediaTrash(emptyList())
        editor.emptyTrash()
    })

    /** An open budget note (BudgetScreen); the undo depth is the Settings value the phone backup carries (D197). */
    fun openBudget(noteId: String) = BudgetSession(db, noteId, undoSteps = settings.read().undoSteps ?: com.notio.shared.domain.UndoHistory.DEFAULT_STEPS)

    fun budgetLog(noteId: String) = db.budgetLogOf(noteId)

    fun clearBudgetLog(noteId: String) = db.clearBudgetLog(noteId)

    // ---- sorting (D137, D158): folders and notes in the backup's settings, a folder's own sort of its notes kept here ----

    fun folderSort(): com.notio.shared.domain.SortOption =
        com.notio.shared.domain.SortOption.decode(settings.read().folderSort, com.notio.shared.domain.SortOption.FOLDERS_DEFAULT)

    private fun perFolderSorts(): Map<String, String> =
        ui.getProperty(FOLDER_SORTS)?.let { runCatching { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()

    fun noteSortFor(folderId: String?): com.notio.shared.domain.SortOption {
        val global = com.notio.shared.domain.SortOption.decode(settings.read().noteSort, com.notio.shared.domain.SortOption.NOTES_DEFAULT)
        return folderId?.let { perFolderSorts()[it] }?.let { com.notio.shared.domain.SortOption.decode(it, global) } ?: global
    }

    /** The selection mode of the notes of one folder (Android D167): picked with check boxes, deleted together. */
    val selection = SelectionState()

    /** "Select" in a folder's menu: that folder opens and its notes get check boxes. */
    fun beginSelection(folderId: String) {
        selectionFolderId = folderId
        selectFolder(folderId)
        selection.begin()
    }

    /** The folder the selection mode works in; opening another one ends the mode. */
    var selectionFolderId: String? = null
        private set

    /** The picked notes to the trash together, with one Undo for all of them. */
    fun trashNotes(ids: List<String>) = edit({ ids.filter { id -> runCatching { editor.trashNote(id) }.isSuccess } }) { done ->
        selection.end()
        if (done.isNotEmpty()) say(res.str("home_notes_moved_to_trash", done.size), undo = { edit({ done.forEach { runCatching { editor.restoreNote(it) } } }) })
    }

    /**
     * The eyes of the selection bar (D156, Android D364): [ids] - the picked notes the press changes (`HideMany.targets`) - are
     * set to [hidden]; the selection stays, and Undo turns back only the notes that changed.
     */
    fun setNotesHidden(ids: List<String>, hidden: Boolean) = edit({ ids.filter { id -> runCatching { editor.setNoteHidden(id, hidden) }.isSuccess } }) { done ->
        if (done.isNotEmpty()) say(
            res.str(if (hidden) "privacy_hidden_many" else "privacy_shown_many", done.size),
            undo = { edit({ done.forEach { runCatching { editor.setNoteHidden(it, !hidden) } } }) },
        )
    }

    /** "Earlier" / "Later" in the menu of a folder while the folders are in their own order (Android D141). */
    fun moveFolder(folderId: String, delta: Int) {
        val ids = _state.value.folders.map { it.folder.id }.toMutableList()
        val from = ids.indexOf(folderId)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        reorderFolders(ids)
    }

    /**
     * The Earlier / Later menu action of a note (Android D274 step 4: what dragging does, without the mouse), or `null` when it does
     * not apply: not in own order, searching, at the end, or beside a note of the other pin group.
     */
    fun noteMove(noteId: String, delta: Int): (() -> Unit)? {
        val s = _state.value
        if (s.noteSort.key != com.notio.shared.domain.SortKey.MANUAL || s.searchOpen) return null
        val at = s.notes.indexOfFirst { it.id == noteId }
        val to = at + delta
        if (at < 0 || to !in s.notes.indices || s.notes[to].isPinned != s.notes[at].isPinned) return null
        return {
            val ids = s.notes.map { it.id }.toMutableList()
            ids.add(to, ids.removeAt(at))
            reorderNotes(ids)
        }
    }

    /** The Earlier / Later menu action of a folder, or `null` when it does not apply (not in own order, already at the end). */
    fun folderMove(folderId: String, delta: Int): (() -> Unit)? {
        val s = _state.value
        if (s.folderSort.key != com.notio.shared.domain.SortKey.MANUAL || s.searchOpen) return null
        val at = s.folders.indexOfFirst { it.folder.id == folderId }
        return if (at < 0 || at + delta !in s.folders.indices) null else ({ moveFolder(folderId, delta) })
    }

    /** A folder dragged to its place (D141): the new order is saved and the folders are then sorted by it. */
    fun reorderFolders(orderedIds: List<String>) = edit({ editor.reorderFolders(orderedIds) }) {
        setFolderSort(com.notio.shared.domain.SortOption(com.notio.shared.domain.SortKey.MANUAL, descending = false))
    }

    /** A note dragged to its place: the open folder is then sorted by the person's own order. */
    fun reorderNotes(orderedIds: List<String>) = edit({ editor.reorderNotes(orderedIds) }) {
        setNoteSort(com.notio.shared.domain.SortOption(com.notio.shared.domain.SortKey.MANUAL, descending = false), applyAll = false)
    }

    fun setFolderSort(option: com.notio.shared.domain.SortOption) {
        settings.write(settings.read().copy(folderSort = option.encode()))
        refresh()
    }

    /** The sort of the open folder's notes; with [applyAll] every folder takes it (and their own sorts are dropped). */
    fun setNoteSort(option: com.notio.shared.domain.SortOption, applyAll: Boolean) {
        val folderId = _state.value.selectedFolderId
        if (applyAll || folderId == null) {
            settings.write(settings.read().copy(noteSort = option.encode()))
            ui.remove(FOLDER_SORTS)
        } else {
            ui.setProperty(FOLDER_SORTS, kotlinx.serialization.json.Json.encodeToString(perFolderSorts() + (folderId to option.encode())))
        }
        saveUiProps()
        refresh()
    }

    fun setFeatured(folderId: String, noteId: String?, mask: Int, layout: com.notio.shared.domain.FeaturedLayout) =
        edit({ editor.setFeatured(folderId, noteId, mask, layout) })

    /** The active notes of a folder, for choosing its featured note. */
    fun notesOf(folderId: String): List<Note> = db.notesOf(folderId).filter { it.deletedAt == null }

    /** Tags (D109): a new one while choosing the tags of a note, then the note's exact set. */
    fun createTag(name: String, done: (Tag) -> Unit) = edit({ editor.getOrCreateTag(name) }) { done(it) }

    fun setNoteTags(noteId: String, tagIds: Set<String>) = edit({ editor.setNoteTags(noteId, tagIds) })

    fun renameTag(id: String, name: String) = edit({ editor.renameTag(id, name) })

    /** A new tag typed in Settings -> Tags, before any note has it (Android D271); a taken or empty name is said, the dialog stays. */
    fun addTag(name: String, done: (Tag) -> Unit) = edit({ editor.createTag(name) }) { done(it) }

    fun deleteTag(id: String) = edit({ editor.deleteTag(id) })

    /** The editor of a drawing or blueprint note (the phone's DrawingViewModel) on this computer's stores. */
    fun drawingViewModel(noteId: String, kind: DrawingKind) = DrawingViewModel(
        noteId, DrawingStore(files), VectorStore(files),
        NoteUseCases(onTouch = { id -> withContext(Dispatchers.IO) { db.note(id)?.let { db.upsertNote(it.copy(updatedAt = System.currentTimeMillis())) } }; refresh() }),
        CoroutineScope(SupervisorJob() + Dispatchers.IO), kind,
        kotlinx.coroutines.flow.flowOf(settings.read().undoSteps ?: com.notio.shared.domain.UndoHistory.DEFAULT_STEPS),
    )

    /** The recordings of an audio note (Android D112; AGENT.md D35): microphone and player of this computer, the phone's rules. */
    fun audioViewModel(noteId: String) = AudioViewModel(
        noteId = noteId,
        notes = NoteLookup { id -> withContext(Dispatchers.IO) { db.note(id)?.takeIf { it.deletedAt == null } } },
        store = AudioStore(files),
        noteUseCases = NoteUseCases(
            onTouch = { id -> withContext(Dispatchers.IO) { db.note(id)?.let { db.upsertNote(it.copy(updatedAt = System.currentTimeMillis())) } }; refresh() },
            onSave = { note, withExtras -> withContext(Dispatchers.IO) { editor.saveNote(note, withExtras) }; refresh() },
        ),
        recorder = DesktopRecorder(),
        player = DesktopPlayer(),
        cacheDir = File(paths.root, "tmp-audio"),
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        defaultName = { n -> res.str("audio_default_name", n) },
        formats = appSettings,
        trash = mediaTrash,
        recordingLimit = appSettings.recordingLimit(com.notio.shared.domain.RecordingKind.AUDIO),
        keepAlive = recordingKeeper,
    )

    /** The file of an item in the media trash (it stays in its note's folder of files until deleted for good). */
    fun mediaFile(m: com.notio.shared.transfer.MediaTrashRecord): File? =
        if (m.kind == TrashedMedia.AUDIO) AudioStore(files).fileIfExists(m.id)
        else m.id.lastIndexOf('_').takeIf { it > 0 }?.let { cut ->
            files.list(m.id.substring(0, cut)).firstOrNull { it.nameWithoutExtension == "item-" + m.id.substring(cut + 1) }
        }

    /** Puts an item back into its note at its old place (Android `MediaTrashUseCases.restore`). */
    fun restoreMedia(id: String) {
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                val row = db.mediaTrash().firstOrNull { it.id == id } ?: return@withContext null
                val note = db.note(row.noteId)?.takeIf { it.deletedAt == null } ?: return@withContext if (db.note(row.noteId) != null) "trash_media_cannot_restore_trash" else "trash_media_cannot_restore_gone"
                if (mediaFile(row) == null) return@withContext "trash_media_cannot_restore_file"
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
                if (row.kind == TrashedMedia.AUDIO) {
                    val doc = com.notio.shared.domain.AudioDocument.forNote(note.extras, AudioStore(files).fileIfExists(note.id) != null && row.id != note.id)
                    val clip = runCatching { json.decodeFromString(com.notio.shared.domain.AudioClip.serializer(), row.itemJson) }.getOrNull() ?: return@withContext null
                    if (doc.clips.none { it.id == clip.id }) {
                        val next = com.notio.shared.domain.AudioDocument(clips = doc.clips.toMutableList().also { it.add(row.position.coerceIn(0, it.size), clip) })
                        editor.saveNote(note.copy(contentText = next.searchText(), extras = next.encode()), withExtras = true)
                    }
                } else {
                    val doc = com.notio.shared.domain.MediaDocument.parse(note.extras)
                    val item = runCatching { json.decodeFromString(com.notio.shared.domain.MediaItem.serializer(), row.itemJson) }.getOrNull() ?: return@withContext null
                    if (doc.items.none { it.id == item.id }) {
                        val next = com.notio.shared.domain.MediaDocument(items = doc.items.toMutableList().also { it.add(row.position.coerceIn(0, it.size), item) })
                        editor.saveNote(note.copy(contentText = next.searchText(), extras = next.encode()), withExtras = true)
                    }
                }
                db.replaceMediaTrash(db.mediaTrash().filter { it.id != id })
                "trash_restored:" + row.title
            }
            refresh()
            message?.let { m -> say(if (m.startsWith("trash_restored:")) res.str("trash_restored", m.substringAfter(':')) else res.str(m)) }
        }
    }

    /** Deletes an item of the media trash and its file for good. */
    fun purgeMedia(id: String) = edit({
        db.mediaTrash().firstOrNull { it.id == id }?.let { row -> mediaFile(row)?.delete() }
        db.replaceMediaTrash(db.mediaTrash().filter { it.id != id })
    })

    // ---- photo, video and screen notes (Android D147; AGENT.md D36) ----

    /** The screen recorder's state, for the note screen and the Stop window. */
    class ScreenRecState {
        var noteId by androidx.compose.runtime.mutableStateOf<String?>(null)
        var recording by androidx.compose.runtime.mutableStateOf(false)
        var saving by androidx.compose.runtime.mutableStateOf(false)
        var failed by androidx.compose.runtime.mutableStateOf(false)
        var startedAt by androidx.compose.runtime.mutableStateOf(0L)
    }

    /** The voice or camera recording that runs, for the floating Stop and the tray (Android D255). */
    val recordingKeeper = DesktopRecordingKeepAlive()

    /** The window is minimised or in the tray: the floating Stop of a voice / camera recording shows then (D255). */
    var windowAway by androidx.compose.runtime.mutableStateOf(false)

    val screenRecording = ScreenRecState()
    private val mediaLock = Any()
    private val screenRecorder = ScreenRecorder()

    /** Set by the window: Notio steps aside while the screen is recorded, and comes back after. */
    var stepAside: (Boolean) -> Unit = {}

    fun startScreenRecording(noteId: String, withMic: Boolean) {
        if (screenRecording.recording || screenRecording.saving) return
        val file = newMediaTempFile("mp4")
        runCatching { screenRecorder.start(file, withMic, overlay = videoOverlay(MediaKind.SCREEN)) }.onFailure {
            com.notio.shared.AppLog.critical("media", "the screen recording could not start", it)
            screenRecording.failed = true
            return
        }
        screenRecording.noteId = noteId
        screenRecording.failed = false
        screenRecording.recording = true
        screenRecording.startedAt = screenRecorder.startedAt
        com.notio.shared.AppLog.i("media", "screen recording")
        stepAside(true)
        // The recording stops and is saved by itself after its limit (D251); Stop before ends this wait.
        // A limit typed for this recording only (D263) goes first, else the one of Settings.
        val limit = RecordingLimitOnce.take(com.notio.shared.domain.RecordingKind.SCREEN, appSettings.recordingLimit(com.notio.shared.domain.RecordingKind.SCREEN).value)
        warnRecordingLimit(this, limit)
        screenLimitJob?.cancel()
        screenLimitJob = if (limit.millis > 0) scope.launch {
            kotlinx.coroutines.delay(limit.millis)
            if (screenRecording.recording) {
                com.notio.shared.AppLog.i("media", "the time limit of the screen recording was reached")
                stopScreenRecording(limitReached = true)
            }
        } else null
    }

    private var screenLimitJob: kotlinx.coroutines.Job? = null

    fun stopScreenRecording() = stopScreenRecording(limitReached = false)

    /**
     * What the open editors still hold (a text typed within the last moment, the last stroke of a drawing): each editor puts its
     * "save now" here while it is on the screen, and Exit calls them before it waits for the writes (Android D294).
     */
    private val unsaved = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addUnsaved(save: () -> Unit) { unsaved.add(save) }

    fun removeUnsaved(save: () -> Unit) { unsaved.remove(save) }

    private fun saveOpenEditors() = unsaved.forEach { save -> runCatching(save).onFailure { com.notio.shared.AppLog.e("app", "an editor could not save before Exit", it) } }

    /**
     * Exit while a recording runs (the user, 2026-09-28): every recording is stopped and written into its note first, then [then]
     * ends Notio; at most 20 s are waited for, so a stuck recorder never keeps Notio from ending.
     */
    fun finishRecordingsThen(then: () -> Unit) {
        // The open note first: its save joins the writes waited for below (Android D294).
        saveOpenEditors()
        if (!screenRecording.recording && !screenRecording.saving && recordingKeeper.active == null && !PendingSaves.any) { then(); return }
        if (screenRecording.recording) stopScreenRecording()
        if (recordingKeeper.active != null) recordingKeeper.requestStop()
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                while (screenRecording.recording || screenRecording.saving || recordingKeeper.active != null || PendingSaves.any) kotlinx.coroutines.delay(50)
            }
            then()
        }
    }

    private fun stopScreenRecording(limitReached: Boolean) {
        if (!screenRecording.recording) return
        if (!limitReached) screenLimitJob?.cancel()
        screenLimitJob = null
        screenRecording.recording = false
        screenRecording.saving = true
        stepAside(false)
        val noteId = screenRecording.noteId ?: return
        PendingSaves.begin()
        scope.launch {
            val captured = try { withContext(Dispatchers.IO) { screenRecorder.stop() } } finally { PendingSaves.end() }
            if (captured == null) screenRecording.failed = true
            else addCapturedMedia(noteId, MediaKind.SCREEN, captured)
            screenRecording.saving = false
            say(res.str(if (captured == null) "rec_failed" else if (limitReached) "rec_limit_reached" else "rec_saved"))
        }
    }

    /** The picture of the image overlay (D148), in the data folder; not part of a backup (the phone's rule). */
    fun overlayImage(name: String): File = File(File(paths.root, "overlays"), File(name).name)

    /** The overlays of photos / videos / screen recordings, per kind; this computer's own, like the phone's (not in a backup). */
    fun overlaySettings(kind: MediaKind): com.notio.shared.domain.OverlaySettings =
        com.notio.shared.domain.OverlaySettings.decode(ui.getProperty("overlay_" + kind.name.lowercase()))

    fun setOverlaySettings(kind: MediaKind, settings: com.notio.shared.domain.OverlaySettings) {
        ui.setProperty("overlay_" + kind.name.lowercase(), settings.encode())
        saveUi()
    }

    fun newMediaTempFile(extension: String): File =
        File(paths.root, "tmp-media").apply { mkdirs() }.let { File(it, "capture-" + System.nanoTime() + "." + extension) }

    /** The file of an item of a media note: `item-<id>.<ext>`, the name a backup gives it. */
    fun mediaItemFile(noteId: String, kind: MediaKind, item: MediaItem): File? =
        runCatching { files.file(noteId, "item-" + item.id + "." + kind.extension) }.getOrNull()

    fun mediaExportName(noteId: String, kind: MediaKind, item: MediaItem, index: Int): String {
        val note = db.note(noteId)
        val naming = com.notio.shared.domain.ExportNaming.decode(settings.read().exportNaming)
        val title = item.name.ifBlank { res.str("media_default_name", index + 1) }
        return com.notio.shared.domain.ExportNaming.safe(
            naming.baseName(note?.title.orEmpty(), title, note?.createdAt ?: item.createdAt, maxOf(item.updatedAt, item.createdAt), System.currentTimeMillis(), fallback = kind.name.lowercase()),
        ).ifBlank { kind.name.lowercase() } + "." + kind.extension
    }

    /** Adds an item at the end of the note's list, its file written by [write]; the list is saved in the note. */
    private fun addMediaItem(noteId: String, kind: MediaKind, width: Int, height: Int, durationMs: Int, audio: String, write: (String) -> Unit) {
        PendingSaves.begin()
        scope.launch {
            val ok = try { withContext(Dispatchers.IO) {
                runCatching {
                    // One change of a note's list at a time: two photos taken quickly must not overwrite each other.
                    synchronized(mediaLock) {
                    val note = db.note(noteId) ?: error("note")
                    val item = MediaItem(id = MediaItem.newId(), createdAt = System.currentTimeMillis(), durationMs = durationMs, width = width, height = height, audio = audio)
                    write("item-" + item.id + "." + kind.extension)
                    val doc = MediaDocument(items = MediaDocument.parse(note.extras).items + item)
                    editor.saveNote(note.copy(contentText = doc.searchText(), extras = doc.encode()), withExtras = true)
                    }
                }.onFailure { com.notio.shared.AppLog.critical("media", "an item could not be stored", it) }.isSuccess
            } } finally { PendingSaves.end() }
            if (!ok) say(res.str("camera_failed"))
            refresh()
            reloadDetail(noteId)
        }
    }

    /** The overlays of [kind] ready for a recording (none when nothing is switched on). */
    fun videoOverlay(kind: MediaKind): VideoOverlay? {
        val settings = overlaySettings(kind)
        if (!settings.any) return null
        return VideoOverlay(settings, OverlayRenderer.loadLogo(settings.image.file.takeIf { it.isNotBlank() }?.let { overlayImage(it) }, settings))
    }

    /**
     * The picture of a drawing or blueprint for its card (the phone's `DrawingPreview`, D160 / D161): its PNG, or — for a note that has
     * only shapes (a blueprint) — the shapes drawn on a sheet first; made small (at most [DRAWING_PREVIEW_PX] on the long side) and kept
     * until the note changes. `null` when it has nothing to show or cannot be read (never a crash).
     */
    fun drawingPicture(note: com.notio.shared.domain.Note): androidx.compose.ui.graphics.ImageBitmap? {
        val key = "drawing:" + note.id
        PictureCache.get(key, note.updatedAt)?.let { return it }
        val made = runCatching {
            val png = files.file(note.id, "drawing.png")?.takeIf { it.isFile }?.readBytes()
            val document = files.file(note.id, "vector.json")?.takeIf { it.isFile }?.readBytes()?.let { VectorDocument.decode(it) }
            val shapes = document?.drawableShapes().orEmpty()
            // The sheet's own size (Android D260).
            val (w, h) = document?.sheet() ?: (DrawingRenderer.WIDTH to DrawingRenderer.HEIGHT)
            // The layers on the sheet's own background (Android D265), then made small for the card.
            val background = document?.background() ?: SheetBackground.DEFAULT
            if (png == null && shapes.isEmpty() && !background.visible) null
            else DrawingRenderer.compose(png, shapes, w, h, background).let { sheet ->
                try { PictureCache.scaled(sheet.toSkiaImage(), DRAWING_PREVIEW_PX) } finally { sheet.recycle() }
            }
        }.onFailure { com.notio.shared.AppLog.e("drawing", "the picture of a card could not be made", it) }.getOrNull() ?: return null
        // One picture per note (an older version is replaced), in the cache bounded by memory.
        PictureCache.put(key, note.updatedAt, made)
        return made
    }

    /** The picture of a drawing's card as kept, of whatever version: shown at once while a newer one is made. */
    fun drawingPictureNow(noteId: String) = PictureCache.peek("drawing:" + noteId)

    /** A picture of the camera as a new photo (JPEG), with the photo overlays drawn on it (D148). */
    fun addPhoto(noteId: String, frame: com.notio.desktop.media.VideoFrame) {
        // A failure (out of memory, a full disk) is a "could not save", never a crash (Android D244, rule 18).
        val jpeg = runCatching {
            val settings = overlaySettings(MediaKind.PHOTO)
            val pixels = frame.argb.copyOf()
            if (settings.any) {
                val logo = OverlayRenderer.loadLogo(settings.image.file.takeIf { it.isNotBlank() }?.let { overlayImage(it) }, settings)
                OverlayRenderer.drawOnPixels(pixels, frame.width, frame.height, settings, OverlayInputs(System.currentTimeMillis(), null, logo))
            }
            com.notio.desktop.media.VideoFrame(frame.width, frame.height, pixels, frame.timeMs).toJpeg()
        }.onFailure { com.notio.shared.AppLog.critical("camera", "the photo could not be saved", it) }.getOrNull() ?: return say(res.str("bg_error"))
        addMediaItem(noteId, MediaKind.PHOTO, frame.width, frame.height, 0, "NONE") { name -> files.write(noteId, name, jpeg.inputStream()) }
    }

    /**
     * A picture file as a new photo: a JPEG as it is, any other picture Skia reads made into a JPEG on white. A picture above
     * [MAX_PHOTO_PIXELS] is halved until it fits (a 50 Mpx one would take 200 MB), and each full-size copy is freed at once (Android D244).
     */
    fun addPhotoFromFile(noteId: String, file: File) {
        val made = runCatching {
            val bytes = file.readBytes()
            val jpegFile = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            org.jetbrains.skia.Image.makeFromEncoded(bytes).use { image ->
                if (jpegFile) Triple(bytes, image.width, image.height)
                else {
                    var w = image.width
                    var h = image.height
                    while (w.toLong() * h > MAX_PHOTO_PIXELS) { w = (w / 2).coerceAtLeast(1); h = (h / 2).coerceAtLeast(1) }
                    // JPEG has no transparency: the picture goes onto white first (the phone's pngToJpeg).
                    org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { surface ->
                        surface.canvas.clear(0xFFFFFFFF.toInt())
                        surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
                        val out = surface.makeImageSnapshot().use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 92)?.bytes }
                        out?.let { Triple(it, w, h) }
                    }
                }
            }
        }.onFailure { com.notio.shared.AppLog.critical("media", "the picture could not be added", it) }.getOrNull() ?: return say(res.str("bg_error"))
        val (jpeg, w, h) = made
        addMediaItem(noteId, MediaKind.PHOTO, w, h, 0, "NONE") { name -> files.write(noteId, name, jpeg.inputStream()) }
    }

    /** A finished recording (camera or screen) moved into the note. */
    fun addCapturedMedia(noteId: String, kind: MediaKind, captured: CapturedMedia) {
        addMediaItem(noteId, kind, captured.width, captured.height, captured.durationMs, captured.audio) { name ->
            captured.file.inputStream().use { files.write(noteId, name, it) }
            captured.file.delete()
        }
    }

    fun editMediaItem(noteId: String, itemId: String, name: String, comment: String) = edit({
        val note = db.note(noteId) ?: return@edit
        val now = System.currentTimeMillis()
        val doc = MediaDocument(items = MediaDocument.parse(note.extras).items.map { if (it.id == itemId) it.copy(name = name.trim().take(80), comment = comment.trim().take(2000), updatedAt = now) else it })
        editor.saveNote(note.copy(contentText = doc.searchText(), extras = doc.encode()), withExtras = true)
    }) { reloadDetail(noteId) }

    /** Items out of their note into the media trash (Android D156): the files stay, the rows go to `media_trash`. */
    fun deleteMediaItems(noteId: String, kind: MediaKind, ids: Set<String>) = edit({
        val note = db.note(noteId) ?: return@edit
        val before = MediaDocument.parse(note.extras).items
        val json = kotlinx.serialization.json.Json { encodeDefaults = false }
        val now = System.currentTimeMillis()
        db.addMediaTrash(before.filter { it.id in ids }.map { item ->
            com.notio.shared.transfer.MediaTrashRecord(
                id = MediaDocument.fileKey(noteId, item.id), noteId = noteId, kind = kind.name, title = item.name,
                itemJson = json.encodeToString(MediaItem.serializer(), item), position = before.indexOf(item), noteTitle = note.title,
                deletedAt = now, sizeBytes = mediaItemFile(noteId, kind, item)?.length() ?: 0,
            )
        })
        val doc = MediaDocument(items = before.filterNot { it.id in ids })
        editor.saveNote(note.copy(contentText = doc.searchText(), extras = doc.encode()), withExtras = true)
    }) { reloadDetail(noteId) }

    /** The trash of recordings and photos (D156). */
    val mediaTrash = MediaTrash(
        stashRow = { row -> withContext(Dispatchers.IO) { db.addMediaTrash(listOf(row)) } },
        sizeOf = { _, key -> AudioStore(files).fileIfExists(key)?.length() ?: 0 },
    )

    /** Whether a folder is active (a trashed note whose folder is in the trash asks where to go back). */
    fun folderIsActive(folderId: String) = _state.value.folders.any { it.folder.id == folderId }

    // ---- settings (the same fields a phone backup carries, so a restore brings the phone's look) ----

    fun setThemeMode(mode: ThemeMode) = updateSettings { it.copy(theme = mode.name) }

    fun setColorTheme(theme: ColorTheme) = updateSettings { it.copy(colorTheme = theme.name) }

    fun setFontSize(size: FontSize) = updateSettings { it.copy(fontSize = size.name) }

    private fun updateSettings(change: (SettingsRecord) -> SettingsRecord) {
        appSettings.update(change)
        val next = settings.read()
        _state.update { it.copy(theme = themeOf(next)) }
    }

    // ---- backups ----

    fun isProtected(file: File): Boolean = runCatching { backup.isProtected(file) }.getOrDefault(false)

    /** Imports [file]; the result or the error goes to [done] on the UI thread. */
    fun importBackup(file: File, mode: ImportMode, password: String?, done: (Result<ImportReport>) -> Unit) = work({
        // A protected file with no password typed tries the saved one first (Android D134).
        val saved = backupPasswords.read().password.takeIf { it.isNotEmpty() }
        val report = try {
            backup.importFile(file, mode, password)
        } catch (e: com.notio.shared.transfer.TransferException) {
            if (password.isNullOrEmpty() && e.error == com.notio.shared.transfer.TransferError.PASSWORD_REQUIRED && saved != null) backup.importFile(file, mode, saved)
            else throw e
        }
        report
    }, { result ->
        result.onSuccess { com.notio.shared.AppLog.i("backup", "restored (" + mode.name + "): notes added " + it.stats.notesAdded + ", updated " + it.stats.notesUpdated + ", skipped " + it.stats.notesSkipped + ", files skipped " + it.filesSkipped) }
        result.onFailure { com.notio.shared.AppLog.critical("backup", "the backup could not be restored (" + mode.name + ", " + ((it as? com.notio.shared.transfer.TransferException)?.error?.name ?: "unexpected") + ")", it) }
        done(result)
    })

    fun exportBackup(target: File, password: String?, done: (Result<BackupExportReport>) -> Unit) = work({
        // Written next to the target first, so a failed export never leaves half a file under the chosen name.
        val temp = File(target.path + ".part")
        val report = temp.outputStream().buffered().use { backup.export(it, password) }
        com.notio.shared.data.SafeFile.replace(temp, target)
        report
    }, { result ->
        result.onSuccess { com.notio.shared.AppLog.i("backup", "backup written, " + it.filesSkipped + " files skipped") }
        result.onFailure { com.notio.shared.AppLog.critical("backup", "the backup could not be written", it) }
        done(result)
    })

    // ---- single notes: export, import, clone (Android D23, D62, D100, D179; AGENT.md D27) ----

    fun exportFormats(note: Note): List<com.notio.shared.transfer.ExportFormat> = noteFiles.formatsFor(note)

    /** The file name the export suggests (the naming of Settings, D139). */
    fun suggestedNoteFileName(note: Note, format: com.notio.shared.transfer.ExportFormat): String =
        com.notio.shared.domain.ExportNaming.decode(settings.read().exportNaming)
            .baseName(note.title, null, note.createdAt, note.updatedAt, System.currentTimeMillis())
            .let { com.notio.shared.domain.ExportNaming.safe(it).ifBlank { "note" }.take(80) } + "." + format.extension

    fun exportNote(noteId: String, format: com.notio.shared.transfer.ExportFormat, target: File) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val note = db.note(noteId) ?: error("note")
                    val file = noteFiles.export(note, format)
                    com.notio.shared.data.SafeFile.write(target, file.bytes)
                }
            }
            result.onFailure { com.notio.shared.AppLog.e("export", "a note could not be exported (" + format.key + ")", it) }
            say(res.str(if (result.isSuccess) "export_done" else "export_failed"))
        }
    }

    /** Imports a note file into the open folder (or the first one) and opens it. */
    fun importNote(file: File) {
        val folderId = _state.value.selectedFolderId?.takeIf(::folderIsActive) ?: _state.value.folders.firstOrNull()?.folder?.id ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { noteFiles.import(file.name, file.readBytes(), folderId) } }
            refresh()
            result.fold(
                { say(res.str("import_note_done", it.title)); openNote(it.id) },
                { e ->
                    com.notio.shared.AppLog.w("import", "a note file was not imported", e)
                    val error = (e as? com.notio.shared.transfer.TransferException)?.error
                    say(
                        when (error) {
                            com.notio.shared.transfer.TransferError.UNRECOGNIZED_FILE -> res.str("transfer_error_unrecognized_file")
                            com.notio.shared.transfer.TransferError.UNSUPPORTED_VERSION -> res.str("transfer_error_unsupported_version")
                            else -> res.str("transfer_error_corrupt")
                        },
                    )
                },
            )
        }
    }

    /**
     * "Share" (Android D216; AGENT.md D38): on a computer the way to hand a note to any app is the clipboard — text notes as their
     * plain text with the title, a drawing as its picture, recordings / photos / videos as their files (pasted into a messenger or a
     * mail they go as attachments).
     */
    fun shareNote(noteId: String) {
        scope.launch { say(if (noteToClipboard(noteId)) strings.sharedToClipboard else res.str("share_nothing")) }
    }

    /**
     * "Copy" (D143, Android D362): the icon of two sheets on a card and in View - the same as "Share" puts on the clipboard, said
     * shortly: "Copied".
     */
    fun copyNote(noteId: String) {
        scope.launch { say(res.str(if (noteToClipboard(noteId)) "sync_copied" else "share_nothing")) }
    }

    /** A text on the clipboard, and "Copied" (D143): one line of a list, a row of a budget, the text of a note. */
    fun copyText(text: String) {
        if (textToClipboard(text)) say(res.str("sync_copied"))
    }

    /** The note on the clipboard as [shareNote] describes; `false` - there was nothing to put there. */
    private suspend fun noteToClipboard(noteId: String): Boolean {
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    val note = db.note(noteId) ?: return@runCatching false
                    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    val media = MediaKind.of(note.type)
                    when {
                        media != null || note.type == com.notio.shared.domain.NoteType.AUDIO -> {
                            val list = if (media != null) MediaDocument.parse(note.extras).items.mapNotNull { mediaItemFile(note.id, media, it) }
                            else com.notio.shared.domain.AudioDocument.forNote(note.extras, AudioStore(files).fileIfExists(note.id) != null).clips
                                .mapNotNull { AudioStore(files).fileIfExists(com.notio.shared.domain.AudioDocument.fileKey(note.id, it.id)) }
                            if (list.isEmpty()) return@runCatching false
                            clipboard.setContents(FilesSelection(list), null)
                        }
                        note.type == com.notio.shared.domain.NoteType.DRAWING || note.type == com.notio.shared.domain.NoteType.BLUEPRINT -> {
                            val format = noteFiles.formatsFor(note).firstOrNull { it.key == "png" } ?: return@runCatching false
                            val png = noteFiles.export(note, format).bytes
                            val image = javax.imageio.ImageIO.read(png.inputStream()) ?: return@runCatching false
                            clipboard.setContents(ImageSelection(image), null)
                        }
                        else -> {
                            val formats = noteFiles.formatsFor(note)
                            val format = formats.firstOrNull { it.key == "txt" } ?: formats.firstOrNull { it.mimeType == "text/plain" }
                            val body = format?.let { noteFiles.export(note, it).bytes.toString(Charsets.UTF_8) } ?: note.contentText.orEmpty()
                            clipboard.setContents(java.awt.datatransfer.StringSelection(if (body.startsWith(note.title)) body else note.title + "\n\n" + body), null)
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            return done
    }

    fun cloneNote(noteId: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { noteFiles.clone(noteId, res.str("note_clone_suffix")) } }
            refresh()
            result.fold({ say(res.str("note_cloned", it.title)); openNote(it.id) }, { say(res.str("error_clone_failed")) })
        }
    }

    /**
     * Fills the data with the sample notes of [DemoBackup]. No button leads here since D125 (owner, 2026-10-10: "without demo
     * now"): it is the data of the tests of the window and of the build's training of the start-up cache.
     */
    fun loadDemo(done: (Result<ImportReport>) -> Unit) = work({
        val temp = File.createTempFile("notio-demo-", ".json.zip")
        try {
            temp.outputStream().use { DemoBackup.write(it) }
            backup.importFile(temp, ImportMode.MERGE)
        } finally {
            temp.delete()
        }
    }, done)

    fun suggestedExportName(protected: Boolean) = backup.suggestedFileName(protected)

    private fun <T> work(block: () -> T, done: (Result<T>) -> Unit) {
        _state.update { it.copy(busy = true) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            // A REPLACE can bring the phone's theme along.
            _state.update { it.copy(busy = false) }
            appSettings.reload()
            refresh()
            done(result)
        }
    }

    @Volatile private var closed = false

    fun close() {
        // Work still queued is dropped before the database goes away.
        closed = true
        scope.cancel()
        reminders.close()
        // The bot of the assistant stops listening to Telegram.
        if (assistantLazy.isInitialized()) runCatching { assistant.close() }
        // A sync still at work (the Sync page open while Exit was chosen) stops before the database goes (the whole-code pass, round 2):
        // its port closes, its announcement goes, nothing writes into a closed database.
        if (syncEngineLazy.isInitialized()) runCatching { syncEngine.stopListening() }
        syncScope.cancel()
        runCatching { db.close() }
    }

    companion object {
        private const val LAST_FOLDER = "lastFolder"
        private const val NOTE_VIEW = "noteView"
        private const val POWER_SAVING = "economy"
        /** Two Backs closer than this are one double click. */
        private const val DOUBLE_BACK_NANOS = 300_000_000L
        private const val FOLDER_SORTS = "folderNoteSorts"
        private const val KEEP_IN_TRAY = "keepInTray"
        private const val CLOSE_TO_TRAY = "closeToTray"
        private const val AUTO_START_IN_TRAY = "autoStartInTray"
        private const val REMINDERS_ON = "reminders"
        private const val SYNC_ALWAYS = "syncAlways"
        private const val CLOSE_TO_TRAY_TOLD = "closeToTrayTold"
        private val PICTURE_KINDS = setOf("DRAWING", "BLUEPRINT", "PHOTO")

        fun themeOf(s: SettingsRecord) = ThemeChoice(
            mode = runCatching { ThemeMode.valueOf(s.theme ?: "") }.getOrDefault(ThemeMode.SYSTEM),
            colorTheme = runCatching { ColorTheme.valueOf(s.colorTheme ?: "") }.getOrDefault(ColorTheme.GREEN),
            customColor = s.customThemeColor ?: DEFAULT_CUSTOM_THEME,
            fontSize = runCatching { FontSize.valueOf(s.fontSize ?: "") }.getOrDefault(FontSize.NORMAL),
        )
    }
}

/** Files on the clipboard, as Explorer puts them there. */
private class FilesSelection(private val files: List<File>) : java.awt.datatransfer.Transferable {
    override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = files
}

/** A picture on the clipboard. */
private class ImageSelection(private val image: java.awt.Image) : java.awt.datatransfer.Transferable {
    override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.imageFlavor
    override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = image
}

/** About 24 Mpx: the largest picture kept at full size as a photo (96 MB while it is drawn), as on the phone (Android D244). */
private const val MAX_PHOTO_PIXELS = 24_000_000L

/** A key pressed down in the window, as the window content's [NotioController.keyHook] reads it. */
data class KeyPress(val key: androidx.compose.ui.input.key.Key, val ctrl: Boolean = false, val shift: Boolean = false)

/** The long side of the picture of a drawing on its card, in pixels (a 96 dp card picture, sharp on a 2x screen). */
private const val DRAWING_PREVIEW_PX = 480

/** How long a Windows notification of a reminder can be clicked to open the calendar (it stays in the Action Center a while). */
private const val NOTIFICATION_CLICK_MS = 10 * 60_000L
