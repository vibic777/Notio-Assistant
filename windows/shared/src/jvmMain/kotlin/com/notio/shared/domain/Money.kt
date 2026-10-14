package com.notio.shared.domain

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formatting and parsing of money kept as `Long` minor units (two decimals). No currency
 * symbol yet: currency is an open question (D32, gap G01).
 */
object Money {

    /** Locale-aware text with digit grouping, e.g. `1 234,50` or `1,234.50`. */
    fun format(minor: Long, locale: Locale = Locale.getDefault()): String =
        DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(locale)).format(BigDecimal.valueOf(minor, 2))

    /** [format] followed by the currency of the budget (D170), e.g. `1 234,50 ₽`; no currency = the plain number. */
    fun formatLabelled(minor: Long, currency: String?, locale: Locale = Locale.getDefault()): String =
        format(minor, locale) + if (currency.isNullOrEmpty()) "" else " $currency"

    /** Editable text without grouping, e.g. `1234.50`; a zero value is an empty string. */
    fun formatForEdit(minor: Long): String =
        if (minor == 0L) "" else BigDecimal.valueOf(minor, 2).toPlainString()

    /**
     * Parses user input. Blank means zero. Accepts `.` or `,` as decimal separator and
     * ignores spaces. Returns `null` for anything that is not a number with at most two
     * decimals or that does not fit into a `Long`.
     */
    fun parse(input: String): Long? {
        val text = input.filterNot { it.isWhitespace() }.replace(',', '.')
        if (text.isEmpty()) return 0L
        if (!NUMBER.matches(text)) return null
        return try {
            BigDecimal(text).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private val NUMBER = Regex("-?(\\d+(\\.\\d{0,2})?|\\.\\d{1,2})")
}
