package com.notio.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.notio.shared.data.SettingsStore
import com.notio.shared.domain.ColorTheme
import com.notio.shared.domain.CommentDisplay
import com.notio.shared.domain.DateTimeFormats
import com.notio.shared.domain.ExportNaming
import com.notio.shared.domain.FontSize
import com.notio.shared.domain.MediaPreviewMode
import com.notio.shared.domain.NoteFooterStamps
import com.notio.shared.domain.NotificationPrivacy
import com.notio.shared.domain.StampDisplay
import com.notio.shared.domain.ThemeMode
import com.notio.shared.domain.TrashRetention
import com.notio.shared.domain.UndoHistory
import com.notio.shared.transfer.SettingsRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The phone's `SettingsDataStore` on the desktop (AGENT.md D29): the same names, flows and setters, so the ported settings pages read
 * as on Android. What a backup carries is kept in `settings.json` (the backup's own `SettingsRecord`, so a restore brings the
 * phone's choices and an export takes them back); what belongs to this computer only (snooze, the tray) in the window's own file.
 */
class AppSettings(
    private val store: SettingsStore,
    private val localGet: (String) -> String?,
    private val localSet: (String, String) -> Unit,
    private val onChange: (SettingsRecord) -> Unit = {},
) : com.notio.shared.domain.FormatPreferences {
    private val record = MutableStateFlow(store.read())
    val current: StateFlow<SettingsRecord> = record.asStateFlow()

    /** Reads the file again (after a backup was restored). */
    fun reload() {
        record.value = store.read()
        onChange(record.value)
    }

    fun update(change: (SettingsRecord) -> SettingsRecord) {
        val next = change(store.read())
        store.write(next)
        record.value = next
        onChange(next)
    }

    private fun <T> field(read: (SettingsRecord) -> T): StateFlow<T> = Derived(record, read)

    val languageTag: StateFlow<String?> = field { it.languageTag }
    suspend fun setLanguageTag(tag: String?) = update { it.copy(languageTag = tag) }

    val notificationPrivacy: StateFlow<NotificationPrivacy> =
        field { s -> runCatching { NotificationPrivacy.valueOf(s.notificationPrivacy ?: "") }.getOrDefault(NotificationPrivacy.GENERIC) }
    suspend fun setNotificationPrivacy(privacy: NotificationPrivacy) = update { it.copy(notificationPrivacy = privacy.name) }

    val themeMode: StateFlow<ThemeMode> = field { s -> runCatching { ThemeMode.valueOf(s.theme ?: "") }.getOrDefault(ThemeMode.SYSTEM) }
    suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(theme = mode.name) }

    val colorTheme: StateFlow<ColorTheme> = field { s -> runCatching { ColorTheme.valueOf(s.colorTheme ?: "") }.getOrDefault(ColorTheme.GREEN) }
    suspend fun setColorTheme(theme: ColorTheme) = update { it.copy(colorTheme = theme.name) }

    val customThemeColor: StateFlow<Int> = field { it.customThemeColor ?: com.notio.desktop.theme.DEFAULT_CUSTOM_THEME }
    suspend fun setCustomThemeColor(argb: Int) = update { it.copy(customThemeColor = argb or (0xFF shl 24)) }

    val fontSize: StateFlow<FontSize> = field { s -> runCatching { FontSize.valueOf(s.fontSize ?: "") }.getOrDefault(FontSize.NORMAL) }
    suspend fun setFontSize(size: FontSize) = update { it.copy(fontSize = size.name) }

    override val dateTimeFormats: StateFlow<DateTimeFormats> = field { DateTimeFormats.decode(it.dateTimeFormats) }
    override suspend fun setDateTimeFormats(formats: DateTimeFormats) = update { it.copy(dateTimeFormats = formats.encode()) }

    override val exportNaming: StateFlow<ExportNaming> = field { ExportNaming.decode(it.exportNaming) }
    override suspend fun setExportNaming(naming: ExportNaming) = update { it.copy(exportNaming = naming.encode()) }

    override val stampDisplay: StateFlow<StampDisplay> = field { StampDisplay.decode(it.stampDisplay) }
    override suspend fun setStampDisplay(display: StampDisplay) = update { it.copy(stampDisplay = display.encode()) }

    override val commentDisplay: StateFlow<CommentDisplay> = field { CommentDisplay.decode(it.commentDisplay) }
    override suspend fun setCommentDisplay(display: CommentDisplay) = update { it.copy(commentDisplay = display.encode()) }

    val footerStamps: StateFlow<NoteFooterStamps> = field { NoteFooterStamps.decode(it.footerStamps) }
    suspend fun setFooterStamps(stamps: NoteFooterStamps) = update { it.copy(footerStamps = stamps.encode()) }

    val undoSteps: StateFlow<Int> = field { (it.undoSteps ?: UndoHistory.DEFAULT_STEPS) }
    suspend fun setUndoSteps(steps: Int) = update { it.copy(undoSteps = steps) }

    val previewLines: StateFlow<Int> = field { (it.previewLines ?: 2).coerceIn(0, 2) }
    suspend fun setPreviewLines(lines: Int) = update { it.copy(previewLines = lines.coerceIn(0, 2)) }

    /** How strongly note and folder cards show their colour, in % (Android D273). */
    val cardTint: StateFlow<Int> = field { (it.cardTint ?: com.notio.shared.domain.CardTint.DEFAULT).coerceIn(com.notio.shared.domain.CardTint.MIN, com.notio.shared.domain.CardTint.MAX) }
    suspend fun setCardTint(percent: Int) = update { it.copy(cardTint = percent.coerceIn(com.notio.shared.domain.CardTint.MIN, com.notio.shared.domain.CardTint.MAX)) }

    val mediaPreview: StateFlow<MediaPreviewMode> =
        field { s -> runCatching { MediaPreviewMode.valueOf(s.mediaPreview ?: "") }.getOrDefault(MediaPreviewMode.FIRST) }
    suspend fun setMediaPreview(mode: MediaPreviewMode) = update { it.copy(mediaPreview = mode.name) }

    val drawingPreview: StateFlow<Boolean> = field { it.drawingPreview ?: true }
    suspend fun setDrawingPreview(on: Boolean) = update { it.copy(drawingPreview = on) }

    val trashRetentionDays: StateFlow<Int> = field { it.trashRetentionDays ?: TrashRetention.DEFAULT_DAYS }
    suspend fun setTrashRetentionDays(days: Int) = update { it.copy(trashRetentionDays = days) }

    // ---- this computer only ----

    private val snooze = MutableStateFlow(localGet(SNOOZE)?.toIntOrNull()?.takeIf { it in SNOOZE_CHOICES } ?: 10)
    val snoozeMinutes: StateFlow<Int> = snooze.asStateFlow()
    val snoozeNow: Int get() = snooze.value
    suspend fun setSnoozeMinutes(minutes: Int) {
        snooze.value = minutes
        localSet(SNOOZE, minutes.toString())
    }

    /** How long a message at the bottom of the window stays, in seconds (Android D360): 0..60, 10 by default, 0 = none is shown. */
    private val notice = MutableStateFlow(com.notio.common.NoticeTime.of(localGet(NOTICE_SECONDS)?.toIntOrNull()).also { com.notio.common.NoticeTime.seconds = it })
    val noticeSeconds: StateFlow<Int> = notice.asStateFlow()
    fun setNoticeSeconds(seconds: Int) {
        notice.value = com.notio.common.NoticeTime.of(seconds)
        com.notio.common.NoticeTime.seconds = notice.value
        localSet(NOTICE_SECONDS, notice.value.toString())
    }

    /** How long each kind of recording may last (Android D251): 4 minutes by default, 0 = until stopped; this computer only. */
    private val limits = com.notio.shared.domain.RecordingKind.entries.associateWith { kind ->
        MutableStateFlow(com.notio.shared.domain.RecordingLimit.decode(localGet("recLimit." + kind.key)))
    }

    fun recordingLimit(kind: com.notio.shared.domain.RecordingKind): StateFlow<com.notio.shared.domain.RecordingLimit> = limits.getValue(kind).asStateFlow()

    fun setRecordingLimit(kind: com.notio.shared.domain.RecordingKind, limit: com.notio.shared.domain.RecordingLimit) {
        limits.getValue(kind).value = limit
        localSet("recLimit." + kind.key, limit.encode())
    }

    /** The small always-on-top Stop window while the screen is recorded (Android D249's switch; on by default). */
    private val floatingStop = MutableStateFlow(localGet(FLOATING_STOP) != "false")
    val floatingStopButton: StateFlow<Boolean> = floatingStop.asStateFlow()
    fun setFloatingStopButton(on: Boolean) {
        floatingStop.value = on
        localSet(FLOATING_STOP, on.toString())
    }

    /**
     * The side of the tool panel of a drawing / blueprint (Android D261, D281): bottom (default), right, top or left; this computer only.
     * The old right / bottom choice carries over.
     */
    private val toolsSide = MutableStateFlow(ToolPanelSide.of(localGet("drawingToolsSide"), localGet("drawingToolsRight") == "true"))
    val drawingToolsSide: StateFlow<ToolPanelSide> = toolsSide.asStateFlow()
    fun setDrawingToolsSide(side: ToolPanelSide) {
        toolsSide.value = side
        localSet("drawingToolsSide", side.name)
    }

    companion object {
        val SNOOZE_CHOICES = listOf(1, 5, 10, 15, 30, 60)
        private const val SNOOZE = "snoozeMinutes"
        private const val NOTICE_SECONDS = "noticeSeconds"
        private const val FLOATING_STOP = "floatingStop"
    }
}

/** The settings of the window, for the ported settings pages (the phone's `rememberAppContainer().settings`). */
val LocalSettings = staticCompositionLocalOf<AppSettings> { error("no settings") }

/** A field of the settings as a StateFlow of its own: its value is there at once, so a page shows the choice from its first frame. */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
private class Derived<T>(private val source: StateFlow<SettingsRecord>, private val read: (SettingsRecord) -> T) : StateFlow<T> {
    override val value: T get() = read(source.value)
    override val replayCache: List<T> get() = listOf(value)
    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<T>): Nothing {
        source.map(read).distinctUntilChanged().collect(collector)
        kotlinx.coroutines.awaitCancellation()
    }
}
