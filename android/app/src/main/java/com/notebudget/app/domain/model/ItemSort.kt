package com.notebudget.app.domain.model

/**
 * How the rows inside one group of a budget are ordered (D178). [MANUAL] is the person's own order (dragging); the others sort by
 * a value, and the manual order is kept underneath, so going back to [MANUAL] brings it back.
 */
enum class BudgetSortKey {
    MANUAL, NAME, CREATED, CHANGED, SUM, GOAL, REMAINDER,

    /** The share of the goal that is reached (sum / goal); a row without a goal has none and comes last. */
    PROGRESS;

    companion object {
        fun of(name: String?): BudgetSortKey = entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/** How the rows of a list note are ordered (D178); the manual order is kept underneath. */
enum class ListSortKey {
    MANUAL, NAME, CREATED, QUANTITY,

    /** The price of one; only lists in the price mode have it. */
    PRICE,

    /** Quantity times price of one; only lists in the price mode have it. */
    COST,

    /** By the name of the unit of the quantity. */
    UNIT,

    /** Not done first (or, descending, done first). */
    DONE;

    /** Whether the key needs prices, so it is offered only in the price mode. */
    val needsPrices: Boolean get() = this == PRICE || this == COST

    companion object {
        fun of(name: String?): ListSortKey = entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/** How the rows of a schedule note are ordered (D182); the manual order is kept underneath. */
enum class ScheduleSortKey {
    MANUAL,

    /** By the time of day, from 00:00 (or, descending, from 23:59); an item without a time comes last. */
    TIME,

    /** By the description; an item without one comes last. */
    NAME;

    companion object {
        fun of(name: String?): ScheduleSortKey = entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/**
 * A comparator on the value picked by [select]: ascending or [descending]; a row without a value (`null`) comes LAST whichever way it
 * goes, so "the biggest first" never starts with rows that have nothing.
 */
fun <T> valueComparator(descending: Boolean, select: (T) -> Comparable<*>?): Comparator<T> = Comparator { a, b ->
    val x = select(a)
    val y = select(b)
    when {
        x == null && y == null -> 0
        x == null -> 1
        y == null -> -1
        else -> {
            @Suppress("UNCHECKED_CAST")
            val c = (x as Comparable<Any>).compareTo(y as Any)
            if (descending) -c else c
        }
    }
}
