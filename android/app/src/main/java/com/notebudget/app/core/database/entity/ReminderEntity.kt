package com.notebudget.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Table `reminder`, AGENT_EN.md §7.6 / §8. Recurrence fields are gap G01 and not modelled yet. */
@Entity(
    tableName = "reminder",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["note_id"]), Index(value = ["status"])],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    /** `ReminderType.name`. */
    val type: String = "ONE_TIME",
    @ColumnInfo(name = "trigger_at") val triggerAt: Long,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean = true,
    /** `ReminderStatus.name`. */
    val status: String = "SCHEDULED",
    @ColumnInfo(name = "sound_enabled") val soundEnabled: Boolean = true,
    @ColumnInfo(name = "sound_uri") val soundUri: String? = null,
    @ColumnInfo(name = "vibration_enabled") val vibrationEnabled: Boolean = true,
    @ColumnInfo(name = "popup_enabled") val popupEnabled: Boolean = true,
    @ColumnInfo(name = "requires_confirmation") val requiresConfirmation: Boolean = false,
    @ColumnInfo(name = "repeat_until_confirmed") val repeatUntilConfirmed: Boolean = false,
    @ColumnInfo(name = "repeat_interval_minutes") val repeatIntervalMinutes: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    // v5 (D130): melody volume and fade-in, extra rings, recurrence.
    @ColumnInfo(name = "volume_percent", defaultValue = "100") val volumePercent: Int = 100,
    @ColumnInfo(name = "ramp_seconds", defaultValue = "0") val rampSeconds: Int = 0,
    @ColumnInfo(name = "repeat_count", defaultValue = "0") val repeatCount: Int = 0,
    @ColumnInfo(name = "repeats_done", defaultValue = "0") val repeatsDone: Int = 0,
    /** `Recurrence.name`. */
    @ColumnInfo(name = "recurrence", defaultValue = "'NONE'") val recurrence: String = "NONE",
    @ColumnInfo(name = "weekdays_mask", defaultValue = "0") val weekdaysMask: Int = 0,
    @ColumnInfo(name = "month_day", defaultValue = "0") val monthDay: Int = 0,
    // v7 (D150): snooze; 0 = not snoozed.
    @ColumnInfo(name = "snoozed_until", defaultValue = "0") val snoozedUntil: Long = 0,
)
