package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.BuiltInUnit
import com.notebudget.app.domain.model.CustomUnit
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.ListDocument
import com.notebudget.app.domain.model.ListEntry
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitUseCasesTest {
    private val env = TestEnv()

    private fun failure(result: DomainResult<*>) = (result as DomainResult.Failure).error

    private suspend fun folderId() = env.folderRepo.getAll().firstOrNull()?.id
        ?: (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value.id

    /** A LIST note whose only row uses [unit], as an item or as a total. */
    private suspend fun listNote(unit: String?, asTotal: Boolean = false, trashed: Boolean = false): Note {
        val note = (env.notes.create(folderId(), "List", NoteType.LIST) as DomainResult.Success).value
        val row = if (asTotal) ListEntry.newTotal(unit) else ListEntry.newItem().copy(text = "x", quantityMilli = 1_000, unit = unit)
        val encoded = ListDocument(entries = listOf(row)).encode({ it }, emptyMap())
        env.notes.save(note.copy(contentText = encoded.contentText, extras = encoded.extras), withExtras = true)
        if (trashed) env.notes.delete(note.id)
        return note
    }

    // ---- add ----

    @Test
    fun `adds a unit with a fresh id and trims the name`() = runBlocking {
        val added = (env.units.add("  packs ") as DomainResult.Success).value
        assertEquals("packs", added.name)
        assertTrue(added.id.isNotBlank())
        assertEquals(listOf(added), env.unitRepo.getCustom())
        assertFalse(BuiltInUnit.isBuiltIn(added.id))
    }

    @Test
    fun `a blank name or one over 24 characters is refused`() = runBlocking {
        assertEquals(DomainError.INVALID_UNIT_NAME, failure(env.units.add("   ")))
        assertEquals(DomainError.INVALID_UNIT_NAME, failure(env.units.add("x".repeat(25))))
        assertTrue(env.units.add("x".repeat(24)) is DomainResult.Success)
        assertEquals(1, env.unitRepo.getCustom().size)
    }

    @Test
    fun `names must be unique ignoring case, also against the built-in names`() = runBlocking {
        env.units.add("Packs")
        assertEquals(DomainError.DUPLICATE_UNIT_NAME, failure(env.units.add("packs")))
        assertEquals(DomainError.DUPLICATE_UNIT_NAME, failure(env.units.add("KG", reservedNames = listOf("kg", "Kilograms"))))
        assertEquals(DomainError.DUPLICATE_UNIT_NAME, failure(env.units.add("kilograms", reservedNames = listOf("kg", "Kilograms"))))
        assertEquals(1, env.unitRepo.getCustom().size)
    }

    // ---- delete ----

    @Test
    fun `built-in units can never be deleted`() = runBlocking {
        for (id in BuiltInUnit.ALL) assertEquals(id, DomainError.UNIT_BUILT_IN, failure(env.units.delete(id)))
    }

    @Test
    fun `there are exactly the built-in units the user asked for`() {
        assertEquals(listOf("pcs", "kg", "l", "m", "rub", "usd", "gbp", "eur"), BuiltInUnit.ALL)
    }

    @Test
    fun `an unknown unit cannot be deleted`() = runBlocking {
        assertEquals(DomainError.UNIT_NOT_FOUND, failure(env.units.delete("nope")))
    }

    @Test
    fun `an unused custom unit is deleted`() = runBlocking {
        val unit = (env.units.add("packs") as DomainResult.Success).value
        listNote(BuiltInUnit.KILOGRAMS) // some other list, using another unit
        assertTrue(env.units.delete(unit.id) is DomainResult.Success)
        assertTrue(env.unitRepo.getCustom().isEmpty())
    }

    @Test
    fun `a unit used by an item of a list cannot be deleted`() = runBlocking {
        val unit = (env.units.add("packs") as DomainResult.Success).value
        listNote(unit.id)
        assertEquals(DomainError.UNIT_IN_USE, failure(env.units.delete(unit.id)))
        assertEquals(listOf(unit), env.unitRepo.getCustom())
    }

    @Test
    fun `a unit used only by a total row is in use too`() = runBlocking {
        val unit = (env.units.add("packs") as DomainResult.Success).value
        listNote(unit.id, asTotal = true)
        assertTrue(env.units.isUsed(unit.id))
        assertEquals(DomainError.UNIT_IN_USE, failure(env.units.delete(unit.id)))
    }

    @Test
    fun `a list in the trash still holds its unit`() = runBlocking {
        val unit = (env.units.add("packs") as DomainResult.Success).value
        val note = listNote(unit.id, trashed = true)
        assertEquals(DomainError.UNIT_IN_USE, failure(env.units.delete(unit.id)))

        // Once the list is gone for good, the unit is free.
        env.trash.purgeNote(note.id)
        assertTrue(env.units.delete(unit.id) is DomainResult.Success)
    }

    @Test
    fun `only lists count, another kind of note with similar settings does not`() = runBlocking {
        val unit = (env.units.add("packs") as DomainResult.Success).value
        val encoded = ListDocument(entries = listOf(ListEntry.newItem().copy(unit = unit.id))).encode({ it }, emptyMap())
        val text = (env.notes.create(folderId(), "Text", NoteType.TEXT) as DomainResult.Success).value
        env.notes.updateExtras(text.id, encoded.extras)
        assertFalse(env.units.isUsed(unit.id))
        assertTrue(env.units.delete(unit.id) is DomainResult.Success)
    }

    // ---- adopt: units that came with an imported list ----

    @Test
    fun `units brought by a list are added once and never override what exists`() = runBlocking {
        env.unitRepo.setCustom(listOf(CustomUnit("known", "Known"), CustomUnit("other", "Bags")))

        env.units.adopt(
            mapOf(
                "new" to "Boxes",
                "known" to "Renamed",      // same id: left alone
                "clash" to "bags",         // name taken by another id: skipped
                "kg" to "Kilos",           // a built-in id: skipped
                "blank" to "  ",           // no name: skipped
                "reserved" to "Kilograms", // a built-in name: skipped
            ),
            reservedNames = listOf("kg", "Kilograms"),
        )

        assertEquals(
            listOf(CustomUnit("known", "Known"), CustomUnit("other", "Bags"), CustomUnit("new", "Boxes")),
            env.unitRepo.getCustom(),
        )
        env.units.adopt(mapOf("new" to "Boxes")) // again: nothing changes
        assertEquals(3, env.unitRepo.getCustom().size)
    }
}
