package com.notio.shared.domain

/** Enums per AGENT_EN.md §7.2. Persisted by `name`. */
/**
 * Kind of a note. An open identifier, not an enum, so that new kinds can be added by
 * registering a definition (see `feature/notes/types`) and so that a note of a kind this
 * version does not know (e.g. from a newer app's backup) is kept intact instead of failing.
 * Persisted and exported as [id].
 */
@JvmInline
value class NoteType(val id: String) {
    companion object {
        val TEXT = NoteType("TEXT")
        val DRAWING = NoteType("DRAWING")
        val LIST = NoteType("LIST")
        val BUDGET = NoteType("BUDGET")
        val AUDIO = NoteType("AUDIO")
        val BLUEPRINT = NoteType("BLUEPRINT")
        val SCHEDULE = NoteType("SCHEDULE")

        /** Formatted text, "like Word" (D214): its own kind next to the Markdown TEXT. */
        val WORD = NoteType("WORD")

        /** A task (D239): name, comment, start and end, done, a description; made in a folder of tasks. */
        val TASK = NoteType("TASK")
    }
}

enum class FolderStyleId { MINIMAL, SOFT, GRADIENT, PATTERN, OUTLINE, COMPACT }

/** Bit of the folder's `featuredMetricsMask`: SUM = 1, GOAL = 2, REMAINDER = 4 (§7.3). */
enum class FeaturedMetric(val bit: Int) {
    SUM(1), GOAL(2), REMAINDER(4);

    companion object {
        const val ALL_MASK = 7

        fun maskOf(vararg metrics: FeaturedMetric): Int = metrics.fold(0) { acc, m -> acc or m.bit }
    }
}

/** `TITLE_ONLY` is not mentioned in §6.4 — see gap G05. */
enum class FeaturedLayout { TITLE_ONLY, COMPACT, FULL }

enum class BudgetNodeKind { LEAF, GROUP }

enum class ReminderType { ONE_TIME }

/**
 * When a reminder comes back (D130): never, on chosen days of the week (every day = all seven), or on one day of every month.
 * A day that a month does not have (the 31st in April) is SKIPPED, not moved.
 */
enum class Recurrence { NONE, WEEKLY, MONTHLY }

enum class ReminderStatus { SCHEDULED, FIRED, AWAITING_CONFIRMATION, COMPLETED, CANCELLED }

enum class LockMethod { NONE, PASSWORD, PATTERN_3X3, LOGIN_PASSWORD, BIOMETRIC, PIN }

/** App theme: follow the system or force light / dark (§6.12). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The size of the text in the whole app (D119). It multiplies the system font scale, so a person who already
 * uses a big system font keeps it and can still go up or down from there.
 */
enum class FontSize(val factor: Float) { SMALL(0.85f), NORMAL(1.0f), MEDIUM(1.15f), LARGE(1.3f), EXTRA_LARGE(1.5f) }

/** Colour theme of the app (D105); the light / dark choice is separate ([ThemeMode]). */
/** D105, D115; YELLOW, LILAC, MAGENTA and CUSTOM (any colour picked by RGB) were added in D219. */
enum class ColorTheme { GREEN, PINK, ORANGE, RED, LIGHT_BLUE, BLUE, LIME, BLACK, GRAY, WHITE, YELLOW, LILAC, MAGENTA, CUSTOM }

/** Notification privacy, D60: full text / title only / generic text. */
enum class NotificationPrivacy { FULL, TITLE_ONLY, GENERIC }
