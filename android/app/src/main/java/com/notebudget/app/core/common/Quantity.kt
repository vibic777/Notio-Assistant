package com.notebudget.app.core.common

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A quantity of things (2.5 kg, 3 pcs, 120.50 rubles) kept as `Long` thousandths, so sums are
 * exact: no `Float` / `Double` anywhere near amounts (§1.2). Up to three decimals.
 */
object Quantity {

    /** Locale-aware text without trailing zeros, e.g. `2,5` or `1 200`. */
    fun format(milli: Long, locale: Locale = Locale.getDefault()): String =
        DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(locale)).format(BigDecimal.valueOf(milli, 3))

    /** Locale-independent text without trailing zeros, e.g. `2.5`; used in text snapshots. */
    fun formatPlain(milli: Long): String = BigDecimal.valueOf(milli, 3).stripTrailingZeros().toPlainString()

    /** Editable text, e.g. `2.5`; no quantity is an empty string. */
    fun formatForEdit(milli: Long?): String = if (milli == null) "" else formatPlain(milli)

    /** Blank input means "no quantity" and is valid. */
    fun isBlank(input: String): Boolean = input.isBlank()

    /**
     * Parses user input: `.` or `,` as decimal separator, spaces ignored, at most three decimals.
     * Returns `null` for blank input (no quantity) AND for input that is not a number or does not fit
     * into a `Long`; use [isBlank] to tell them apart.
     */
    fun parse(input: String): Long? {
        val text = input.filterNot { it.isWhitespace() }.replace(',', '.')
        if (text.isEmpty() || !NUMBER.matches(text)) return null
        return try {
            BigDecimal(text).movePointRight(3).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** `true` for blank input and for a valid number. */
    fun isValid(input: String): Boolean = isBlank(input) || parse(input) != null

    private val NUMBER = Regex("-?(\\d+(\\.\\d{0,3})?|\\.\\d{1,3})")
}
