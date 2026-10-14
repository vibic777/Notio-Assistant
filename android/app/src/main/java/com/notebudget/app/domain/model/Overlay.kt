package com.notebudget.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where an overlay sits on the picture (D148): a 3 x 3 grid. */
enum class OverlayPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT, MIDDLE_LEFT, CENTER, MIDDLE_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

    /**
     * The top-left corner of a box [boxW] x [boxH] placed at this position of a frame [frameW] x [frameH], with [margin]
     * kept from the frame's edges. A box larger than the frame is pinned to the top-left.
     */
    fun place(frameW: Float, frameH: Float, boxW: Float, boxH: Float, margin: Float): Pair<Float, Float> {
        val col = ordinal % 3
        val row = ordinal / 3
        val x = when (col) {
            0 -> margin
            1 -> (frameW - boxW) / 2f
            else -> frameW - boxW - margin
        }
        val y = when (row) {
            0 -> margin
            1 -> (frameH - boxH) / 2f
            else -> frameH - boxH - margin
        }
        return x.coerceAtLeast(0f) to y.coerceAtLeast(0f)
    }
}

private const val WHITE = 0xFFFFFFFF.toInt()

/** A text laid over the picture: colour, size (percent of the shorter side of the frame), place and an optional plate behind it. */
@Serializable
data class TextLayer(
    val enabled: Boolean = false,
    val text: String = "",
    val colorArgb: Int = WHITE,
    val sizePercent: Int = 4,
    val position: OverlayPosition = OverlayPosition.BOTTOM_LEFT,
    /** The plate behind the text; `null` = transparent (nothing behind the letters). */
    val backgroundArgb: Int? = null,
)

/** The date and time the picture was made, in a format of its own choice (D148). */
@Serializable
data class StampLayer(
    val enabled: Boolean = false,
    val showDate: Boolean = true,
    val showTime: Boolean = true,
    val formats: DateTimeFormats = DateTimeFormats(DateStyle.DMY_DOT, TimeStyle.H24_SEC),
    val colorArgb: Int = WHITE,
    val sizePercent: Int = 3,
    val position: OverlayPosition = OverlayPosition.BOTTOM_RIGHT,
    val backgroundArgb: Int? = null,
) {
    fun text(millis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String = when {
        showDate && showTime -> formats.format(millis, zone)
        showDate -> formats.formatDate(millis, zone)
        showTime -> formats.formatTime(millis, zone)
        else -> ""
    }
}

/** The place where it was made, as coordinates. */
@Serializable
data class GeoLayer(
    val enabled: Boolean = false,
    val colorArgb: Int = WHITE,
    val sizePercent: Int = 3,
    val position: OverlayPosition = OverlayPosition.BOTTOM_CENTER,
    val backgroundArgb: Int? = null,
)

/** A picture laid over the frame: a logo, a signature. [file] is a name in the overlay picture folder. */
@Serializable
data class ImageLayer(
    val enabled: Boolean = false,
    val file: String = "",
    val position: OverlayPosition = OverlayPosition.TOP_RIGHT,
    /** Width of the picture as a percent of the frame's width. */
    val widthPercent: Int = 20,
    val opacityPercent: Int = 100,
    /** The plate behind the picture; `null` = transparent, so a logo with a see-through background stays see-through. */
    val backgroundArgb: Int? = null,
    /** Grows when the picture file is replaced, so a preview knows to load it again. */
    val version: Int = 0,
)

/**
 * Everything laid over a photo or a video (D148): the time stamp, the place, a text and a picture. One set of settings per
 * kind of media note, kept in the settings as JSON. Every field has a default so an older stored value still reads.
 */
@Serializable
data class OverlaySettings(
    val stamp: StampLayer = StampLayer(),
    val geo: GeoLayer = GeoLayer(),
    val text: TextLayer = TextLayer(),
    val image: ImageLayer = ImageLayer(),
) {
    /** Whether anything at all is to be drawn. */
    val any: Boolean
        get() = (stamp.enabled && (stamp.showDate || stamp.showTime)) || geo.enabled ||
            (text.enabled && text.text.isNotBlank()) || (image.enabled && image.file.isNotBlank())

    /** Whether the picture changes with time (the time stamp does), so a video needs a new overlay every second. */
    val changesWithTime: Boolean get() = stamp.enabled && (stamp.showDate || stamp.showTime)

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

        fun decode(text: String?): OverlaySettings =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: OverlaySettings()
    }
}

/** What sound goes with a recording (D147): none, the microphone, the sound the phone plays, or both mixed. */
enum class AudioSource { NONE, MIC, SYSTEM, BOTH;

    val usesMic: Boolean get() = this == MIC || this == BOTH
    val usesSystem: Boolean get() = this == SYSTEM || this == BOTH

    companion object {
        fun of(name: String?): AudioSource = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Where the overlay settings of each kind are kept, so screens and tests do not depend on Android. */
interface OverlayPreferences {
    fun overlay(kind: MediaKind): kotlinx.coroutines.flow.Flow<OverlaySettings>
    suspend fun setOverlay(kind: MediaKind, settings: OverlaySettings)
}
