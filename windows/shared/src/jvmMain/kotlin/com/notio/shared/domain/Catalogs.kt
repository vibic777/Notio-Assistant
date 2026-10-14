package com.notio.shared.domain

/**
 * The rules of the lists of units (Android `UnitUseCases`, D69, D92) and currencies (`CurrencyUseCases`, D170), on plain lists so the
 * desktop keeps them in its settings file: built-in ones never go away, names are unique (also against the built-in names of the
 * chosen language), and one that a note still uses (trashed notes included) cannot be deleted.
 */
object Catalogs {

    fun addUnit(existing: List<CustomUnit>, name: String, reservedNames: Collection<String>, ids: IdGenerator = IdGenerator.Random): DomainResult<CustomUnit> {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > CustomUnit.MAX_NAME_LENGTH) return DomainError.INVALID_UNIT_NAME.asFailure()
        if (taken(clean, existing.map { it.name }, reservedNames)) return DomainError.DUPLICATE_UNIT_NAME.asFailure()
        return CustomUnit(ids.newId(), clean).asSuccess()
    }

    /** `null` when [unitId] may go; else why not. [allNotes] includes the trashed ones. */
    fun unitDeleteError(existing: List<CustomUnit>, unitId: String, allNotes: List<Note>): DomainError? = when {
        BuiltInUnit.isBuiltIn(unitId) -> DomainError.UNIT_BUILT_IN
        existing.none { it.id == unitId } -> DomainError.UNIT_NOT_FOUND
        allNotes.any { it.type == NoteType.LIST && unitId in ListDocument.fromNote(it.contentText, it.extras).usedUnitIds() } -> DomainError.UNIT_IN_USE
        else -> null
    }

    fun addCurrency(existing: List<CustomCurrency>, name: String, reservedNames: Collection<String>, ids: IdGenerator = IdGenerator.Random): DomainResult<CustomCurrency> {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > CustomCurrency.MAX_NAME_LENGTH) return DomainError.INVALID_CURRENCY_NAME.asFailure()
        if (taken(clean, existing.map { it.name }, reservedNames)) return DomainError.DUPLICATE_CURRENCY_NAME.asFailure()
        return CustomCurrency(ids.newId(), clean).asSuccess()
    }

    fun currencyDeleteError(existing: List<CustomCurrency>, currencyId: String, allNotes: List<Note>): DomainError? = when {
        BuiltInCurrency.isBuiltIn(currencyId) -> DomainError.CURRENCY_BUILT_IN
        existing.none { it.id == currencyId } -> DomainError.CURRENCY_NOT_FOUND
        allNotes.any { it.type == NoteType.BUDGET && BudgetCurrency.idOf(it.extras) == currencyId } -> DomainError.CURRENCY_IN_USE
        else -> null
    }

    /**
     * Renames a unit of one's own (Android D277): the rules of a new one; lists refer to a unit by its id, so they show the new name
     * at once. The whole new list, or why not.
     */
    fun renameUnit(existing: List<CustomUnit>, unitId: String, name: String, reservedNames: Collection<String>): DomainResult<List<CustomUnit>> {
        if (BuiltInUnit.isBuiltIn(unitId)) return DomainError.UNIT_BUILT_IN.asFailure()
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > CustomUnit.MAX_NAME_LENGTH) return DomainError.INVALID_UNIT_NAME.asFailure()
        if (existing.none { it.id == unitId }) return DomainError.UNIT_NOT_FOUND.asFailure()
        if (taken(clean, existing.filterNot { it.id == unitId }.map { it.name }, reservedNames)) return DomainError.DUPLICATE_UNIT_NAME.asFailure()
        return existing.map { if (it.id == unitId) it.copy(name = clean) else it }.asSuccess()
    }

    /** Renames a currency of one's own (Android D277), the rules of a new one; the budgets that use it get the new text from the caller. */
    fun renameCurrency(existing: List<CustomCurrency>, currencyId: String, name: String, reservedNames: Collection<String>): DomainResult<List<CustomCurrency>> {
        if (BuiltInCurrency.isBuiltIn(currencyId)) return DomainError.CURRENCY_BUILT_IN.asFailure()
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > CustomCurrency.MAX_NAME_LENGTH) return DomainError.INVALID_CURRENCY_NAME.asFailure()
        if (existing.none { it.id == currencyId }) return DomainError.CURRENCY_NOT_FOUND.asFailure()
        if (taken(clean, existing.filterNot { it.id == currencyId }.map { it.name }, reservedNames)) return DomainError.DUPLICATE_CURRENCY_NAME.asFailure()
        return existing.map { if (it.id == currencyId) it.copy(name = clean) else it }.asSuccess()
    }

    private fun taken(name: String, names: List<String>, reserved: Collection<String>) =
        names.any { it.equals(name, ignoreCase = true) } || reserved.any { it.equals(name, ignoreCase = true) }
}
