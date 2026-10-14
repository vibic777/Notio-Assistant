package com.notebudget.app.feature.currencies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.domain.model.CurrencyRepository
import com.notebudget.app.domain.model.CustomCurrency
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.usecase.CurrencyUseCases
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface CurrenciesDialog {
    data object Add : CurrenciesDialog

    /** A new name for a currency the person added (D277). */
    data class Rename(val currency: CustomCurrency) : CurrenciesDialog

    /** Deleting a currency is confirmed first (INV-6). */
    data class ConfirmDelete(val currency: CustomCurrency) : CurrenciesDialog
}

sealed interface CurrenciesEvent {
    data class Added(val name: String) : CurrenciesEvent
    data class Renamed(val name: String) : CurrenciesEvent
    data class Deleted(val name: String) : CurrenciesEvent
    data class Failed(val error: DomainError) : CurrenciesEvent
}

/** The list of currencies the person added (D170); the built-in ones are shown by the screen and cannot change. */
class CurrenciesViewModel(
    repository: CurrencyRepository,
    private val useCases: CurrencyUseCases,
) : ViewModel() {

    val custom: StateFlow<List<CustomCurrency>> = repository.observeCustomCurrencies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dialog = MutableStateFlow<CurrenciesDialog?>(null)
    val dialog: StateFlow<CurrenciesDialog?> = _dialog.asStateFlow()

    private val _events = Channel<CurrenciesEvent>(Channel.BUFFERED)
    val events: Flow<CurrenciesEvent> = _events.receiveAsFlow()

    fun askAdd() { _dialog.value = CurrenciesDialog.Add }

    fun askDelete(currency: CustomCurrency) { _dialog.value = CurrenciesDialog.ConfirmDelete(currency) }

    fun askRename(currency: CustomCurrency) { _dialog.value = CurrenciesDialog.Rename(currency) }

    /** A taken or empty name keeps the dialog open with the reason; the budgets using it take the new name. */
    fun rename(currency: CustomCurrency, name: String, reservedNames: List<String>) = viewModelScope.launch {
        when (val result = useCases.rename(currency.id, name, reservedNames)) {
            is DomainResult.Success -> {
                _dialog.value = null
                _events.send(CurrenciesEvent.Renamed(result.value.name))
            }
            is DomainResult.Failure -> _events.send(CurrenciesEvent.Failed(result.error))
        }
    }

    fun dismissDialog() { _dialog.value = null }

    /** [reservedNames] are the signs, codes and names of the built-in currencies. */
    fun add(name: String, reservedNames: List<String>) = viewModelScope.launch {
        when (val result = useCases.add(name, reservedNames)) {
            is DomainResult.Success -> {
                _dialog.value = null
                _events.send(CurrenciesEvent.Added(result.value.name))
            }
            // The dialog stays open so that the name can be fixed.
            is DomainResult.Failure -> _events.send(CurrenciesEvent.Failed(result.error))
        }
    }

    fun confirmDelete() {
        val dialog = _dialog.value as? CurrenciesDialog.ConfirmDelete ?: return
        _dialog.value = null
        viewModelScope.launch {
            when (val result = useCases.delete(dialog.currency.id)) {
                is DomainResult.Success -> _events.send(CurrenciesEvent.Deleted(dialog.currency.name))
                is DomainResult.Failure -> _events.send(CurrenciesEvent.Failed(result.error))
            }
        }
    }
}
