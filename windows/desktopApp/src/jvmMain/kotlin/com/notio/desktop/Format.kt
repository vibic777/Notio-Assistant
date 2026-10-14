package com.notio.desktop

import androidx.compose.ui.graphics.Color
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Display helpers; the stored values stay UTC millis and minor units (Android §1.2). */
object Format {
    /** The date and time format of Settings -> Language and formats (Android D138); every date the app shows goes through it. */
    @Volatile var formats: com.notio.shared.domain.DateTimeFormats = com.notio.shared.domain.DateTimeFormats()

    fun dateTime(millis: Long): String = formats.format(millis)

    fun date(millis: Long): String = formats.formatDate(millis)

    fun time(millis: Long): String = formats.formatTime(millis)

    /** `1 234.50 ₽` for 123450 minor units. */
    fun money(minor: Long, currency: String?): String {
        val plain = BigDecimal.valueOf(minor, 2).toPlainString()
        val negative = plain.startsWith("-")
        val digits = plain.removePrefix("-")
        val whole = digits.substringBefore('.').reversed().chunked(3).joinToString(" ").reversed()
        val text = (if (negative) "−" else "") + whole + "." + digits.substringAfter('.')
        return if (currency.isNullOrEmpty()) text else "$text $currency"
    }

    /** The label shown after every sum of a budget note (Android `BudgetCurrency.labelOf`). */
    fun currencyOf(extras: String?): String? = extras?.takeIf { it.isNotBlank() }?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject["currencyLabel"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }?.takeIf { it.isNotBlank() }

    fun color(hex: String?): Color? {
        val h = hex?.removePrefix("#") ?: return null
        val v = h.toLongOrNull(16) ?: return null
        return when (h.length) {
            6 -> Color(0xFF000000 or v)
            8 -> Color(v)
            else -> null
        }
    }
}
