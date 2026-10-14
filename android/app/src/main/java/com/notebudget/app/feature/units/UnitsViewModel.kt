package com.notebudget.app.feature.units

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.domain.model.CustomUnit
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.repository.UnitRepository
import com.notebudget.app.domain.usecase.UnitUseCases
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Dialogs live in the ViewModel so that they survive rotation. */
sealed interface UnitsDialog {
    data object Add : UnitsDialog

    /** A new name for a unit the person added (D277). */
    data class Rename(val unit: CustomUnit) : UnitsDialog

    /** Deleting a unit is confirmed first (INV-6, section 1.3). */
    data class ConfirmDelete(val unit: CustomUnit) : UnitsDialog
}

sealed interface UnitsEvent {
    data class Added(val name: String) : UnitsEvent
    data class Renamed(val name: String) : UnitsEvent
    data class Deleted(val name: String) : UnitsEvent
    data class Failed(val error: DomainError) : UnitsEvent
}

/** The list of units the user added (D92); the built-in ones are shown by the screen and cannot change. */
class UnitsViewModel(
    repository: UnitRepository,
    private val useCases: UnitUseCases,
) : ViewModel() {

    val custom: StateFlow<List<CustomUnit>> = repository.observeCustom()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dialog = MutableStateFlow<UnitsDialog?>(null)
    val dialog: StateFlow<UnitsDialog?> = _dialog.asStateFlow()

    private val _events = Channel<UnitsEvent>(Channel.BUFFERED)
    val events: Flow<UnitsEvent> = _events.receiveAsFlow()

    fun askAdd() {
        _dialog.value = UnitsDialog.Add
    }

    fun askRename(unit: CustomUnit) {
        _dialog.value = UnitsDialog.Rename(unit)
    }

    /** A taken or empty name keeps the dialog open with the reason. */
    fun rename(unit: CustomUnit, name: String, reservedNames: List<String>) = viewModelScope.launch {
        when (val result = useCases.rename(unit.id, name, reservedNames)) {
            is DomainResult.Success -> {
                _dialog.value = null
                _events.send(UnitsEvent.Renamed(result.value.name))
            }
            is DomainResult.Failure -> _events.send(UnitsEvent.Failed(result.error))
        }
    }

    fun askDelete(unit: CustomUnit) {
        _dialog.value = UnitsDialog.ConfirmDelete(unit)
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    /** [reservedNames] are the localized names of the built-in units, which a custom unit may not reuse. */
    fun add(name: String, reservedNames: List<String>) = viewModelScope.launch {
        when (val result = useCases.add(name, reservedNames)) {
            is DomainResult.Success -> {
                _dialog.value = null
                _events.send(UnitsEvent.Added(result.value.name))
            }
            // The dialog stays open so that the name can be fixed.
            is DomainResult.Failure -> _events.send(UnitsEvent.Failed(result.error))
        }
    }

    fun confirmDelete() {
        val dialog = _dialog.value as? UnitsDialog.ConfirmDelete ?: return
        _dialog.value = null
        viewModelScope.launch {
            when (val result = useCases.delete(dialog.unit.id)) {
                is DomainResult.Success -> _events.send(UnitsEvent.Deleted(dialog.unit.name))
                is DomainResult.Failure -> _events.send(UnitsEvent.Failed(result.error))
            }
        }
    }
}
