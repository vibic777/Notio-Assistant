package com.notebudget.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** How a date is written on the screen (D138): the phone's own way, or one fixed pattern. */
enum class DateStyle(val pattern: String?) {
    SYSTEM(null),
    DMY_DOT("dd.MM.yyyy"),
    DMY_SLASH("dd/MM/yyyy"),
    DMY_DASH("dd-MM-yyyy"),
    MDY_SLASH("MM/dd/yyyy"),
    YMD_DASH("yyyy-MM-dd"),
    YMD_SLASH("yyyy/MM/dd"),
    D_MONTH_Y("d MMM yyyy"),
}

/** How a time is written on the screen (D138). */
enum class TimeStyle(val pattern: String?) {
    SYSTEM(null),
    H24("HH:mm"),
    H24_SEC("HH:mm:ss"),
    H12("hh:mm a"),
    H12_SEC("hh:mm:ss a"),
}

/**
 * The global date and time format (D138): every stamp of a note, a folder or a recording is written with it.
 * Stored as JSON in the settings; every field has a default so a stored value from another version still reads.
 */
@Serializable
data class DateTimeFormats(
    val date: DateStyle = DateStyle.SYSTEM,
    val time: TimeStyle = TimeStyle.SYSTEM,
) {
    fun formatDate(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val f = date.pattern?.let { DateTimeFormatter.ofPattern(it, locale) } ?: DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        return f.format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    fun formatTime(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val f = time.pattern?.let { DateTimeFormatter.ofPattern(it, locale) } ?: DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        return f.format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    fun format(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        formatDate(millis, zone, locale) + " " + formatTime(millis, zone, locale)

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

        fun decode(text: String?): DateTimeFormats =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: DateTimeFormats()
    }
}

/**
 * Which time stamps are SHOWN (D157): for notes (with their recordings, photos and videos) and for folders, the creation time
 * and the change time separately. Only the display changes, the times are kept either way. **All four are off by default**
 * (the user's choice, 2026-09-21: cards are tidier); a person who switched one on keeps it.
 */
@Serializable
data class StampDisplay(
    val notesCreated: Boolean = false,
    val notesModified: Boolean = false,
    val foldersCreated: Boolean = false,
    val foldersModified: Boolean = false,
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

        fun decode(text: String?): StampDisplay =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: StampDisplay()
    }
}

/**
 * The stamps at the BOTTOM of an open note, after its content, chosen for every kind of note on its own (D198): the ids of
 * the kinds ([NoteType.id]) that show when the note was made and when it was last changed. Off for every kind by default.
 * The way a moment is written is the global one (D138); the stamps at the top of a note (D157) are a separate switch.
 */
@Serializable
data class NoteFooterStamps(
    val created: Set<String> = emptySet(),
    val modified: Set<String> = emptySet(),
) {
    fun showsCreated(type: NoteType): Boolean = type.id in created
    fun showsModified(type: NoteType): Boolean = type.id in modified

    fun withCreated(type: NoteType, on: Boolean) = copy(created = if (on) created + type.id else created - type.id)
    fun withModified(type: NoteType, on: Boolean) = copy(modified = if (on) modified + type.id else modified - type.id)

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

        fun decode(text: String?): NoteFooterStamps =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: NoteFooterStamps()
    }
}

/**
 * Whether the cards show the comment (D177): the last line of a note card and of a folder card. Only what is shown changes.
 * A comment is only drawn when there is one, so both are on by default.
 */
@Serializable
data class CommentDisplay(
    val notes: Boolean = true,
    val folders: Boolean = true,
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

        fun decode(text: String?): CommentDisplay =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: CommentDisplay()
    }
}

/**
 * What a photo, video or screen-recording note shows of its pictures on its card (D160): nothing, the first photo or frame, or
 * a "deck of cards" of up to three: the first, the last and a random one.
 */
enum class MediaPreviewMode { NONE, FIRST, DECK;

    companion object {
        fun of(name: String?): MediaPreviewMode = entries.firstOrNull { it.name == name } ?: FIRST
    }
}

/** Which moment goes into the date and time of an exported file name (D139). */
enum class StampSource { EXPORT_MOMENT, CREATED, MODIFIED }

enum class DateOrder { YMD, DMY, MDY }

/** What stands between the parts of a date or a time inside a file name; [SOLID] writes them together (`20260920`). */
enum class StampSeparator(val text: String) { DASH("-"), DOT("."), SOLID("") }

/** What stands between the parts of a file name (title, item title, date, time). */
enum class NameJoiner(val text: String) { UNDERSCORE("_"), DASH("-"), SPACE(" "), DOT(".") }

/**
 * What an exported file is called (D139): any of the note's title, the recording's own title (an item of a multi-note),
 * a date stamp and a time stamp, each written the way the user chose. Colons cannot be in a file name, hence the
 * separators `-`, `.` or none. A 12-hour time ends with AM or PM.
 */
@Serializable
data class ExportNaming(
    val useNoteTitle: Boolean = true,
    val useItemTitle: Boolean = true,
    val useDate: Boolean = false,
    val useTime: Boolean = false,
    val stampSource: StampSource = StampSource.EXPORT_MOMENT,
    val dateOrder: DateOrder = DateOrder.YMD,
    val dateSeparator: StampSeparator = StampSeparator.DASH,
    val timeSeparator: StampSeparator = StampSeparator.DASH,
    val twelveHour: Boolean = false,
    val joiner: NameJoiner = NameJoiner.UNDERSCORE,
) {
    fun datePattern(): String {
        val s = dateSeparator.text
        return when (dateOrder) {
            DateOrder.YMD -> "yyyy${s}MM${s}dd"
            DateOrder.DMY -> "dd${s}MM${s}yyyy"
            DateOrder.MDY -> "MM${s}dd${s}yyyy"
        }
    }

    fun dateText(millis: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern(datePattern(), Locale.ROOT).format(Instant.ofEpochMilli(millis).atZone(zone))

    fun timeText(millis: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        return if (twelveHour) {
            DateTimeFormatter.ofPattern("hh${timeSeparator.text}mm${timeSeparator.text}ss", Locale.ROOT).format(at) +
                (if (at.hour < 12) "AM" else "PM")
        } else {
            DateTimeFormatter.ofPattern("HH${timeSeparator.text}mm${timeSeparator.text}ss", Locale.ROOT).format(at)
        }
    }

    /**
     * The file name without the extension. [itemTitle] is the title of a recording inside a multi-note (`null` for a
     * whole note). When every part is off or empty the name is [fallback].
     */
    fun baseName(
        noteTitle: String,
        itemTitle: String?,
        createdAt: Long,
        updatedAt: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        fallback: String = "note",
    ): String {
        val moment = when (stampSource) {
            StampSource.EXPORT_MOMENT -> now
            StampSource.CREATED -> createdAt
            StampSource.MODIFIED -> updatedAt
        }
        val parts = listOfNotNull(
            noteTitle.takeIf { useNoteTitle },
            itemTitle?.takeIf { useItemTitle },
            dateText(moment, zone).takeIf { useDate },
            timeText(moment, zone).takeIf { useTime },
        ).map { safe(it) }.filter { it.isNotBlank() }
        return parts.joinToString(joiner.text).ifBlank { fallback }.take(120)
    }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

        fun decode(text: String?): ExportNaming =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: ExportNaming()

        /** A piece of a file name: no characters a file system refuses. */
        fun safe(text: String): String =
            text.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
    }
}

/** Where the formats are kept (the settings), so the screens and their tests do not depend on Android. */
interface FormatPreferences {
    val dateTimeFormats: kotlinx.coroutines.flow.Flow<DateTimeFormats>
    val exportNaming: kotlinx.coroutines.flow.Flow<ExportNaming>
    val stampDisplay: kotlinx.coroutines.flow.Flow<StampDisplay>
    suspend fun setStampDisplay(display: StampDisplay)
    val commentDisplay: kotlinx.coroutines.flow.Flow<CommentDisplay>
    suspend fun setCommentDisplay(display: CommentDisplay)
    suspend fun setDateTimeFormats(formats: DateTimeFormats)
    suspend fun setExportNaming(naming: ExportNaming)
}
