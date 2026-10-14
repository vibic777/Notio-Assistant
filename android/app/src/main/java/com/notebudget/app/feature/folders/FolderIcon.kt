package com.notebudget.app.feature.folders

import java.text.BreakIterator

/** The icon of a folder: up to three "characters" as a person sees them, so a whole emoji is never cut in half. */
object FolderIcon {
    const val MAX_CHARACTERS = 3

    /** Keeps at most [MAX_CHARACTERS] visible characters (an emoji with skin tone or a family is one). */
    fun limit(text: String): String {
        if (text.isEmpty()) return text
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(text)
        var end = breaks.first()
        var count = 0
        while (count < MAX_CHARACTERS) {
            val next = breaks.next()
            if (next == BreakIterator.DONE) return text
            end = next
            count++
        }
        return text.substring(0, end)
    }
}
