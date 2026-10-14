package com.notio.shared

import com.notio.shared.domain.Catalogs
import com.notio.shared.domain.CustomCurrency
import com.notio.shared.domain.CustomUnit
import com.notio.shared.domain.DomainError
import com.notio.shared.domain.DomainResult
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The phone's UnitUseCases / CurrencyUseCases rules on the desktop's lists (AGENT.md D30). */
class CatalogsTest {
    private fun note(type: NoteType, content: String? = null, extras: String? = null, deleted: Long? = null) = Note(
        id = "n", folderId = "f", title = "t", type = type, colorHex = null, isPinned = false, isFavorite = false, isTitleBold = false,
        contentText = content, createdAt = 1, updatedAt = 1, deletedAt = deleted, extras = extras,
    )

    @Test
    fun unitNamesAreUniqueAgainstOwnAndBuiltInNames() {
        val own = listOf(CustomUnit("u1", "ящик"))
        assertEquals(DomainError.DUPLICATE_UNIT_NAME, (Catalogs.addUnit(own, " ЯЩИК ", emptyList()) as DomainResult.Failure).error)
        assertEquals(DomainError.DUPLICATE_UNIT_NAME, (Catalogs.addUnit(own, "кг", listOf("кг", "Килограммы")) as DomainResult.Failure).error)
        assertEquals(DomainError.INVALID_UNIT_NAME, (Catalogs.addUnit(own, "  ", emptyList()) as DomainResult.Failure).error)
        assertEquals("мешок", (Catalogs.addUnit(own, " мешок ", emptyList()) as DomainResult.Success).value.name)
    }

    @Test
    fun aUnitUsedByAListEvenInTheTrashStays() {
        val own = listOf(CustomUnit("u1", "ящик"))
        val doc = com.notio.shared.domain.ListDocument(entries = listOf(com.notio.shared.domain.ListEntry("e1", text = "яблоки", quantityMilli = 2000, unit = "u1")))
        val encoded = doc.encode({ if (it == "u1") "ящик" else it }, mapOf("u1" to "ящик"))
        val list = note(NoteType.LIST, content = encoded.contentText, extras = encoded.extras, deleted = 5)
        assertEquals(DomainError.UNIT_IN_USE, Catalogs.unitDeleteError(own, "u1", listOf(list)))
        assertEquals(DomainError.UNIT_BUILT_IN, Catalogs.unitDeleteError(own, "kg", emptyList()))
        assertNull(Catalogs.unitDeleteError(own, "u1", emptyList()))
    }

    @Test
    fun aCurrencyChosenByABudgetStays() {
        val own = listOf(CustomCurrency("c1", "бон"))
        val budget = note(NoteType.BUDGET, extras = """{"currency":"c1","currencyLabel":"бон"}""")
        assertEquals(DomainError.CURRENCY_IN_USE, Catalogs.currencyDeleteError(own, "c1", listOf(budget)))
        assertEquals(DomainError.CURRENCY_BUILT_IN, Catalogs.currencyDeleteError(own, "RUB", emptyList()))
        assertEquals(DomainError.DUPLICATE_CURRENCY_NAME, (Catalogs.addCurrency(own, "₽", listOf("₽", "RUB")) as DomainResult.Failure).error)
        assertNull(Catalogs.currencyDeleteError(own, "c1", emptyList()))
    }
}
