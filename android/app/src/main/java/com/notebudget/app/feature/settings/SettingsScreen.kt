package com.notebudget.app.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.database.AppDatabase
import com.notebudget.app.core.mail.MailResult
import com.notebudget.app.core.mail.SmtpSettings
import com.notebudget.app.core.ui.PasswordTextField
import com.notebudget.app.feature.lock.mailErrorText
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.core.ui.theme.themeSwatch
import com.notebudget.app.data.transfer.BackupPasswordSettings
import com.notebudget.app.data.transfer.ImportMode
import com.notebudget.app.data.transfer.TransferError
import com.notebudget.app.data.transfer.TransferException
import com.notebudget.app.data.transfer.TransferFormat
import com.notebudget.app.domain.model.ColorTheme
import com.notebudget.app.domain.model.DateOrder
import com.notebudget.app.domain.model.DateStyle
import com.notebudget.app.domain.model.DateTimeFormats
import com.notebudget.app.domain.model.ExportNaming
import com.notebudget.app.domain.model.FontSize
import com.notebudget.app.domain.model.NameJoiner
import com.notebudget.app.domain.model.NotificationPrivacy
import com.notebudget.app.domain.model.StampSeparator
import com.notebudget.app.domain.model.StampSource
import com.notebudget.app.domain.model.ThemeMode
import com.notebudget.app.domain.model.TimeStyle
import com.notebudget.app.domain.usecase.TrashRetention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

@StringRes
fun TransferError.messageRes(): Int = when (this) {
    TransferError.NOT_A_BACKUP -> R.string.transfer_error_not_a_backup
    TransferError.UNSUPPORTED_VERSION -> R.string.transfer_error_unsupported_version
    TransferError.CORRUPT -> R.string.transfer_error_corrupt
    TransferError.NO_FOLDERS -> R.string.transfer_error_no_folders
    TransferError.UNRECOGNIZED_FILE -> R.string.transfer_error_unrecognized_file
    TransferError.FOLDER_NOT_FOUND -> R.string.transfer_error_folder_not_found
    TransferError.PASSWORD_REQUIRED -> R.string.transfer_error_password_required
    TransferError.WRONG_PASSWORD -> R.string.transfer_error_wrong_password
    TransferError.TOO_LARGE -> R.string.transfer_error_too_large
}

/** The pages of the settings (D145): a menu of these, each with its own options, like the settings of a phone. */
enum class SettingsSection(val id: String, @StringRes val title: Int, @StringRes val hint: Int) {
    GENERAL("general", R.string.settings_cat_general, R.string.settings_cat_general_hint),
    APPEARANCE("appearance", R.string.settings_cat_appearance, R.string.settings_cat_appearance_hint),
    NOTES("notes", R.string.settings_cat_notes, R.string.settings_cat_notes_hint),
    RECORDINGS("recordings", R.string.settings_cat_recordings, R.string.settings_cat_recordings_hint),
    SECURITY("security", R.string.settings_cat_security, R.string.settings_cat_security_hint),
    PERMISSIONS("permissions", R.string.settings_cat_permissions, R.string.settings_cat_permissions_hint),
    TRASH("trash", R.string.settings_cat_trash, R.string.settings_cat_trash_hint),
    DATA("data", R.string.settings_cat_data, R.string.settings_cat_data_hint),
    SYNC("sync", R.string.settings_cat_sync, R.string.settings_cat_sync_hint),
    ABOUT("about", R.string.settings_cat_about, R.string.settings_cat_about_hint);

    companion object {
        fun byId(id: String?): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One option of the settings: the search finds it by its [title], its [hint] and the words of its choices ([words]), and
 * opens its [section] scrolled to it.
 */
class SettingEntry(
    val id: String,
    val section: SettingsSection,
    @StringRes val title: Int,
    @StringRes val hint: Int? = null,
    val words: List<Int> = emptyList(),
)

object SettingIds {
    const val LANGUAGE = "language"
    const val DATE_TIME = "date_time"
    const val THEME = "theme"
    const val COLOR_THEME = "color_theme"
    const val CARD_TINT = "card_tint"
    const val NOTICES = "notices"
    const val FONT_SIZE = "font_size"
    const val BACKGROUNDS = "backgrounds"
    const val STAMPS = "stamps"
    const val FOOTER_STAMPS = "footer_stamps"
    const val UNDO = "undo"
    const val COMMENTS = "comments"
    const val PREVIEW = "preview"
    const val MEDIA_PREVIEW = "media_preview"
    const val TAGS = "tags"
    const val UNITS = "units"
    const val CURRENCIES = "currencies"
    const val EXPORT_NAMING = "export_naming"
    const val AUDIO_PLACE = "audio_place"
    const val REC_LIMIT_AUDIO = "rec_limit_audio"
    const val REC_LIMIT_VIDEO = "rec_limit_video"
    const val REC_LIMIT_SCREEN = "rec_limit_screen"
    const val LOCK = "lock"
    const val PRIVACY = "privacy"
    const val ALARM = "alarm"
    const val BATTERY = "battery"
    const val TRASH = "trash"
    const val BACKUP = "backup"
    const val BACKUP_PASSWORD = "backup_password"
    const val DATABASE_ENCRYPTION = "database_encryption"
    const val SYNC = "sync"
    const val HELP = "help"
    const val ABOUT = "about"
    const val APP_LOG = "app_log"
}

val SETTING_ENTRIES: List<SettingEntry> = listOf(
    SettingEntry(SettingIds.LANGUAGE, SettingsSection.GENERAL, R.string.settings_language),
    SettingEntry(SettingIds.DATE_TIME, SettingsSection.GENERAL, R.string.settings_datetime, R.string.settings_datetime_hint),
    SettingEntry(SettingIds.BATTERY, SettingsSection.GENERAL, R.string.settings_battery, null, listOf(R.string.battery_open_settings, R.string.battery_open_exact)),
    SettingEntry(SettingIds.THEME, SettingsSection.APPEARANCE, R.string.settings_theme, null, listOf(R.string.settings_theme_light, R.string.settings_theme_dark)),
    SettingEntry(SettingIds.COLOR_THEME, SettingsSection.APPEARANCE, R.string.settings_color_theme),
    SettingEntry(SettingIds.CARD_TINT, SettingsSection.APPEARANCE, R.string.settings_card_tint, R.string.settings_card_tint_hint),
    SettingEntry(SettingIds.NOTICES, SettingsSection.GENERAL, R.string.settings_notices, R.string.settings_notices_hint),
    SettingEntry(SettingIds.FONT_SIZE, SettingsSection.APPEARANCE, R.string.settings_font_size, R.string.settings_font_size_hint),
    SettingEntry(SettingIds.PREVIEW, SettingsSection.APPEARANCE, R.string.settings_preview, R.string.settings_preview_hint),
    SettingEntry(SettingIds.MEDIA_PREVIEW, SettingsSection.APPEARANCE, R.string.settings_media_preview, R.string.settings_media_preview_hint),
    SettingEntry(SettingIds.STAMPS, SettingsSection.APPEARANCE, R.string.settings_stamps, R.string.settings_stamps_hint),
    SettingEntry(SettingIds.FOOTER_STAMPS, SettingsSection.APPEARANCE, R.string.settings_footer_stamps, R.string.settings_footer_stamps_hint),
    SettingEntry(SettingIds.COMMENTS, SettingsSection.APPEARANCE, R.string.settings_comments, R.string.settings_comments_hint),
    SettingEntry(SettingIds.BACKGROUNDS, SettingsSection.APPEARANCE, R.string.settings_backgrounds, R.string.settings_backgrounds_hint),
    SettingEntry(SettingIds.TAGS, SettingsSection.NOTES, R.string.settings_tags, R.string.settings_tags_hint),
    SettingEntry(SettingIds.UNITS, SettingsSection.NOTES, R.string.settings_units, R.string.settings_units_hint),
    SettingEntry(SettingIds.CURRENCIES, SettingsSection.NOTES, R.string.settings_currencies, R.string.settings_currencies_hint),
    SettingEntry(SettingIds.EXPORT_NAMING, SettingsSection.NOTES, R.string.settings_export_naming, R.string.settings_export_naming_hint),
    SettingEntry(SettingIds.UNDO, SettingsSection.NOTES, R.string.settings_undo, R.string.settings_undo_hint),
    SettingEntry(SettingIds.AUDIO_PLACE, SettingsSection.NOTES, R.string.settings_audio_place, R.string.settings_audio_place_hint),
    SettingEntry(SettingIds.REC_LIMIT_AUDIO, SettingsSection.RECORDINGS, R.string.settings_rec_limit_audio, R.string.settings_rec_limit_hint),
    SettingEntry(SettingIds.REC_LIMIT_VIDEO, SettingsSection.RECORDINGS, R.string.settings_rec_limit_video, R.string.settings_rec_limit_hint),
    SettingEntry(SettingIds.REC_LIMIT_SCREEN, SettingsSection.RECORDINGS, R.string.settings_rec_limit_screen, R.string.settings_rec_limit_hint),
    SettingEntry(SettingIds.LOCK, SettingsSection.SECURITY, R.string.settings_lock, R.string.settings_lock_hint),
    SettingEntry(SettingIds.PRIVACY, SettingsSection.SECURITY, R.string.settings_privacy, R.string.settings_privacy_hint),
    SettingEntry(SettingIds.ALARM, SettingsSection.SECURITY, R.string.settings_alarm, R.string.settings_alarm_hint, listOf(R.string.battery_economy)),
    SettingEntry(SettingIds.TRASH, SettingsSection.TRASH, R.string.settings_trash, R.string.settings_trash_hint, listOf(R.string.settings_trash_never)),
    SettingEntry(SettingIds.BACKUP, SettingsSection.DATA, R.string.settings_data, R.string.settings_data_hint, listOf(R.string.settings_export_all, R.string.settings_import_all)),
    SettingEntry(SettingIds.BACKUP_PASSWORD, SettingsSection.DATA, R.string.settings_backup_password, R.string.settings_backup_password_hint),
    SettingEntry(SettingIds.DATABASE_ENCRYPTION, SettingsSection.DATA, R.string.settings_db_encryption, R.string.settings_db_encryption_hint),
    SettingEntry(
        "permissions", SettingsSection.PERMISSIONS, R.string.settings_cat_permissions, R.string.settings_cat_permissions_hint,
        listOf(R.string.perm_notifications, R.string.perm_exact_alarms, R.string.perm_full_screen, R.string.perm_overlay, R.string.perm_microphone, R.string.perm_camera, R.string.perm_location, R.string.perm_revoke_all),
    ),
    SettingEntry(SettingIds.SYNC, SettingsSection.SYNC, R.string.settings_sync, R.string.settings_sync_hint, listOf(R.string.sync_wait, R.string.sync_find, R.string.sync_connections)),
    SettingEntry(SettingIds.HELP, SettingsSection.ABOUT, R.string.settings_help),
    SettingEntry(SettingIds.ABOUT, SettingsSection.ABOUT, R.string.about_title),
    SettingEntry(SettingIds.APP_LOG, SettingsSection.ABOUT, R.string.app_log, R.string.app_log_hint),
)

/** The options that match [query] (every word of it, in the title, the hint, a choice or the page name); a blank query matches none. */
fun searchSettings(query: String, text: (Int) -> String): List<SettingEntry> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return SETTING_ENTRIES.filter { entry ->
        val haystack = buildString {
            append(text(entry.title)).append(' ')
            entry.hint?.let { append(text(it)).append(' ') }
            entry.words.forEach { append(text(it)).append(' ') }
            append(text(entry.section.title))
        }.lowercase()
        words.all { it in haystack }
    }
}

/**
 * Settings as a menu of pages with a search (D145). [initialSection] / [initialEntry] open a page and scroll to an option
 * straight away (the trash has a link to its own settings); the Back button of such a visit returns to where it came from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenUnits: () -> Unit,
    onOpenCurrencies: () -> Unit = {},
    onOpenTrash: () -> Unit,
    /** The Reminders page, from the menu of the settings (D349). */
    onOpenReminders: () -> Unit = {},
    onOpenTags: () -> Unit,
    onOpenBackgrounds: () -> Unit,
    onOpenLock: () -> Unit,
    initialSection: String? = null,
    initialEntry: String? = null,
) {
    val container = rememberAppContainer()
    val settings = container.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf(initialSection) }
    var focus by rememberSaveable { mutableStateOf(initialEntry) }
    var query by rememberSaveable { mutableStateOf("") }
    val openedDirectly = initialSection != null
    val current = SettingsSection.byId(section)

    // Back goes up one level: a page returns to the menu, unless the page was opened from another screen.
    BackHandler(enabled = current != null && !openedDirectly) { section = null; focus = null }

    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var passwordFor by remember { mutableStateOf<PasswordRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A big backup takes minutes (D174): the person is told, and sees how many files are done.
    var filesDone by remember { mutableStateOf(0) }
    var filesTotal by remember { mutableStateOf(0) }

    fun exportTo(uri: Uri) {
        scope.launch {
            busy = true
            filesDone = 0
            filesTotal = 0
            val result = runCatching {
                val password = container.backupService.exportPassword()
                container.toTheEnd {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        container.backupService.export(it, password) { done, total -> filesDone = done; filesTotal = total }
                    } ?: error("Cannot open $uri")
                }
            }
            busy = false
            val report = result.getOrNull()
            result.onFailure { com.notebudget.app.core.common.AppLog.critical("backup", "the backup could not be written", it) }
            report?.let { com.notebudget.app.core.common.AppLog.i("backup", "backup written, " + it.filesSkipped + " files skipped") }
            when {
                report == null -> toast(context, R.string.backup_export_failed)
                report.filesSkipped > 0 -> Toast.makeText(context, context.getString(R.string.backup_export_partial, report.filesSkipped), Toast.LENGTH_LONG).show()
                else -> toast(context, R.string.backup_export_done)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) exportTo(uri) }
    val protectedExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) exportTo(uri) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    /** Imports [uri]; a protected file first tries the saved password, and asks for one when that does not open it. */
    fun runImport(uri: Uri, mode: ImportMode, typed: String?) {
        scope.launch {
            busy = true
            filesDone = 0
            filesTotal = 0
            val result = runCatching {
                container.toTheEnd {
                    // The file is opened again for its second pass, so nothing is copied to a temporary place first.
                    suspend fun attempt(password: String?) = container.backupService.importFrom(
                        { context.contentResolver.openInputStream(uri) }, mode, password,
                    ) { done -> filesDone = done }
                    try {
                        attempt(typed)
                    } catch (e: TransferException) {
                        val saved = container.backupService.savedPassword()
                        if (typed == null && e.error == TransferError.PASSWORD_REQUIRED && saved != null) attempt(saved) else throw e
                    }
                }
            }
            busy = false
            result.onSuccess { report ->
                passwordFor = null
                val s = report.stats
                com.notebudget.app.core.common.AppLog.i("backup", "restored (" + mode.name + "): notes added " + s.notesAdded + ", updated " + s.notesUpdated + ", skipped " + s.notesSkipped + ", files skipped " + report.filesSkipped)
                val done = context.getString(R.string.backup_import_done, s.notesAdded, s.notesUpdated, s.notesSkipped, s.foldersAdded)
                Toast.makeText(
                    context,
                    if (report.filesSkipped > 0) done + "\n" + context.getString(R.string.backup_import_files_skipped, report.filesSkipped) else done,
                    Toast.LENGTH_LONG,
                ).show()
                // A full restore can change language and theme: rebuild the screen.
                if (mode == ImportMode.REPLACE) context.findActivity()?.recreate()
            }.onFailure { e ->
                val error = (e as? TransferException)?.error
                if (error != TransferError.PASSWORD_REQUIRED && error != TransferError.WRONG_PASSWORD) {
                    com.notebudget.app.core.common.AppLog.critical("backup", "the backup could not be restored (" + mode.name + ", " + (error?.name ?: "unexpected") + ")", e)
                }
                if (error == TransferError.PASSWORD_REQUIRED || error == TransferError.WRONG_PASSWORD) {
                    passwordFor = PasswordRequest(uri, mode, wrong = error == TransferError.WRONG_PASSWORD || typed != null)
                } else {
                    passwordFor = null
                    toast(context, error?.messageRes() ?: R.string.transfer_error_generic)
                }
            }
        }
    }

    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.backup_busy_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_busy_message))
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (filesDone > 0) {
                        Text(
                            if (filesTotal > 0) stringResource(R.string.backup_busy_files_of, filesDone, filesTotal)
                            else stringResource(R.string.backup_busy_files, filesDone),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(current?.title ?: R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (current != null && !openedDirectly) { section = null; focus = null } else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            SettingsMenu(
                query = query,
                onQuery = { query = it },
                onOpen = { s, entry -> query = ""; section = s.id; focus = entry },
                onOpenReminders = onOpenReminders,
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (current) {
                    SettingsSection.GENERAL -> {
                        SettingBlock(SettingIds.LANGUAGE, focus, R.string.settings_language) { LanguageOptions() }
                        SettingBlock(SettingIds.DATE_TIME, focus, R.string.settings_datetime) { DateTimeOptions() }
                        SettingBlock(SettingIds.BATTERY, focus, R.string.settings_battery) { BatteryOptions() }
                        SettingBlock(SettingIds.NOTICES, focus, R.string.settings_notices) { NoticeOptions() }
                    }
                    SettingsSection.APPEARANCE -> {
                        SettingBlock(SettingIds.THEME, focus, R.string.settings_theme) { ThemeOptions() }
                        SettingBlock(SettingIds.COLOR_THEME, focus, R.string.settings_color_theme) { ColorThemeOptions() }
                        SettingBlock(SettingIds.CARD_TINT, focus, R.string.settings_card_tint) { CardTintOptions() }
                        SettingBlock(SettingIds.FONT_SIZE, focus, R.string.settings_font_size) { FontSizeOptions() }
                        SettingBlock(SettingIds.PREVIEW, focus, R.string.settings_preview) { PreviewOptions() }
                        SettingBlock(SettingIds.MEDIA_PREVIEW, focus, R.string.settings_media_preview) { MediaPreviewOptions() }
                        SettingBlock(SettingIds.STAMPS, focus, R.string.settings_stamps) { StampOptions() }
                        SettingBlock(SettingIds.FOOTER_STAMPS, focus, R.string.settings_footer_stamps) { FooterStampOptions() }
                        SettingBlock(SettingIds.COMMENTS, focus, R.string.settings_comments) { CommentOptions() }
                        SettingBlock(SettingIds.BACKGROUNDS, focus, R.string.settings_backgrounds) {
                            Hint(R.string.settings_backgrounds_hint)
                            OutlinedButton(onClick = onOpenBackgrounds, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_backgrounds_open)) }
                        }
                    }
                    SettingsSection.NOTES -> {
                        SettingBlock(SettingIds.TAGS, focus, R.string.settings_tags) {
                            Hint(R.string.settings_tags_hint)
                            OutlinedButton(onClick = onOpenTags, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_tags_open)) }
                        }
                        SettingBlock(SettingIds.UNITS, focus, R.string.settings_units) {
                            Hint(R.string.settings_units_hint)
                            OutlinedButton(onClick = onOpenUnits, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_units_open)) }
                        }
                        SettingBlock(SettingIds.CURRENCIES, focus, R.string.settings_currencies) {
                            Hint(R.string.settings_currencies_hint)
                            OutlinedButton(onClick = onOpenCurrencies, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_currencies_open)) }
                        }
                        SettingBlock(SettingIds.EXPORT_NAMING, focus, R.string.settings_export_naming) { ExportNamingOptions() }
                        SettingBlock(SettingIds.UNDO, focus, R.string.settings_undo) { UndoOptions() }
                        SettingBlock(SettingIds.AUDIO_PLACE, focus, R.string.settings_audio_place) { AudioPlaceOption() }
                    }
                    SettingsSection.RECORDINGS -> {
                        // Whether Android lets Notio record in the background at all (D249): read again when the person comes back.
                        val resumedRec = com.notebudget.app.core.ui.rememberResumeCount()
                        val restricted = remember(resumedRec) { com.notebudget.app.core.common.BackgroundRestriction.isRestricted(context) }
                        Text(
                            stringResource(if (restricted) R.string.settings_background_restricted else R.string.settings_background_ok),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (restricted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        if (restricted) {
                            Hint(R.string.rec_restricted_text)
                            OutlinedButton(onClick = { com.notebudget.app.core.common.BackgroundRestriction.openSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.rec_restricted_open))
                            }
                        }
                        // How long each kind of recording may last (D251): 4 minutes by default, 0 = until stopped.
                        listOf(
                            Triple(SettingIds.REC_LIMIT_AUDIO, R.string.settings_rec_limit_audio, com.notebudget.app.domain.model.RecordingKind.AUDIO),
                            Triple(SettingIds.REC_LIMIT_VIDEO, R.string.settings_rec_limit_video, com.notebudget.app.domain.model.RecordingKind.VIDEO),
                            Triple(SettingIds.REC_LIMIT_SCREEN, R.string.settings_rec_limit_screen, com.notebudget.app.domain.model.RecordingKind.SCREEN),
                        ).forEach { (id, title, kind) ->
                            SettingBlock(id, focus, title) {
                                Hint(R.string.settings_rec_limit_hint)
                                val limit by remember(kind) { settings.recordingLimit(kind) }.collectAsState(initial = com.notebudget.app.domain.model.RecordingLimit.DEFAULT)
                                com.notebudget.app.core.ui.RecordingLimitEditor(limit) { next -> scope.launch { settings.setRecordingLimit(kind, next) } }
                            }
                        }
                    }
                    SettingsSection.SECURITY -> {
                        SettingBlock(SettingIds.LOCK, focus, R.string.settings_lock) {
                            Hint(R.string.settings_lock_hint)
                            OutlinedButton(onClick = onOpenLock, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_lock_open)) }
                        }
                        SettingBlock(SettingIds.PRIVACY, focus, R.string.settings_privacy) { PrivacyOptions() }
                        SettingBlock(SettingIds.ALARM, focus, R.string.settings_alarm) { AlarmOptions() }
                    }
                    SettingsSection.TRASH -> {
                        SettingBlock(SettingIds.TRASH, focus, R.string.settings_trash) {
                            Hint(R.string.settings_trash_hint)
                            OutlinedButton(onClick = onOpenTrash, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_trash_open)) }
                            val trashDays by settings.trashRetentionDays.collectAsState(initial = TrashRetention.DEFAULT_DAYS)
                            TrashRetention.OPTIONS.forEach { days ->
                                val label = if (days == TrashRetention.NEVER) stringResource(R.string.settings_trash_never)
                                else stringResource(R.string.settings_trash_days, days)
                                OptionRow(label, selected = trashDays == days) { scope.launch { settings.setTrashRetentionDays(days) } }
                            }
                        }
                    }
                    SettingsSection.DATA -> {
                        SettingBlock(SettingIds.BACKUP, focus, R.string.settings_data) {
                            Hint(R.string.settings_data_hint)
                            Hint(R.string.settings_backup_media_note)
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val protectedFile = container.backupService.exportPassword() != null
                                        val name = container.backupService.suggestedFileName(protectedFile)
                                        if (protectedFile) protectedExportLauncher.launch(name) else exportLauncher.launch(name)
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.settings_export_all)) }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.settings_import_all)) }
                        }
                        SettingBlock(SettingIds.BACKUP_PASSWORD, focus, R.string.settings_backup_password) { BackupPasswordOptions() }
                        SettingBlock(SettingIds.DATABASE_ENCRYPTION, focus, R.string.settings_db_encryption) { DatabaseEncryptionOptions() }
                    }
                    // Every right of the app, given and taken back in one place (D354).
                    SettingsSection.PERMISSIONS -> com.notebudget.app.core.ui.tip.HelpTopic("permissions") { PermissionsOptions() }
                    SettingsSection.SYNC -> {
                        SettingBlock(SettingIds.SYNC, focus, R.string.settings_sync) { SyncOptions() }
                    }
                    SettingsSection.ABOUT -> {
                        SettingBlock(SettingIds.HELP, focus, R.string.settings_help) {
                            OutlinedButton(onClick = onOpenHelp, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_open_help)) }
                        }
                        SettingBlock(SettingIds.ABOUT, focus, R.string.about_title) {
                            val (versionName, buildNumber) = appVersion(context)
                            Text(stringResource(R.string.about_version, versionName, buildNumber), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.about_edition), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.about_developer), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.about_made_for), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.about_formats, TransferFormat.FORMAT_VERSION, AppDatabase.VERSION),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // The credits the licences ask for (D225): SQLCipher wants its notice in the app's interface.
                            Text(stringResource(R.string.about_credits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            var licences by remember { mutableStateOf(false) }
                            com.notebudget.app.core.ui.tip.TextButton(onClick = { licences = true }) { Text(stringResource(R.string.about_licenses)) }
                            if (licences) LicencesDialog(onClose = { licences = false })
                        }
                        SettingBlock(SettingIds.APP_LOG, focus, R.string.app_log) {
                            Text(stringResource(R.string.app_log_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            var showAppLog by remember { mutableStateOf(false) }
                            OutlinedButton(onClick = { showAppLog = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_log)) }
                            if (showAppLog) AppLogDialog(onClose = { showAppLog = false })
                            AppLogOptions()
                        }
                    }
                }
            }
        }
    }

    pendingImport?.let { uri ->
        ImportModeDialog(
            onDismiss = { pendingImport = null },
            onConfirm = { mode ->
                pendingImport = null
                runImport(uri, mode, typed = null)
            },
        )
    }

    passwordFor?.let { request ->
        ImportPasswordDialog(
            wrong = request.wrong,
            onDismiss = { passwordFor = null },
            onConfirm = { password -> runImport(request.uri, request.mode, typed = password) },
        )
    }
}

/** A protected backup waiting for its password; [wrong] when the last try did not open it. */
private class PasswordRequest(val uri: Uri, val mode: ImportMode, val wrong: Boolean)

@Composable
private fun SettingsMenu(query: String, onQuery: (String) -> Unit, onOpen: (SettingsSection, String?) -> Unit, modifier: Modifier = Modifier, onOpenReminders: () -> Unit = {}) {
    val context = LocalContext.current
    val results = remember(query) { searchSettings(query) { context.getString(it) } }
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close))
                    }
                }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isBlank()) {
                SettingsSection.entries.forEach { s ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(s, null) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(s.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(s.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // The reminders have a page of their own (D309, D318); the menu of the settings leads there too (D349), as the
                    // icon of the main screen does.
                    if (s == SettingsSection.NOTES) {
                        Card(Modifier.fillMaxWidth().clickable { onOpenReminders() }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.reminders_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.settings_cat_reminders_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else if (results.isEmpty()) {
                Text(stringResource(R.string.settings_search_none), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
            } else {
                results.forEach { entry ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(entry.section, entry.id) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(entry.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(entry.section.title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            entry.hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

/** A titled option; when the search led here ([focus] is its id) it scrolls into view and is highlighted for a moment. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingBlock(id: String, focus: String?, @StringRes title: Int, content: @Composable () -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(focus) {
        if (focus == id) {
            highlighted = true
            delay(150)
            requester.bringIntoView()
            delay(2000)
            highlighted = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(MaterialTheme.shapes.medium)
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Hint(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LanguageOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val languageTag by settings.languageTag.collectAsState(initial = null)
    val options = listOf<Pair<String?, String>>(
        null to stringResource(R.string.settings_language_system),
        "en" to "English",
        "ru" to "Русский",
        "be" to "Беларуская",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "it" to "Italiano",
        "zh" to "中文（简体）",
        "ja" to "日本語",
        "ko" to "한국어",
    )
    options.forEach { (tag, label) ->
        OptionRow(label, selected = languageTag == tag) {
            scope.launch {
                settings.setLanguageTag(tag)
                // The language is applied when the activity is created.
                context.findActivity()?.recreate()
            }
        }
    }
}

@Composable
private fun ThemeOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val theme by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    OptionRow(stringResource(R.string.settings_theme_system), theme == ThemeMode.SYSTEM) { scope.launch { settings.setThemeMode(ThemeMode.SYSTEM) } }
    OptionRow(stringResource(R.string.settings_theme_light), theme == ThemeMode.LIGHT) { scope.launch { settings.setThemeMode(ThemeMode.LIGHT) } }
    OptionRow(stringResource(R.string.settings_theme_dark), theme == ThemeMode.DARK) { scope.launch { settings.setThemeMode(ThemeMode.DARK) } }
}

@Composable
private fun ColorThemeOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val colorTheme by settings.colorTheme.collectAsState(initial = ColorTheme.GREEN)
    val customColor by settings.customThemeColor.collectAsState(initial = com.notebudget.app.core.ui.theme.DEFAULT_CUSTOM_THEME)
    var picking by remember { mutableStateOf(false) }
    ColorTheme.entries.forEach { option ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = colorTheme == option, onClick = { scope.launch { settings.setColorTheme(option) } }, role = Role.RadioButton)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = colorTheme == option, onClick = null)
            Box(
                Modifier
                    .padding(start = 12.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(themeSwatch(option, customColor))
                    // White and black dots need an outline to be seen on either page.
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
            Text(stringResource(colorThemeName(option)), Modifier.padding(start = 12.dp).weight(1f))
            // Any colour (D219): the same RGB tool as for notes and folders.
            if (option == ColorTheme.CUSTOM) {
                OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.theme_custom_pick)) }
            }
        }
    }
    if (picking) {
        com.notebudget.app.feature.colors.ColorPickerDialog(
            initial = customColor,
            onPick = { argb ->
                picking = false
                scope.launch {
                    settings.setCustomThemeColor(argb)
                    settings.setColorTheme(ColorTheme.CUSTOM)
                }
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun FontSizeOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val fontSize by settings.fontSize.collectAsState(initial = FontSize.NORMAL)
    Hint(R.string.settings_font_size_hint)
    FontSize.entries.forEach { option ->
        OptionRow(stringResource(fontSizeName(option)), fontSize == option) { scope.launch { settings.setFontSize(option) } }
    }
}

@Composable
private fun PrivacyOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val privacy by settings.notificationPrivacy.collectAsState(initial = NotificationPrivacy.GENERIC)
    Hint(R.string.settings_privacy_hint)
    OptionRow(stringResource(R.string.settings_privacy_generic), privacy == NotificationPrivacy.GENERIC) { scope.launch { settings.setNotificationPrivacy(NotificationPrivacy.GENERIC) } }
    OptionRow(stringResource(R.string.settings_privacy_title_only), privacy == NotificationPrivacy.TITLE_ONLY) { scope.launch { settings.setNotificationPrivacy(NotificationPrivacy.TITLE_ONLY) } }
    OptionRow(stringResource(R.string.settings_privacy_full), privacy == NotificationPrivacy.FULL) { scope.launch { settings.setNotificationPrivacy(NotificationPrivacy.FULL) } }
}

/** How many lines a note card previews: none, one or two (D159). */
@Composable
private fun PreviewOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val lines by settings.previewLines.collectAsState(initial = 2)
    Hint(R.string.settings_preview_hint)
    OptionRow(stringResource(R.string.settings_preview_0), lines == 0) { scope.launch { settings.setPreviewLines(0) } }
    OptionRow(stringResource(R.string.settings_preview_1), lines == 1) { scope.launch { settings.setPreviewLines(1) } }
    OptionRow(stringResource(R.string.settings_preview_2), lines == 2) { scope.launch { settings.setPreviewLines(2) } }
}

/** How long a message at the bottom of the screen stays (D360): 0..60 seconds, 0 = no messages. */
@Composable
private fun NoticeOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val saved by settings.noticeSeconds.collectAsState(initial = com.notio.common.NoticeTime.DEFAULT)
    // The slider moves freely; the value is written when the finger lifts, not on every step.
    var value by remember(saved) { mutableStateOf(saved.toFloat()) }
    Hint(R.string.settings_notices_hint)
    Text(
        if (value.toInt() == 0) stringResource(R.string.settings_notices_off) else stringResource(R.string.settings_notices_value, value.toInt()),
        style = MaterialTheme.typography.bodyMedium,
    )
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { scope.launch { settings.setNoticeSeconds(value.toInt()) } },
        valueRange = com.notio.common.NoticeTime.MIN.toFloat()..com.notio.common.NoticeTime.MAX.toFloat(),
        steps = com.notio.common.NoticeTime.MAX - com.notio.common.NoticeTime.MIN - 1,
    )
}

/** How strongly note and folder cards show their colour (D273), with a card drawn in it as a sample. */
@Composable
private fun CardTintOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val saved by settings.cardTint.collectAsState(initial = com.notebudget.app.core.datastore.CardTint.DEFAULT)
    // The slider moves freely; the value is written when the finger lifts, not on every step.
    var value by remember(saved) { mutableStateOf(saved.toFloat()) }
    Hint(R.string.settings_card_tint_hint)
    Text(stringResource(R.string.settings_card_tint_value, value.toInt()), style = MaterialTheme.typography.bodyMedium)
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { scope.launch { settings.setCardTint(value.toInt()) } },
        valueRange = com.notebudget.app.core.datastore.CardTint.MIN.toFloat()..com.notebudget.app.core.datastore.CardTint.MAX.toFloat(),
    )
    val sample = androidx.compose.ui.graphics.Color(0xFF3366FF).copy(alpha = value / 100f).compositeOver(MaterialTheme.colorScheme.surface)
    com.notebudget.app.core.ui.ReadableOn(sample) {
        androidx.compose.material3.Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = sample, contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.settings_card_tint_sample), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_card_tint_sample_line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The picture on the card: for photo / video / screen notes none, the first, or a deck of three; for drawings on or off (D160). */
@Composable
private fun MediaPreviewOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val mode by settings.mediaPreview.collectAsState(initial = com.notebudget.app.domain.model.MediaPreviewMode.FIRST)
    val drawing by settings.drawingPreview.collectAsState(initial = true)
    Hint(R.string.settings_media_preview_hint)
    com.notebudget.app.domain.model.MediaPreviewMode.entries.forEach { option ->
        val label = when (option) {
            com.notebudget.app.domain.model.MediaPreviewMode.NONE -> R.string.settings_media_preview_none
            com.notebudget.app.domain.model.MediaPreviewMode.FIRST -> R.string.settings_media_preview_first
            com.notebudget.app.domain.model.MediaPreviewMode.DECK -> R.string.settings_media_preview_deck
        }
        OptionRow(stringResource(label), mode == option) { scope.launch { settings.setMediaPreview(option) } }
    }
    Text(stringResource(R.string.settings_drawing_preview), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    OptionRow(stringResource(R.string.settings_drawing_preview_on), drawing) { scope.launch { settings.setDrawingPreview(true) } }
    OptionRow(stringResource(R.string.settings_drawing_preview_off), !drawing) { scope.launch { settings.setDrawingPreview(false) } }
}

/** Four switches (D157): the creation and the change time stamp of notes, and of folders; only what is shown changes. */
@Composable
internal fun StampOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val display by settings.stampDisplay.collectAsState(initial = com.notebudget.app.domain.model.StampDisplay())
    fun save(next: com.notebudget.app.domain.model.StampDisplay) { scope.launch { settings.setStampDisplay(next) } }

    @Composable
    fun Switched(label: Int, on: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = onChange)
        }
    }
    Hint(R.string.settings_stamps_hint)
    Text(stringResource(R.string.settings_stamps_notes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    Switched(R.string.settings_stamp_created, display.notesCreated) { save(display.copy(notesCreated = it)) }
    Switched(R.string.settings_stamp_modified, display.notesModified) { save(display.copy(notesModified = it)) }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text(stringResource(R.string.settings_stamps_folders), style = MaterialTheme.typography.titleSmall)
    Switched(R.string.settings_stamp_created, display.foldersCreated) { save(display.copy(foldersCreated = it)) }
    Switched(R.string.settings_stamp_modified, display.foldersModified) { save(display.copy(foldersModified = it)) }
}

/**
 * The stamps at the bottom of an open note, for every kind on its own (D198): a tab (chip) per kind and, under it, the two
 * switches of that kind. How a moment is written is the global format of "Language and formats".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FooterStampOptions() {
    val container = rememberAppContainer()
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val stamps by settings.footerStamps.collectAsState(initial = com.notebudget.app.domain.model.NoteFooterStamps())
    val kinds = container.noteTypes.creatable
    var picked by rememberSaveable { mutableStateOf(kinds.firstOrNull()?.type?.id) }
    val kind = kinds.firstOrNull { it.type.id == picked } ?: kinds.firstOrNull() ?: return
    fun save(next: com.notebudget.app.domain.model.NoteFooterStamps) { scope.launch { settings.setFooterStamps(next) } }
    Hint(R.string.settings_footer_stamps_hint)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        kinds.forEach { k ->
            val on = stamps.showsCreated(k.type) || stamps.showsModified(k.type)
            FilterChip(
                selected = k.type == kind.type,
                onClick = { picked = k.type.id },
                label = { Text(stringResource(k.labelRes) + if (on) " •" else "") },
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_stamp_created), Modifier.weight(1f))
        Switch(checked = stamps.showsCreated(kind.type), onCheckedChange = { save(stamps.withCreated(kind.type, it)) })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_stamp_modified), Modifier.weight(1f))
        Switch(checked = stamps.showsModified(kind.type), onCheckedChange = { save(stamps.withModified(kind.type, it)) })
    }
}

/** How many steps back Undo goes in the editors of notes (D197). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UndoOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val steps by settings.undoSteps.collectAsState(initial = com.notebudget.app.core.common.UndoHistory.DEFAULT_STEPS)
    Hint(R.string.settings_undo_hint)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.notebudget.app.core.common.UndoHistory.CHOICES.forEach { n ->
            FilterChip(steps == n, { scope.launch { settings.setUndoSteps(n) } }, { Text(n.toString()) })
        }
    }
}

/** Two switches (D177): whether the cards of notes and of folders show the first 16 characters of their comment. */
@Composable
private fun CommentOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val display by settings.commentDisplay.collectAsState(initial = com.notebudget.app.domain.model.CommentDisplay())
    fun save(next: com.notebudget.app.domain.model.CommentDisplay) { scope.launch { settings.setCommentDisplay(next) } }

    Hint(R.string.settings_comments_hint)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_comments_notes), Modifier.weight(1f))
        Switch(checked = display.notes, onCheckedChange = { save(display.copy(notes = it)) })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_comments_folders), Modifier.weight(1f))
        Switch(checked = display.folders, onCheckedChange = { save(display.copy(folders = it)) })
    }
}

/** Snooze length and the permission for the full-screen alarm over the lock screen (D150). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmOptions() {
    val container = rememberAppContainer()
    val settings = container.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val minutes by settings.snoozeMinutes.collectAsState(initial = 10)
    Hint(R.string.settings_alarm_hint)
    Text(stringResource(R.string.settings_snooze), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.notebudget.app.core.datastore.SettingsDataStore.SNOOZE_CHOICES.forEach { m ->
            FilterChip(minutes == m, { scope.launch { settings.setSnoozeMinutes(m) } }, { Text(stringResource(R.string.settings_snooze_minutes, m)) })
        }
    }
    val statusOn by settings.statusNotification.collectAsState(initial = true)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_status_notification), Modifier.weight(1f))
        Switch(checked = statusOn, onCheckedChange = { scope.launch { settings.setStatusNotification(it) } })
    }
    Hint(R.string.settings_status_notification_hint)
    // The battery saver stands with the reminders it makes late (D309; before: Settings -> General -> Battery, D308).
    var saving by remember { mutableStateOf(com.notebudget.app.core.common.PowerSaving.isOn(context)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.battery_economy), Modifier.weight(1f))
        Switch(
            checked = saving,
            onCheckedChange = { on ->
                saving = on
                com.notebudget.app.core.common.PowerSaving.set(context, on)
                // The alarms that are set follow the switch at once.
                scope.launch {
                    runCatching { container.budgetMerge.rescheduleAll() }
                    runCatching { container.reminderUseCases.restoreAll() }
                }
            },
        )
    }
    Hint(R.string.battery_economy_hint)
    // Read again every time the screen comes back (D199): the person grants these in the phone's settings and returns here;
    // read once, the page kept saying "not allowed" in red although the switch was already on.
    val resumed = com.notebudget.app.core.ui.rememberResumeCount()
    val fullScreenOk = remember(resumed) { com.notebudget.app.core.notification.ReminderNotifier.canUseFullScreen(context) }
    val overlayOk = remember(resumed) { android.provider.Settings.canDrawOverlays(context) }
    Text(
        stringResource(if (fullScreenOk) R.string.alarm_status_fullscreen_ok else R.string.alarm_status_fullscreen_no),
        style = MaterialTheme.typography.bodyMedium,
        color = if (fullScreenOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    Text(
        stringResource(if (overlayOk) R.string.alarm_status_overlay_ok else R.string.alarm_status_overlay_no),
        style = MaterialTheme.typography.bodyMedium,
        color = if (overlayOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    if (!overlayOk) {
        Text(stringResource(R.string.alarm_overlay_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.rec_floating_allow)) }
    }
    OutlinedButton(
        onClick = {
            val alarms = context.getSystemService(android.app.AlarmManager::class.java)
            val at = System.currentTimeMillis() + 10_000
            val intent = android.content.Intent(context, com.notebudget.app.core.alarm.ReminderReceiver::class.java)
                .setAction(com.notebudget.app.core.alarm.ReminderReceiver.ACTION_TEST)
                .setData(Uri.parse("notebudget://reminder/test"))
            val pending = android.app.PendingIntent.getBroadcast(context, 9, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            runCatching { alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending) }
            Toast.makeText(context, context.getString(R.string.alarm_test_started), Toast.LENGTH_LONG).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.alarm_test)) }
    if (!fullScreenOk) {
        Text(stringResource(R.string.settings_fullscreen_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        // Shown only on Android 14+ (canUseFullScreen is always true before 34).
                        //noinspection InlinedApi
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName)),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_fullscreen_open)) }
    }
}

/** A fixed moment (2026-09-20 15:07:09 UTC) so that every choice of a format shows what it looks like. */
private const val SAMPLE_MOMENT = 1_789_916_829_000L

@Composable
private fun DateTimeOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val formats by settings.dateTimeFormats.collectAsState(initial = DateTimeFormats())
    Hint(R.string.settings_datetime_hint)
    Text(stringResource(R.string.settings_date_label), style = MaterialTheme.typography.titleSmall)
    DateStyle.entries.forEach { style ->
        val sample = formats.copy(date = style).formatDate(SAMPLE_MOMENT)
        OptionRow(if (style == DateStyle.SYSTEM) stringResource(R.string.format_system) + " (" + sample + ")" else sample, formats.date == style) {
            scope.launch { settings.setDateTimeFormats(formats.copy(date = style)) }
        }
    }
    Text(stringResource(R.string.settings_time_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    TimeStyle.entries.forEach { style ->
        val sample = formats.copy(time = style).formatTime(SAMPLE_MOMENT)
        OptionRow(if (style == TimeStyle.SYSTEM) stringResource(R.string.format_system) + " (" + sample + ")" else sample, formats.time == style) {
            scope.launch { settings.setDateTimeFormats(formats.copy(time = style)) }
        }
    }
    Text(stringResource(R.string.settings_example, formats.format(SAMPLE_MOMENT)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

/** "Note the place of audio recordings": switching it on asks for the location permission right away (D148). */
@Composable
private fun AudioPlaceOption() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val on by settings.audioPlace.collectAsState(initial = false)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Hint(R.string.settings_audio_place_hint)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_audio_place), Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { checked ->
            scope.launch { settings.setAudioPlace(checked) }
            if (checked) permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportNamingOptions() {
    val settings = rememberAppContainer().settings
    val scope = rememberCoroutineScope()
    val naming by settings.exportNaming.collectAsState(initial = ExportNaming())
    val zone = remember { ZoneId.systemDefault() }
    fun save(next: ExportNaming) { scope.launch { settings.setExportNaming(next) } }

    @Composable
    fun Check(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = checked, onClick = { onChange(!checked) }, role = Role.Checkbox),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(stringResource(label), Modifier.padding(start = 12.dp))
        }
    }

    Hint(R.string.settings_export_naming_hint)
    Check(R.string.naming_note_title, naming.useNoteTitle) { save(naming.copy(useNoteTitle = it)) }
    Check(R.string.naming_item_title, naming.useItemTitle) { save(naming.copy(useItemTitle = it)) }
    Check(R.string.naming_date, naming.useDate) { save(naming.copy(useDate = it)) }
    Check(R.string.naming_time, naming.useTime) { save(naming.copy(useTime = it)) }

    if (naming.useDate || naming.useTime) {
        Text(stringResource(R.string.naming_source), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        OptionRow(stringResource(R.string.naming_source_export), naming.stampSource == StampSource.EXPORT_MOMENT) { save(naming.copy(stampSource = StampSource.EXPORT_MOMENT)) }
        OptionRow(stringResource(R.string.naming_source_created), naming.stampSource == StampSource.CREATED) { save(naming.copy(stampSource = StampSource.CREATED)) }
        OptionRow(stringResource(R.string.naming_source_modified), naming.stampSource == StampSource.MODIFIED) { save(naming.copy(stampSource = StampSource.MODIFIED)) }
    }
    if (naming.useDate) {
        Text(stringResource(R.string.naming_date_format), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DateOrder.entries.forEach { order ->
                FilterChip(naming.dateOrder == order, { save(naming.copy(dateOrder = order)) }, { Text(naming.copy(dateOrder = order).dateText(SAMPLE_MOMENT, zone)) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StampSeparator.entries.forEach { sep ->
                FilterChip(naming.dateSeparator == sep, { save(naming.copy(dateSeparator = sep)) }, { Text(separatorLabel(sep)) })
            }
        }
    }
    if (naming.useTime) {
        Text(stringResource(R.string.naming_time_format), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StampSeparator.entries.forEach { sep ->
                FilterChip(naming.timeSeparator == sep, { save(naming.copy(timeSeparator = sep)) }, { Text(separatorLabel(sep)) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.naming_twelve_hour), Modifier.weight(1f))
            Switch(checked = naming.twelveHour, onCheckedChange = { save(naming.copy(twelveHour = it)) })
        }
    }
    Text(stringResource(R.string.naming_joiner), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NameJoiner.entries.forEach { joiner ->
            FilterChip(naming.joiner == joiner, { save(naming.copy(joiner = joiner)) }, { Text(joinerLabel(joiner)) })
        }
    }
    val example = naming.baseName(
        stringResource(R.string.naming_example_note), stringResource(R.string.naming_example_item),
        SAMPLE_MOMENT, SAMPLE_MOMENT, SAMPLE_MOMENT, zone,
    )
    Text(stringResource(R.string.naming_example, example), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun separatorLabel(sep: StampSeparator): String = when (sep) {
    StampSeparator.DASH -> "-"
    StampSeparator.DOT -> "."
    StampSeparator.SOLID -> stringResource(R.string.naming_sep_none)
}

@Composable
private fun joinerLabel(joiner: NameJoiner): String = when (joiner) {
    NameJoiner.UNDERSCORE -> "_"
    NameJoiner.DASH -> "-"
    NameJoiner.SPACE -> stringResource(R.string.naming_join_space)
    NameJoiner.DOT -> "."
}

/**
 * Password protection of exported backups (D134): on or off, the password itself (hidden, with a button that shows it, also
 * after it was saved), and a warning that a lost password means an unreadable backup.
 */
@Composable
private fun BackupPasswordOptions() {
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val stored = container.backupPasswords.load()
        enabled = stored.enabled
        saved = stored.password
        typed = stored.password
        loaded = true
    }

    Hint(R.string.settings_backup_password_hint)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.backup_password_enable), Modifier.weight(1f))
        Switch(
            checked = enabled,
            enabled = loaded,
            onCheckedChange = { on ->
                enabled = on
                if (on && saved.isEmpty()) {
                    // Switching on needs a password: nothing is stored until one is saved.
                    message = R.string.backup_password_type_first
                } else {
                    message = null
                    scope.launch { container.backupPasswords.save(BackupPasswordSettings(enabled = on, password = saved)) }
                }
            },
        )
    }
    if (enabled) {
        PasswordTextField(
            value = typed,
            onValueChange = { typed = it.take(MAX_BACKUP_PASSWORD); message = null },
            label = { Text(stringResource(R.string.backup_password_field)) },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(R.string.backup_password_warning)) },
        )
        com.notebudget.app.core.ui.tip.Button(
            enabled = typed.isNotEmpty() && typed != saved,
            onClick = {
                scope.launch {
                    container.backupPasswords.save(BackupPasswordSettings(enabled = true, password = typed))
                    saved = typed
                    message = R.string.backup_password_saved
                }
            },
        ) { Text(stringResource(R.string.backup_password_save)) }
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

private const val MAX_BACKUP_PASSWORD = 128

/**
 * Database encryption (D-encryption): a switch, on or off, that converts the live database file in place through
 * [com.notebudget.app.core.database.DatabaseEncryptor] — the person never types the passphrase, the app generates one
 * and keeps it sealed with the Keystore (like the backup password, D134), shown behind Show / Hide with a Copy button.
 * Below it, exporting or importing the raw database file (D-encryption): a second, file-level way to move data,
 * next to the existing ZIP+JSON backup above, which stays the one guaranteed to open on any app version.
 */
@Composable
private fun DatabaseEncryptionOptions() {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmToggle by remember { mutableStateOf<Boolean?>(null) } // the value the switch is about to take
    var restartReady by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var failDetail by remember { mutableStateOf<String?>(null) }

    var pendingDbImport by remember { mutableStateOf<Uri?>(null) }
    var dbImportPassword by remember { mutableStateOf<String?>(null) }
    var dbImportWrong by remember { mutableStateOf(false) }

    var confirmEmail by remember { mutableStateOf<SmtpSettings?>(null) }
    var emailBusy by remember { mutableStateOf(false) }
    var emailResult by remember { mutableStateOf<MailResult?>(null) }

    suspend fun refresh() {
        val state = container.databaseEncryption.read()
        enabled = state.enabled
        passphrase = state.passphrase
    }
    LaunchedEffect(Unit) { refresh(); loaded = true }

    fun toggle(turnOn: Boolean) {
        scope.launch {
            busy = true
            message = null
            failDetail = null
            val outcome = withContext(Dispatchers.IO) {
                container.database.close()
                if (turnOn) {
                    val newPassphrase = container.databaseEncryption.generatePassphrase()
                    container.databaseEncryptor.encrypt(newPassphrase)
                } else {
                    val current = container.databaseEncryption.read().passphrase
                    if (current != null) container.databaseEncryptor.decrypt(current)
                    else com.notebudget.app.core.database.DatabaseEncryptionResult.Failed("no stored passphrase")
                }
            }
            busy = false
            when (outcome) {
                is com.notebudget.app.core.database.DatabaseEncryptionResult.Success -> {
                    container.databaseEncryption.setEnabled(turnOn)
                    refresh()
                    restartReady = true
                }
                is com.notebudget.app.core.database.DatabaseEncryptionResult.Failed -> {
                    message = if (turnOn) R.string.db_encryption_enable_failed else R.string.db_encryption_disable_failed
                    failDetail = outcome.message
                    android.util.Log.e("DatabaseEncryption", "toggle(turnOn=$turnOn) failed: ${outcome.message}")
                }
            }
        }
    }

    fun runDbImport(uri: Uri, password: String?) {
        scope.launch {
            busy = true
            val outcome = container.toTheEnd {
                container.database.close()
                // The chosen file may be gone or no longer readable: that is a failed import, not a crash (rule 18).
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { container.rawDatabaseTransfer.import(it, password) }
                }.getOrNull() ?: (com.notebudget.app.core.database.RawDatabaseImportResult.Failed("cannot open $uri") to null)
            }
            busy = false
            val (result, candidate) = outcome
            when (result) {
                is com.notebudget.app.core.database.RawDatabaseImportResult.Success -> {
                    pendingDbImport = null
                    dbImportPassword = null
                    dbImportWrong = false
                    container.databaseEncryption.setEnabled(false) // clears any old passphrase first
                    if (candidate?.enabled == true && candidate.passphrase != null) {
                        container.databaseEncryption.setPassphrase(candidate.passphrase)
                        container.databaseEncryption.setEnabled(true)
                    }
                    refresh()
                    restartReady = true
                }
                is com.notebudget.app.core.database.RawDatabaseImportResult.WrongPassword -> dbImportWrong = true
                is com.notebudget.app.core.database.RawDatabaseImportResult.Failed -> {
                    com.notebudget.app.core.common.AppLog.critical("database", "the database file could not be imported")
                    pendingDbImport = null
                    dbImportPassword = null
                    dbImportWrong = false
                    toast(context, R.string.db_file_import_failed)
                }
            }
        }
    }

    val exportDbLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val ok = container.toTheEnd {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { container.rawDatabaseTransfer.export(container.database, it) } ?: error("no stream")
                    }.onFailure { com.notebudget.app.core.common.AppLog.critical("database", "the database file could not be exported", it) }.isSuccess
                }
                busy = false
                toast(context, if (ok) R.string.db_file_export_done else R.string.db_file_export_failed)
            }
        }
    }
    val importDbLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingDbImport = uri
    }

    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.db_encryption_busy_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.db_encryption_busy_message))
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }

    if (restartReady) {
        AlertDialog(
            onDismissRequest = { restartReady = false },
            title = { Text(stringResource(R.string.db_encryption_restart_title)) },
            text = { Text(stringResource(R.string.db_encryption_restart_message)) },
            confirmButton = {
                TextButton(onClick = { restartAppProcess(context) }) { Text(stringResource(R.string.db_encryption_restart_now)) }
            },
            dismissButton = { TextButton(onClick = { restartReady = false }) { Text(stringResource(R.string.action_later)) } },
        )
    }

    confirmToggle?.let { turnOn ->
        AlertDialog(
            onDismissRequest = { confirmToggle = null },
            title = { Text(stringResource(if (turnOn) R.string.db_encryption_confirm_on_title else R.string.db_encryption_confirm_off_title)) },
            text = { Text(stringResource(if (turnOn) R.string.db_encryption_confirm_on_message else R.string.db_encryption_confirm_off_message)) },
            confirmButton = {
                TextButton(onClick = { confirmToggle = null; toggle(turnOn) }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = { TextButton(onClick = { confirmToggle = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    pendingDbImport?.let { uri ->
        com.notebudget.app.core.ui.EditorDialog(
            onDismissRequest = { pendingDbImport = null; dbImportPassword = null; dbImportWrong = false },
            title = { Text(stringResource(R.string.db_file_import_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.db_file_import_confirm_message))
                    if (dbImportWrong || dbImportPassword != null) {
                        PasswordTextField(
                            value = dbImportPassword.orEmpty(),
                            onValueChange = { dbImportPassword = it; dbImportWrong = false },
                            label = { Text(stringResource(R.string.db_encryption_password_field)) },
                            isError = dbImportWrong,
                            supportingText = if (dbImportWrong) { { Text(stringResource(R.string.transfer_error_wrong_password)) } } else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { runDbImport(uri, dbImportPassword) }) { Text(stringResource(R.string.import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDbImport = null; dbImportPassword = null; dbImportWrong = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Hint(R.string.settings_db_encryption_hint)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.db_encryption_enable), Modifier.weight(1f))
        Switch(checked = enabled, enabled = loaded && !busy, onCheckedChange = { on -> confirmToggle = on })
    }
    if (enabled && passphrase != null) {
        PasswordTextField(
            value = passphrase.orEmpty(),
            onValueChange = {},
            label = { Text(stringResource(R.string.db_encryption_password_field)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                com.notebudget.app.core.ui.copyText(context, "Notio", passphrase.orEmpty(), sensitive = true)
                toast(context, R.string.db_encryption_copied)
            }) { Text(stringResource(R.string.action_copy)) }
            OutlinedButton(enabled = !emailBusy, onClick = {
                scope.launch {
                    val settings = container.appLock.smtpSettings()
                    if (settings == null || !settings.isComplete) {
                        toast(context, R.string.db_encryption_email_not_configured)
                    } else {
                        confirmEmail = settings
                    }
                }
            }) { Text(stringResource(R.string.db_encryption_email_button)) }
        }
        Hint(R.string.db_encryption_password_warning)
    }
    message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    failDetail?.let { detail ->
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = {
            com.notebudget.app.core.ui.copyText(context, "Notio error", detail)
            toast(context, R.string.db_encryption_error_copied)
        }) { Text(stringResource(R.string.db_encryption_copy_error)) }
    }
    emailResult?.let {
        Text(
            if (it is MailResult.Sent) stringResource(R.string.db_encryption_email_sent) else mailErrorText((it as MailResult.Failed).error, it.detail),
            style = MaterialTheme.typography.bodyMedium,
            color = if (it is MailResult.Sent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }

    confirmEmail?.let { settings ->
        AlertDialog(
            onDismissRequest = { confirmEmail = null },
            title = { Text(stringResource(R.string.db_encryption_email_confirm_title)) },
            text = { Text(stringResource(R.string.db_encryption_email_confirm_message, settings.to)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmail = null
                    val subject = context.getString(R.string.db_encryption_mail_subject)
                    val body = context.getString(R.string.db_encryption_mail_body, passphrase.orEmpty())
                    scope.launch {
                        emailBusy = true
                        emailResult = container.appLock.sendTestMail(settings, subject, body)
                        emailBusy = false
                    }
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = { TextButton(onClick = { confirmEmail = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Hint(R.string.db_file_transfer_hint)
    OutlinedButton(
        onClick = { exportDbLauncher.launch(suggestedDbFileName()) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.db_file_export)) }
    OutlinedButton(
        onClick = { importDbLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.db_file_import)) }
}

/**
 * Runs [work] in the scope of the app and waits for it (D347): a backup, a restore, the file of the database must reach their
 * end even when this screen is gone meanwhile — the lock came up, the phone was turned, Settings were left. In the scope of the
 * screen such a job was cancelled a moment after the file was made, and the file stayed empty or cut.
 */
private suspend fun <T> com.notebudget.app.core.common.AppContainer.toTheEnd(work: suspend () -> T): T =
    appScope.async(Dispatchers.IO) { work() }.await()

private fun suggestedDbFileName(): String =
    "notio-database-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()) + ".db.zip"

/** Restarts the whole process (not just the Activity), the only reliable way to reopen the database through a
 * different [com.notebudget.app.core.database.DatabaseEncryptor.encrypt]/`decrypt` outcome: the open database handle
 * lives in [com.notebudget.app.core.common.AppContainer], which is scoped to the process, not the Activity. */
private fun restartAppProcess(context: Context) {
    val packageManager = context.packageManager
    val intent = packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
        context.startActivity(android.content.Intent.makeRestartActivityTask(intent.component))
    }
    Runtime.getRuntime().exit(0)
}

/** Asks for the password of a protected backup; [wrong] shows that the last one did not open it. */
@Composable
private fun ImportPasswordDialog(wrong: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.import_password_hint))
                PasswordTextField(
                    value = password,
                    onValueChange = { password = it.take(MAX_BACKUP_PASSWORD) },
                    label = { Text(stringResource(R.string.backup_password_field)) },
                    isError = wrong,
                    supportingText = if (wrong) { { Text(stringResource(R.string.transfer_error_wrong_password)) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.import_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@StringRes
private fun fontSizeName(size: FontSize): Int = when (size) {
    FontSize.SMALL -> R.string.font_small
    FontSize.NORMAL -> R.string.font_normal
    FontSize.MEDIUM -> R.string.font_medium
    FontSize.LARGE -> R.string.font_large
    FontSize.EXTRA_LARGE -> R.string.font_extra_large
}

private fun colorThemeName(theme: ColorTheme): Int = when (theme) {
    ColorTheme.GREEN -> R.string.theme_green
    ColorTheme.PINK -> R.string.theme_pink
    ColorTheme.ORANGE -> R.string.theme_orange
    ColorTheme.RED -> R.string.theme_red
    ColorTheme.LIGHT_BLUE -> R.string.theme_light_blue
    ColorTheme.BLUE -> R.string.theme_blue
    ColorTheme.LIME -> R.string.theme_lime
    ColorTheme.BLACK -> R.string.theme_black
    ColorTheme.GRAY -> R.string.theme_gray
    ColorTheme.WHITE -> R.string.theme_white
    ColorTheme.YELLOW -> R.string.theme_yellow
    ColorTheme.LILAC -> R.string.theme_lilac
    ColorTheme.MAGENTA -> R.string.theme_magenta
    ColorTheme.CUSTOM -> R.string.theme_custom
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

/** How incoming data meets existing data (D63); replace is spelled out because it deletes. */
@Composable
private fun ImportModeDialog(onDismiss: () -> Unit, onConfirm: (ImportMode) -> Unit) {
    var mode by remember { mutableStateOf(ImportMode.MERGE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_mode_title)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeRow(R.string.import_mode_merge, R.string.import_mode_merge_hint, mode == ImportMode.MERGE) { mode = ImportMode.MERGE }
                ModeRow(R.string.import_mode_new, R.string.import_mode_new_hint, mode == ImportMode.NEW_IDS) { mode = ImportMode.NEW_IDS }
                ModeRow(R.string.import_mode_replace, R.string.import_mode_replace_hint, mode == ImportMode.REPLACE) { mode = ImportMode.REPLACE }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode) }) { Text(stringResource(R.string.import_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ModeRow(@StringRes label: Int, @StringRes hint: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun toast(context: Context, @StringRes message: Int) =
    Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Version name and build number (`versionCode`) of the installed app. */
private fun appVersion(context: Context): Pair<String, Long> =
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        (info.versionName ?: "?") to info.longVersionCode
    }.getOrDefault("?" to 0L)

/** Whether to keep the app log, from which level and how often it clears itself (D254). */
@Composable
private fun AppLogOptions() {
    var options by remember { mutableStateOf(com.notebudget.app.core.common.AppLog.options()) }
    fun change(next: com.notebudget.app.core.common.AppLog.Options) {
        options = next
        com.notebudget.app.core.common.AppLog.setOptions(next)
    }
    Row(
        Modifier.fillMaxWidth().toggleable(value = options.enabled, role = androidx.compose.ui.semantics.Role.Switch) { change(options.copy(enabled = it)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.app_log_keep), Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = options.enabled, onCheckedChange = null)
    }
    Hint(R.string.app_log_keep_hint)
    if (options.enabled) {
        // Each level its own tick (user, 2026-09-27); FATAL is always written, so it is shown ticked and cannot be changed.
        Text(stringResource(R.string.app_log_level), style = MaterialTheme.typography.labelLarge)
        com.notebudget.app.core.common.AppLog.CHOOSABLE.forEach { level ->
            Row(
                Modifier.fillMaxWidth().toggleable(value = level in options.levels, role = androidx.compose.ui.semantics.Role.Checkbox) { on ->
                    change(options.copy(levels = if (on) options.levels + level else options.levels - level))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(checked = level in options.levels, onCheckedChange = null)
                Text(level.name, Modifier.padding(start = 8.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = true, onCheckedChange = null, enabled = false)
            Text("FATAL", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Hint(R.string.app_log_level_hint)
    }
    Text(stringResource(R.string.app_log_clear_period), style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            com.notebudget.app.core.common.AppLog.ClearPeriod.NEVER to R.string.app_log_clear_never,
            com.notebudget.app.core.common.AppLog.ClearPeriod.DAILY to R.string.app_log_clear_daily,
            com.notebudget.app.core.common.AppLog.ClearPeriod.WEEKLY to R.string.app_log_clear_weekly,
            com.notebudget.app.core.common.AppLog.ClearPeriod.MONTHLY to R.string.app_log_clear_monthly,
        ).forEach { (period, label) ->
            androidx.compose.material3.FilterChip(options.clear == period, { change(options.copy(clear = period)) }, { Text(stringResource(label)) })
        }
    }
    Hint(R.string.app_log_size_note)
}

/** The app log (D230): newest lines first, Copy (the whole file, oldest first, as a report) and Clear. */
@Composable
private fun AppLogDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val text by androidx.compose.runtime.produceState("", version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.notebudget.app.core.common.AppLog.read() }
    }
    // The newest at the top; an error's own lines (indented) stay under it.
    val shown = remember(text) {
        val entries = mutableListOf<StringBuilder>()
        text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            if (line.startsWith(" ") && entries.isNotEmpty()) entries.last().append('\n').append(line) else entries.add(StringBuilder(line))
        }
        entries.asReversed().joinToString("\n")
    }
    // The log as a file (D303, as in FitEasy): JSON, TXT or LOG, saved where the person says.
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf<com.notio.common.AppLogExport.Type?>(null) }
    val saveFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val type = saving
        saving = null
        if (uri != null && type != null) {
            scope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                        val bytes = com.notio.common.AppLogExport.export(
                            com.notebudget.app.core.common.AppLog.read(), type, version,
                            "Android " + android.os.Build.VERSION.RELEASE, java.time.Instant.now().toString(),
                        )
                        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                    }.isSuccess
                }
                Toast.makeText(context, context.getString(if (ok) R.string.app_log_saved else R.string.error_generic), Toast.LENGTH_SHORT).show()
            }
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.app_log)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.app_log_export), style = MaterialTheme.typography.labelLarge)
                    com.notio.common.AppLogExport.Type.entries.forEach { type ->
                        com.notebudget.app.core.ui.tip.TextButton(
                            onClick = {
                                saving = type
                                val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
                                saveFile.launch(com.notio.common.AppLogExport.fileName(version, type))
                            },
                            enabled = text.isNotEmpty(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) { Text(type.name) }
                    }
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        shown.ifEmpty { stringResource(R.string.app_log_empty) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            com.notebudget.app.core.ui.tip.TextButton(onClick = {
                com.notebudget.app.core.ui.copyText(context, "Notio log", text)
                Toast.makeText(context, context.getString(R.string.app_log_copied), Toast.LENGTH_SHORT).show()
            }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.action_copy)) }
        },
        dismissButton = {
            Row {
                com.notebudget.app.core.ui.tip.TextButton(onClick = { com.notebudget.app.core.common.AppLog.clear(); version++ }) { Text(stringResource(R.string.app_log_clear)) }
                com.notebudget.app.core.ui.tip.TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

/** The licence texts of what the app ships (D225): read from `assets/licenses` only when this dialog is opened. */
@Composable
private fun LicencesDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val text by androidx.compose.runtime.produceState("") {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            listOf("SQLCipher-LICENSE.txt", "Roboto-OFL.txt").joinToString("\n\n———\n\n") { name ->
                runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault(name)
            }
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.about_licenses)) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            )
        },
        confirmButton = { com.notebudget.app.core.ui.tip.TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}

/**
 * The battery (D308, as in FitEasy): what the phone allows the app — exact alarms, work in the background, the system's
 * battery optimisation — with the way into the phone's settings. The states are read again when the person comes back from
 * those settings. The app's own saver is a switch of the reminder settings (D309).
 */
@Composable
private fun BatteryOptions() {
    val context = LocalContext.current
    val container = rememberAppContainer()
    var again by remember { mutableStateOf(0) }
    val resumed = com.notebudget.app.core.ui.rememberResumeCount()
    val exact = remember(resumed, again) { container.reminderScheduler.canScheduleExact() }
    val restricted = remember(resumed, again) { com.notebudget.app.core.common.BackgroundRestriction.isRestricted(context) }
    val exempt = remember(resumed, again) {
        runCatching { context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true }.getOrDefault(false)
    }
    Text(stringResource(if (exact) R.string.battery_exact_on else R.string.battery_exact_off), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(if (restricted) R.string.battery_restricted_on else R.string.battery_restricted_off), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(if (exempt) R.string.battery_exempt_on else R.string.battery_exempt_off), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { com.notebudget.app.core.common.BackgroundRestriction.openSettings(context) }) { Text(stringResource(R.string.battery_open_settings)) }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text(stringResource(R.string.battery_open_exact)) }
        }
        OutlinedButton(onClick = { again++ }) { Text(stringResource(R.string.battery_check_again)) }
    }
    Hint(R.string.battery_note)
}
