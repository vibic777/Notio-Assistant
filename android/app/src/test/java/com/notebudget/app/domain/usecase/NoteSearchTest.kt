package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.FeaturedLayout
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {
    private fun note(id: String, title: String, content: String? = null) = Note(
        id = id, folderId = "f", title = title, type = NoteType.TEXT, colorHex = null,
        isPinned = false, isFavorite = false, isTitleBold = false, contentText = content, createdAt = 0, updatedAt = 0,
    )

    private fun folder(id: String, name: String) = Folder(
        id = id, name = name, colorHex = "#3366FF", styleId = "SOFT", icon = null, sortOrder = 0,
        featuredNoteId = null, featuredMetricsMask = 0, featuredLayout = FeaturedLayout.COMPACT, createdAt = 0, updatedAt = 0,
    )

    private val work = Tag("t1", "Work")
    private val home = Tag("t2", "Дом")

    private fun q(text: String = "", vararg tags: String, scope: SearchScope = SearchScope.NOTES) =
        SearchQuery(scope, text, tags.toSet())

    @Test
    fun `a search with nothing typed and no tag is not active`() {
        assertFalse(q().active)
        assertFalse(q("   ").active)
        assertTrue(q("a").active)
        assertTrue(q("", "t1").active)
    }

    @Test
    fun `text is found in the title or the body, ignoring case`() {
        val n = note("1", "Trip to Rome", "book the HOTEL")
        assertTrue(NoteSearch.matches(n, emptyList(), q("rome")))
        assertTrue(NoteSearch.matches(n, emptyList(), q("hotel")))
        assertFalse(NoteSearch.matches(n, emptyList(), q("paris")))
    }

    @Test
    fun `Cyrillic text is found regardless of case`() {
        val n = note("1", "Список покупок", "Молоко и хлеб")
        assertTrue(NoteSearch.matches(n, emptyList(), q("МОЛОКО")))
        assertTrue(NoteSearch.matches(n, emptyList(), q("покупок")))
        assertFalse(NoteSearch.matches(n, emptyList(), q("сыр")))
    }

    @Test
    fun `every word must be found, in any order and in any place`() {
        val n = note("1", "Trip", "hotel in Rome")
        assertTrue(NoteSearch.matches(n, emptyList(), q("rome trip")))
        assertTrue(NoteSearch.matches(n, emptyList(), q("  hotel   rome ")))
        assertFalse(NoteSearch.matches(n, emptyList(), q("rome paris")))
    }

    @Test
    fun `the name of a tag of the note is searched too`() {
        val n = note("1", "Plain")
        assertTrue(NoteSearch.matches(n, listOf(work), q("work")))
        assertFalse(NoteSearch.matches(n, emptyList(), q("work")))
    }

    @Test
    fun `markdown and list syntax in the body does not get in the way`() {
        val n = note("1", "x", "- [ ] **Итого** 5")
        assertTrue(NoteSearch.matches(n, emptyList(), q("итого")))
    }

    @Test
    fun `a tag filter needs ALL the chosen tags`() {
        val n = note("1", "n")
        assertTrue(NoteSearch.matches(n, listOf(work, home), q("", "t1")))
        assertTrue(NoteSearch.matches(n, listOf(work, home), q("", "t1", "t2")))
        assertFalse(NoteSearch.matches(n, listOf(work), q("", "t1", "t2")))
        assertFalse(NoteSearch.matches(n, emptyList(), q("", "t1")))
    }

    @Test
    fun `text and tags both have to match`() {
        val n = note("1", "Budget report")
        assertTrue(NoteSearch.matches(n, listOf(work), q("report", "t1")))
        assertFalse(NoteSearch.matches(n, listOf(work), q("holiday", "t1")))
        assertFalse(NoteSearch.matches(n, listOf(home), q("report", "t1")))
    }

    @Test
    fun `filtering keeps the notes that match and drops the others`() {
        val notes = listOf(note("1", "Alpha"), note("2", "Beta"), note("3", "Alphabet"))
        val tags = mapOf("2" to listOf(work))
        assertEquals(listOf("1", "3"), NoteSearch.filterNotes(notes, tags, q("alpha")).map { it.id })
        assertEquals(listOf("2"), NoteSearch.filterNotes(notes, tags, q("", "t1")).map { it.id })
        assertEquals(listOf("1", "2", "3"), NoteSearch.filterNotes(notes, tags, q()).map { it.id })
    }

    @Test
    fun `folders are searched by name, ignoring case, every word`() {
        val folders = listOf(folder("1", "Family Finances"), folder("2", "Work"), folder("3", "Семья"))
        assertEquals(listOf("1"), NoteSearch.filterFolders(folders, q("family", scope = SearchScope.FOLDERS)).map { it.id })
        assertEquals(listOf("1"), NoteSearch.filterFolders(folders, q("finan fam", scope = SearchScope.FOLDERS)).map { it.id })
        assertEquals(listOf("3"), NoteSearch.filterFolders(folders, q("СЕМЬЯ", scope = SearchScope.FOLDERS)).map { it.id })
        assertEquals(3, NoteSearch.filterFolders(folders, q(scope = SearchScope.FOLDERS)).size)
        assertTrue(NoteSearch.filterFolders(folders, q("zzz", scope = SearchScope.FOLDERS)).isEmpty())
    }

    @Test
    fun `tags never make a folder search active`() {
        assertFalse(q("", "t1", scope = SearchScope.FOLDERS).active)
    }

    @Test
    fun `a tag search looks at tag names only and does not read titles or text`() {
        val n = note("1", "Work plan", "work work work")
        assertFalse("the word is in the title and text, but no tag has it", NoteSearch.matches(n, listOf(home), q("work", scope = SearchScope.TAGS)))
        assertTrue(NoteSearch.matches(n, listOf(work), q("wor", scope = SearchScope.TAGS)))
        assertTrue("part of a tag name, Cyrillic, any case", NoteSearch.matches(n, listOf(home), q("ДО", scope = SearchScope.TAGS)))
        assertFalse("a note without tags never matches a typed word", NoteSearch.matches(n, emptyList(), q("w", scope = SearchScope.TAGS)))
    }

    @Test
    fun `in a tag search every word must be part of some tag and chosen tags must all be there`() {
        val n = note("1", "x")
        val tags = listOf(work, home)
        assertTrue(NoteSearch.matches(n, tags, q("wo дом", scope = SearchScope.TAGS)))
        assertFalse(NoteSearch.matches(n, tags, q("wo zzz", scope = SearchScope.TAGS)))
        assertTrue(NoteSearch.matches(n, tags, q("", "t1", "t2", scope = SearchScope.TAGS)))
        assertFalse(NoteSearch.matches(n, listOf(work), q("wo", "t2", scope = SearchScope.TAGS)))
        assertTrue(q("", "t1", scope = SearchScope.TAGS).active)
        assertFalse("a folder search ignores tags", q("", "t1", scope = SearchScope.FOLDERS).active)
    }

    @Test
    fun `with ignore case off the letters must match exactly (D246)`() {
        val n = note("1", "Вкусная каша", "tea")
        assertTrue(NoteSearch.matches(n, emptyList(), q("вкусная")))
        assertFalse(NoteSearch.matches(n, emptyList(), q("вкусная").copy(ignoreCase = false)))
        assertTrue(NoteSearch.matches(n, emptyList(), q("Вкусная").copy(ignoreCase = false)))
    }

    @Test
    fun `wildcards in a word, and a regular expression instead of words (D246)`() {
        val n = note("1", "Вкусная каша", "250 g of tea")
        assertTrue(NoteSearch.matches(n, emptyList(), q("*Вкусная*")))
        assertTrue(NoteSearch.matches(n, emptyList(), q("Вкус*ая к?ша")))
        assertFalse(NoteSearch.matches(n, emptyList(), q("Вкус*ая суп")))
        assertTrue(NoteSearch.matches(n, emptyList(), q("\\d+ g").copy(regex = true)))
        assertFalse(NoteSearch.matches(n, emptyList(), q("^tea").copy(regex = true)))
        // A wrong expression finds nothing and says why; it never fails.
        val wrong = q("*Вкусная*").copy(regex = true)
        assertTrue(wrong.active)
        assertTrue(wrong.error != null)
        assertFalse(NoteSearch.matches(n, emptyList(), wrong))
        assertTrue(NoteSearch.filterFolders(listOf(folder("f", "Вкусная")), wrong).isEmpty())
    }
}
