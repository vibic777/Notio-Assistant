package com.notebudget.app.domain.model

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The three media kinds of notes (D147): photos, videos of the camera and recordings of the screen. */
enum class MediaKind(val type: NoteType, val extension: String, val mime: String, val isVideo: Boolean) {
    PHOTO(NoteType("PHOTO"), "jpg", "image/jpeg", false),
    VIDEO(NoteType("VIDEO"), "mp4", "video/mp4", true),
    SCREEN(NoteType("SCREEN"), "mp4", "video/mp4", true);

    companion object {
        fun of(type: NoteType): MediaKind? = entries.firstOrNull { it.type == type }
    }
}

/** Where a picture was taken or a recording made (D148). */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double) {
    /** `55.75581, 37.61764`: five decimals are about a metre. */
    fun text(): String = String.format(java.util.Locale.ROOT, "%.5f, %.5f", latitude, longitude)
}

/**
 * One item of a media note (D147): a photo or a recording, with its own name, comment and stamps.
 * The file lives outside the database (see `MediaStore`); [name] may be empty: the screen then shows a numbered default.
 */
@Serializable
data class MediaItem(
    val id: String,
    val name: String = "",
    val comment: String = "",
    val createdAt: Long = 0,
    /** When the name or the comment was last edited; 0 = never edited. */
    val updatedAt: Long = 0,
    /** Length in milliseconds of a recording; 0 for a photo. */
    val durationMs: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** Where it was made, when the person asked for the place to be noted. */
    val place: GeoPoint? = null,
    /** What sound a recording has: `NONE`, `MIC`, `SYSTEM` or `BOTH` (informational). */
    val audio: String = "NONE",
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/**
 * The items of a media note. It lives in `note.extras` as JSON (no schema change), like the recordings of an audio note.
 * Every field has a default and unknown fields are ignored, so files from another version still read (D84, D86).
 */
@Serializable
data class MediaDocument(
    val v: Int = 1,
    val items: List<MediaItem> = emptyList(),
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    /** A readable copy for `contentText`, so search finds the names and comments. */
    fun searchText(): String = items.joinToString("\n") { item ->
        item.name + if (item.comment.isNotBlank()) ": " + item.comment.replace('\n', ' ') else ""
    }.trim()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

        fun parse(extras: String?): MediaDocument =
            extras?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: MediaDocument()

        /** The key of an item's file in the media store; includes the note id, so two notes never share a file. */
        fun fileKey(noteId: String, itemId: String): String = "${noteId}_$itemId"
    }
}
