package com.notio.shared.domain

/** How long the trash keeps things (Android D65, D140). */
object TrashRetention {
    /** 0 means "never remove automatically". */
    const val NEVER = 0

    /**
     * The trash empties itself only when the person chose so (D140): a default of 30 days would silently delete things of
     * someone who never saw the setting.
     */
    const val DEFAULT_DAYS = NEVER

    const val DAYS_30 = 30

    /** The choices offered in settings. */
    val OPTIONS = listOf(7, 14, DAYS_30, 60, 90, NEVER)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Whole days left before an item trashed at [deletedAt] is removed; `null` when nothing expires. */
    fun daysLeft(deletedAt: Long, now: Long, retentionDays: Int): Int? {
        if (retentionDays <= NEVER) return null
        val leftMs = deletedAt + retentionDays * DAY_MS - now
        return if (leftMs <= 0) 0 else ((leftMs + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** Items trashed at or before this moment are expired; `null` when nothing expires. */
    fun cutoff(now: Long, retentionDays: Int): Long? =
        if (retentionDays <= NEVER) null else now - retentionDays * DAY_MS
}

