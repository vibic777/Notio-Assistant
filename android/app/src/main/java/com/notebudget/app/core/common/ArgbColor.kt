package com.notebudget.app.core.common

import java.util.Locale

/** Helpers for opaque ARGB colours kept as `Int`; used by the RGB picker (D103). */
object ArgbColor {
    fun opaque(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    fun red(argb: Int): Int = argb shr 16 and 0xFF
    fun green(argb: Int): Int = argb shr 8 and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    /** `#RRGGBB`, upper case. */
    fun toHex(argb: Int): String = String.format(Locale.ROOT, "#%02X%02X%02X", red(argb), green(argb), blue(argb))

    /** Accepts `#RRGGBB`, `RRGGBB`, `#RGB` and `RGB` (any case); anything else is `null`. The result is opaque. */
    fun parseHex(input: String): Int? {
        val text = input.trim().removePrefix("#")
        if (text.any { it !in HEX }) return null
        return when (text.length) {
            6 -> opaque(text.substring(0, 2).toInt(16), text.substring(2, 4).toInt(16), text.substring(4, 6).toInt(16))
            3 -> opaque(text[0].digitToInt(16) * 17, text[1].digitToInt(16) * 17, text[2].digitToInt(16) * 17)
            else -> null
        }
    }

    private val HEX = "0123456789abcdefABCDEF"

    /** Recent colours are stored as comma-separated signed ints. Bad entries are dropped. */
    fun decodeList(text: String?): List<Int> = text.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }

    fun encodeList(colors: List<Int>): String = colors.joinToString(",")

    /** Puts [color] first, removes its duplicate, keeps at most [max]. */
    fun pushRecent(list: List<Int>, color: Int, max: Int = 8): List<Int> =
        (listOf(color) + list.filter { it != color }).take(max)
}
