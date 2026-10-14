package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.domain.model.BudgetCurrency
import com.notebudget.app.domain.model.BuiltInCurrency
import com.notebudget.app.domain.model.CurrencyRepository
import com.notebudget.app.domain.model.CustomCurrency
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.asFailure
import com.notebudget.app.domain.model.asSuccess
import com.notebudget.app.domain.repository.NoteRepository

/**
 * Rules of the list of currencies (D170), the same as for units: built-in currencies never go away, names are unique, and a
 * custom currency that a budget still uses (trashed budgets included) cannot be deleted.
 */
class CurrencyUseCases(
    private val currencies: CurrencyRepository,
    private val notes: NoteRepository,
    private val ids: IdGenerator = IdGenerator.Random,
) {

    /** [reservedNames] are the signs, codes and localized names of the built-in currencies, which a custom one may not reuse. */
    suspend fun add(name: String, reservedNames: Collection<String> = emptyList()): DomainResult<CustomCurrency> {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > CustomCurrency.MAX_NAME_LENGTH) return DomainError.INVALID_CURRENCY_NAME.asFailure()
        val existing = currencies.getCustomCurrencies()
        if (existing.any { it.name.equals(clean, ignoreCase = true) } || reservedNames.any { it.equals(clean, ignoreCase = true) }) {
            return DomainError.DUPLICATE_CURRENCY_NAME.asFailure()
        }
        val currency = CustomCurrency(ids.newId(), clean)
        currencies.setCustomCurrencies(existing + currency)
        return currency.asSuccess()
    }

    /**
     * Renames a custom currency (D277): the same rules as a new one. A budget keeps the text of its currency in itself (so it
     * reads right on another phone), so every budget that uses this currency, trashed ones included, gets the new text too.
     */
    suspend fun rename(currencyId: String, name: String, reservedNames: Collection<String> = emptyList()): DomainResult<CustomCurrency> {
        if (BuiltInCurrency.isBuiltIn(currencyId)) return DomainError.CURRENCY_BUILT_IN.asFailure()
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > CustomCurrency.MAX_NAME_LENGTH) return DomainError.INVALID_CURRENCY_NAME.asFailure()
        val existing = currencies.getCustomCurrencies()
        val currency = existing.firstOrNull { it.id == currencyId } ?: return DomainError.CURRENCY_NOT_FOUND.asFailure()
        val others = existing.filterNot { it.id == currencyId }
        if (others.any { it.name.equals(clean, ignoreCase = true) } || reservedNames.any { it.equals(clean, ignoreCase = true) }) {
            return DomainError.DUPLICATE_CURRENCY_NAME.asFailure()
        }
        val renamed = currency.copy(name = clean)
        currencies.setCustomCurrencies(existing.map { if (it.id == currencyId) renamed else it })
        // The time a note was changed stays: renaming an entry of the list is not an edit of those budgets.
        notes.getAllIncludingDeleted()
            .filter { it.type == NoteType.BUDGET && BudgetCurrency.idOf(it.extras) == currencyId }
            .forEach { note -> BudgetCurrency.withLabel(note.extras, clean)?.let { notes.update(note.copy(extras = it)) } }
        return renamed.asSuccess()
    }

    suspend fun delete(currencyId: String): DomainResult<Unit> {
        if (BuiltInCurrency.isBuiltIn(currencyId)) return DomainError.CURRENCY_BUILT_IN.asFailure()
        val existing = currencies.getCustomCurrencies()
        if (existing.none { it.id == currencyId }) return DomainError.CURRENCY_NOT_FOUND.asFailure()
        if (isUsed(currencyId)) return DomainError.CURRENCY_IN_USE.asFailure()
        currencies.setCustomCurrencies(existing.filterNot { it.id == currencyId })
        return Unit.asSuccess()
    }

    /** Is the currency the chosen one of any budget note, trashed notes included? */
    suspend fun isUsed(currencyId: String): Boolean = notes.getAllIncludingDeleted().any { note ->
        note.type == NoteType.BUDGET && BudgetCurrency.idOf(note.extras) == currencyId
    }
}
