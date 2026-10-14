package com.notebudget.app.core.common

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.notebudget.app.R
import com.notebudget.app.core.alarm.AndroidReminderScheduler
import com.notebudget.app.core.database.AppDatabase
import com.notebudget.app.core.datastore.SettingsDataStore
import com.notebudget.app.core.notification.NotificationChannels
import com.notebudget.app.core.notification.ReminderNotifier
import com.notebudget.app.core.security.CredentialHasher
import com.notebudget.app.data.drawing.AudioStore
import com.notebudget.app.data.drawing.DrawingStore
import com.notebudget.app.data.drawing.VectorStore
import com.notebudget.app.data.repository.RoomBudgetRepository
import com.notebudget.app.data.transfer.BackupService
import com.notebudget.app.data.transfer.BackupStore
import com.notebudget.app.data.transfer.AudioTransferHandler
import com.notebudget.app.data.transfer.BudgetTransferHandler
import com.notebudget.app.data.transfer.DrawingTransferHandler
import com.notebudget.app.data.transfer.ListTransferHandler
import com.notebudget.app.data.transfer.NoteCloneService
import com.notebudget.app.data.transfer.NoteFileService
import com.notebudget.app.data.transfer.RoomBackupStore
import com.notebudget.app.data.transfer.TextTransferHandler
import com.notebudget.app.feature.drawing.DrawingRenderer
import com.notebudget.app.feature.notes.types.AudioNoteType
import com.notebudget.app.feature.notes.types.BudgetNoteType
import com.notebudget.app.feature.notes.types.BlueprintNoteType
import com.notebudget.app.data.transfer.BlueprintTransferHandler
import com.notebudget.app.feature.notes.types.DrawingNoteType
import com.notebudget.app.feature.notes.types.ListNoteType
import com.notebudget.app.feature.notes.types.NoteTypeRegistry
import com.notebudget.app.feature.notes.types.TextNoteType
import com.notebudget.app.data.repository.RoomFolderRepository
import com.notebudget.app.data.repository.RoomNoteRepository
import com.notebudget.app.data.repository.RoomReminderRepository
import com.notebudget.app.data.repository.RoomTagRepository
import com.notebudget.app.domain.repository.TagRepository
import com.notebudget.app.domain.usecase.TagUseCases
import com.notebudget.app.domain.repository.BudgetRepository
import com.notebudget.app.domain.repository.NoteDataCleaner
import com.notebudget.app.domain.repository.UnitRepository
import com.notebudget.app.domain.repository.FolderRepository
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.ReminderRepository
import com.notebudget.app.domain.repository.ReminderScheduler
import com.notebudget.app.domain.usecase.BudgetEditor
import com.notebudget.app.domain.usecase.FolderUseCases
import com.notebudget.app.domain.usecase.NoteUseCases
import com.notebudget.app.domain.usecase.ReminderUseCases
import com.notebudget.app.domain.usecase.TrashUseCases
import com.notebudget.app.domain.usecase.UnitUseCases

/**
 * Simple manual DI container (D77). Everything is created lazily so that app start stays fast.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** D-encryption: the passphrase, when the person turned encryption on, is read once, before the database itself opens. */
    val databaseEncryption: com.notebudget.app.core.security.DatabaseEncryptionStore by lazy {
        com.notebudget.app.core.security.DatabaseEncryptionStore(appContext, com.notebudget.app.core.security.KeystoreSecretBox())
    }
    val databaseEncryptor: com.notebudget.app.core.database.DatabaseEncryptor by lazy {
        com.notebudget.app.core.database.DatabaseEncryptor(appContext)
    }
    val rawDatabaseTransfer: com.notebudget.app.core.database.RawDatabaseTransfer by lazy {
        com.notebudget.app.core.database.RawDatabaseTransfer(appContext)
    }

    val database: AppDatabase by lazy { AppDatabase.create(appContext, databaseEncryption.readBlocking().passphrase) }
    val settings: SettingsDataStore by lazy { SettingsDataStore(appContext) }

    val folderRepository: FolderRepository by lazy { RoomFolderRepository(database) }
    val noteRepository: NoteRepository by lazy { RoomNoteRepository(database) }
    val budgetRepository: BudgetRepository by lazy { RoomBudgetRepository(database) }
    val reminderRepository: ReminderRepository by lazy { RoomReminderRepository(database) }
    val tagRepository: TagRepository by lazy { RoomTagRepository(database) }
    val tagUseCases: TagUseCases by lazy { TagUseCases(tagRepository, noteRepository) }

    val reminderScheduler: ReminderScheduler by lazy { AndroidReminderScheduler(appContext) }
    val reminderNotifier: ReminderNotifier by lazy { ReminderNotifier(appContext) }
    val credentialHasher: CredentialHasher by lazy { CredentialHasher() }

    val folderUseCases: FolderUseCases by lazy {
        FolderUseCases(folderRepository, noteRepository, reminderRepository, reminderScheduler)
    }
    val noteUseCases: NoteUseCases by lazy {
        NoteUseCases(noteRepository, folderRepository, reminderRepository, reminderScheduler)
    }
    /** Permanent deletion; the files of a purged note are removed through its kind's handler. */
    val trashUseCases: TrashUseCases by lazy {
        TrashUseCases(
            folderRepository, noteRepository, reminderRepository, reminderScheduler,
            cleaner = NoteDataCleaner { note -> noteTypes.definitionFor(note.type).transfer.deleteAttachments(note) },
        )
    }
    /** The units the user added (D92); the built-in ones are part of the app. */
    val unitRepository: UnitRepository get() = settings
    val unitUseCases: UnitUseCases by lazy { UnitUseCases(unitRepository, noteRepository) }
    val currencyRepository: com.notebudget.app.domain.model.CurrencyRepository get() = settings
    val currencyUseCases: com.notebudget.app.domain.usecase.CurrencyUseCases by lazy {
        com.notebudget.app.domain.usecase.CurrencyUseCases(currencyRepository, noteRepository)
    }
    val budgetLogRepository: com.notebudget.app.domain.model.BudgetLogRepository by lazy { com.notebudget.app.data.repository.RoomBudgetLogRepository(database) }
    val budgetEditor: BudgetEditor by lazy { BudgetEditor(budgetRepository, log = budgetLogRepository) }

    /** Income / expense budgets merged into their account (D237). */
    val budgetMerge: com.notebudget.app.feature.budget.BudgetMergeService by lazy {
        com.notebudget.app.feature.budget.BudgetMergeService(
            noteRepository, budgetRepository, budgetLogRepository, noteUseCases, budgetEditor,
            com.notebudget.app.core.alarm.AndroidMergeScheduler(appContext),
        )
    }
    val reminderUseCases: ReminderUseCases by lazy {
        ReminderUseCases(reminderRepository, noteRepository, reminderScheduler, enabled = { com.notebudget.app.core.alarm.RemindersSwitch.isOn(appContext) })
    }

    /** The quiet notification that stays while a reminder is set (D154). */
    val reminderStatus: com.notebudget.app.core.notification.ReminderStatusNotification by lazy {
        com.notebudget.app.core.notification.ReminderStatusNotification(appContext, reminderRepository, noteRepository)
    }

    /** Shows, updates or removes that notification according to the reminders and to the setting. */
    suspend fun refreshReminderStatus() {
        runCatching { reminderStatus.refresh(settings.statusNotification.first(), settings.dateTimeFormats.first()) }
    }

    /**
     * For work that must finish even if the screen that started it is gone (e.g. a last save). A failure of one such job is
     * written to the app log as CRITICAL and does not close the app (D231); the other jobs go on (SupervisorJob).
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> AppLog.critical("app", "a background job failed", e) },
    )

    private val appVersion: String by lazy {
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }

    val drawingStore: DrawingStore by lazy { DrawingStore(File(appContext.filesDir, "drawings")) }
    val vectorStore: VectorStore by lazy { VectorStore(File(appContext.filesDir, "drawings-vector")) }
    val backgroundImages: com.notebudget.app.data.BackgroundImages by lazy {
        com.notebudget.app.data.BackgroundImages(File(appContext.filesDir, "backgrounds"))
    }

    val appLock: com.notebudget.app.domain.usecase.AppLockUseCases by lazy {
        val box = com.notebudget.app.core.security.KeystoreSecretBox()
        com.notebudget.app.domain.usecase.AppLockUseCases(
            store = com.notebudget.app.core.security.SharedPreferencesLockStore(appContext),
            secrets = box,
            mailer = com.notebudget.app.core.mail.SmtpClient(),
            smtp = com.notebudget.app.core.security.SharedPreferencesSmtpStore(appContext, box),
            wiper = com.notebudget.app.core.security.AndroidAppDataWiper(appContext),
            elapsed = android.os.SystemClock::elapsedRealtime,
        )
    }

    /** A note the person asked to open from a notification (D126). */
    val openNote: com.notebudget.app.core.notification.OpenNoteRequest by lazy { com.notebudget.app.core.notification.OpenNoteRequest() }

    val lockGate: com.notebudget.app.core.security.AppLockGate by lazy { com.notebudget.app.core.security.AppLockGate(appLock) }

    val audioStore: AudioStore by lazy { AudioStore(File(appContext.filesDir, "audio")) }

    /** The trash of photos, videos, screen and audio recordings (D156). */
    val mediaTrashRepository: com.notebudget.app.domain.model.MediaTrashRepository by lazy { com.notebudget.app.data.repository.RoomMediaTrashRepository(database) }
    val mediaFiles: com.notebudget.app.data.media.AndroidMediaFiles by lazy { com.notebudget.app.data.media.AndroidMediaFiles(mediaStore, audioStore) }
    val mediaTrash: com.notebudget.app.domain.usecase.MediaTrashUseCases by lazy {
        com.notebudget.app.domain.usecase.MediaTrashUseCases(mediaTrashRepository, noteRepository, noteUseCases, mediaFiles)
    }

    /** Photos, camera videos and screen recordings (D147) and the pictures laid over them (D148). */
    val mediaStore: com.notebudget.app.data.media.MediaStore by lazy { com.notebudget.app.data.media.MediaStore(File(appContext.filesDir, "media")) }
    val overlayImages: com.notebudget.app.data.media.OverlayImages by lazy { com.notebudget.app.data.media.OverlayImages(File(appContext.filesDir, "overlay_images")) }
    val mediaNoteWriter: com.notebudget.app.data.media.MediaNoteWriter by lazy {
        com.notebudget.app.data.media.MediaNoteWriter(noteRepository, noteUseCases, mediaStore, mediaTrash)
    }

    /**
     * Every kind of note the app knows. To add a kind, implement `NoteTypeDefinition` and add it
     * here: the new-note dialog, editor, export/import and backups pick it up from this list.
     */
    private val drawingHandler: DrawingTransferHandler by lazy {
        DrawingTransferHandler(
            drawingStore,
            vectorStore,
            flatten = { png, shapes, w, h, bg -> withContext(Dispatchers.Default) { DrawingRenderer.flatten(png, shapes, w, h, bg) } },
            toJpeg = { png -> withContext(Dispatchers.Default) { DrawingRenderer.pngToJpeg(png) } },
            appVersion = appVersion,
        )
    }

    val noteTypes: NoteTypeRegistry by lazy {
        NoteTypeRegistry(
            listOf(
                TextNoteType(TextTransferHandler()),
                com.notebudget.app.feature.notes.types.WordNoteType(),
                com.notebudget.app.feature.notes.types.TaskNoteType(TextTransferHandler()),
                ListNoteType(ListTransferHandler(appVersion = appVersion)),
                BudgetNoteType(BudgetTransferHandler(budgetRepository, appVersion = appVersion)),
                DrawingNoteType(drawingHandler),
                BlueprintNoteType(BlueprintTransferHandler(drawingHandler)),
                AudioNoteType(AudioTransferHandler(audioStore, mediaTrash)),
                com.notebudget.app.feature.notes.types.ScheduleNoteType(com.notebudget.app.data.transfer.ScheduleTransferHandler(appVersion = appVersion)),
                com.notebudget.app.feature.notes.types.MediaNoteType(
                    com.notebudget.app.domain.model.MediaKind.PHOTO, R.string.note_type_photo,
                    com.notebudget.app.data.transfer.MediaTransferHandler(com.notebudget.app.domain.model.MediaKind.PHOTO, mediaStore, mediaTrash),
                ),
                com.notebudget.app.feature.notes.types.MediaNoteType(
                    com.notebudget.app.domain.model.MediaKind.VIDEO, R.string.note_type_video,
                    com.notebudget.app.data.transfer.MediaTransferHandler(com.notebudget.app.domain.model.MediaKind.VIDEO, mediaStore, mediaTrash),
                ),
                com.notebudget.app.feature.notes.types.MediaNoteType(
                    com.notebudget.app.domain.model.MediaKind.SCREEN, R.string.note_type_screen,
                    com.notebudget.app.data.transfer.MediaTransferHandler(com.notebudget.app.domain.model.MediaKind.SCREEN, mediaStore, mediaTrash),
                ),
            ),
        )
    }

    private val backupStore: BackupStore by lazy { RoomBackupStore(database) }

    /** The password that protects exported backups (D134), kept encrypted by the Keystore. */
    val backupPasswords: com.notebudget.app.data.transfer.BackupPasswordStore by lazy {
        com.notebudget.app.core.security.SharedPreferencesBackupPasswordStore(appContext, com.notebudget.app.core.security.KeystoreSecretBox())
    }

    val backupService: BackupService by lazy {
        BackupService(
            backupStore, noteTypes, reminderScheduler, reminderUseCases, settings, appVersion,
            tagRepository = tagRepository, tagUseCases = tagUseCases, passwords = backupPasswords, mediaTrash = mediaTrash, budgetLog = budgetLogRepository,
        )
    }

    /** Sync between two Notio devices on the local network (D226): pairings, and the engine that runs only while its page is open. */
    val syncStore: com.notebudget.app.data.sync.SyncStore by lazy {
        com.notebudget.app.data.sync.SyncStore(appContext, com.notebudget.app.core.security.KeystoreSecretBox())
    }
    val syncEngine: com.notebudget.app.data.sync.SyncEngine by lazy {
        com.notebudget.app.data.sync.SyncEngine(
            store = syncStore,
            discovery = com.notebudget.app.data.sync.LanDiscovery(appContext) { syncStore.log(it) },
            data = object : com.notebudget.app.data.sync.SyncData {
                override suspend fun folders() = folderRepository.getAll().map { com.notebudget.app.data.sync.FolderInfo(it.id, it.name) }
                override suspend fun export(out: java.io.OutputStream, folderIds: Set<String>?) = export(out, folderIds, reminders = true)
                override suspend fun export(out: java.io.OutputStream, folderIds: Set<String>?, reminders: Boolean) {
                    backupService.export(out, password = null, folderIds = folderIds, withReminders = reminders)
                }
                override suspend fun import(file: File, mode: com.notebudget.app.data.transfer.ImportMode): List<Int> = import(file, mode, reminders = true)
                override suspend fun import(file: File, mode: com.notebudget.app.data.transfer.ImportMode, reminders: Boolean): List<Int> = import(file, mode, reminders, folders = null)
                override suspend fun import(file: File, mode: com.notebudget.app.data.transfer.ImportMode, reminders: Boolean, folders: Set<String>?): List<Int> {
                    val r = backupService.importFrom({ file.inputStream() }, mode, withReminders = reminders, replaceFolders = folders)
                    return listOf(r.stats.foldersAdded, r.stats.foldersMerged, r.stats.notesAdded, r.stats.notesUpdated, r.stats.notesSkipped, r.filesRestored)
                }
            },
            tempDir = File(appContext.cacheDir, "sync"),
            scope = appScope,
            freeBytes = { allocatableBytes(File(appContext.cacheDir, "sync")) },
        )
    }

    /**
     * What can be written at [dir]: the free storage plus the cached data Android clears for it on demand (the app's own cache and
     * others'), so a nearly full phone with a big cache is not refused. Falls back to the plain free storage.
     */
    private fun allocatableBytes(dir: File): Long {
        dir.mkdirs()
        return runCatching {
            val storage = appContext.getSystemService(android.os.storage.StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        }.getOrElse { dir.usableSpace }
    }

    val noteFileService: NoteFileService by lazy {
        NoteFileService(noteRepository, folderRepository, noteTypes, tagRepository = tagRepository, tagUseCases = tagUseCases, formats = settings)
    }

    val noteCloneService: NoteCloneService by lazy {
        NoteCloneService(noteRepository, noteTypes, tagRepository = tagRepository, tagUseCases = tagUseCases)
    }

    /** Called once from `Application.onCreate`. */
    fun initialize() {
        NotificationChannels.create(appContext)
        // Whenever a reminder or the setting changes, the notification in the shade follows (D154).
        appScope.launch {
            // The tasks are counted too (D266); only a change of the counts refreshes it, not every save of a note being typed.
            // Only the settings of the task notes are read (D331): before, every save of any note — and every start of the
            // process for an alarm — read all notes with their texts to count the tasks.
            val taskCounts = noteRepository.observeExtrasByType(com.notebudget.app.domain.model.NoteType.TASK)
                .map { com.notebudget.app.domain.model.TaskSummary.ofExtras(it, System.currentTimeMillis()) }
                .distinctUntilChanged()
            kotlinx.coroutines.flow.combine(reminderRepository.observeAll(), taskCounts, settings.statusNotification, settings.dateTimeFormats) { _, _, _, _ -> }
                .collect { refreshReminderStatus() }
        }
        // The process is also started for a reminder or after a reboot: housekeeping runs at most once a day, the one-time move
        // once ever, so such a start does only what it came for (D221, battery).
        val housekeeping = appContext.getSharedPreferences("housekeeping", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - housekeeping.getLong(KEY_LAST_PURGE, 0L) !in 0 until DAY_MILLIS) {
            housekeeping.edit().putLong(KEY_LAST_PURGE, now).apply()
            // Remove what has been in the trash longer than the retention setting (D65, D93).
            appScope.launch { runCatching { trashUseCases.purgeExpired(settings.trashRetentionDays.first()) } }
            appScope.launch { runCatching { mediaTrash.purgeExpired(settings.trashRetentionDays.first()) } }
        }
        // A merge whose time came while the phone was off or the alarm was not delivered (D237): done now. One query of the notes.
        appScope.launch { runCatching { budgetMerge.runDue() }.onFailure { AppLog.e("budget", "the due merges could not run", it) } }
        if (!housekeeping.getBoolean(KEY_WORD_MOVED, false)) {
            // Text notes made "formatted" in 0.9.32 - 0.9.33 become Word notes (D214): the same text and styles, the new kind.
            appScope.launch { runCatching { moveFormattedTextNotesToWord(); housekeeping.edit().putBoolean(KEY_WORD_MOVED, true).apply() } }
        }
        if (!housekeeping.getBoolean(KEY_CLOUD_FORGOTTEN, false)) {
            // Cloud backups were removed (D223): what 0.9.36 - 0.9.39 kept for them on this phone goes too.
            appScope.launch { runCatching { settings.forgetCloudBackups(); housekeeping.edit().putBoolean(KEY_CLOUD_FORGOTTEN, true).apply() } }
        }
    }

    private companion object {
        const val KEY_CLOUD_FORGOTTEN = "cloud_backups_forgotten"
        const val KEY_LAST_PURGE = "last_purge"
        const val KEY_WORD_MOVED = "rich_text_moved_to_word"
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

    private suspend fun moveFormattedTextNotesToWord() {
        noteRepository.getAllIncludingDeleted()
            .filter { it.type == com.notebudget.app.domain.model.NoteType.TEXT && com.notebudget.app.domain.model.RichDocument.isRich(it.extras) }
            .forEach { noteRepository.update(it.copy(type = com.notebudget.app.domain.model.NoteType.WORD)) }
    }
}
