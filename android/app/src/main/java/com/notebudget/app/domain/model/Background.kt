package com.notebudget.app.domain.model

/**
 * The places that can have their own background (D110): the whole app, the folder cards, and each kind of note. Every kind of note has
 * its own slot (the schedule and the three media kinds were added in D181); a new kind needs a slot here and a name in the screen.
 */
enum class BackgroundSlot {
    APP, FOLDERS, NOTE_TEXT, NOTE_LIST, NOTE_BUDGET, NOTE_DRAWING, NOTE_BLUEPRINT, NOTE_AUDIO,
    NOTE_SCHEDULE, NOTE_PHOTO, NOTE_VIDEO, NOTE_SCREEN, NOTE_WORD, NOTE_TASK;

    companion object {
        /** The slot of a note screen; `null` for a kind that has none (an unknown kind). */
        fun forNote(type: NoteType): BackgroundSlot? = when (type.id) {
            "TEXT" -> NOTE_TEXT
            "LIST" -> NOTE_LIST
            "BUDGET" -> NOTE_BUDGET
            "DRAWING" -> NOTE_DRAWING
            "BLUEPRINT" -> NOTE_BLUEPRINT
            "AUDIO" -> NOTE_AUDIO
            "SCHEDULE" -> NOTE_SCHEDULE
            "PHOTO" -> NOTE_PHOTO
            "VIDEO" -> NOTE_VIDEO
            "SCREEN" -> NOTE_SCREEN
            "WORD" -> NOTE_WORD
            "TASK" -> NOTE_TASK
            else -> null
        }
    }
}

/**
 * What a slot shows: nothing special (the theme's page colour), one colour, or a picture chosen from the phone.
 * Stored in the settings as text: `NONE`, `COLOR:#RRGGBB` or `IMAGE:<version>` (the version changes when the picture
 * is replaced, so the screen reloads it).
 */
sealed interface BackgroundSpec {
    data object None : BackgroundSpec
    data class Color(val argb: Int) : BackgroundSpec
    data class Image(val version: Long) : BackgroundSpec

    fun encode(): String = when (this) {
        None -> "NONE"
        is Color -> "COLOR:" + String.format("#%06X", argb and 0xFFFFFF)
        is Image -> "IMAGE:$version"
    }

    companion object {
        /** Reads a stored value; anything unreadable is [None] (a settings file may hold anything). */
        fun decode(text: String?): BackgroundSpec {
            val value = text ?: return None
            return when {
                value.startsWith("COLOR:#") ->
                    value.removePrefix("COLOR:#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { Color(it or (0xFF shl 24)) } ?: None
                value == "IMAGE" -> Image(0)
                value.startsWith("IMAGE:") -> value.removePrefix("IMAGE:").toLongOrNull()?.let { Image(it) } ?: None
                else -> None
            }
        }
    }
}

/** How much a picture is shrunk when it is taken over (D110): the long side is at most [MAX_SIDE] pixels. */
object BackgroundImageMath {
    const val MAX_SIDE = 1600

    /** The power-of-two `inSampleSize` that brings the long side of a [width] x [height] picture to about [MAX_SIDE]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var longSide = maxOf(width, height)
        var sample = 1
        while (longSide / 2 >= maxSide) {
            longSide /= 2
            sample *= 2
        }
        return sample
    }
}
