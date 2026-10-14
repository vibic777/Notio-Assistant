package com.notio.shared.domain

/**
 * The strength of the colour of note and folder cards (Android D273): the share of the card's colour laid over the page, in %. Before it
 * was fixed at 14 % for notes and 22 % for folders, which the owner found too pale.
 */
object CardTint {
    const val MIN = 10
    const val MAX = 80
    const val DEFAULT = 40

    /** A folder card is a little stronger than a note card, as before (22 % against 14 %), never beyond [MAX]. */
    fun folder(note: Float): Float = (note * 1.25f).coerceAtMost(MAX / 100f)
}
