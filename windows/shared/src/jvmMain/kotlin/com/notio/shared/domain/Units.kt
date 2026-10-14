package com.notio.shared.domain

import kotlinx.serialization.Serializable

/**
 * Units of measurement for list items (D37, D69, D92). The built-in units have stable ids that are
 * stored in notes and backups (never change them); their names are localized in the UI. They can
 * never be deleted. Custom units are added and deleted by the user (see [CustomUnit]).
 */
object BuiltInUnit {
    const val PIECES = "pcs"
    const val KILOGRAMS = "kg"
    const val LITRES = "l"
    const val METRES = "m"
    const val ROUBLES = "rub"
    const val DOLLARS = "usd"
    const val POUNDS = "gbp"
    const val EUROS = "eur"

    /** In the order they are offered. */
    val ALL = listOf(PIECES, KILOGRAMS, LITRES, METRES, ROUBLES, DOLLARS, POUNDS, EUROS)

    fun isBuiltIn(id: String): Boolean = id in ALL
}

/** A unit added by the user. [id] is stable (a UUID), [name] is what the user typed. */
@Serializable
data class CustomUnit(val id: String, val name: String) {
    companion object {
        const val MAX_NAME_LENGTH = 24
    }
}
