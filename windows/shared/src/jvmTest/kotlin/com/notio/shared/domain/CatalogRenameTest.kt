package com.notio.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Renaming a unit or a currency of one's own (Android D277): the rules of a new one; a budget's currency text follows. */
class CatalogRenameTest {
    private val units = listOf(CustomUnit("u1", "jar"), CustomUnit("u2", "box"))
    private val currencies = listOf(CustomCurrency("c1", "coins"))

    @Test
    fun aUnitIsRenamedUnderTheRulesOfANewOne() {
        assertEquals("pot", (Catalogs.renameUnit(units, "u1", "  pot ", listOf("kg")) as DomainResult.Success).value.first().name)
        assertEquals(DomainResult.Failure(DomainError.DUPLICATE_UNIT_NAME), Catalogs.renameUnit(units, "u1", "BOX", emptyList()))
        assertEquals(DomainResult.Failure(DomainError.DUPLICATE_UNIT_NAME), Catalogs.renameUnit(units, "u1", "Kg", listOf("kg")))
        assertEquals(DomainResult.Failure(DomainError.INVALID_UNIT_NAME), Catalogs.renameUnit(units, "u1", " ", emptyList()))
        assertEquals(DomainResult.Failure(DomainError.UNIT_NOT_FOUND), Catalogs.renameUnit(units, "u9", "pot", emptyList()))
        // Its own name in another case is not "taken".
        assertEquals("Jar", (Catalogs.renameUnit(units, "u1", "Jar", emptyList()) as DomainResult.Success).value.first().name)
    }

    @Test
    fun aCurrencyIsRenamedAndABudgetTakesItsNewText() {
        assertEquals("tokens", (Catalogs.renameCurrency(currencies, "c1", "tokens", listOf("€")) as DomainResult.Success).value.single().name)
        assertEquals(DomainResult.Failure(DomainError.DUPLICATE_CURRENCY_NAME), Catalogs.renameCurrency(currencies, "c1", "€", listOf("€")))
        val extras = BudgetLayout(currency = "c1", currencyLabel = "coins", hideSums = true).toExtras()
        val renamed = BudgetCurrency.withLabel(extras, "tokens")
        assertEquals("tokens", BudgetCurrency.labelOf(renamed))
        assertEquals("c1", BudgetCurrency.idOf(renamed))
        assertEquals(true, BudgetLayout.parse(renamed).hideSums)
        assertEquals(null, BudgetCurrency.withLabel(null, "x"))
    }
}
