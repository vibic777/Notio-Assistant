package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.domain.model.BuiltInUnit
import com.notebudget.app.domain.model.CustomUnit
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.ListDocument
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.asFailure
import com.notebudget.app.domain.model.asSuccess
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.UnitRepository

/**
 * Rules of the units list (D69, D92): built-in units never go away, custom names are unique, a custom
 * unit that a list still uses (items or a total, trashed lists included) cannot be deleted. The
 * confirmation dialog before a delete (INV-6) is a UI concern.
 */
class UnitUseCases(
    private val units: UnitRepository,
    private val notes: NoteRepository,
    private val ids: IdGenerator = IdGenerator.Random,
) {

    /**
     * Adds a custom unit. [reservedNames] are the localized names of the built-in units, which the
     * domain does not know: a custom unit may not reuse one (case-insensitive).
     */
    suspend fun add(name: String, reservedNames: Collection<String> = emptyList()): DomainResult<CustomUnit> {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > CustomUnit.MAX_NAME_LENGTH) return DomainError.INVALID_UNIT_NAME.asFailure()
        val existing = units.getCustom()
        if (isTaken(clean, existing, reservedNames)) return DomainError.DUPLICATE_UNIT_NAME.asFailure()
        val unit = CustomUnit(ids.newId(), clean)
        units.setCustom(existing + unit)
        return unit.asSuccess()
    }

    /**
     * Renames a custom unit (D277): the same rules as a new one (not empty, not longer than the limit, not the name of another
     * unit or of a built-in one, case ignored). Lists refer to a unit by its id, so every list shows the new name at once; the
     * copy of the name a list keeps for another phone is written anew when that list is saved.
     */
    suspend fun rename(unitId: String, name: String, reservedNames: Collection<String> = emptyList()): DomainResult<CustomUnit> {
        if (BuiltInUnit.isBuiltIn(unitId)) return DomainError.UNIT_BUILT_IN.asFailure()
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > CustomUnit.MAX_NAME_LENGTH) return DomainError.INVALID_UNIT_NAME.asFailure()
        val existing = units.getCustom()
        val unit = existing.firstOrNull { it.id == unitId } ?: return DomainError.UNIT_NOT_FOUND.asFailure()
        if (isTaken(clean, existing.filterNot { it.id == unitId }, reservedNames)) return DomainError.DUPLICATE_UNIT_NAME.asFailure()
        val renamed = unit.copy(name = clean)
        units.setCustom(existing.map { if (it.id == unitId) renamed else it })
        return renamed.asSuccess()
    }

    suspend fun delete(unitId: String): DomainResult<Unit> {
        if (BuiltInUnit.isBuiltIn(unitId)) return DomainError.UNIT_BUILT_IN.asFailure()
        val existing = units.getCustom()
        if (existing.none { it.id == unitId }) return DomainError.UNIT_NOT_FOUND.asFailure()
        if (isUsed(unitId)) return DomainError.UNIT_IN_USE.asFailure()
        units.setCustom(existing.filterNot { it.id == unitId })
        return Unit.asSuccess()
    }

    /** Is the unit used by any item or total of any list, trashed lists included? */
    suspend fun isUsed(unitId: String): Boolean = notes.getAllIncludingDeleted().any { note ->
        note.type == NoteType.LIST && unitId in ListDocument.fromNote(note.contentText, note.extras).usedUnitIds()
    }

    /**
     * Custom units that an opened list brings along (a list imported from another phone): known by id
     * and name only in the list. They are added to the catalog so the list can be edited with them; a
     * unit is skipped when its id is known already or its name is taken. Idempotent.
     */
    suspend fun adopt(names: Map<String, String>, reservedNames: Collection<String> = emptyList()) {
        if (names.isEmpty()) return
        var current = units.getCustom()
        var changed = false
        for ((id, name) in names) {
            val clean = name.trim()
            if (BuiltInUnit.isBuiltIn(id) || current.any { it.id == id }) continue
            if (clean.isEmpty() || clean.length > CustomUnit.MAX_NAME_LENGTH) continue
            if (isTaken(clean, current, reservedNames)) continue
            current = current + CustomUnit(id, clean)
            changed = true
        }
        if (changed) units.setCustom(current)
    }

    private fun isTaken(name: String, existing: List<CustomUnit>, reservedNames: Collection<String>) =
        existing.any { it.name.equals(name, ignoreCase = true) } || reservedNames.any { it.equals(name, ignoreCase = true) }
}
