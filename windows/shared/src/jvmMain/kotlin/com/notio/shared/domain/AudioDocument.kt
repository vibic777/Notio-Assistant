package com.notio.shared.domain

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One recording of an AUDIO note (D112): a name and a comment the user can edit, next to the file.
 * [name] may be empty: the screen then shows a numbered default.
 */
@Serializable
data class AudioClip(
    val id: String,
    val name: String = "",
    val comment: String = "",
    val createdAt: Long = 0,
    /** When the name or the comment was last edited (D138); 0 = never edited. */
    val updatedAt: Long = 0,
    /** Length in milliseconds when known (0 = not known, e.g. a clip from before this field existed). */
    val durationMs: Int = 0,
    /** Where it was recorded, when the person switched that on in Settings (D148). */
    val place: GeoPoint? = null,
) {
    companion object {
        /** The id of the single recording that notes made before D112 have: its file is named after the note. */
        const val LEGACY_ID = "legacy"

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/**
 * The list of recordings of an AUDIO note. It lives in `note.extras` as JSON (no schema change).
 *
 * Compatibility (D84, D86): every field has a default and unknown fields are ignored. A note made before
 * D112 has NO document and one file named after the note: [forNote] shows it as the first clip
 * ([AudioClip.LEGACY_ID]), so nothing has to be migrated; the document appears when the user first
 * changes something.
 */
@Serializable
data class AudioDocument(
    val v: Int = 1,
    val clips: List<AudioClip> = emptyList(),
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    /** A readable copy for `contentText`, so search finds clip names and comments. */
    fun searchText(): String = clips.joinToString("\n") { clip ->
        clip.name + if (clip.comment.isNotBlank()) ": " + clip.comment.replace('\n', ' ') else ""
    }.trim()

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = false
        }

        /** `null` when [extras] is empty or not an audio document. */
        fun parse(extras: String?): AudioDocument? =
            extras?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }

        /**
         * The clips of a note. [legacyFileExists]: whether the pre-D112 file `<noteId>.m4a` is there; a
         * note without a document and with that file has exactly one (unnamed) clip.
         */
        fun forNote(extras: String?, legacyFileExists: Boolean): AudioDocument =
            parse(extras) ?: AudioDocument(
                clips = if (legacyFileExists) listOf(AudioClip(id = AudioClip.LEGACY_ID)) else emptyList(),
            )

        /**
         * The key of a clip's file in the audio store. Includes the note id, so clips of two notes never
         * share a file (an imported copy of a note gets its own files).
         */
        fun fileKey(noteId: String, clipId: String): String =
            if (clipId == AudioClip.LEGACY_ID) noteId else "${noteId}_$clipId"
    }
}
