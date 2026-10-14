package com.notebudget.app.feature.drawing

import kotlin.math.roundToInt

/** The unit a sheet size is typed in (D260): pixels, or millimetres / centimetres at [SheetSize.PPI] pixels per inch. */
enum class SheetUnit(val key: String) {
    PX("px"), MM("mm"), CM("cm");

    /** [value] in this unit as whole pixels. */
    fun toPixels(value: Double): Int = when (this) {
        PX -> value
        MM -> value / 25.4 * SheetSize.PPI
        CM -> value / 2.54 * SheetSize.PPI
    }.roundToInt()

    /** [pixels] in this unit (mm and cm to one decimal place). */
    fun fromPixels(pixels: Int): Double = when (this) {
        PX -> pixels.toDouble()
        MM -> Math.round(pixels * 25.4 / SheetSize.PPI * 10) / 10.0
        CM -> Math.round(pixels * 2.54 / SheetSize.PPI * 10) / 10.0
    }

    companion object {
        fun of(key: String?): SheetUnit = entries.firstOrNull { it.key == key } ?: PX
    }
}

/** Why a sheet size cannot be used. */
enum class SheetSizeProblem { TOO_SMALL, TOO_LARGE }

/**
 * The size of a drawing / blueprint sheet in pixels (D260). Every note has its own; before D260 it was always
 * [DEFAULT_WIDTH] x [DEFAULT_HEIGHT] (3:4). The limits keep the three bitmaps a drawing holds in memory within reason.
 */
object SheetSize {
    const val DEFAULT_WIDTH = 1080
    const val DEFAULT_HEIGHT = 1440

    /** Pixels per inch for sizes in mm and cm (a good print quality at this app's sheet sizes). */
    const val PPI = 150

    const val MIN_SIDE = 64
    const val MAX_SIDE = 4096

    /** At most 2048 x 2048 pixels in all (16 MB per bitmap). */
    const val MAX_PIXELS = 2048 * 2048

    /** The ready sizes: the ratio and its pixels. */
    data class Preset(val label: String, val width: Int, val height: Int)

    val PRESETS = listOf(
        Preset("16:9", 1920, 1080),
        Preset("9:16", 1080, 1920),
        Preset("4:3", 1440, 1080),
        Preset("3:4", 1080, 1440),
    )

    fun problem(width: Int, height: Int): SheetSizeProblem? = when {
        width < MIN_SIDE || height < MIN_SIDE -> SheetSizeProblem.TOO_SMALL
        width > MAX_SIDE || height > MAX_SIDE || width.toLong() * height > MAX_PIXELS -> SheetSizeProblem.TOO_LARGE
        else -> null
    }

    /** A stored size, or the default when it is missing or out of the limits (an edited or damaged file). */
    fun safe(width: Int, height: Int): Pair<Int, Int> =
        if (problem(width, height) == null) width to height else DEFAULT_WIDTH to DEFAULT_HEIGHT
}
