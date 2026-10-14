package com.notio.common

/**
 * How long a message at the bottom of the screen (a snackbar) stays, in seconds (Android D360): 0..60, 10 by default, and 0 means
 * no message is shown at all. The app writes [seconds] from its settings; whoever shows a message reads it.
 */
object NoticeTime {
    const val MIN = 0
    const val MAX = 60
    const val DEFAULT = 10

    @Volatile
    var seconds: Int = DEFAULT
        set(value) { field = of(value) }

    /** A stored value as a time that is allowed; nothing stored - the default. */
    fun of(stored: Int?): Int = (stored ?: DEFAULT).coerceIn(MIN, MAX)
}
