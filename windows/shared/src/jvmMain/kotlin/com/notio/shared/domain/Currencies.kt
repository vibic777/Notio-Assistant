package com.notio.shared.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Currencies of the budget notes (D170). Each budget note has ONE chosen currency (or none) that labels every sum in it. The
 * built-in ones have stable ids (ISO codes) that are stored in notes and backups (never change them) and can never be deleted;
 * the person can add more of his own (see [CustomCurrency]), as with the units of measurement.
 */
object BuiltInCurrency {
    /** id (ISO 4217 code) -> the sign shown after a sum; the signs are the same in every language. */
    val SYMBOLS: Map<String, String> = linkedMapOf(
        "RUB" to "₽", "USD" to "$", "EUR" to "€", "GBP" to "£", "BYN" to "Br", "UAH" to "₴", "KZT" to "₸",
        "PLN" to "zł", "CNY" to "CN¥", "JPY" to "JP¥", "KRW" to "₩", "CHF" to "CHF", "TRY" to "₺", "INR" to "₹",
    )

    /** In the order they are offered. */
    val ALL: List<String> = SYMBOLS.keys.toList()

    fun isBuiltIn(id: String): Boolean = id in SYMBOLS

    fun symbolOf(id: String): String? = SYMBOLS[id]
}

/** A currency added by the person: [id] is stable (a UUID), [name] is what he typed and what is shown after the sums. */
@Serializable
data class CustomCurrency(val id: String, val name: String) {
    companion object {
        const val MAX_NAME_LENGTH = 8
    }
}


/**
 * The currency a budget note carries in its layout (`note.extras`): the [id] of the chosen currency and the [label] that was
 * shown for it when it was chosen, so a budget moved to another phone still reads right (D170). Read here as plain JSON so the
 * domain does not depend on the screen that writes it.
 */
object BudgetCurrency {
    private val JSON = Json { ignoreUnknownKeys = true }

    private fun field(extras: String?, name: String): String? =
        extras?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSON.parseToJsonElement(it).jsonObject[name]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    fun idOf(extras: String?): String? = field(extras, "currency")

    /** The text to show after every sum of the budget; `null` = no currency. */
    fun labelOf(extras: String?): String? = field(extras, "currencyLabel")

    /** [extras] with the text of the currency replaced by [label], every other field kept; `null` when they cannot be read (Android D277). */
    fun withLabel(extras: String?, label: String): String? {
        val obj = extras?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        return kotlinx.serialization.json.JsonObject(obj + ("currencyLabel" to kotlinx.serialization.json.JsonPrimitive(label))).toString()
    }
}
