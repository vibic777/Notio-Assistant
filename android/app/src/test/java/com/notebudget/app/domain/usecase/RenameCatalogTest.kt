package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.BudgetCurrency
import com.notebudget.app.domain.model.BuiltInUnit
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.budget.BudgetLayout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renaming a unit or a currency of one's own in Settings (D277), as a tag is renamed. */
class RenameCatalogTest {
    private val env = TestEnv()
    private val currencyRepo = FakeCurrencyRepository()
    private val currencies = CurrencyUseCases(currencyRepo, env.noteRepo, env.ids)

    private fun failure(result: DomainResult<*>) = (result as DomainResult.Failure).error

    @Test
    fun `a unit is renamed under the rules of a new one`() = runTest {
        val packs = (env.units.add("packs") as DomainResult.Success).value
        env.units.add("boxes")
        assertEquals("pack", (env.units.rename(packs.id, "  pack ") as DomainResult.Success).value.name)
        assertEquals(listOf("pack", "boxes"), env.unitRepo.getCustom().map { it.name })
        // The same name in another case is fine for the unit itself; another unit's or a built-in one's is not.
        assertEquals("Pack", (env.units.rename(packs.id, "Pack") as DomainResult.Success).value.name)
        assertEquals(DomainError.DUPLICATE_UNIT_NAME, failure(env.units.rename(packs.id, "BOXES")))
        assertEquals(DomainError.DUPLICATE_UNIT_NAME, failure(env.units.rename(packs.id, "kg", reservedNames = listOf("kg"))))
        assertEquals(DomainError.INVALID_UNIT_NAME, failure(env.units.rename(packs.id, "  ")))
        assertEquals(DomainError.UNIT_BUILT_IN, failure(env.units.rename(BuiltInUnit.ALL.first(), "x")))
        assertEquals(DomainError.UNIT_NOT_FOUND, failure(env.units.rename("gone", "x")))
    }

    @Test
    fun `a renamed currency is renamed in every budget that uses it, the others stay`() = runTest {
        val coin = (currencies.add("USDT") as DomainResult.Success).value
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val trip = (env.notes.create(folder.id, "Trip", NoteType.BUDGET) as DomainResult.Success).value
        val home = (env.notes.create(folder.id, "Home", NoteType.BUDGET) as DomainResult.Success).value
        env.notes.updateExtras(trip.id, BudgetLayout(totalAtBottom = true, currency = coin.id, currencyLabel = "USDT").toExtras())
        env.notes.updateExtras(home.id, BudgetLayout(currency = "RUB", currencyLabel = "₽").toExtras())

        assertEquals("USDC", (currencies.rename(coin.id, "USDC") as DomainResult.Success).value.name)

        val tripExtras = env.noteRepo.getById(trip.id)?.extras
        assertEquals("USDC", BudgetCurrency.labelOf(tripExtras))
        assertEquals(coin.id, BudgetCurrency.idOf(tripExtras))
        // Every other setting of the budget is kept.
        assertTrue(BudgetLayout.parse(tripExtras).totalAtBottom)
        assertEquals("₽", BudgetCurrency.labelOf(env.noteRepo.getById(home.id)?.extras))
    }

    @Test
    fun `a currency cannot take a taken name`() = runTest {
        val a = (currencies.add("AAA") as DomainResult.Success).value
        currencies.add("BBB")
        assertEquals(DomainError.DUPLICATE_CURRENCY_NAME, failure(currencies.rename(a.id, "bbb")))
        assertEquals(DomainError.INVALID_CURRENCY_NAME, failure(currencies.rename(a.id, "123456789")))
        assertEquals(listOf("AAA", "BBB"), currencyRepo.getCustomCurrencies().map { it.name })
    }
}
