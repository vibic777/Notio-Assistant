package com.notebudget.app.feature.units

import android.content.Context
import androidx.annotation.StringRes
import com.notebudget.app.R
import com.notebudget.app.domain.model.BuiltInUnit
import com.notebudget.app.domain.model.CustomUnit

/** Names of units for the screen: built-in units come from string resources, custom ones are typed by the user. */
object UnitLabels {

    /** Short text shown next to a quantity: `kg`, `шт.`, `€`. */
    @StringRes
    fun symbolRes(id: String): Int? = when (id) {
        BuiltInUnit.PIECES -> R.string.unit_pcs_symbol
        BuiltInUnit.KILOGRAMS -> R.string.unit_kg_symbol
        BuiltInUnit.LITRES -> R.string.unit_l_symbol
        BuiltInUnit.METRES -> R.string.unit_m_symbol
        BuiltInUnit.ROUBLES -> R.string.unit_rub_symbol
        BuiltInUnit.DOLLARS -> R.string.unit_usd_symbol
        BuiltInUnit.POUNDS -> R.string.unit_gbp_symbol
        BuiltInUnit.EUROS -> R.string.unit_eur_symbol
        else -> null
    }

    /** Full name: `Kilograms`, `Евро`. */
    @StringRes
    fun nameRes(id: String): Int? = when (id) {
        BuiltInUnit.PIECES -> R.string.unit_pcs_name
        BuiltInUnit.KILOGRAMS -> R.string.unit_kg_name
        BuiltInUnit.LITRES -> R.string.unit_l_name
        BuiltInUnit.METRES -> R.string.unit_m_name
        BuiltInUnit.ROUBLES -> R.string.unit_rub_name
        BuiltInUnit.DOLLARS -> R.string.unit_usd_name
        BuiltInUnit.POUNDS -> R.string.unit_gbp_name
        BuiltInUnit.EUROS -> R.string.unit_eur_name
        else -> null
    }

    /**
     * The text for [id]: the built-in symbol, the custom unit's name, or - for a unit this phone does not
     * know (a list from another phone) - the name the list carries, at worst the id itself.
     */
    fun symbol(context: Context, id: String, custom: List<CustomUnit>, unitNames: Map<String, String>): String =
        symbolRes(id)?.let(context::getString)
            ?: custom.firstOrNull { it.id == id }?.name
            ?: unitNames[id]
            ?: id

    /** Symbol and name together, for pickers: `kg - Kilograms`; custom units are just their name. */
    fun fullLabel(context: Context, id: String, custom: List<CustomUnit>, unitNames: Map<String, String>): String {
        val symbol = symbolRes(id)?.let(context::getString)
        val name = nameRes(id)?.let(context::getString)
        return if (symbol != null && name != null) "$symbol - $name" else symbol(context, id, custom, unitNames)
    }

    /** Names and symbols of the built-in units: a custom unit may not reuse them. */
    fun builtInNames(context: Context): List<String> =
        BuiltInUnit.ALL.flatMap { listOfNotNull(symbolRes(it)?.let(context::getString), nameRes(it)?.let(context::getString)) }
}
