package com.notebudget.app.domain.model

import com.notebudget.app.core.common.Quantity
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One row of a LIST note (D92): either an ITEM (text, optional quantity + unit + comment) or a TOTAL
 * ("Total by unit": a name, a comment and a unit whose result is the sum of the quantities of the
 * items in that unit). [kind] is a string so a row kind from a newer app is kept, not dropped.
 */
@Serializable
data class ListEntry(
    val id: String,
    val kind: String = ITEM,
    /** The item text, or the name of a total. */
    val text: String = "",
    val done: Boolean = false,
    val comment: String? = null,
    /** Thousandths of a unit (see `Quantity`); `null` = no quantity. Items only. */
    val quantityMilli: Long? = null,
    /** Unit id (see `BuiltInUnit` / `CustomUnit`); for a total, the unit it sums. */
    val unit: String? = null,
    /** Price of ONE (thousandths) in [priceUnit]; used in the PRICE mode of the list (D101). Items only. */
    val priceMilli: Long? = null,
    /** Unit id of the price (any unit: rubles, euros, or even kilograms); `null` = no unit chosen. */
    val priceUnit: String? = null,
    /** When the row was added (D178), for sorting by date; 0 = not known (rows of older lists). */
    val createdAt: Long = 0,
) {
    val isItem: Boolean get() = kind == ITEM
    val isTotal: Boolean get() = kind == TOTAL

    companion object {
        const val ITEM = "ITEM"
        const val TOTAL = "TOTAL"

        fun newId(): String = UUID.randomUUID().toString()
        fun newItem(now: Long = System.currentTimeMillis()) = ListEntry(id = newId(), createdAt = now)
        fun newTotal(unit: String? = null, now: Long = System.currentTimeMillis()) = ListEntry(id = newId(), kind = TOTAL, unit = unit, createdAt = now)
    }
}

/**
 * The structure of a LIST note. It lives in `note.extras` as JSON (no schema change), and
 * `contentText` always holds a readable text copy of it (`fallbackText`) for search, previews, text
 * export and older app versions, which keep showing the list as plain check boxes.
 *
 * Compatibility (D84, D86): every field has a default, unknown fields are ignored, unknown row kinds are
 * kept. [textHash] is the hash of the text copy written with the structure: if `contentText` no longer
 * matches (an older app edited the list, or the text was changed some other way), the text wins and the
 * structure of the rows is rebuilt from it, so a list is never shown out of date.
 */
@Serializable
data class ListDocument(
    val v: Int = 1,
    val entries: List<ListEntry> = emptyList(),
    /**
     * Names of the custom units used here, by id, so a list moved to another phone still reads right
     * even if that phone has never heard of the unit.
     */
    val unitNames: Map<String, String> = emptyMap(),
    val textHash: Int = 0,
    /** [MODE_QUANTITY] (default) or [MODE_PRICE] (D101): items also have a price of one and the list adds up costs. */
    val mode: String = MODE_QUANTITY,
    /** A [ListSortKey] name (D178); the manual order of [entries] is kept whatever is chosen. */
    val sort: String = ListSortKey.MANUAL.name,
    val sortDescending: Boolean = false,
) {
    val isPriceMode: Boolean get() = mode == MODE_PRICE

    val sortKey: ListSortKey get() = ListSortKey.of(sort)

    /** Whether the rows are shown sorted by a value (then they cannot be dragged). */
    val isSorted: Boolean get() = sortKey != ListSortKey.MANUAL

    /**
     * The rows in the order to SHOW (D178): the manual order, or the items sorted by [sortKey] (ties keep the manual order) with the
     * "Total by unit" rows and the rows of a kind this version does not know after them, in their own order.
     * [unitName] gives the name of a unit for the sort by unit.
     */
    fun displayEntries(unitName: (String) -> String = { it }): List<ListEntry> {
        val key = sortKey
        if (key == ListSortKey.MANUAL) return entries
        val items = entries.filter { it.isItem }
        val rest = entries.filterNot { it.isItem }
        val by: Comparator<ListEntry> = when (key) {
            ListSortKey.NAME -> valueComparator(sortDescending) { it.text.trim().lowercase() }
            ListSortKey.CREATED -> valueComparator(sortDescending) { it.createdAt.takeIf { at -> at > 0 } }
            ListSortKey.QUANTITY -> valueComparator(sortDescending) { it.quantityMilli }
            ListSortKey.PRICE -> valueComparator(sortDescending) { it.priceMilli }
            ListSortKey.COST -> valueComparator(sortDescending) { lineCostMilli(it) }
            ListSortKey.UNIT -> valueComparator(sortDescending) { e -> e.unit?.let(unitName)?.lowercase() }
            ListSortKey.DONE -> valueComparator(sortDescending) { if (it.done) 1 else 0 }
            ListSortKey.MANUAL -> return entries
        }
        return items.sortedWith(by) + rest
    }

    /**
     * What one item costs: quantity x price of one, in the price unit. No quantity counts as one piece; no price
     * means no cost. `null` when there is nothing to add up (or the product does not fit into a `Long`).
     */
    fun lineCostMilli(entry: ListEntry): Long? {
        val price = entry.priceMilli ?: return null
        val quantity = entry.quantityMilli ?: 1_000L
        return try {
            BigDecimal.valueOf(quantity, 3).multiply(BigDecimal.valueOf(price, 3))
                .movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Quantities of all items added up per unit (first-used unit first; `null` = items without a unit). */
    fun quantityTotals(): List<Pair<String?, Long>> = sumBy(entries.filter { it.isItem && it.quantityMilli != null }, { it.unit }) { it.quantityMilli ?: 0L }

    /** Costs of all items added up per PRICE unit (`null` = prices without a unit). */
    fun priceTotals(): List<Pair<String?, Long>> =
        sumBy(entries.filter { it.isItem && lineCostMilli(it) != null }, { it.priceUnit }) { lineCostMilli(it) ?: 0L }

    /** The costs of the items in the unit a "Total by unit" row sums, per price unit. */
    fun priceTotalsOf(total: ListEntry): List<Pair<String?, Long>> {
        val unit = total.unit ?: return emptyList()
        return copy(entries = entries.filter { it.isItem && it.unit == unit }).priceTotals()
    }

    private fun sumBy(items: List<ListEntry>, key: (ListEntry) -> String?, value: (ListEntry) -> Long): List<Pair<String?, Long>> {
        val sums = LinkedHashMap<String?, Long>()
        items.forEach { sums[key(it)] = (sums[key(it)] ?: 0L) + value(it) }
        return sums.map { it.key to it.value }
    }

    /** The result of a total: the sum of the quantities of all items in its unit. Nothing chosen = 0. */
    fun totalOf(total: ListEntry): Long {
        val unit = total.unit ?: return 0L
        return entries.filter { it.isItem && it.unit == unit }.sumOf { it.quantityMilli ?: 0L }
    }

    fun usedUnitIds(): Set<String> = entries.flatMap { listOfNotNull(it.unit, it.priceUnit) }.toSet()

    fun add(entry: ListEntry) = copy(entries = entries + entry)

    fun replace(id: String, change: (ListEntry) -> ListEntry) =
        copy(entries = entries.map { if (it.id == id) change(it) else it })

    fun remove(id: String) = copy(entries = entries.filterNot { it.id == id })

    /** The rows in the order of [ids] (a drag, D155); a row that is not named follows in its own order. */
    fun reorder(ids: List<String>): ListDocument {
        val byId = entries.associateBy { it.id }
        return copy(entries = ids.mapNotNull { byId[it] } + entries.filter { it.id !in ids })
    }

    /** The text and the JSON to store in `contentText` and `extras`. */
    class Encoded(val contentText: String, val extras: String)

    /**
     * [unitLabel] turns a unit id into the short text shown next to a quantity (the app's language at the
     * time of saving); [customNames] are the names of the custom units used here.
     */
    fun encode(unitLabel: (String) -> String, customNames: Map<String, String>): Encoded {
        val text = fallbackText(unitLabel)
        val stored = copy(unitNames = customNames, textHash = text.hashCode())
        return Encoded(text, JSON.encodeToString(serializer(), stored))
    }

    /** ` × 50 rub = 100 rub` for an item with a price; empty otherwise. */
    private fun priceText(entry: ListEntry, unitLabel: (String) -> String): String {
        val price = entry.priceMilli ?: return ""
        val cost = lineCostMilli(entry)
        val perOne = " × " + Quantity.formatPlain(price) + entry.priceUnit?.let { " " + unitLabel(it) }.orEmpty()
        val sum = cost?.let { " = " + Quantity.formatPlain(it) + entry.priceUnit?.let { u -> " " + unitLabel(u) }.orEmpty() }.orEmpty()
        return perOne + sum
    }

    /** `5 pcs, 3 kg` / `400 rub` for a list of sums. */
    fun sumsText(sums: List<Pair<String?, Long>>, unitLabel: (String) -> String): String =
        sums.joinToString(", ") { (unit, milli) -> Quantity.formatPlain(milli) + unit?.let { " " + unitLabel(it) }.orEmpty() }

    /** Readable text: `- [ ] Milk — 2 L (whole)`, `= Flour: 5 kg (total)`. Locale independent. */
    fun fallbackText(unitLabel: (String) -> String): String = entries.joinToString("\n") { entry ->
        val comment = entry.comment?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        when (entry.kind) {
            ListEntry.ITEM -> {
                val quantity = entry.quantityMilli?.let { q ->
                    " — " + Quantity.formatPlain(q) + entry.unit?.let { " " + unitLabel(it) }.orEmpty()
                }.orEmpty()
                val price = if (isPriceMode) priceText(entry, unitLabel) else ""
                (if (entry.done) "- [x] " else "- [ ] ") + entry.text.replace('\n', ' ') + quantity + price + comment
            }
            ListEntry.TOTAL -> {
                val unit = entry.unit?.let { " " + unitLabel(it) }.orEmpty()
                "= " + entry.text.replace('\n', ' ') + ": " + Quantity.formatPlain(totalOf(entry)) + unit + comment
            }
            else -> "? " + entry.text.replace('\n', ' ')
        }
    } + priceSummaryText(unitLabel)

    /** In the PRICE mode the last line sums everything: `= Total: 5 pcs; 400 rub`. */
    private fun priceSummaryText(unitLabel: (String) -> String): String {
        if (!isPriceMode) return ""
        val quantities = sumsText(quantityTotals(), unitLabel)
        val prices = sumsText(priceTotals(), unitLabel)
        if (quantities.isEmpty() && prices.isEmpty()) return ""
        return "\n= Total: " + listOf(quantities, prices).filter { it.isNotEmpty() }.joinToString("; ")
    }

    companion object {
        const val MODE_QUANTITY = "QUANTITY"
        const val MODE_PRICE = "PRICE"

        private val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = false
        }

        /**
         * The document of a note: the stored structure when it still matches the text, otherwise rebuilt
         * from the text. Lists made before D92 have no structure and come out as plain items.
         */
        fun fromNote(contentText: String?, extras: String?): ListDocument {
            val text = contentText.orEmpty()
            val parsed = parseExtras(extras)
            if (parsed != null && parsed.textHash == text.hashCode()) return parsed
            return fromText(text).copy(unitNames = parsed?.unitNames.orEmpty(), mode = parsed?.mode ?: MODE_QUANTITY)
        }

        /** `null` when [extras] is empty or not a list document. */
        fun parseExtras(extras: String?): ListDocument? =
            extras?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }

        /** Markdown task lines (`- [ ] milk`, `- [x] bread`); any other non-blank line is an open item. */
        fun fromText(text: String): ListDocument = ListDocument(
            entries = text.lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
                // Stable ids, so the rows keep their identity between reads of the same text.
                val id = "text-$index"
                when {
                    line.startsWith("- [x] ", ignoreCase = true) -> ListEntry(id, text = line.substring(6), done = true)
                    line.startsWith("- [ ] ") -> ListEntry(id, text = line.substring(6))
                    else -> ListEntry(id, text = line)
                }
            },
        )
    }
}
