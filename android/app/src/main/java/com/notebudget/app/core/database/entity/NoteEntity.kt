package com.notebudget.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Table `note`, see AGENT_EN.md §7.4 / §8.
 * INV-1: every note has a folder. The FK is RESTRICT, so a folder with notes cannot be
 * deleted at DB level; the use case must move or delete the notes in a transaction first.
 */
@Entity(
    tableName = "note",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["folder_id"])],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
    val title: String,
    /** `NoteType.id`: TEXT, DRAWING, LIST, BUDGET, or a kind added later. */
    val type: String,
    @ColumnInfo(name = "color_hex") val colorHex: String? = null,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_title_bold") val isTitleBold: Boolean = false,
    @ColumnInfo(name = "content_text") val contentText: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** Type-specific settings as JSON; added in schema version 2. */
    val extras: String? = null,
    /** When the note was last pinned; the most recently pinned note is the first one. Added in schema version 6. */
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long = 0,
    /** The user's own order inside a folder (drag and drop). Added in schema version 6. */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    /** A comment on the note. Added in schema version 10. */
    val comment: String? = null,
    /** The content is hidden until the eye is pressed (D363). Added in schema version 14. */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)
