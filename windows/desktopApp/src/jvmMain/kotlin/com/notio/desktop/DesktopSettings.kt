package com.notio.desktop

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.notio.shared.AppInfo
import com.notio.shared.domain.TrashRetention
import com.notio.shared.sync.SecretBox
import com.notio.shared.transfer.TransferFormat
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Settings as the phone has them (Android D145, AGENT.md D29): a menu of pages with a search, each page with its options. The options
 * are the phone's own code (SettingsPages.kt); what a computer does differently is here: reminders ring from the tray, backups are
 * files chosen in the Save / Open dialogs, the backup password is sealed with DPAPI. What the desktop does not have yet (lock,
 * backgrounds, the tags / units / currencies pages, database encryption) is left out of the menu and of the search.
 */
@Composable
fun SettingsScreen(s: Strings, state: UiState, controller: NotioController, actions: HomeActions) {
    val current = SettingsSection.byId(state.settingsSection)
    var query by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalSettings provides controller.appSettings, LocalStrings provides s, LocalBackupPasswords provides controller.backupPasswords) {
        Page { pad ->
            if (current == null) {
                SettingsMenu(
                    query = query,
                    onQuery = { query = it },
                    onOpen = { section, entry -> query = ""; focus = entry; controller.openSettingsSection(section.id) },
                    onOpenReminders = { controller.openReminders(null) },
                    modifier = Modifier.padding(pad).widthIn(max = 900.dp),
                )
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (current) {
                        SettingsSection.GENERAL -> {
                            SettingBlock(SettingIds.LANGUAGE, focus, "settings_language") { LanguageOptions() }
                            SettingBlock(SettingIds.DATE_TIME, focus, "settings_datetime") { DateTimeOptions() }
                            SettingBlock(SettingIds.WINDOW, focus, "desktop_window") { WindowOptions(controller) }
                            SettingBlock(SettingIds.NOTICES, focus, "settings_notices") { NoticeOptions() }
                            // The assistant (D145, the branch `assistant`): a page of its own.
                            SettingBlock(SettingIds.ASSISTANT, focus, "assistant_title") {
                                Hint("assistant_hint")
                                OutlinedButton(onClick = { controller.openCatalog("assistant") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("assistant_setup")) }
                            }
                        }
                        SettingsSection.APPEARANCE -> {
                            SettingBlock(SettingIds.THEME, focus, "settings_theme") { ThemeOptions() }
                            SettingBlock(SettingIds.COLOR_THEME, focus, "settings_color_theme") { ColorThemeOptions() }
                            SettingBlock(SettingIds.FONT_SIZE, focus, "settings_font_size") { FontSizeOptions() }
                            SettingBlock(SettingIds.PREVIEW, focus, "settings_preview") { PreviewOptions() }
                            SettingBlock(SettingIds.CARD_TINT, focus, "settings_card_tint") { CardTintOptions() }
                            SettingBlock(SettingIds.MEDIA_PREVIEW, focus, "settings_media_preview") { MediaPreviewOptions() }
                            SettingBlock(SettingIds.STAMPS, focus, "settings_stamps") { StampOptions() }
                            SettingBlock(SettingIds.FOOTER_STAMPS, focus, "settings_footer_stamps") { FooterStampOptions() }
                            SettingBlock(SettingIds.COMMENTS, focus, "settings_comments") { CommentOptions() }
                            SettingBlock(SettingIds.BACKGROUNDS, focus, "settings_backgrounds") {
                                Hint("settings_backgrounds_hint")
                                OutlinedButton(onClick = { controller.openCatalog("backgrounds") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("settings_backgrounds_open")) }
                            }
                        }
                        SettingsSection.NOTES -> {
                            for ((id, name) in listOf(SettingIds.TAGS to "tags", SettingIds.UNITS to "units", SettingIds.CURRENCIES to "currencies")) {
                                SettingBlock(id, focus, "settings_$name") {
                                    Hint("settings_${name}_hint")
                                    OutlinedButton(onClick = { controller.openCatalog(name) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("settings_${name}_open")) }
                                }
                            }
                            SettingBlock(SettingIds.EXPORT_NAMING, focus, "settings_export_naming") { ExportNamingOptions() }
                            SettingBlock(SettingIds.UNDO, focus, "settings_undo") { UndoOptions() }
                        }
                        SettingsSection.SECURITY -> {
                            SettingBlock(SettingIds.LOCK, focus, "settings_lock") {
                                Hint("settings_lock_hint")
                                OutlinedButton(onClick = { controller.openCatalog("lock") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("settings_lock_open")) }
                            }
                            SettingBlock(SettingIds.PRIVACY, focus, "settings_privacy") { PrivacyOptions() }
                            SettingBlock(SettingIds.ALARM, focus, "settings_alarm") { DesktopAlarmOptions(s, controller) }
                        }
                        SettingsSection.TRASH -> {
                            SettingBlock(SettingIds.TRASH, focus, "settings_trash") {
                                Hint("settings_trash_hint")
                                OutlinedButton(onClick = controller::openTrash, modifier = Modifier.fillMaxWidth()) { Text(stringResource("settings_trash_open")) }
                                val scope = rememberCoroutineScope()
                                val days by controller.appSettings.trashRetentionDays.collectAsState()
                                TrashRetention.OPTIONS.forEach { d ->
                                    val label = if (d == TrashRetention.NEVER) stringResource("settings_trash_never") else stringResource("settings_trash_days", d)
                                    OptionRow(label, selected = days == d) { scope.launch { controller.appSettings.setTrashRetentionDays(d) } }
                                }
                            }
                        }
                        SettingsSection.RECORDINGS -> {
                            // How long each kind of recording may last (Android D251): 4 minutes by default, 0 = until stopped.
                            listOf(
                                Triple(SettingIds.REC_LIMIT_AUDIO, "settings_rec_limit_audio", com.notio.shared.domain.RecordingKind.AUDIO),
                                Triple(SettingIds.REC_LIMIT_VIDEO, "settings_rec_limit_video", com.notio.shared.domain.RecordingKind.VIDEO),
                                Triple(SettingIds.REC_LIMIT_SCREEN, "settings_rec_limit_screen", com.notio.shared.domain.RecordingKind.SCREEN),
                            ).forEach { (id, title, kind) ->
                                SettingBlock(id, focus, title) {
                                    Hint("settings_rec_limit_hint")
                                    val limit by controller.appSettings.recordingLimit(kind).collectAsState()
                                    RecordingLimitEditor(limit) { next -> controller.appSettings.setRecordingLimit(kind, next) }
                                }
                            }
                            // The small Stop window over all others while the screen is recorded (Android D249's switch).
                            SettingBlock(SettingIds.FLOATING_STOP, focus, "rec_floating_switch") {
                                val on by controller.appSettings.floatingStopButton.collectAsState()
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource("rec_floating_switch"), Modifier.weight(1f))
                                    Switch(on, { controller.appSettings.setFloatingStopButton(it) })
                                }
                                Hint("desktop_floating_stop_hint")
                            }
                        }
                        SettingsSection.DATA -> {
                            SettingBlock(SettingIds.BACKUP, focus, "settings_data") {
                                Hint("settings_data_hint")
                                OutlinedButton(onClick = actions.exportBackup, modifier = Modifier.fillMaxWidth()) { Text(stringResource("settings_export_all")) }
                                OutlinedButton(onClick = actions.importBackup, modifier = Modifier.fillMaxWidth()) { Text(stringResource("settings_import_all")) }
                                OutlinedButton(onClick = actions.openDataFolder, modifier = Modifier.fillMaxWidth()) { Text(s.openDataFolder) }
                            }
                            SettingBlock(SettingIds.BACKUP_PASSWORD, focus, "settings_backup_password") { BackupPasswordOptions() }
                            SettingBlock(SettingIds.DATABASE_ENCRYPTION, focus, "settings_db_encryption") { DatabaseEncryptionOptions(controller) }
                        }
                        SettingsSection.SYNC -> {
                            SettingBlock(SettingIds.SYNC, focus, "settings_sync") {
                                Hint("settings_sync_hint")
                                OutlinedButton(onClick = controller::openSync, modifier = Modifier.fillMaxWidth()) { Text(stringResource("settings_sync") + " →") }
                            }
                        }
                        // The assistant (D146, the branch `assistant`): its line of the menu opens its own page.
                        SettingsSection.ASSISTANT -> {
                            SettingBlock(SettingIds.ASSISTANT, focus, "assistant_title") {
                                Hint("assistant_hint")
                                Button(onClick = { controller.openCatalog("assistant") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("assistant_setup")) }
                            }
                        }
                        SettingsSection.ABOUT -> {
                            SettingBlock(SettingIds.HELP, focus, "settings_help") {
                                OutlinedButton(onClick = controller::openHelp, modifier = Modifier.fillMaxWidth()) { Text(stringResource("settings_open_help")) }
                            }
                            SettingBlock(SettingIds.ABOUT, focus, "about_title") {
                                Text(stringResource("about_version", AppInfo.SHOWN, AppInfo.BUILD), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource("about_edition"), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource("about_developer"), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource("about_made_for"), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource("about_formats", TransferFormat.FORMAT_VERSION, com.notio.shared.data.NotioDatabase.SCHEMA_VERSION),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // The credits the licences ask for (Android D225): SQLCipher wants its notice in the app itself.
                                Text(stringResource("about_credits"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                var licences by remember { mutableStateOf(false) }
                                TextButton(onClick = { licences = true }) { Text(stringResource("about_licenses")) }
                                if (licences) LicencesDialog(onClose = { licences = false })
                            }
                            SettingBlock(SettingIds.APP_LOG, focus, "app_log") {
                                Text(stringResource("app_log_hint").replace("телефоне", "компьютере").replace("this phone", "this computer"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                var showAppLog by remember { mutableStateOf(false) }
                                OutlinedButton(onClick = { showAppLog = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource("app_log")) }
                                if (showAppLog) AppLogDialog(onClose = { showAppLog = false })
                                AppLogOptions()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The options of the phone's settings the desktop does not have yet: out of the menu's search too. */
val HIDDEN_ON_DESKTOP = setOf(
    SettingIds.AUDIO_PLACE,
)

/**
 * Reminders on the computer (Android D150 "Alarm"): how long Snooze waits, whether Notio stays in the tray when the window closes
 * (D25; the phone's alarms are the system's), the battery saver, and a test alarm in 10 seconds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopAlarmOptions(s: Strings, controller: NotioController) {
    val settings = controller.appSettings
    val scope = rememberCoroutineScope()
    val minutes by settings.snoozeMinutes.collectAsState()
    Text(stringResource("settings_snooze"), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppSettings.SNOOZE_CHOICES.forEach { m ->
            FilterChip(minutes == m, { scope.launch { settings.setSnoozeMinutes(m) } }, { Text(stringResource("settings_snooze_minutes", m)) })
        }
    }
    var keep by remember { mutableStateOf(controller.keepInTray) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(s.keepInTray, Modifier.weight(1f))
        Switch(keep, { keep = it; controller.keepInTray = it })
    }
    Hint2(s.keepInTrayHint)
    // The battery saver of this computer stands with the reminders it makes late (D89, Android D309; before: General, D86).
    var saving by remember { mutableStateOf(controller.powerSaving) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("battery_economy"), Modifier.weight(1f))
        Switch(saving, { saving = it; controller.choosePowerSaving(it) })
    }
    Hint2(stringResource("battery_economy_hint"))
    OutlinedButton(onClick = { controller.reminders.testAlarm(controller.res.str("alarm_test")); controller.say(controller.res.str("alarm_test_started")) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource("alarm_test"))
    }
}

/** The window of the computer (DESKTOP-ONLY, AGENT.md D45): Close into the tray, one Notio at a time. */
@Composable
private fun WindowOptions(controller: NotioController) {
    var toTray by remember { mutableStateOf(controller.closeToTray) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("desktop_close_to_tray"), Modifier.weight(1f))
        Switch(toTray, { toTray = it; controller.closeToTray = it })
    }
    Hint2(stringResource("desktop_close_to_tray_hint"))
    var single by remember { mutableStateOf(controller.singleInstance) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("desktop_single_instance"), Modifier.weight(1f))
        Switch(single, { single = it; controller.singleInstance = it })
    }
    Hint2(stringResource("desktop_single_instance_hint"))
    // Start with Windows (D61): the registry is read off the UI thread; the switch waits for it and for each change.
    val scope = rememberCoroutineScope()
    var autoStart by remember { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        autoStart = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AutoStart.isOn() }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(AutoStart.systemName(stringResource("desktop_auto_start")), Modifier.weight(1f))
        Switch(autoStart == true, { on ->
            autoStart = null
            scope.launch { autoStart = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AutoStart.set(on); AutoStart.isOn() } }
        }, enabled = autoStart != null && AutoStart.available)
    }
    Hint2(AutoStart.systemName(stringResource(if (AutoStart.available) "desktop_auto_start_hint" else "desktop_auto_start_unavailable")))
    var inTray by remember { mutableStateOf(controller.autoStartInTray) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource("desktop_auto_start_tray"), Modifier.weight(1f))
        Switch(inTray, { inTray = it; controller.autoStartInTray = it }, enabled = autoStart == true)
    }
    // How the window is drawn (D83): by the graphics card or by the processor; works from the next start.
    var rendering by remember { mutableStateOf(Rendering.mode(controller.dataDir)) }
    Text(stringResource("desktop_render"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    listOf(Rendering.AUTO to stringResource("desktop_render_auto"), Rendering.SOFTWARE to stringResource("desktop_render_software"), Rendering.DIRECT3D to "Direct3D", Rendering.OPENGL to "OpenGL").forEach { (mode, label) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = rendering == mode, onClick = { rendering = mode; Rendering.setMode(controller.dataDir, mode) })
            Text(label)
        }
    }
    Hint2(stringResource("desktop_render_hint", System.getProperty("skiko.renderApi") ?: "Direct3D / OpenGL", Rendering.adapters.ifBlank { "—" }))
}

@Composable
private fun Hint2(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- the backup password (Android D134), sealed for this Windows user ----

@Serializable
private data class BackupPasswordFile(val enabled: Boolean = false, val sealed: String? = null)

data class BackupPasswordSettings(val enabled: Boolean = false, val password: String = "")

/**
 * The password exported backups are protected with (Android `BackupPasswords`, D134): on or off and the password, which is kept only
 * sealed with DPAPI (the phone's Keystore) and never goes into a backup or a sync.
 */
class BackupPasswords(private val file: File, private val box: SecretBox = SecretBox.forThisSystem()) {
    suspend fun load(): BackupPasswordSettings = read()

    fun read(): BackupPasswordSettings = runCatching {
        val f = TransferFormat.json.decodeFromString(BackupPasswordFile.serializer(), file.readText())
        BackupPasswordSettings(f.enabled, f.sealed?.let { box.open(it) }.orEmpty())
    }.getOrDefault(BackupPasswordSettings())

    suspend fun save(settings: BackupPasswordSettings) {
        file.parentFile?.mkdirs()
        val sealed = settings.password.takeIf { it.isNotEmpty() }?.let { box.seal(it) }
        file.writeText(TransferFormat.json.encodeToString(BackupPasswordFile.serializer(), BackupPasswordFile(settings.enabled, sealed)))
    }

    /** The password an export uses: set only while protection is on. */
    fun exportPassword(): String? = read().takeIf { it.enabled && it.password.isNotEmpty() }?.password
}

val LocalBackupPasswords = staticCompositionLocalOf<BackupPasswords> { error("no backup passwords") }

val LocalStrings = staticCompositionLocalOf<Strings> { Strings.forSystem() }

/** A password field with Show / Hide (the phone's PasswordTextField). */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = label, singleLine = true, isError = isError, supportingText = supportingText,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { shown = !shown }) { Text(stringResource(if (shown) "password_hide" else "password_show")) } },
        modifier = modifier,
    )
}

/** Whether to keep the app log, which levels, and how often it clears itself (Android D254). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppLogOptions() {
    var options by remember { mutableStateOf(com.notio.shared.AppLog.options()) }
    fun change(next: com.notio.shared.AppLog.Options) {
        options = next
        com.notio.shared.AppLog.setOptions(next)
    }
    Row(
        Modifier.fillMaxWidth().toggleable(value = options.enabled, role = androidx.compose.ui.semantics.Role.Switch) { change(options.copy(enabled = it)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource("app_log_keep"), Modifier.weight(1f))
        Switch(checked = options.enabled, onCheckedChange = null)
    }
    Hint("app_log_keep_hint")
    if (options.enabled) {
        // Each level its own tick; FATAL is always written, so it is shown ticked and cannot be changed.
        Text(stringResource("app_log_level"), style = MaterialTheme.typography.labelLarge)
        com.notio.shared.AppLog.CHOOSABLE.forEach { level ->
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
        Hint("app_log_level_hint")
    }
    Text(stringResource("app_log_clear_period"), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            com.notio.shared.AppLog.ClearPeriod.NEVER to "app_log_clear_never",
            com.notio.shared.AppLog.ClearPeriod.DAILY to "app_log_clear_daily",
            com.notio.shared.AppLog.ClearPeriod.WEEKLY to "app_log_clear_weekly",
            com.notio.shared.AppLog.ClearPeriod.MONTHLY to "app_log_clear_monthly",
        ).forEach { (period, label) ->
            FilterChip(options.clear == period, { change(options.copy(clear = period)) }, { Text(stringResource(label)) })
        }
    }
    Hint("app_log_size_note")
}
