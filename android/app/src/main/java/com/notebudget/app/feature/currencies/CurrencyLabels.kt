package com.notebudget.app.feature.currencies

import android.content.Context
import com.notebudget.app.domain.model.BuiltInCurrency
import com.notebudget.app.domain.model.CustomCurrency
import java.util.Currency
import java.util.Locale

/** The texts of currencies for the screens (D170): the sign of a built-in one, the name of a custom one, the localized full name. */
object CurrencyLabels {

    private fun locale(context: Context): Locale = context.resources.configuration.locales[0]

    /** The localized name of a built-in currency ("Russian ruble"), from the system; the code itself if the system has none. */
    fun nameOf(context: Context, id: String): String =
        runCatching { Currency.getInstance(id).getDisplayName(locale(context)) }.getOrNull()?.takeIf { it.isNotBlank() } ?: id

    /** What is written after a sum: the built-in sign, the custom name, or (a currency this phone does not know) the id. */
    fun symbol(id: String, custom: List<CustomCurrency>): String =
        BuiltInCurrency.symbolOf(id) ?: custom.firstOrNull { it.id == id }?.name ?: id

    /** For the pickers: `₽ - Russian ruble`; a custom currency is just its name. */
    fun fullLabel(context: Context, id: String, custom: List<CustomCurrency>): String {
        val sign = BuiltInCurrency.symbolOf(id) ?: return symbol(id, custom)
        return "$sign - ${nameOf(context, id)}"
    }

    /** Signs, codes and names of the built-in currencies: a custom currency may not reuse them. */
    fun reservedNames(context: Context): List<String> =
        BuiltInCurrency.ALL.flatMap { listOfNotNull(BuiltInCurrency.symbolOf(it), it, nameOf(context, it)) }
}
