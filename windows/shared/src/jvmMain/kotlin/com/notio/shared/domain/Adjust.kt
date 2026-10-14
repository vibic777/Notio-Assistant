package com.notio.shared.domain

/** How the "Change" button of a budget item or a list item changes a number (D165). */
enum class AdjustMode { ADD, SUBTRACT, SET }

/** The arithmetic of the "Change" dialog, kept apart from the screen so it can be tested. Works on any fixed-point `Long`. */
object Adjust {

    /**
     * The value after applying [amount] to [current]: added, subtracted, or (for [AdjustMode.SET]) put in place of it.
     * `null` when the result does not fit into a `Long`.
     */
    fun apply(current: Long, mode: AdjustMode, amount: Long): Long? = try {
        when (mode) {
            AdjustMode.ADD -> Math.addExact(current, amount)
            AdjustMode.SUBTRACT -> Math.subtractExact(current, amount)
            AdjustMode.SET -> amount
        }
    } catch (_: ArithmeticException) {
        null
    }
}
