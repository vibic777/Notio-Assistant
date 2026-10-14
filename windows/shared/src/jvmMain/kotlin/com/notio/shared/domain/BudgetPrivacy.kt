package com.notio.shared.domain


/**
 * The hidden amounts of a budget (Android D272): a budget may hide its sums, its goals or both. Hidden, they show as [MASK] on the card
 * and in the note until the eye of the note is pressed ([revealed], for as long as the note is open). The remainder is goal
 * minus sum, so it is hidden when either is. What is exported or shared is not masked: that is the person's own explicit step.
 */
data class BudgetPrivacy(
    val hideSums: Boolean = false,
    val hideGoals: Boolean = false,
    val revealed: Boolean = false,
    /** Whether the budget has goals and a remainder at all (D326): an income and an expense have none to show or to hide. */
    val goals: Boolean = true,
) {
    /** Whether the budget hides anything, so the note shows its eye. */
    val hidesAnything: Boolean get() = hideSums || (hideGoals && goals)

    private val sumsMasked: Boolean get() = hideSums && !revealed
    private val goalsMasked: Boolean get() = hideGoals && goals && !revealed

    fun sum(minor: Long, currency: String?): String = if (sumsMasked) MASK else Money.formatLabelled(minor, currency)

    fun goal(minor: Long, currency: String?): String = if (goalsMasked) MASK else Money.formatLabelled(minor, currency)

    val remainderMasked: Boolean get() = sumsMasked || goalsMasked

    fun remainder(minor: Long, currency: String?): String = if (remainderMasked) MASK else Money.formatLabelled(minor, currency)

    companion object {
        const val MASK = "*****"
        val NONE = BudgetPrivacy()

        fun of(layout: BudgetLayout, revealed: Boolean = false) = BudgetPrivacy(layout.hideSums, layout.hideGoals, revealed, layout.hasGoals)
    }
}
