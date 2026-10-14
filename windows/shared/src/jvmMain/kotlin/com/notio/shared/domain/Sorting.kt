package com.notio.shared.domain

import com.notio.shared.domain.ArgbColor
import java.time.Instant
import java.time.ZoneId

/**
 * What folders or notes are ordered by (D54, D57, D121, D137). [MANUAL] is the user's own order. The two dates come in three
 * kinds: the whole moment (`CREATED`), the calendar day only (`CREATED_DATE`: the notes of one day are then ordered by name)
 * and the time of day only (`CREATED_TIME`: everything made at 9 in the morning together, whatever the day).
 * [TAGS] and [TYPE] are for notes, [COUNT] (how many notes) is for folders. [STATUS] and [PRIORITY] are for the tasks of a folder
 * of tasks (D338), and so is [DONE] (Android D358): the tasks still open before the completed ones.
 */
enum class SortKey {
    MANUAL, UPDATED, CREATED, NAME, COLOR,
    TAGS, TYPE, COUNT, CREATED_DATE, CREATED_TIME, UPDATED_DATE, UPDATED_TIME, STATUS, PRIORITY, DONE;

    /** Whether the key orders by a moment, so "newest first" is the natural start. */
    val isDate: Boolean
        get() = this == UPDATED || this == CREATED || this == CREATED_DATE || this == CREATED_TIME ||
            this == UPDATED_DATE || this == UPDATED_TIME
}

/** A sort choice: the key and the direction. Stored in the settings as text such as `NAME:asc`. */
data class SortOption(val key: SortKey, val descending: Boolean) {
    fun encode(): String = key.name + ":" + if (descending) "desc" else "asc"

    companion object {
        /** Notes: the most recently changed first. */
        val NOTES_DEFAULT = SortOption(SortKey.UPDATED, descending = true)

        /** Folders: in the order the user arranged them (creation order until they do). */
        val FOLDERS_DEFAULT = SortOption(SortKey.MANUAL, descending = false)

        /** The keys the sort dialog offers for notes and for folders, in that order. */
        val NOTE_KEYS = listOf(
            SortKey.MANUAL, SortKey.NAME, SortKey.TAGS, SortKey.COLOR, SortKey.TYPE,
            SortKey.CREATED, SortKey.CREATED_DATE, SortKey.CREATED_TIME,
            SortKey.UPDATED, SortKey.UPDATED_DATE, SortKey.UPDATED_TIME,
        )
        /** What a folder of tasks adds to them (D338): the status and the priority of a task. */
        val TASK_KEYS = NOTE_KEYS + listOf(SortKey.STATUS, SortKey.PRIORITY, SortKey.DONE)
        val FOLDER_KEYS = listOf(
            SortKey.MANUAL, SortKey.NAME, SortKey.COLOR, SortKey.COUNT,
            SortKey.CREATED, SortKey.CREATED_DATE, SortKey.CREATED_TIME,
            SortKey.UPDATED, SortKey.UPDATED_DATE, SortKey.UPDATED_TIME,
        )

        /** Reads a stored value; anything unreadable gives [default] (a settings file may hold anything). */
        fun decode(text: String?, default: SortOption): SortOption {
            val parts = text?.split(':') ?: return default
            val key = parts.getOrNull(0)?.let { runCatching { SortKey.valueOf(it) }.getOrNull() } ?: return default
            val descending = when (parts.getOrNull(1)) {
                "desc" -> true
                "asc" -> false
                else -> return default
            }
            return SortOption(key, descending)
        }
    }
}

/** The ordering of folders and notes on the main screen. Pinned ones always come first, the one pinned last before the others. */
object Sorting {

    /**
     * Pinned notes first (the note pinned last on top, D136), then favourites (§12.3), then by [option]; ties keep a
     * stable order. [zone] decides what a "day" and a "time of day" are; [tagsByNote] feeds the tag order.
     */
    fun notes(
        notes: List<Note>,
        option: SortOption,
        tagsByNote: Map<String, List<Tag>> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Note> {
        val d = option.descending
        val select: (Note) -> Comparable<*>? = when (option.key) {
            SortKey.MANUAL -> { n -> n.sortOrder }
            SortKey.CREATED -> { n -> n.createdAt }
            SortKey.CREATED_DATE -> { n -> day(n.createdAt, zone) }
            SortKey.CREATED_TIME -> { n -> timeOfDay(n.createdAt, zone) }
            SortKey.UPDATED_DATE -> { n -> day(n.updatedAt, zone) }
            SortKey.UPDATED_TIME -> { n -> timeOfDay(n.updatedAt, zone) }
            SortKey.NAME -> { n -> n.title.lowercase() }
            SortKey.COLOR -> { n -> colorRank(n.colorHex) }
            SortKey.TYPE -> { n -> n.type.id }
            SortKey.TAGS -> { n -> tagKey(tagsByNote[n.id]) }
            SortKey.UPDATED, SortKey.COUNT -> { n -> n.updatedAt }
            // A note that is not a task has neither: it is last, as every missing value (D338).
            SortKey.STATUS -> { n -> task(n)?.state?.ordinal }
            SortKey.PRIORITY -> { n -> task(n)?.priority }
            SortKey.DONE -> { n -> task(n)?.let { if (it.ended) 1 else 0 } }
        }
        // The keys are worked out once per note, not in every comparison (D200): a colour is parsed, a date turned into a day,
        // a name lower-cased about 2 x n x log(n) times otherwise.
        val keys = HashMap<String, Comparable<*>?>(notes.size * 2)
        val names = HashMap<String, String>(notes.size * 2)
        notes.forEach { keys[it.id] = select(it); names[it.id] = it.title.lowercase() }
        val byKey: Comparator<Note> = comparing(d) { keys[it.id] }
        return notes.sortedWith(
            // The pinned ones stay on top, the one pinned last first (D136); nothing else jumps ahead (a favourite was merged into a pin, D180).
            compareByDescending<Note> { it.isPinned }.thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
                .then(byKey).thenBy { names[it.id] }.thenBy { it.id },
        )
    }

    fun folders(
        folders: List<Folder>,
        option: SortOption,
        counts: Map<String, Int> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Folder> {
        val d = option.descending
        val select: (Folder) -> Comparable<*>? = when (option.key) {
            SortKey.MANUAL, SortKey.TAGS, SortKey.TYPE, SortKey.STATUS, SortKey.PRIORITY, SortKey.DONE -> { f -> f.sortOrder }
            SortKey.UPDATED -> { f -> f.updatedAt }
            SortKey.CREATED -> { f -> f.createdAt }
            SortKey.CREATED_DATE -> { f -> day(f.createdAt, zone) }
            SortKey.CREATED_TIME -> { f -> timeOfDay(f.createdAt, zone) }
            SortKey.UPDATED_DATE -> { f -> day(f.updatedAt, zone) }
            SortKey.UPDATED_TIME -> { f -> timeOfDay(f.updatedAt, zone) }
            SortKey.NAME -> { f -> f.name.lowercase() }
            SortKey.COLOR -> { f -> colorRank(f.colorHex) }
            SortKey.COUNT -> { f -> counts[f.id] ?: 0 }
        }
        val keys = HashMap<String, Comparable<*>?>(folders.size * 2)
        val names = HashMap<String, String>(folders.size * 2)
        folders.forEach { keys[it.id] = select(it); names[it.id] = it.name.lowercase() }
        val byKey: Comparator<Folder> = comparing(d) { keys[it.id] }
        return folders.sortedWith(
            compareByDescending<Folder> { it.isPinned }.thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
                .then(byKey).thenBy { names[it.id] }.thenBy { it.createdAt }.thenBy { it.id },
        )
    }

    private fun task(note: Note): TaskDocument? = if (note.type == NoteType.TASK) TaskDocument.decode(note.extras) else null

    private fun day(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    private fun timeOfDay(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().toNanoOfDay()

    /** The tags of a note as one text (sorted by name), so notes with the same tags sit together; no tags = none = last. */
    private fun tagKey(tags: List<Tag>?): String? =
        tags?.takeIf { it.isNotEmpty() }?.map { it.name.lowercase() }?.sorted()?.joinToString("\u0000")

    /**
     * Where a colour stands in a rainbow: reds, oranges, yellows, greens, blues, purples, then greys from dark to light.
     * Equal colours have equal ranks, so sorting by colour also groups them. `null` (no colour) has no rank: it is last.
     */
    fun colorRank(hex: String?): Long? {
        val hsv = hsv(hex) ?: return null
        if (hsv.neutral) return 380_000_000L + hsv.value
        // Reds just below 360 degrees are moved in front of the reds at 0, so the reds are one run and one group (D196).
        val hue = (if (hsv.hue >= RED_WRAP) hsv.hue - 360f else hsv.hue) + (360f - RED_WRAP)
        return hue.toLong() * 1_000_000L + hsv.saturation * 1000L + hsv.value
    }

    /**
     * The family of a colour, for the group headers of a list sorted by colour (D196): a few named ranges of the hue, the
     * greys (black and white included) and "no colour". The families follow each other in the order of [colorRank].
     */
    fun colorFamily(hex: String?): ColorFamily {
        val hsv = hsv(hex) ?: return ColorFamily.NONE
        if (hsv.neutral) return ColorFamily.NEUTRAL
        val h = hsv.hue
        return when {
            h >= RED_WRAP || h < 15f -> ColorFamily.RED
            h < 45f -> ColorFamily.ORANGE
            h < 70f -> ColorFamily.YELLOW
            h < 165f -> ColorFamily.GREEN
            h < 200f -> ColorFamily.CYAN
            h < 255f -> ColorFamily.BLUE
            h < 290f -> ColorFamily.PURPLE
            else -> ColorFamily.PINK
        }
    }

    private const val RED_WRAP = 345f

    private class Hsv(val hue: Float, val saturation: Int, val value: Int, val neutral: Boolean)

    private fun hsv(hex: String?): Hsv? {
        val argb = hex?.let { runCatching { ArgbColor.parseHex(it) }.getOrNull() } ?: return null
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val value = (max * 1000).toInt()
        if (delta < 0.08f || max < 0.05f) return Hsv(0f, 0, value, neutral = true)
        val hue = when (max) {
            r -> 60f * ((g - b) / delta).mod(6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return Hsv(hue, ((delta / max) * 1000).toInt(), value, neutral = false)
    }

    /** Compares by [select]; a missing value (`null`) is always last, whichever way the list runs. */
    private fun <T> comparing(descending: Boolean, select: (T) -> Comparable<*>?): Comparator<T> = Comparator { a, b ->
        val x = select(a)
        val y = select(b)
        when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            else -> compareValues(x, y).let { if (descending) -it else it }
        }
    }
}

/** The named groups of a list sorted by colour (D196), in rainbow order; [NEUTRAL] = greys, black, white; [NONE] = no colour. */
enum class ColorFamily { RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK, NEUTRAL, NONE }

/**
 * Where the group headers of a list sorted by colour go (D196): the index of every item that starts a new group, with its
 * family. Pinned items stay on top in their own run and get no colour header; [keyOf] gives the colour and [pinned] the pin.
 */
fun <T> colorGroupStarts(items: List<T>, pinned: (T) -> Boolean, keyOf: (T) -> String?): Map<Int, ColorFamily> {
    val starts = HashMap<Int, ColorFamily>()
    var previous: ColorFamily? = null
    items.forEachIndexed { i, item ->
        if (pinned(item)) return@forEachIndexed
        val family = Sorting.colorFamily(keyOf(item))
        if (family != previous) starts[i] = family
        previous = family
    }
    return starts
}

/** Where the folder the person was last in is kept (D161), so the app opens on it. */
interface FolderSelectionStore {
    val lastFolderId: kotlinx.coroutines.flow.Flow<String?>
    suspend fun setLastFolder(folderId: String)
}

/** Where the sort choices are kept (the settings), so the main screen and its tests do not depend on Android. */
interface SortPreferences {
    val folderSort: kotlinx.coroutines.flow.Flow<SortOption>
    val noteSort: kotlinx.coroutines.flow.Flow<SortOption>
    suspend fun setFolderSort(option: SortOption)
    suspend fun setNoteSort(option: SortOption)

    /** The sort of the notes of each folder that has one of its own (D158); the others follow [noteSort]. */
    val folderNoteSorts: kotlinx.coroutines.flow.Flow<Map<String, SortOption>> get() = kotlinx.coroutines.flow.flowOf(emptyMap())

    /**
     * Sets how the notes of ONE folder are ordered; with [applyToAll] every folder gets it (the folders' own choices are
     * dropped and [noteSort] becomes the choice).
     */
    suspend fun setNoteSortFor(folderId: String, option: SortOption, applyToAll: Boolean) = setNoteSort(option)
}
