package com.notebudget.app.domain.model

/** Domain models per AGENT_EN.md §7. Time is UTC epoch millis, money is `Long` minor units. */

data class Folder(
    val id: String,
    val name: String,
    val colorHex: String,
    /** `FolderStyleId.name`. */
    val styleId: String,
    val icon: String?,
    val sortOrder: Int,
    val featuredNoteId: String?,
    val featuredMetricsMask: Int,
    val featuredLayout: FeaturedLayout,
    val createdAt: Long,
    val updatedAt: Long,
    /** Set while the folder is in the trash (soft delete, D29, D81). */
    val deletedAt: Long? = null,
    /** A pinned folder stays before the others; the one pinned last comes first (D136). */
    val isPinned: Boolean = false,
    val pinnedAt: Long = 0,
    /** A comment on the folder (D177); its first 16 characters can be shown on the card. */
    val comment: String? = null,
    /** What the folder holds (D239): notes (every folder made before) or tasks. */
    val kind: FolderKind = FolderKind.NOTES,
) {
    fun shows(metric: FeaturedMetric): Boolean = featuredMetricsMask and metric.bit != 0
}

/** The kind of a folder (D239): a folder of notes, or a folder of tasks, where "New" makes a task. */
enum class FolderKind {
    NOTES, TASKS;

    companion object {
        fun of(name: String?): FolderKind = entries.firstOrNull { it.name == name } ?: NOTES
    }
}

data class Note(
    val id: String,
    /** INV-1: never null. */
    val folderId: String,
    val title: String,
    val type: NoteType,
    /** `null` = transparent, inherits the folder colour (D56). */
    val colorHex: String?,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val isTitleBold: Boolean,
    val contentText: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Set while the note is in the trash (soft delete, D29, D81). */
    val deletedAt: Long? = null,
    /** Type-specific settings as JSON (e.g. the budget layout). Owned by the note type. */
    val extras: String? = null,
    /**
     * Names of the note's tags (D109). NOT stored in the note table: filled only when a note is exported (backup,
     * note file) or imported, so tags can travel with it.
     */
    val tags: List<String> = emptyList(),
    /** When the note was pinned (0 = not known); the note pinned last is the first (D136). */
    val pinnedAt: Long = 0,
    /** The user's own order inside the folder (D136). */
    val sortOrder: Int = 0,
    /** A comment on the note (D177); its first 16 characters can be shown on the card. */
    val comment: String? = null,
    /** The content is hidden on the card and in View until the eye is pressed (D363); set in Edit. */
    val hidden: Boolean = false,
)

/** A label that can be put on any number of notes (D109). */
data class Tag(val id: String, val name: String)

data class BudgetNode(
    val id: String,
    val noteId: String,
    /** `null` for the root node; a budget note has exactly one root. */
    val parentId: String?,
    val title: String,
    val kind: BudgetNodeKind,
    /** Source of truth for LEAF only (INV-4). */
    val manualSumMinor: Long,
    val manualGoalMinor: Long,
    val comment: String?,
    val cachedSumMinor: Long,
    val cachedGoalMinor: Long,
    /** INV-5: always `cachedGoalMinor - cachedSumMinor`. */
    val cachedRemainderMinor: Long,
    val isExpanded: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * How many times a LEAF repeats (D94), 1..[MAX_QUANTITY], default 1. [manualSumMinor] and
     * [manualGoalMinor] are for ONE piece; the node counts `quantity` times into the totals.
     * Always 1 for a GROUP.
     */
    val quantity: Int = 1,
) {
    companion object {
        const val MAX_QUANTITY = 9_999
    }
}

data class Reminder(
    val id: String,
    val noteId: String,
    val type: ReminderType,
    val triggerAt: Long,
    val isEnabled: Boolean,
    val status: ReminderStatus,
    val soundEnabled: Boolean,
    val soundUri: String?,
    val vibrationEnabled: Boolean,
    val popupEnabled: Boolean,
    val requiresConfirmation: Boolean,
    val repeatUntilConfirmed: Boolean,
    val repeatIntervalMinutes: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    /** How loud the melody is, 10..100 % of the phone's alarm volume (D130). */
    val volumePercent: Int = 100,
    /** The melody starts quiet and reaches [volumePercent] after this many seconds; 0 = at once. */
    val rampSeconds: Int = 0,
    /** Extra rings after the first, every [repeatIntervalMinutes] minutes, whether or not it was confirmed (an alarm clock's "repeat"). */
    val repeatCount: Int = 0,
    /** How many of those extra rings already happened for the current occurrence. */
    val repeatsDone: Int = 0,
    val recurrence: Recurrence = Recurrence.NONE,
    /** Days of the week of a weekly reminder, one bit per day: Monday = 1 ... Sunday = 64. */
    val weekdaysMask: Int = 0,
    /** The day of the month of a monthly reminder, 1..31. */
    val monthDay: Int = 0,
    /** Snoozed: rings once more at this moment (D150); 0 = not snoozed. */
    val snoozedUntil: Long = 0,
)

/** User-editable part of a reminder. */
data class ReminderDraft(
    val triggerAt: Long,
    val soundEnabled: Boolean = true,
    val soundUri: String? = null,
    val vibrationEnabled: Boolean = true,
    val popupEnabled: Boolean = true,
    val requiresConfirmation: Boolean = false,
    val repeatUntilConfirmed: Boolean = false,
    val repeatIntervalMinutes: Int? = null,
    val volumePercent: Int = 100,
    val rampSeconds: Int = 0,
    val repeatCount: Int = 0,
    val recurrence: Recurrence = Recurrence.NONE,
    val weekdaysMask: Int = 0,
    val monthDay: Int = 0,
)

/**
 * Lock settings, AGENT_EN.md §7.7. Must live in secure storage, NOT in the ordinary DB or
 * DataStore. Only the model exists so far; the storage belongs to Phase 5 (gap G03: roles).
 */
data class AppLockSettings(
    val enabled: Boolean,
    val primaryMethod: LockMethod,
    val biometricEnabled: Boolean,
    val fallbackMethod: LockMethod?,
    val username: String?,
    val credentialSalt: String?,
    val credentialHash: String?,
    val patternHash: String?,
    val lockTimeoutSeconds: Int?,
    val lastUnlockAt: Long?,
)
