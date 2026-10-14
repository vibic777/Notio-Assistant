package com.notebudget.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.notebudget.app.data.transfer.SettingsPort
import com.notebudget.app.data.transfer.SettingsRecord
import com.notebudget.app.core.common.ArgbColor
import com.notebudget.app.domain.model.BuiltInUnit
import com.notebudget.app.domain.model.ColorTheme
import com.notebudget.app.domain.model.BackgroundSlot
import com.notebudget.app.domain.model.BackgroundSpec
import com.notebudget.app.domain.model.FontSize
import com.notebudget.app.domain.model.DateTimeFormats
import com.notebudget.app.domain.model.ExportNaming
import com.notebudget.app.domain.model.FormatPreferences
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.OverlayPreferences
import com.notebudget.app.domain.model.OverlaySettings
import com.notebudget.app.domain.model.SortOption
import com.notebudget.app.domain.model.SortPreferences
import com.notebudget.app.domain.model.CustomUnit
import com.notebudget.app.domain.model.NotificationPrivacy
import com.notebudget.app.domain.model.ThemeMode
import com.notebudget.app.domain.repository.UnitRepository
import com.notebudget.app.domain.usecase.TrashRetention
import com.notebudget.app.data.transfer.TransferFormat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Non-secret app settings. Secrets (password/pattern hashes) must NOT live here;
 * they go to secure storage (AGENT_EN.md §7.7, §13).
 */
class SettingsDataStore(private val context: Context) : SettingsPort, UnitRepository, SortPreferences, FormatPreferences, OverlayPreferences, com.notebudget.app.domain.model.FolderSelectionStore, com.notebudget.app.domain.model.CurrencyRepository {

    /** BCP-47 language tag chosen by the user, or `null` to follow the system language. */
    val languageTag: Flow<String?> =
        context.settingsDataStore.data.map { prefs -> prefs[KEY_LANGUAGE_TAG] }

    suspend fun setLanguageTag(tag: String?) {
        context.settingsDataStore.edit { prefs ->
            if (tag == null) prefs.remove(KEY_LANGUAGE_TAG) else prefs[KEY_LANGUAGE_TAG] = tag
        }
    }

    /**
     * What a reminder notification reveals (D60). Defaults to the most private option so that
     * a fresh install never leaks note content.
     */
    val notificationPrivacy: Flow<NotificationPrivacy> =
        context.settingsDataStore.data.map { prefs ->
            prefs[KEY_NOTIFICATION_PRIVACY]
                ?.let { runCatching { NotificationPrivacy.valueOf(it) }.getOrNull() }
                ?: NotificationPrivacy.GENERIC
        }

    suspend fun setNotificationPrivacy(privacy: NotificationPrivacy) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_NOTIFICATION_PRIVACY] = privacy.name }
    }

    /** Light / dark / follow the system. */
    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    /** The colour theme: green, pink, orange, red, light blue, blue, lime (D105). */
    val colorTheme: Flow<ColorTheme> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_COLOR_THEME]?.let { runCatching { ColorTheme.valueOf(it) }.getOrNull() } ?: ColorTheme.GREEN
    }

    // ---- backgrounds (D110) ----

    /** What each slot shows; a slot that was never set is [BackgroundSpec.None]. */
    val backgrounds: Flow<Map<BackgroundSlot, BackgroundSpec>> = context.settingsDataStore.data.map { prefs ->
        BackgroundSlot.entries.associateWith { BackgroundSpec.decode(prefs[backgroundKey(it)]) }
    }

    suspend fun setBackground(slot: BackgroundSlot, spec: BackgroundSpec) {
        context.settingsDataStore.edit { prefs ->
            if (spec == BackgroundSpec.None) prefs.remove(backgroundKey(slot)) else prefs[backgroundKey(slot)] = spec.encode()
        }
    }

    // ---- sorting (D121) ----

    override val folderSort: Flow<SortOption> = context.settingsDataStore.data.map { prefs ->
        SortOption.decode(prefs[KEY_FOLDER_SORT], SortOption.FOLDERS_DEFAULT)
    }
    override val noteSort: Flow<SortOption> = context.settingsDataStore.data.map { prefs ->
        SortOption.decode(prefs[KEY_NOTE_SORT], SortOption.NOTES_DEFAULT)
    }

    override suspend fun setFolderSort(option: SortOption) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_FOLDER_SORT] = option.encode() }
    }

    override suspend fun setNoteSort(option: SortOption) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_NOTE_SORT] = option.encode() }
    }

    // ---- the sort of the notes is kept per folder (D158) ----

    private fun decodeSortMap(text: String?): Map<String, SortOption> =
        text?.let { runCatching { TransferFormat.json.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()
            .mapNotNull { (id, v) -> SortOption.decode(v, SortOption.NOTES_DEFAULT).takeIf { v.isNotBlank() }?.let { id to it } }.toMap()

    override val folderNoteSorts: Flow<Map<String, SortOption>> = context.settingsDataStore.data.map { prefs ->
        decodeSortMap(prefs[KEY_FOLDER_NOTE_SORTS])
    }

    override suspend fun setNoteSortFor(folderId: String, option: SortOption, applyToAll: Boolean) {
        context.settingsDataStore.edit { prefs ->
            if (applyToAll) {
                prefs[KEY_NOTE_SORT] = option.encode()
                prefs.remove(KEY_FOLDER_NOTE_SORTS)
            } else {
                val map = decodeSortMap(prefs[KEY_FOLDER_NOTE_SORTS]) + (folderId to option)
                prefs[KEY_FOLDER_NOTE_SORTS] = TransferFormat.json.encodeToString<Map<String, String>>(map.mapValues { it.value.encode() })
            }
        }
    }

    // ---- date, time and file-name formats (D138, D139) ----

    override val dateTimeFormats: Flow<DateTimeFormats> = context.settingsDataStore.data.map { prefs ->
        DateTimeFormats.decode(prefs[KEY_DATE_TIME_FORMATS])
    }
    override val exportNaming: Flow<ExportNaming> = context.settingsDataStore.data.map { prefs ->
        ExportNaming.decode(prefs[KEY_EXPORT_NAMING])
    }

    override val stampDisplay: Flow<com.notebudget.app.domain.model.StampDisplay> = context.settingsDataStore.data.map { prefs ->
        com.notebudget.app.domain.model.StampDisplay.decode(prefs[KEY_STAMP_DISPLAY])
    }

    override val commentDisplay: Flow<com.notebudget.app.domain.model.CommentDisplay> = context.settingsDataStore.data.map { prefs ->
        com.notebudget.app.domain.model.CommentDisplay.decode(prefs[KEY_COMMENT_DISPLAY])
    }

    override suspend fun setCommentDisplay(display: com.notebudget.app.domain.model.CommentDisplay) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_COMMENT_DISPLAY] = display.encode() }
    }

    /** How the notes of a folder are shown (D300): "cards", "list" (one line each) or "grid" (two in a row). Kept on this phone. */
    val noteView: Flow<String> = context.settingsDataStore.data.map { prefs -> prefs[KEY_NOTE_VIEW] ?: NOTE_VIEW_CARDS }

    suspend fun setNoteView(view: String) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_NOTE_VIEW] = view }
    }

    override suspend fun setStampDisplay(display: com.notebudget.app.domain.model.StampDisplay) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_STAMP_DISPLAY] = display.encode() }
    }

    override suspend fun setDateTimeFormats(formats: DateTimeFormats) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_DATE_TIME_FORMATS] = formats.encode() }
    }

    override suspend fun setExportNaming(naming: ExportNaming) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_EXPORT_NAMING] = naming.encode() }
    }

    // ---- overlays of photos and videos (D148), one set per kind; not part of backups (they name a picture file) ----

    override fun overlay(kind: MediaKind): Flow<OverlaySettings> = context.settingsDataStore.data.map { prefs ->
        OverlaySettings.decode(prefs[stringPreferencesKey("overlay_" + kind.name.lowercase())])
    }

    override suspend fun setOverlay(kind: MediaKind, settings: OverlaySettings) {
        context.settingsDataStore.edit { prefs -> prefs[stringPreferencesKey("overlay_" + kind.name.lowercase())] = settings.encode() }
    }

    // ---- the folder the person was in (D161); not part of backups: the ids of another phone mean nothing ----

    override val lastFolderId: Flow<String?> = context.settingsDataStore.data.map { prefs -> prefs[KEY_LAST_FOLDER] }

    override suspend fun setLastFolder(folderId: String) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_LAST_FOLDER] = folderId }
    }

    /** How many steps back the editors of notes can undo (D197); 3 by default. */
    val undoSteps: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[KEY_UNDO_STEPS] ?: com.notebudget.app.core.common.UndoHistory.DEFAULT_STEPS)
            .coerceIn(com.notebudget.app.core.common.UndoHistory.MIN_STEPS, com.notebudget.app.core.common.UndoHistory.MAX_STEPS)
    }

    suspend fun setUndoSteps(steps: Int) {
        val valid = steps.coerceIn(com.notebudget.app.core.common.UndoHistory.MIN_STEPS, com.notebudget.app.core.common.UndoHistory.MAX_STEPS)
        context.settingsDataStore.edit { prefs -> prefs[KEY_UNDO_STEPS] = valid }
    }

    /** The stamps at the bottom of an open note, per kind (D198); none by default. */
    val footerStamps: Flow<com.notebudget.app.domain.model.NoteFooterStamps> = context.settingsDataStore.data.map { prefs ->
        com.notebudget.app.domain.model.NoteFooterStamps.decode(prefs[KEY_FOOTER_STAMPS])
    }

    suspend fun setFooterStamps(stamps: com.notebudget.app.domain.model.NoteFooterStamps) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_FOOTER_STAMPS] = stamps.encode() }
    }

    /** Cloud backups were removed (D223): the places and accounts they kept on this phone are forgotten. */
    suspend fun forgetCloudBackups() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("cloud_google"))
            prefs.remove(stringPreferencesKey("cloud_yandex"))
        }
    }

    /** How many lines of a note its card previews: 0, 1 or 2 (D159); 2 by default. */
    val previewLines: Flow<Int> = context.settingsDataStore.data.map { prefs -> (prefs[KEY_PREVIEW_LINES] ?: 2).coerceIn(0, 2) }

    suspend fun setPreviewLines(lines: Int) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_PREVIEW_LINES] = lines.coerceIn(0, 2) }
    }

    /** The picture on the card of a photo, video or screen note (D160). */
    val mediaPreview: Flow<com.notebudget.app.domain.model.MediaPreviewMode> = context.settingsDataStore.data.map { prefs ->
        com.notebudget.app.domain.model.MediaPreviewMode.of(prefs[KEY_MEDIA_PREVIEW])
    }

    suspend fun setMediaPreview(mode: com.notebudget.app.domain.model.MediaPreviewMode) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_MEDIA_PREVIEW] = mode.name }
    }

    /** Whether the card of a drawing or a blueprint shows its picture (D160); on by default. */
    /** The tool panel of a drawing / blueprint lies on the right (true) or at the bottom (false, the default) of the sheet (D261). */
    val drawingToolsRight: Flow<Boolean> = context.settingsDataStore.data.map { prefs -> prefs[KEY_DRAWING_TOOLS_RIGHT] ?: false }

    suspend fun setDrawingToolsRight(on: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_DRAWING_TOOLS_RIGHT] = on }
    }

    /** The side of the tool panel of a drawing / blueprint (D281): bottom, right, top or left; the old right / bottom choice carries over. */
    val drawingToolsSide: Flow<com.notebudget.app.feature.drawing.ToolPanelSide> = context.settingsDataStore.data.map { prefs ->
        com.notebudget.app.feature.drawing.ToolPanelSide.of(prefs[KEY_DRAWING_TOOLS_SIDE], prefs[KEY_DRAWING_TOOLS_RIGHT] ?: false)
    }

    suspend fun setDrawingToolsSide(side: com.notebudget.app.feature.drawing.ToolPanelSide) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DRAWING_TOOLS_SIDE] = side.name
            prefs[KEY_DRAWING_TOOLS_RIGHT] = side == com.notebudget.app.feature.drawing.ToolPanelSide.RIGHT
        }
    }

    val drawingPreview: Flow<Boolean> = context.settingsDataStore.data.map { prefs -> prefs[KEY_DRAWING_PREVIEW] ?: true }

    /** How strongly note and folder cards show their colour, in % (D273); 40 by default (it was a fixed 14 % / 22 % before). */
    val cardTint: Flow<Int> = context.settingsDataStore.data.map { prefs -> (prefs[KEY_CARD_TINT] ?: CardTint.DEFAULT).coerceIn(CardTint.MIN, CardTint.MAX) }

    suspend fun setCardTint(percent: Int) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_CARD_TINT] = percent.coerceIn(CardTint.MIN, CardTint.MAX) }
    }

    suspend fun setDrawingPreview(on: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_DRAWING_PREVIEW] = on }
    }

    /** Whether a quiet notification stays in the shade while a reminder is set (D154); on by default. */
    val statusNotification: Flow<Boolean> = context.settingsDataStore.data.map { prefs -> prefs[KEY_STATUS] ?: true }

    suspend fun setStatusNotification(on: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_STATUS] = on }
    }

    /** How long a message at the bottom of the screen stays, in seconds (D360): 0..60, 10 by default, 0 = none is shown; this device only. */
    val noticeSeconds: Flow<Int> = context.settingsDataStore.data.map { prefs -> com.notio.common.NoticeTime.of(prefs[KEY_NOTICE_SECONDS]) }

    suspend fun setNoticeSeconds(seconds: Int) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_NOTICE_SECONDS] = com.notio.common.NoticeTime.of(seconds) }
    }

    /** How long "Snooze" on a reminder waits, in minutes (D150). */
    val snoozeMinutes: Flow<Int> = context.settingsDataStore.data.map { prefs -> prefs[KEY_SNOOZE]?.takeIf { it in SNOOZE_CHOICES } ?: 10 }

    suspend fun setSnoozeMinutes(minutes: Int) {
        if (minutes in SNOOZE_CHOICES) context.settingsDataStore.edit { prefs -> prefs[KEY_SNOOZE] = minutes }
    }

    /**
     * Whether a screen recording shows the floating Stop button (D249); on by default. Turned off by the app itself when Android
     * stopped a recording right after the button appeared (Samsung, Android 14).
     */
    val screenFloatingStop: Flow<Boolean> = context.settingsDataStore.data.map { prefs -> prefs[KEY_SCREEN_FLOATING_STOP] ?: true }

    suspend fun setScreenFloatingStop(on: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_SCREEN_FLOATING_STOP] = on }
    }

    /** How long a recording of [kind] may last (D251): 4 minutes by default, 0 = until stopped. */
    fun recordingLimit(kind: com.notebudget.app.domain.model.RecordingKind): Flow<com.notebudget.app.domain.model.RecordingLimit> =
        context.settingsDataStore.data.map { prefs ->
            com.notebudget.app.domain.model.RecordingLimit.decode(prefs[androidx.datastore.preferences.core.stringPreferencesKey("rec_limit_" + kind.key)])
        }

    suspend fun setRecordingLimit(kind: com.notebudget.app.domain.model.RecordingKind, limit: com.notebudget.app.domain.model.RecordingLimit) {
        context.settingsDataStore.edit { prefs -> prefs[androidx.datastore.preferences.core.stringPreferencesKey("rec_limit_" + kind.key)] = limit.encode() }
    }

    /** Whether audio recordings note the place they were made (D148); off by default. */
    val audioPlace: Flow<Boolean> = context.settingsDataStore.data.map { prefs -> prefs[KEY_AUDIO_PLACE] ?: false }

    suspend fun setAudioPlace(on: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_AUDIO_PLACE] = on }
    }

    /** The text size of the whole app (D119). */
    val fontSize: Flow<FontSize> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_FONT_SIZE]?.let { runCatching { FontSize.valueOf(it) }.getOrNull() } ?: FontSize.NORMAL
    }

    suspend fun setFontSize(size: FontSize) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_FONT_SIZE] = size.name }
    }

    /** The colour of the "Your colour" theme, picked with the RGB tool (D219). */
    val customThemeColor: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_THEME] ?: com.notebudget.app.core.ui.theme.DEFAULT_CUSTOM_THEME
    }

    suspend fun setCustomThemeColor(argb: Int) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_CUSTOM_THEME] = argb or (0xFF shl 24) }
    }

    suspend fun setColorTheme(theme: ColorTheme) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_COLOR_THEME] = theme.name }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs -> prefs[KEY_THEME] = mode.name }
    }

    /** Days the trash keeps deleted items (D65, D93); 0 = never remove automatically. */
    val trashRetentionDays: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_TRASH_DAYS]?.takeIf { it in TrashRetention.OPTIONS } ?: TrashRetention.DEFAULT_DAYS
    }

    suspend fun setTrashRetentionDays(days: Int) {
        if (days !in TrashRetention.OPTIONS) return
        context.settingsDataStore.edit { prefs -> prefs[KEY_TRASH_DAYS] = days }
    }

    // ---- colours the user picked by RGB, most recent first (D103); not part of backups ----

    val recentColors: Flow<List<Int>> =
        context.settingsDataStore.data.map { prefs -> ArgbColor.decodeList(prefs[KEY_RECENT_COLORS]) }

    suspend fun addRecentColor(argb: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_RECENT_COLORS] = ArgbColor.encodeList(ArgbColor.pushRecent(ArgbColor.decodeList(prefs[KEY_RECENT_COLORS]), argb))
        }
    }

    // ---- units of measurement the user added (D92) ----

    override fun observeCustom(): Flow<List<CustomUnit>> =
        context.settingsDataStore.data.map { prefs -> decodeUnits(prefs[KEY_CUSTOM_UNITS]) }

    override suspend fun getCustom(): List<CustomUnit> = observeCustom().first()

    override suspend fun setCustom(units: List<CustomUnit>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CUSTOM_UNITS] = TransferFormat.json.encodeToString(ListSerializer(CustomUnit.serializer()), units)
        }
    }

    private fun decodeUnits(json: String?): List<CustomUnit> =
        json?.let { runCatching { TransferFormat.json.decodeFromString(ListSerializer(CustomUnit.serializer()), it) }.getOrNull() }
            .orEmpty()

    // ---- currencies the user added (D170) ----

    override fun observeCustomCurrencies(): Flow<List<com.notebudget.app.domain.model.CustomCurrency>> =
        context.settingsDataStore.data.map { prefs -> decodeCurrencies(prefs[KEY_CUSTOM_CURRENCIES]) }

    override suspend fun getCustomCurrencies(): List<com.notebudget.app.domain.model.CustomCurrency> = observeCustomCurrencies().first()

    override suspend fun setCustomCurrencies(currencies: List<com.notebudget.app.domain.model.CustomCurrency>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_CUSTOM_CURRENCIES] = TransferFormat.json.encodeToString(
                ListSerializer(com.notebudget.app.domain.model.CustomCurrency.serializer()), currencies,
            )
        }
    }

    private fun decodeCurrencies(json: String?): List<com.notebudget.app.domain.model.CustomCurrency> =
        json?.let {
            runCatching {
                TransferFormat.json.decodeFromString(ListSerializer(com.notebudget.app.domain.model.CustomCurrency.serializer()), it)
            }.getOrNull()
        }.orEmpty()

    // ---- backup (INV-11: only these five non-secret values travel) ----

    override suspend fun read() = SettingsRecord(
        languageTag = languageTag.first(),
        theme = themeMode.first().name,
        notificationPrivacy = notificationPrivacy.first().name,
        trashRetentionDays = trashRetentionDays.first(),
        customUnits = getCustom(),
        customCurrencies = getCustomCurrencies(),
        colorTheme = colorTheme.first().name,
        fontSize = fontSize.first().name,
        folderSort = folderSort.first().encode(),
        noteSort = noteSort.first().encode(),
        backgrounds = backgrounds.first().filterValues { it != BackgroundSpec.None }.mapKeys { it.key.name }.mapValues { it.value.encode() },
        dateTimeFormats = dateTimeFormats.first().encode(),
        exportNaming = exportNaming.first().encode(),
        stampDisplay = stampDisplay.first().encode(),
        commentDisplay = commentDisplay.first().encode(),
        previewLines = previewLines.first(),
        mediaPreview = mediaPreview.first().name,
        drawingPreview = drawingPreview.first(),
        cardTint = cardTint.first(),
        undoSteps = undoSteps.first(),
        customThemeColor = customThemeColor.first(),
        footerStamps = footerStamps.first().encode(),
    )

    override suspend fun write(settings: SettingsRecord) {
        settings.languageTag?.takeIf { it in SUPPORTED_LANGUAGES }?.let { setLanguageTag(it) }
        settings.theme?.let { v -> runCatching { ThemeMode.valueOf(v) }.getOrNull() }?.let { setThemeMode(it) }
        settings.notificationPrivacy?.let { v -> runCatching { NotificationPrivacy.valueOf(v) }.getOrNull() }
            ?.let { setNotificationPrivacy(it) }
        settings.colorTheme?.let { v -> runCatching { ColorTheme.valueOf(v) }.getOrNull() }?.let { setColorTheme(it) }
        settings.fontSize?.let { v -> runCatching { FontSize.valueOf(v) }.getOrNull() }?.let { setFontSize(it) }
        settings.folderSort?.let { setFolderSort(SortOption.decode(it, SortOption.FOLDERS_DEFAULT)) }
        settings.noteSort?.let { setNoteSort(SortOption.decode(it, SortOption.NOTES_DEFAULT)) }
        // Colours come back; a picture is not part of a backup, so a picture slot is shown as default until a new one is chosen.
        settings.backgrounds?.let { map ->
            BackgroundSlot.entries.forEach { slot ->
                val spec = BackgroundSpec.decode(map[slot.name])
                setBackground(slot, if (spec is BackgroundSpec.Image) BackgroundSpec.None else spec)
            }
        }
        settings.dateTimeFormats?.let { setDateTimeFormats(DateTimeFormats.decode(it)) }
        settings.exportNaming?.let { setExportNaming(ExportNaming.decode(it)) }
        settings.previewLines?.let { setPreviewLines(it) }
        settings.mediaPreview?.let { setMediaPreview(com.notebudget.app.domain.model.MediaPreviewMode.of(it)) }
        settings.drawingPreview?.let { setDrawingPreview(it) }
        settings.cardTint?.let { setCardTint(it) }
        settings.commentDisplay?.let { setCommentDisplay(com.notebudget.app.domain.model.CommentDisplay.decode(it)) }
        settings.stampDisplay?.let { setStampDisplay(com.notebudget.app.domain.model.StampDisplay.decode(it)) }
        settings.undoSteps?.let { setUndoSteps(it) }
        settings.customThemeColor?.let { setCustomThemeColor(it) }
        settings.footerStamps?.let { setFooterStamps(com.notebudget.app.domain.model.NoteFooterStamps.decode(it)) }
        settings.trashRetentionDays?.let { setTrashRetentionDays(it) }
        settings.customUnits?.let { units ->
            // A file may hold anything: keep only well-formed custom units, one per id and per name.
            val valid = units.filter {
                !BuiltInUnit.isBuiltIn(it.id) && it.id.isNotBlank() && it.name.isNotBlank() &&
                    it.name.length <= CustomUnit.MAX_NAME_LENGTH
            }.distinctBy { it.id }.distinctBy { it.name.lowercase() }
            setCustom(valid)
        }
        settings.customCurrencies?.let { list ->
            val valid = list.filter {
                !com.notebudget.app.domain.model.BuiltInCurrency.isBuiltIn(it.id) && it.id.isNotBlank() && it.name.isNotBlank() &&
                    it.name.length <= com.notebudget.app.domain.model.CustomCurrency.MAX_NAME_LENGTH
            }.distinctBy { it.id }.distinctBy { it.name.lowercase() }
            setCustomCurrencies(valid)
        }
    }

    companion object {
        private val KEY_COMMENT_DISPLAY = stringPreferencesKey("comment_display")
        private val KEY_CUSTOM_CURRENCIES = stringPreferencesKey("custom_currencies")

        /** Languages of the app (D15). `null` in [languageTag] means "follow the system". */
        val SUPPORTED_LANGUAGES = listOf("en", "ru", "be", "fr", "de", "es", "it", "zh", "ja", "ko")

        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_LANGUAGE_TAG = stringPreferencesKey("language_tag")
        private val KEY_NOTIFICATION_PRIVACY = stringPreferencesKey("notification_privacy")
        private val KEY_TRASH_DAYS = intPreferencesKey("trash_retention_days")
        private val KEY_COLOR_THEME = stringPreferencesKey("color_theme")
        private val KEY_FONT_SIZE = stringPreferencesKey("font_size")
        private fun backgroundKey(slot: BackgroundSlot) = stringPreferencesKey("background_" + slot.name.lowercase())
        private val KEY_FOLDER_SORT = stringPreferencesKey("folder_sort")
        private val KEY_NOTE_SORT = stringPreferencesKey("note_sort")
        private val KEY_CUSTOM_UNITS = stringPreferencesKey("custom_units")
        private val KEY_RECENT_COLORS = stringPreferencesKey("recent_colors")
        private val KEY_LAST_FOLDER = stringPreferencesKey("last_folder")
        private val KEY_MEDIA_PREVIEW = stringPreferencesKey("media_preview")
        private val KEY_DRAWING_TOOLS_RIGHT = androidx.datastore.preferences.core.booleanPreferencesKey("drawing_tools_right")
        private val KEY_DRAWING_TOOLS_SIDE = androidx.datastore.preferences.core.stringPreferencesKey("drawing_tools_side")
        private val KEY_DRAWING_PREVIEW = androidx.datastore.preferences.core.booleanPreferencesKey("drawing_preview")
        private val KEY_CARD_TINT = androidx.datastore.preferences.core.intPreferencesKey("card_tint")
        private val KEY_PREVIEW_LINES = intPreferencesKey("preview_lines")
        private val KEY_STATUS = androidx.datastore.preferences.core.booleanPreferencesKey("status_notification")
        val SNOOZE_CHOICES = listOf(1, 5, 10, 15, 30, 60)
        private val KEY_SNOOZE = intPreferencesKey("snooze_minutes")
        private val KEY_NOTICE_SECONDS = intPreferencesKey("notice_seconds")
        private val KEY_AUDIO_PLACE = androidx.datastore.preferences.core.booleanPreferencesKey("audio_place")
        private val KEY_SCREEN_FLOATING_STOP = androidx.datastore.preferences.core.booleanPreferencesKey("screen_floating_stop")
        private val KEY_DATE_TIME_FORMATS = stringPreferencesKey("date_time_formats")
        private val KEY_EXPORT_NAMING = stringPreferencesKey("export_naming")
        private val KEY_FOLDER_NOTE_SORTS = stringPreferencesKey("folder_note_sorts")
        private val KEY_STAMP_DISPLAY = stringPreferencesKey("stamp_display")
        private val KEY_NOTE_VIEW = stringPreferencesKey("note_view")
        const val NOTE_VIEW_CARDS = "cards"
        const val NOTE_VIEW_LIST = "list"
        const val NOTE_VIEW_GRID = "grid"
        private val KEY_UNDO_STEPS = intPreferencesKey("undo_steps")
        private val KEY_CUSTOM_THEME = intPreferencesKey("custom_theme_color")
        private val KEY_FOOTER_STAMPS = stringPreferencesKey("footer_stamps")
    }
}
