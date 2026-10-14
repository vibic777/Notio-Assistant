package com.notebudget.app.domain.model

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One row of a SCHEDULE note (D155): an ITEM (a time of day, a description, a comment) or a CALC, a calculator that shows
 * the time between two items ([fromId] and [toId]) under a description of its own. [kind] is a string so a row kind from
 * a newer app is kept, not dropped.
 */
@Serializable
data class ScheduleEntry(
    val id: String,
    val kind: String = ITEM,
    /** The description of the item, or of the calculator. */
    val text: String = "",
    val comment: String? = null,
    /** ITEM: minutes after midnight, 0..1439; `null` = no time yet. */
    val minutes: Int? = null,
    /** CALC: the two items the time between is taken from and to. */
    val fromId: String? = null,
    val toId: String? = null,
) {
    val isItem: Boolean get() = kind == ITEM
    val isCalc: Boolean get() = kind == CALC

    companion object {
        const val ITEM = "ITEM"
        const val CALC = "CALC"

        fun newId(): String = UUID.randomUUID().toString()
        fun newItem(minutes: Int? = null) = ScheduleEntry(id = newId(), minutes = minutes)
        fun newCalc() = ScheduleEntry(id = newId(), kind = CALC)
    }
}

/** How long a calculator says it is: [minutes] of a whole [days] + hours; `null` from [ScheduleMath] when a time is missing. */
object ScheduleMath {
    const val DAY = 24 * 60

    /**
     * The time from [from] to [to] in minutes. When [to] is earlier than [from] the second time is taken to be on the NEXT
     * day (23:00 to 01:00 is two hours). Equal times give 0. `null` when either time is missing.
     */
    fun between(from: Int?, to: Int?): Int? {
        if (from == null || to == null) return null
        return ((to - from) % DAY + DAY) % DAY
    }

    /** `08:05` for 485 minutes after midnight. */
    fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}

/**
 * The rows of a SCHEDULE note, in the order the person put them. Lives in `note.extras` as JSON (no schema change); a readable
 * copy is kept in `contentText` for search, previews and text export. Every field has a default and unknown fields are
 * ignored (D84, D86).
 */
@Serializable
data class ScheduleDocument(
    val v: Int = 1,
    val entries: List<ScheduleEntry> = emptyList(),
    /** A [ScheduleSortKey] name (D182); the manual order of [entries] is kept whatever is chosen. */
    val sort: String = ScheduleSortKey.MANUAL.name,
    val sortDescending: Boolean = false,
) {
    val sortKey: ScheduleSortKey get() = ScheduleSortKey.of(sort)

    /** Whether the rows are shown sorted by a value (then they cannot be dragged). */
    val isSorted: Boolean get() = sortKey != ScheduleSortKey.MANUAL

    /**
     * The rows to SHOW (D182): the manual order, or the items sorted by time or by description (ties keep the manual order), then the
     * calculators and the rows of a kind this version does not know, in their own order.
     */
    fun displayEntries(): List<ScheduleEntry> {
        val by: Comparator<ScheduleEntry> = when (sortKey) {
            ScheduleSortKey.MANUAL -> return entries
            ScheduleSortKey.TIME -> valueComparator(sortDescending) { it.minutes }
            ScheduleSortKey.NAME -> valueComparator(sortDescending) { it.text.trim().lowercase().takeIf { text -> text.isNotEmpty() } }
        }
        return entries.filter { it.isItem }.sortedWith(by) + entries.filterNot { it.isItem }
    }

    fun add(entry: ScheduleEntry) = copy(entries = entries + entry)

    fun replace(id: String, change: (ScheduleEntry) -> ScheduleEntry) =
        copy(entries = entries.map { if (it.id == id) change(it) else it })

    fun remove(id: String) = copy(entries = entries.filterNot { it.id == id })

    /** The rows in the order of [ids]; a row that is not named follows in its own order. */
    fun reorder(ids: List<String>): ScheduleDocument {
        val byId = entries.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }
        return copy(entries = ordered + entries.filter { it.id !in ids })
    }

    fun item(id: String?): ScheduleEntry? = entries.firstOrNull { it.id == id && it.isItem }

    /** What a calculator shows, in minutes; `null` when one of its two items is gone or has no time. */
    fun result(calc: ScheduleEntry): Int? = ScheduleMath.between(item(calc.fromId)?.minutes, item(calc.toId)?.minutes)

    /**
     * The readable copy: `09:00 Wake up`, and for a calculator `<description>: <result>` where [duration] writes minutes
     * (`1 h 30 min`) and [unknown] stands for a missing result.
     */
    fun fallbackText(duration: (Int) -> String, unknown: String): String = entries.joinToString("\n") { e ->
        val note = e.comment?.takeIf { it.isNotBlank() }?.let { " (" + it.replace('\n', ' ') + ")" }.orEmpty()
        when (e.kind) {
            ScheduleEntry.ITEM -> (e.minutes?.let { ScheduleMath.clock(it) } ?: "--:--") + " " + e.text + note
            ScheduleEntry.CALC -> "= " + e.text + ": " + (result(e)?.let(duration) ?: unknown) + note
            else -> e.text
        }
    }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

        fun parse(extras: String?): ScheduleDocument =
            extras?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: ScheduleDocument()
    }
}
