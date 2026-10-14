package com.notio.shared.domain

/** The recordings that have a time limit (D251). */
enum class RecordingKind(val key: String) { AUDIO("audio"), VIDEO("video"), SCREEN("screen") }

enum class LimitUnit(val seconds: Long) { SECONDS(1), MINUTES(60), HOURS(3_600) }

/**
 * How long a recording may last before it stops by itself (D251): [amount] of [unit], 0 = until the person stops it. A safety net
 * for a recording left running (a screen recording whose Stop button could not be shown, a phone forgotten in a pocket).
 */
data class RecordingLimit(val amount: Int = DEFAULT_AMOUNT, val unit: LimitUnit = LimitUnit.MINUTES) {
    val unlimited: Boolean get() = amount <= 0

    /** The limit in milliseconds; 0 = none. */
    val millis: Long get() = if (unlimited) 0 else amount * unit.seconds * 1_000

    /** How it is stored: `4:MINUTES`. */
    fun encode(): String = "$amount:${unit.name}"

    companion object {
        const val DEFAULT_AMOUNT = 4
        const val MAX_AMOUNT = 65_535
        val DEFAULT = RecordingLimit()
        val NONE = RecordingLimit(0)

        /** A stored value, or the default when there is none or it cannot be read. The amount is kept within 0..65535. */
        fun decode(text: String?): RecordingLimit {
            val parts = text?.split(':') ?: return DEFAULT
            if (parts.size != 2) return DEFAULT
            val amount = parts[0].toIntOrNull() ?: return DEFAULT
            val unit = runCatching { LimitUnit.valueOf(parts[1]) }.getOrNull() ?: return DEFAULT
            return RecordingLimit(amount.coerceIn(0, MAX_AMOUNT), unit)
        }

        /** What the person typed: digits only, cut to 0..65535; `null` while the field is empty. */
        fun parseAmount(typed: String): Int? {
            val digits = typed.filter { it.isDigit() }.take(6)
            if (digits.isEmpty()) return null
            return digits.toLong().coerceIn(0, MAX_AMOUNT.toLong()).toInt()
        }
    }
}
