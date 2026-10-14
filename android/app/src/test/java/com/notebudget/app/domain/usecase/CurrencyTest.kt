package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.Money
import com.notebudget.app.data.transfer.BudgetTransferHandler
import com.notebudget.app.domain.model.BudgetCurrency
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.BuiltInCurrency
import com.notebudget.app.domain.model.CurrencyRepository
import com.notebudget.app.domain.model.CustomCurrency
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.budget.BudgetLayout
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCurrencyRepository(initial: List<CustomCurrency> = emptyList()) : CurrencyRepository {
    val state = MutableStateFlow(initial)
    override fun observeCustomCurrencies(): Flow<List<CustomCurrency>> = state
    override suspend fun getCustomCurrencies() = state.value
    override suspend fun setCustomCurrencies(currencies: List<CustomCurrency>) { state.value = currencies }
}

/** Currencies of the budget notes (D170): the list, the rules, and how the chosen one is stored and written after the sums. */
class CurrencyTest {
    private val env = TestEnv()
    private val repo = FakeCurrencyRepository()
    private val currencies = CurrencyUseCases(repo, env.noteRepo, env.ids)

    @Test
    fun `a custom currency is added, trimmed and kept once`() = runTest {
        val added = (currencies.add("  USDT ") as DomainResult.Success).value
        assertEquals("USDT", added.name)
        assertEquals(listOf(added), repo.getCustomCurrencies())

        assertEquals(DomainError.DUPLICATE_CURRENCY_NAME, (currencies.add("usdt") as DomainResult.Failure).error)
        assertEquals(DomainError.INVALID_CURRENCY_NAME, (currencies.add("   ") as DomainResult.Failure).error)
        assertEquals(DomainError.INVALID_CURRENCY_NAME, (currencies.add("123456789") as DomainResult.Failure).error) // 9 > 8
        assertEquals(1, repo.getCustomCurrencies().size)
    }

    @Test
    fun `a custom currency may not reuse a built-in sign, code or name`() = runTest {
        val reserved = listOf("₽", "RUB", "Euro")
        for (name in listOf("₽", "rub", "EURO")) {
            assertEquals(DomainError.DUPLICATE_CURRENCY_NAME, (currencies.add(name, reserved) as DomainResult.Failure).error)
        }
        assertTrue(repo.getCustomCurrencies().isEmpty())
    }

    @Test
    fun `the built-in currencies have distinct signs, are never deleted and are found by id`() = runTest {
        assertEquals(BuiltInCurrency.ALL.size, BuiltInCurrency.SYMBOLS.values.toSet().size)
        assertEquals("₽", BuiltInCurrency.symbolOf("RUB"))
        assertNull(BuiltInCurrency.symbolOf("XXX"))
        assertEquals(DomainError.CURRENCY_BUILT_IN, (currencies.delete("EUR") as DomainResult.Failure).error)
        assertEquals(DomainError.CURRENCY_NOT_FOUND, (currencies.delete("nope") as DomainResult.Failure).error)
    }

    @Test
    fun `a currency a budget uses cannot be deleted until the budget lets it go`() = runTest {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "Trip", NoteType.BUDGET) as DomainResult.Success).value
        val coin = (currencies.add("USDT") as DomainResult.Success).value
        env.notes.updateExtras(note.id, BudgetLayout(currency = coin.id, currencyLabel = "USDT").toExtras())

        assertTrue(currencies.isUsed(coin.id))
        assertEquals(DomainError.CURRENCY_IN_USE, (currencies.delete(coin.id) as DomainResult.Failure).error)

        env.notes.updateExtras(note.id, BudgetLayout().toExtras()) // no currency any more
        assertTrue(currencies.delete(coin.id) is DomainResult.Success)
        assertTrue(repo.getCustomCurrencies().isEmpty())
    }

    @Test
    fun `the currency of a budget lives in its layout and old layouts have none`() {
        val layout = BudgetLayout(totalAtBottom = true, currency = "RUB", currencyLabel = "₽")
        val extras = layout.toExtras()
        assertEquals(layout, BudgetLayout.parse(extras))
        assertEquals("RUB", BudgetCurrency.idOf(extras))
        assertEquals("₽", BudgetCurrency.labelOf(extras))

        // A layout written before currencies existed, and junk.
        val old = """{"totalAtBottom":true,"newestFirst":false}"""
        assertNull(BudgetLayout.parse(old).currency)
        assertNull(BudgetCurrency.idOf(old))
        assertNull(BudgetCurrency.labelOf(old))
        assertNull(BudgetCurrency.labelOf(null))
        assertNull(BudgetCurrency.labelOf("not json"))
    }

    @Test
    fun `a sum is written with the currency after it, or alone without one`() {
        val us = Locale.US
        assertEquals("1,234.50", Money.formatLabelled(123_450, null, us))
        assertEquals("1,234.50", Money.formatLabelled(123_450, "", us))
        assertEquals("1,234.50 ₽", Money.formatLabelled(123_450, "₽", us))
        assertEquals("-3.00 USDT", Money.formatLabelled(-300, "USDT", us))
    }

    @Test
    fun `the text snapshot of a budget writes the currency after every sum`() {
        fun node(id: String, parent: String?, kind: BudgetNodeKind, sum: Long, goal: Long) = BudgetNode(
            id = id, noteId = "n", parentId = parent, title = id, kind = kind, manualSumMinor = sum, manualGoalMinor = goal,
            comment = null, cachedSumMinor = sum, cachedGoalMinor = goal, cachedRemainderMinor = goal - sum,
            isExpanded = true, sortOrder = 0, createdAt = 0, updatedAt = 0,
        )
        val nodes = listOf(node("root", null, BudgetNodeKind.GROUP, 1_000, 2_500), node("bread", "root", BudgetNodeKind.LEAF, 1_000, 2_500))

        val handler = BudgetTransferHandler(env.budgetRepo)
        val plain = handler.snapshot(nodes)
        val eur = handler.snapshot(nodes, "€")

        assertTrue(plain.contains("sum 10.00 / goal 25.00 / remainder 15.00"))
        assertTrue(eur.contains("sum 10.00 € / goal 25.00 € / remainder 15.00 €"))
    }
}
