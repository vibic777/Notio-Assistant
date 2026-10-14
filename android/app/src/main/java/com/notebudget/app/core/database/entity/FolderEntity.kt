package com.notebudget.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Table `folder`, see AGENT_EN.md §7.3 / §8. Time is UTC epoch millis; ids are UUID strings. */
@Entity(tableName = "folder")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "color_hex") val colorHex: String,
    @ColumnInfo(name = "style_id") val styleId: String,
    val icon: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "featured_note_id") val featuredNoteId: String? = null,
    @ColumnInfo(name = "featured_metrics_mask") val featuredMetricsMask: Int = 0,
    @ColumnInfo(name = "featured_layout") val featuredLayout: String = "COMPACT",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** A pinned folder stays before the others; the most recently pinned first. Added in schema version 6. */
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long = 0,
    /** A comment on the folder. Added in schema version 10. */
    val comment: String? = null,
    /** NOTES or TASKS (D239). Added in schema version 12. */
    @ColumnInfo(defaultValue = "NOTES") val kind: String = "NOTES",
)
