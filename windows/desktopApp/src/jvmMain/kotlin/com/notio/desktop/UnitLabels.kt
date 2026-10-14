package com.notio.desktop

import androidx.annotation.StringRes
import com.notio.shared.domain.BuiltInUnit
import com.notio.shared.domain.CustomUnit

/** Names of units for the screen: built-in units come from string resources, custom ones are typed by the user. */
object UnitLabels {

    /** Short text shown next to a quantity: `kg`, `шт.`, `€`. */
    fun symbolRes(id: String): String? = when (id) {
        BuiltInUnit.PIECES -> "unit_pcs_symbol"
        BuiltInUnit.KILOGRAMS -> "unit_kg_symbol"
        BuiltInUnit.LITRES -> "unit_l_symbol"
        BuiltInUnit.METRES -> "unit_m_symbol"
        BuiltInUnit.ROUBLES -> "unit_rub_symbol"
        BuiltInUnit.DOLLARS -> "unit_usd_symbol"
        BuiltInUnit.POUNDS -> "unit_gbp_symbol"
        BuiltInUnit.EUROS -> "unit_eur_symbol"
        else -> null
    }

    /** Full name: `Kilograms`, `Евро`. */
    fun nameRes(id: String): String? = when (id) {
        BuiltInUnit.PIECES -> "unit_pcs_name"
        BuiltInUnit.KILOGRAMS -> "unit_kg_name"
        BuiltInUnit.LITRES -> "unit_l_name"
        BuiltInUnit.METRES -> "unit_m_name"
        BuiltInUnit.ROUBLES -> "unit_rub_name"
        BuiltInUnit.DOLLARS -> "unit_usd_name"
        BuiltInUnit.POUNDS -> "unit_gbp_name"
        BuiltInUnit.EUROS -> "unit_eur_name"
        else -> null
    }

    /**
     * The text for [id]: the built-in symbol, the custom unit's name, or - for a unit this phone does not
     * know (a list from another phone) - the name the list carries, at worst the id itself.
     */
    fun symbol(context: AndroidRes, id: String, custom: List<CustomUnit>, unitNames: Map<String, String>): String =
        symbolRes(id)?.let(context::str)
            ?: custom.firstOrNull { it.id == id }?.name
            ?: unitNames[id]
            ?: id

    /** Symbol and name together, for pickers: `kg - Kilograms`; custom units are just their name. */
    fun fullLabel(context: AndroidRes, id: String, custom: List<CustomUnit>, unitNames: Map<String, String>): String {
        val symbol = symbolRes(id)?.let(context::str)
        val name = nameRes(id)?.let(context::str)
        return if (symbol != null && name != null) "$symbol - $name" else symbol(context, id, custom, unitNames)
    }

    /** Names and symbols of the built-in units: a custom unit may not reuse them. */
    fun builtInNames(context: AndroidRes): List<String> =
        BuiltInUnit.ALL.flatMap { listOfNotNull(symbolRes(it)?.let(context::str), nameRes(it)?.let(context::str)) }
}
