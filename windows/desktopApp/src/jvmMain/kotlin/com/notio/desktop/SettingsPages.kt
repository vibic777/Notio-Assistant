package com.notio.desktop

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.notio.desktop.theme.DEFAULT_CUSTOM_THEME
import com.notio.desktop.theme.themeSwatch
import com.notio.shared.domain.ColorTheme
import com.notio.shared.domain.DateOrder
import com.notio.shared.domain.DateStyle
import com.notio.shared.domain.DateTimeFormats
import com.notio.shared.domain.ExportNaming
import com.notio.shared.domain.FontSize
import com.notio.shared.domain.NameJoiner
import com.notio.shared.domain.NotificationPrivacy
import com.notio.shared.domain.StampSeparator
import com.notio.shared.domain.StampSource
import com.notio.shared.domain.ThemeMode
import com.notio.shared.domain.TimeStyle
import com.notio.shared.domain.TrashRetention
import com.notio.shared.transfer.ImportMode
import com.notio.shared.transfer.TransferError
import com.notio.shared.transfer.TransferException
import com.notio.shared.transfer.TransferFormat
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The pages of the phone's settings (Android feature/settings/SettingsScreen.kt, D145), ported (AGENT.md D29). The page frame
// (SettingsScreen) and the options that differ on a computer (reminders, backups) are in DesktopSettings.kt.


fun TransferError.messageRes(): String = when (this) {
    TransferError.NOT_A_BACKUP -> "transfer_error_not_a_backup"
    TransferError.UNSUPPORTED_VERSION -> "transfer_error_unsupported_version"
    TransferError.CORRUPT -> "transfer_error_corrupt"
    TransferError.NO_FOLDERS -> "transfer_error_no_folders"
    TransferError.TOO_LARGE -> "transfer_error_too_large"
    TransferError.UNRECOGNIZED_FILE -> "transfer_error_unrecognized_file"
    TransferError.FOLDER_NOT_FOUND -> "transfer_error_folder_not_found"
    TransferError.PASSWORD_REQUIRED -> "transfer_error_password_required"
    TransferError.WRONG_PASSWORD -> "transfer_error_wrong_password"
}

/** The pages of the settings (D145): a menu of these, each with its own options, like the settings of a phone. */
enum class SettingsSection(val id: String, val title: String, val hint: String) {
    GENERAL("general", "settings_cat_general", "settings_cat_general_hint"),
    APPEARANCE("appearance", "settings_cat_appearance", "settings_cat_appearance_hint"),
    NOTES("notes", "settings_cat_notes", "settings_cat_notes_hint"),
    RECORDINGS("recordings", "settings_cat_recordings", "settings_cat_recordings_hint"),
    SECURITY("security", "settings_cat_security", "settings_cat_security_hint"),
    TRASH("trash", "settings_cat_trash", "settings_cat_trash_hint"),
    DATA("data", "settings_cat_data", "settings_cat_data_hint"),
    SYNC("sync", "settings_cat_sync", "settings_cat_sync_hint"),
    /** The assistant (D146, the branch `assistant`): a line of its own in the menu of the settings, so that it is found at once. */
    ASSISTANT("assistant", "assistant_title", "assistant_hint"),
    ABOUT("about", "settings_cat_about", "settings_cat_about_hint");

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
    val title: String,
    val hint: String? = null,
    val words: List<String> = emptyList(),
)

object SettingIds {
    const val LANGUAGE = "language"
    const val DATE_TIME = "date_time"
    const val THEME = "theme"
    const val COLOR_THEME = "color_theme"
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
    const val LOCK = "lock"
    const val PRIVACY = "privacy"
    const val ALARM = "alarm"
    const val TRASH = "trash"
    const val BACKUP = "backup"
    const val BACKUP_PASSWORD = "backup_password"
    const val DATABASE_ENCRYPTION = "database_encryption"
    const val SYNC = "sync"
    const val HELP = "help"
    const val ABOUT = "about"
    const val APP_LOG = "app_log"
    const val WINDOW = "window"
    const val CARD_TINT = "card_tint"
    const val NOTICES = "notices"
    const val ASSISTANT = "assistant"
    const val REC_LIMIT_AUDIO = "rec_limit_audio"
    const val REC_LIMIT_VIDEO = "rec_limit_video"
    const val REC_LIMIT_SCREEN = "rec_limit_screen"
    const val FLOATING_STOP = "floating_stop"
}

val SETTING_ENTRIES: List<SettingEntry> = listOf(
    SettingEntry(SettingIds.LANGUAGE, SettingsSection.GENERAL, "settings_language"),
    SettingEntry(SettingIds.DATE_TIME, SettingsSection.GENERAL, "settings_datetime", "settings_datetime_hint"),
    SettingEntry(SettingIds.REC_LIMIT_AUDIO, SettingsSection.RECORDINGS, "settings_rec_limit_audio", "settings_rec_limit_hint"),
    SettingEntry(SettingIds.REC_LIMIT_VIDEO, SettingsSection.RECORDINGS, "settings_rec_limit_video", "settings_rec_limit_hint"),
    SettingEntry(SettingIds.REC_LIMIT_SCREEN, SettingsSection.RECORDINGS, "settings_rec_limit_screen", "settings_rec_limit_hint"),
    SettingEntry(SettingIds.FLOATING_STOP, SettingsSection.RECORDINGS, "rec_floating_switch", "desktop_floating_stop_hint"),
    SettingEntry(SettingIds.WINDOW, SettingsSection.GENERAL, "desktop_window", "desktop_close_to_tray", listOf("desktop_single_instance", "desktop_close_to_tray_hint", "desktop_auto_start")),
    SettingEntry(SettingIds.THEME, SettingsSection.APPEARANCE, "settings_theme", null, listOf("settings_theme_light", "settings_theme_dark")),
    SettingEntry(SettingIds.COLOR_THEME, SettingsSection.APPEARANCE, "settings_color_theme"),
    SettingEntry(SettingIds.FONT_SIZE, SettingsSection.APPEARANCE, "settings_font_size", "settings_font_size_hint"),
    SettingEntry(SettingIds.PREVIEW, SettingsSection.APPEARANCE, "settings_preview", "settings_preview_hint"),
    SettingEntry(SettingIds.CARD_TINT, SettingsSection.APPEARANCE, "settings_card_tint", "settings_card_tint_hint"),
    SettingEntry(SettingIds.NOTICES, SettingsSection.GENERAL, "settings_notices", "settings_notices_hint"),
    SettingEntry(SettingIds.ASSISTANT, SettingsSection.ASSISTANT, "assistant_title", "assistant_hint", listOf("assistant_setup", "assistant_chat", "assistant_rights", "assistant_access", "assistant_journal")),
    SettingEntry(SettingIds.MEDIA_PREVIEW, SettingsSection.APPEARANCE, "settings_media_preview", "settings_media_preview_hint"),
    SettingEntry(SettingIds.STAMPS, SettingsSection.APPEARANCE, "settings_stamps", "settings_stamps_hint"),
    SettingEntry(SettingIds.FOOTER_STAMPS, SettingsSection.APPEARANCE, "settings_footer_stamps", "settings_footer_stamps_hint"),
    SettingEntry(SettingIds.COMMENTS, SettingsSection.APPEARANCE, "settings_comments", "settings_comments_hint"),
    SettingEntry(SettingIds.BACKGROUNDS, SettingsSection.APPEARANCE, "settings_backgrounds", "settings_backgrounds_hint"),
    SettingEntry(SettingIds.TAGS, SettingsSection.NOTES, "settings_tags", "settings_tags_hint"),
    SettingEntry(SettingIds.UNITS, SettingsSection.NOTES, "settings_units", "settings_units_hint"),
    SettingEntry(SettingIds.CURRENCIES, SettingsSection.NOTES, "settings_currencies", "settings_currencies_hint"),
    SettingEntry(SettingIds.EXPORT_NAMING, SettingsSection.NOTES, "settings_export_naming", "settings_export_naming_hint"),
    SettingEntry(SettingIds.UNDO, SettingsSection.NOTES, "settings_undo", "settings_undo_hint"),
    SettingEntry(SettingIds.AUDIO_PLACE, SettingsSection.NOTES, "settings_audio_place", "settings_audio_place_hint"),
    SettingEntry(SettingIds.LOCK, SettingsSection.SECURITY, "settings_lock", "settings_lock_hint"),
    SettingEntry(SettingIds.PRIVACY, SettingsSection.SECURITY, "settings_privacy", "settings_privacy_hint"),
    SettingEntry(SettingIds.ALARM, SettingsSection.SECURITY, "settings_alarm", "settings_alarm_hint", listOf("battery_economy")),
    SettingEntry(SettingIds.TRASH, SettingsSection.TRASH, "settings_trash", "settings_trash_hint", listOf("settings_trash_never")),
    SettingEntry(SettingIds.BACKUP, SettingsSection.DATA, "settings_data", "settings_data_hint", listOf("settings_export_all", "settings_import_all")),
    SettingEntry(SettingIds.BACKUP_PASSWORD, SettingsSection.DATA, "settings_backup_password", "settings_backup_password_hint"),
    SettingEntry(SettingIds.DATABASE_ENCRYPTION, SettingsSection.DATA, "settings_db_encryption", "settings_db_encryption_hint"),
    SettingEntry(SettingIds.SYNC, SettingsSection.SYNC, "settings_sync", "settings_sync_hint", listOf("sync_wait", "sync_find", "sync_connections")),
    SettingEntry(SettingIds.HELP, SettingsSection.ABOUT, "settings_help"),
    SettingEntry(SettingIds.ABOUT, SettingsSection.ABOUT, "about_title"),
    SettingEntry(SettingIds.APP_LOG, SettingsSection.ABOUT, "app_log", "app_log_hint"),
)

/** The options that match [query] (every word of it, in the title, the hint, a choice or the page name); a blank query matches none. */
fun searchSettings(query: String, text: (String) -> String): List<SettingEntry> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return SETTING_ENTRIES.filter { it.id !in HIDDEN_ON_DESKTOP }.filter { entry ->
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
internal fun SettingsMenu(query: String, onQuery: (String) -> Unit, onOpen: (SettingsSection, String?) -> Unit, modifier: Modifier = Modifier, onOpenReminders: () -> Unit = {}) {
    val context = LocalRes.current
    val results = remember(query) { searchSettings(query) { context.str(it) } }
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource("settings_search_hint")) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource("search_close"))
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
                    // The reminders have a page of their own; the menu of the settings leads there too (D124, Android D349), as the
                    // bell of the main window does.
                    if (s == SettingsSection.NOTES) {
                        Card(Modifier.fillMaxWidth().clickable { onOpenReminders() }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource("reminders_title"), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource("settings_cat_reminders_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else if (results.isEmpty()) {
                Text(stringResource("settings_search_none"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
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
internal fun SettingBlock(id: String, focus: String?, title: String, content: @Composable () -> Unit) {
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
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun LanguageOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val context = LocalRes.current
    val languageTag by settings.languageTag.collectAsState()
    val options = listOf<Pair<String?, String>>(
        null to stringResource("settings_language_system"),
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
            }
        }
    }
}

@Composable
internal fun ThemeOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val theme by settings.themeMode.collectAsState()
    OptionRow(stringResource("settings_theme_system"), theme == ThemeMode.SYSTEM) { scope.launch { settings.setThemeMode(ThemeMode.SYSTEM) } }
    OptionRow(stringResource("settings_theme_light"), theme == ThemeMode.LIGHT) { scope.launch { settings.setThemeMode(ThemeMode.LIGHT) } }
    OptionRow(stringResource("settings_theme_dark"), theme == ThemeMode.DARK) { scope.launch { settings.setThemeMode(ThemeMode.DARK) } }
}

@Composable
internal fun ColorThemeOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val colorTheme by settings.colorTheme.collectAsState()
    val customColor by settings.customThemeColor.collectAsState()
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
                OutlinedButton(onClick = { picking = true }) { Text(stringResource("theme_custom_pick")) }
            }
        }
    }
    if (picking) {
        ColorPickerDialog(
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
internal fun FontSizeOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val fontSize by settings.fontSize.collectAsState()
    Hint("settings_font_size_hint")
    FontSize.entries.forEach { option ->
        OptionRow(stringResource(fontSizeName(option)), fontSize == option) { scope.launch { settings.setFontSize(option) } }
    }
}

@Composable
internal fun PrivacyOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val privacy by settings.notificationPrivacy.collectAsState()
    Hint("settings_privacy_hint")
    OptionRow(stringResource("settings_privacy_generic"), privacy == NotificationPrivacy.GENERIC) { scope.launch { settings.setNotificationPrivacy(NotificationPrivacy.GENERIC) } }
    OptionRow(stringResource("settings_privacy_title_only"), privacy == NotificationPrivacy.TITLE_ONLY) { scope.launch { settings.setNotificationPrivacy(NotificationPrivacy.TITLE_ONLY) } }
    OptionRow(stringResource("settings_privacy_full"), privacy == NotificationPrivacy.FULL) { scope.launch { settings.setNotificationPrivacy(NotificationPrivacy.FULL) } }
}

/** How many lines a note card previews: none, one or two (D159). */
@Composable
internal fun PreviewOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val lines by settings.previewLines.collectAsState()
    Hint("settings_preview_hint")
    OptionRow(stringResource("settings_preview_0"), lines == 0) { scope.launch { settings.setPreviewLines(0) } }
    OptionRow(stringResource("settings_preview_1"), lines == 1) { scope.launch { settings.setPreviewLines(1) } }
    OptionRow(stringResource("settings_preview_2"), lines == 2) { scope.launch { settings.setPreviewLines(2) } }
}

/** How long a message at the bottom of the window stays (Android D360): 0..60 seconds, 0 = no messages. */
@Composable
internal fun NoticeOptions() {
    val settings = LocalSettings.current
    val saved by settings.noticeSeconds.collectAsState()
    // The slider moves freely; the value is written when the mouse lets go, not on every step.
    var value by remember(saved) { mutableStateOf(saved.toFloat()) }
    Hint("settings_notices_hint")
    Text(
        if (value.toInt() == 0) stringResource("settings_notices_off") else stringResource("settings_notices_value", value.toInt()),
        style = MaterialTheme.typography.bodyMedium,
    )
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { settings.setNoticeSeconds(value.toInt()) },
        valueRange = com.notio.common.NoticeTime.MIN.toFloat()..com.notio.common.NoticeTime.MAX.toFloat(),
        steps = com.notio.common.NoticeTime.MAX - com.notio.common.NoticeTime.MIN - 1,
    )
}

/** How strongly note and folder cards show their colour (Android D273), with a card drawn in it as a sample. */
@Composable
internal fun CardTintOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val saved by settings.cardTint.collectAsState()
    // The slider moves freely; the value is written when the mouse lets go, not on every step.
    var value by remember(saved) { mutableStateOf(saved.toFloat()) }
    Hint("settings_card_tint_hint")
    Text(stringResource("settings_card_tint_value", value.toInt()), style = MaterialTheme.typography.bodyMedium)
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { scope.launch { settings.setCardTint(value.toInt()) } },
        valueRange = com.notio.shared.domain.CardTint.MIN.toFloat()..com.notio.shared.domain.CardTint.MAX.toFloat(),
    )
    val sample = androidx.compose.ui.graphics.Color(0xFF3366FF).copy(alpha = value / 100f).compositeOver(MaterialTheme.colorScheme.surface)
    ReadableOn(sample) {
        androidx.compose.material3.Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = sample, contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource("settings_card_tint_sample"), style = MaterialTheme.typography.titleMedium)
                Text(stringResource("settings_card_tint_sample_line"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The picture on the card: for photo / video / screen notes none, the first, or a deck of three; for drawings on or off (D160). */
@Composable
internal fun MediaPreviewOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val mode by settings.mediaPreview.collectAsState()
    val drawing by settings.drawingPreview.collectAsState()
    Hint("settings_media_preview_hint")
    com.notio.shared.domain.MediaPreviewMode.entries.forEach { option ->
        val label = when (option) {
            com.notio.shared.domain.MediaPreviewMode.NONE -> "settings_media_preview_none"
            com.notio.shared.domain.MediaPreviewMode.FIRST -> "settings_media_preview_first"
            com.notio.shared.domain.MediaPreviewMode.DECK -> "settings_media_preview_deck"
        }
        OptionRow(stringResource(label), mode == option) { scope.launch { settings.setMediaPreview(option) } }
    }
    Text(stringResource("settings_drawing_preview"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    OptionRow(stringResource("settings_drawing_preview_on"), drawing) { scope.launch { settings.setDrawingPreview(true) } }
    OptionRow(stringResource("settings_drawing_preview_off"), !drawing) { scope.launch { settings.setDrawingPreview(false) } }
}

/** Four switches (D157): the creation and the change time stamp of notes, and of folders; only what is shown changes. */
@Composable
internal fun StampOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val display by settings.stampDisplay.collectAsState()
    fun save(next: com.notio.shared.domain.StampDisplay) { scope.launch { settings.setStampDisplay(next) } }

    @Composable
    fun Switched(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = onChange)
        }
    }
    Hint("settings_stamps_hint")
    Text(stringResource("settings_stamps_notes"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    Switched("settings_stamp_created", display.notesCreated) { save(display.copy(notesCreated = it)) }
    Switched("settings_stamp_modified", display.notesModified) { save(display.copy(notesModified = it)) }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    Text(stringResource("settings_stamps_folders"), style = MaterialTheme.typography.titleSmall)
    Switched("settings_stamp_created", display.foldersCreated) { save(display.copy(foldersCreated = it)) }
    Switched("settings_stamp_modified", display.foldersModified) { save(display.copy(foldersModified = it)) }
}

/**
 * The stamps at the bottom of an open note, for every kind on its own (D198): a tab (chip) per kind and, under it, the two
 * switches of that kind. How a moment is written is the global format of "Language and formats".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FooterStampOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val stamps by settings.footerStamps.collectAsState()
    val kinds = ALL_KINDS
    var picked by remember { mutableStateOf(kinds.firstOrNull()?.id) }
    val kind = kinds.firstOrNull { it.id == picked } ?: kinds.firstOrNull() ?: return
    fun save(next: com.notio.shared.domain.NoteFooterStamps) { scope.launch { settings.setFooterStamps(next) } }
    Hint("settings_footer_stamps_hint")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        kinds.forEach { k ->
            val on = stamps.showsCreated(k) || stamps.showsModified(k)
            FilterChip(
                selected = k == kind,
                onClick = { picked = k.id },
                label = { Text(LocalStrings.current.kind(k) + if (on) " •" else "") },
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("settings_stamp_created"), Modifier.weight(1f))
        Switch(checked = stamps.showsCreated(kind), onCheckedChange = { save(stamps.withCreated(kind, it)) })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("settings_stamp_modified"), Modifier.weight(1f))
        Switch(checked = stamps.showsModified(kind), onCheckedChange = { save(stamps.withModified(kind, it)) })
    }
}

/** How many steps back Undo goes in the editors of notes (D197). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UndoOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val steps by settings.undoSteps.collectAsState()
    Hint("settings_undo_hint")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.notio.shared.domain.UndoHistory.CHOICES.forEach { n ->
            FilterChip(steps == n, { scope.launch { settings.setUndoSteps(n) } }, { Text(n.toString()) })
        }
    }
}

/** Two switches (D177): whether the cards of notes and of folders show the first 16 characters of their comment. */
@Composable
internal fun CommentOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val display by settings.commentDisplay.collectAsState()
    fun save(next: com.notio.shared.domain.CommentDisplay) { scope.launch { settings.setCommentDisplay(next) } }

    Hint("settings_comments_hint")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("settings_comments_notes"), Modifier.weight(1f))
        Switch(checked = display.notes, onCheckedChange = { save(display.copy(notes = it)) })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("settings_comments_folders"), Modifier.weight(1f))
        Switch(checked = display.folders, onCheckedChange = { save(display.copy(folders = it)) })
    }
}

/** A fixed moment (2026-09-20 15:07:09 UTC) so that every choice of a format shows what it looks like. */
private const val SAMPLE_MOMENT = 1_789_916_829_000L

@Composable
internal fun DateTimeOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val formats by settings.dateTimeFormats.collectAsState()
    Hint("settings_datetime_hint")
    Text(stringResource("settings_date_label"), style = MaterialTheme.typography.titleSmall)
    DateStyle.entries.forEach { style ->
        val sample = formats.copy(date = style).formatDate(SAMPLE_MOMENT)
        OptionRow(if (style == DateStyle.SYSTEM) stringResource("format_system") + " (" + sample + ")" else sample, formats.date == style) {
            scope.launch { settings.setDateTimeFormats(formats.copy(date = style)) }
        }
    }
    Text(stringResource("settings_time_label"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    TimeStyle.entries.forEach { style ->
        val sample = formats.copy(time = style).formatTime(SAMPLE_MOMENT)
        OptionRow(if (style == TimeStyle.SYSTEM) stringResource("format_system") + " (" + sample + ")" else sample, formats.time == style) {
            scope.launch { settings.setDateTimeFormats(formats.copy(time = style)) }
        }
    }
    Text(stringResource("settings_example", formats.format(SAMPLE_MOMENT)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportNamingOptions() {
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val naming by settings.exportNaming.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    fun save(next: ExportNaming) { scope.launch { settings.setExportNaming(next) } }

    @Composable
    fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = checked, onClick = { onChange(!checked) }, role = Role.Checkbox),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(stringResource(label), Modifier.padding(start = 12.dp))
        }
    }

    Hint("settings_export_naming_hint")
    Check("naming_note_title", naming.useNoteTitle) { save(naming.copy(useNoteTitle = it)) }
    Check("naming_item_title", naming.useItemTitle) { save(naming.copy(useItemTitle = it)) }
    Check("naming_date", naming.useDate) { save(naming.copy(useDate = it)) }
    Check("naming_time", naming.useTime) { save(naming.copy(useTime = it)) }

    if (naming.useDate || naming.useTime) {
        Text(stringResource("naming_source"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        OptionRow(stringResource("naming_source_export"), naming.stampSource == StampSource.EXPORT_MOMENT) { save(naming.copy(stampSource = StampSource.EXPORT_MOMENT)) }
        OptionRow(stringResource("naming_source_created"), naming.stampSource == StampSource.CREATED) { save(naming.copy(stampSource = StampSource.CREATED)) }
        OptionRow(stringResource("naming_source_modified"), naming.stampSource == StampSource.MODIFIED) { save(naming.copy(stampSource = StampSource.MODIFIED)) }
    }
    if (naming.useDate) {
        Text(stringResource("naming_date_format"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
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
        Text(stringResource("naming_time_format"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StampSeparator.entries.forEach { sep ->
                FilterChip(naming.timeSeparator == sep, { save(naming.copy(timeSeparator = sep)) }, { Text(separatorLabel(sep)) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource("naming_twelve_hour"), Modifier.weight(1f))
            Switch(checked = naming.twelveHour, onCheckedChange = { save(naming.copy(twelveHour = it)) })
        }
    }
    Text(stringResource("naming_joiner"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NameJoiner.entries.forEach { joiner ->
            FilterChip(naming.joiner == joiner, { save(naming.copy(joiner = joiner)) }, { Text(joinerLabel(joiner)) })
        }
    }
    val example = naming.baseName(
        stringResource("naming_example_note"), stringResource("naming_example_item"),
        SAMPLE_MOMENT, SAMPLE_MOMENT, SAMPLE_MOMENT, zone,
    )
    Text(stringResource("naming_example", example), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun separatorLabel(sep: StampSeparator): String = when (sep) {
    StampSeparator.DASH -> "-"
    StampSeparator.DOT -> "."
    StampSeparator.SOLID -> stringResource("naming_sep_none")
}

@Composable
internal fun joinerLabel(joiner: NameJoiner): String = when (joiner) {
    NameJoiner.UNDERSCORE -> "_"
    NameJoiner.DASH -> "-"
    NameJoiner.SPACE -> stringResource("naming_join_space")
    NameJoiner.DOT -> "."
}

/**
 * Password protection of exported backups (D134): on or off, the password itself (hidden, with a button that shows it, also
 * after it was saved), and a warning that a lost password means an unreadable backup.
 */
@Composable
internal fun BackupPasswordOptions() {
    val passwords = LocalBackupPasswords.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val stored = passwords.load()
        enabled = stored.enabled
        saved = stored.password
        typed = stored.password
        loaded = true
    }

    Hint("settings_backup_password_hint")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("backup_password_enable"), Modifier.weight(1f))
        Switch(
            checked = enabled,
            enabled = loaded,
            onCheckedChange = { on ->
                enabled = on
                if (on && saved.isEmpty()) {
                    // Switching on needs a password: nothing is stored until one is saved.
                    message = "backup_password_type_first"
                } else {
                    message = null
                    scope.launch { passwords.save(BackupPasswordSettings(enabled = on, password = saved)) }
                }
            },
        )
    }
    if (enabled) {
        PasswordField(
            value = typed,
            onValueChange = { typed = it.take(MAX_BACKUP_PASSWORD); message = null },
            label = { Text(stringResource("backup_password_field")) },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource("backup_password_warning")) },
        )
        Button(
            enabled = typed.isNotEmpty() && typed != saved,
            onClick = {
                scope.launch {
                    passwords.save(BackupPasswordSettings(enabled = true, password = typed))
                    saved = typed
                    message = "backup_password_saved"
                }
            },
        ) { Text(stringResource("backup_password_save")) }
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

private const val MAX_BACKUP_PASSWORD = 128
@Composable
internal fun ImportPasswordDialog(wrong: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("import_password_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource("import_password_hint"))
                PasswordField(
                    value = password,
                    onValueChange = { password = it.take(MAX_BACKUP_PASSWORD) },
                    label = { Text(stringResource("backup_password_field")) },
                    isError = wrong,
                    supportingText = if (wrong) { { Text(stringResource("transfer_error_wrong_password")) } } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text(stringResource("import_confirm")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

internal fun fontSizeName(size: FontSize): String = when (size) {
    FontSize.SMALL -> "font_small"
    FontSize.NORMAL -> "font_normal"
    FontSize.MEDIUM -> "font_medium"
    FontSize.LARGE -> "font_large"
    FontSize.EXTRA_LARGE -> "font_extra_large"
}

internal fun colorThemeName(theme: ColorTheme): String = when (theme) {
    ColorTheme.GREEN -> "theme_green"
    ColorTheme.PINK -> "theme_pink"
    ColorTheme.ORANGE -> "theme_orange"
    ColorTheme.RED -> "theme_red"
    ColorTheme.LIGHT_BLUE -> "theme_light_blue"
    ColorTheme.BLUE -> "theme_blue"
    ColorTheme.LIME -> "theme_lime"
    ColorTheme.BLACK -> "theme_black"
    ColorTheme.GRAY -> "theme_gray"
    ColorTheme.WHITE -> "theme_white"
    ColorTheme.YELLOW -> "theme_yellow"
    ColorTheme.LILAC -> "theme_lilac"
    ColorTheme.MAGENTA -> "theme_magenta"
    ColorTheme.CUSTOM -> "theme_custom"
}

@Composable
internal fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
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
internal fun ImportModeDialog(onDismiss: () -> Unit, onConfirm: (ImportMode) -> Unit) {
    var mode by remember { mutableStateOf(ImportMode.MERGE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("import_mode_title")) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeRow("import_mode_merge", "import_mode_merge_hint", mode == ImportMode.MERGE) { mode = ImportMode.MERGE }
                ModeRow("import_mode_new", "import_mode_new_hint", mode == ImportMode.NEW_IDS) { mode = ImportMode.NEW_IDS }
                ModeRow("import_mode_replace", "import_mode_replace_hint", mode == ImportMode.REPLACE) { mode = ImportMode.REPLACE }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mode) }) { Text(stringResource("import_confirm")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

@Composable
internal fun ModeRow(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
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

/** The app log (D230): newest lines first, Copy (the whole file, oldest first, as a report) and Clear. */
@Composable
internal fun AppLogDialog(onClose: () -> Unit) {
    var version by remember { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val text by androidx.compose.runtime.produceState("", version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.notio.shared.AppLog.read() }
    }
    // The newest at the top; an error's own lines (indented) stay under it.
    val shown = remember(text) {
        val entries = mutableListOf<StringBuilder>()
        text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            if (line.startsWith(" ") && entries.isNotEmpty()) entries.last().append('\n').append(line) else entries.add(StringBuilder(line))
        }
        entries.asReversed().joinToString("\n")
    }
    // The log as a file (Android D303, as in FitEasy): JSON, TXT or LOG, saved where the person says.
    val scope = rememberCoroutineScope()
    val exportTitle = stringResource("app_log_export")
    var saved by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource("app_log")) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(exportTitle, style = MaterialTheme.typography.labelLarge)
                    com.notio.common.AppLogExport.Type.entries.forEach { type ->
                        TextButton(
                            onClick = {
                                val file = chooseFile(exportTitle, save = true, name = com.notio.common.AppLogExport.fileName(com.notio.shared.AppInfo.SHOWN, type)) ?: return@TextButton
                                scope.launch {
                                    saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            file.writeBytes(com.notio.common.AppLogExport.export(
                                                com.notio.shared.AppLog.read(), type, com.notio.shared.AppInfo.SHOWN,
                                                System.getProperty("os.name") + " " + System.getProperty("os.version"), java.time.Instant.now().toString(),
                                            ))
                                            file.path
                                        }.getOrElse { it.message ?: it.toString() }
                                    }
                                }
                            },
                            enabled = text.isNotEmpty(),
                        ) { Text(type.name) }
                    }
                }
                saved?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        shown.ifEmpty { stringResource("app_log_empty") },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(text)
                copied = true
            }, enabled = text.isNotEmpty()) { Text(stringResource("action_copy")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { com.notio.shared.AppLog.clear(); version++ }) { Text(stringResource("app_log_clear")) }
                TextButton(onClick = onClose) { Text(stringResource("action_close")) }
            }
        },
    )
}

/** The licence texts of what the app ships (D225): read from `assets/licenses` only when this dialog is opened. */
@Composable
internal fun LicencesDialog(onClose: () -> Unit) {
    val text by androidx.compose.runtime.produceState("") {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LICENCE_FILES.joinToString("\n\n———\n\n") { name ->
                runCatching { AppLogHolder::class.java.getResourceAsStream("/licenses/$name")!!.bufferedReader().use { it.readText() } }.getOrDefault(name)
            }
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource("about_licenses")) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource("action_close")) } },
    )
}


/** The licence texts of what the desktop app ships (Android D225), in the app's resources under `licenses/`. */
private val LICENCE_FILES = listOf("NOTICE.txt", "Apache-2.0.txt", "Roboto-OFL.txt")

private object AppLogHolder
