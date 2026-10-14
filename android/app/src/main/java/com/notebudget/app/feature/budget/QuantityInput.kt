package com.notebudget.app.feature.budget

import com.notebudget.app.domain.model.BudgetNode

/** The quantity field of a budget item (D94, D114): how many times it repeats, 1..[BudgetNode.MAX_QUANTITY]. */
object QuantityInput {
    /** The number typed, or `null` when it is empty, not a number, or out of range. */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..BudgetNode.MAX_QUANTITY }

    /** The text after tapping - or + ([delta] -1 / +1): unreadable text counts as 1, the result stays in range. */
    fun step(text: String, delta: Int): String {
        val current = text.trim().toIntOrNull() ?: 1
        return (current + delta).coerceIn(1, BudgetNode.MAX_QUANTITY).toString()
    }
}
