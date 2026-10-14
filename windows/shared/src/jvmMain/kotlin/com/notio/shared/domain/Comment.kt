package com.notio.shared.domain

/**
 * A short comment the person can put on any note, folder and budget row (D177). Stored as typed (up to [MAX_LENGTH]); a card shows
 * only its first line, cut to [SHORT_LENGTH] characters.
 */
object Comment {
    const val MAX_LENGTH = 500

    /** The most a card shows: 16 characters, the last one being "…" when the comment was cut. */
    const val SHORT_LENGTH = 16

    /** The comment to store: trimmed and cut to [MAX_LENGTH]; `null` when nothing is left. */
    fun clean(text: String?): String? = text?.trim()?.take(MAX_LENGTH)?.trim()?.takeIf { it.isNotEmpty() }

    /** What a card shows: the first non-empty line, spaces collapsed, at most [max] characters; `null` when there is no comment. */
    fun short(text: String?, max: Int = SHORT_LENGTH): String? {
        val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        val flat = line.replace(Regex("\\s+"), " ")
        if (flat.codePointCount(0, flat.length) <= max) return flat
        // Cut on a character, never in the middle of an emoji.
        return flat.substring(0, flat.offsetByCodePoints(0, max - 1)) + "\u2026"
    }
}
