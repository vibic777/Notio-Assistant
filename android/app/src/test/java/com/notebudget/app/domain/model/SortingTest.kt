package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ordering of folders and notes (D54, D57, D121). */
class SortingTest {
    private fun note(id: String, title: String, updated: Long = 0, created: Long = 0, color: String? = null, pinned: Boolean = false, fav: Boolean = false) = Note(
        id = id, folderId = "f", title = title, type = NoteType.TEXT, colorHex = color, isPinned = pinned, isFavorite = fav,
        isTitleBold = false, contentText = null, createdAt = created, updatedAt = updated,
    )

    private fun folder(id: String, name: String, order: Int = 0, created: Long = 0, color: String = "#3366FF") = Folder(
        id = id, name = name, colorHex = color, styleId = "SOFT", icon = null, sortOrder = order,
        featuredNoteId = null, featuredMetricsMask = 0, featuredLayout = FeaturedLayout.COMPACT, createdAt = created, updatedAt = created,
    )

    private fun titles(list: List<Note>) = list.map { it.title }

    @Test
    fun `the option is stored as text and an unreadable value gives the default`() {
        assertEquals("NAME:asc", SortOption(SortKey.NAME, false).encode())
        assertEquals(SortOption(SortKey.CREATED, true), SortOption.decode("CREATED:desc", SortOption.NOTES_DEFAULT))
        for (bad in listOf(null, "", "NAME", "NAME:sideways", "SHAPE:asc", "::")) {
            assertEquals("'$bad'", SortOption.NOTES_DEFAULT, SortOption.decode(bad, SortOption.NOTES_DEFAULT))
        }
    }

    @Test
    fun `notes are newest first by default, pinned always on top`() {
        val list = listOf(note("1", "old", 1), note("2", "fresh", 3), note("3", "mid", 2), note("4", "pin", 0, pinned = true))
        assertEquals(listOf("pin", "fresh", "mid", "old"), titles(Sorting.notes(list, SortOption.NOTES_DEFAULT)))
        // the direction flips the rest, but never the pinned rule
        assertEquals(listOf("pin", "old", "mid", "fresh"), titles(Sorting.notes(list, SortOption(SortKey.UPDATED, false))))
    }

    @Test
    fun `notes by name ignore case and work for Cyrillic, by created date, and by colour with the uncoloured last`() {
        val list = listOf(note("1", "banana", created = 5, color = "#FF0000"), note("2", "Apple", created = 9), note("3", "Ёлка", created = 1, color = "#0000FF"), note("4", "cherry", created = 7, color = "#00ff00"))
        assertEquals(listOf("Apple", "banana", "cherry", "Ёлка"), titles(Sorting.notes(list, SortOption(SortKey.NAME, false))))
        assertEquals(listOf("Ёлка", "cherry", "banana", "Apple"), titles(Sorting.notes(list, SortOption(SortKey.NAME, true))))
        assertEquals(listOf("Ёлка", "banana", "cherry", "Apple"), titles(Sorting.notes(list, SortOption(SortKey.CREATED, false))))
        // Colours run through the rainbow (red, green, blue); a note without a colour is last either way.
        assertEquals(listOf("banana", "cherry", "Ёлка", "Apple"), titles(Sorting.notes(list, SortOption(SortKey.COLOR, false))))
        assertEquals(listOf("Ёлка", "cherry", "banana", "Apple"), titles(Sorting.notes(list, SortOption(SortKey.COLOR, true))))
    }

    @Test
    fun `ties fall back to the title so the order never jumps`() {
        val list = listOf(note("2", "b", 5), note("1", "a", 5), note("3", "c", 5))
        assertEquals(listOf("a", "b", "c"), titles(Sorting.notes(list, SortOption.NOTES_DEFAULT)))
    }

    @Test
    fun `folders follow the manual order, then name, created date and colour`() {
        val list = listOf(folder("a", "Work", order = 2, created = 1, color = "#FF0000"), folder("b", "home", order = 0, created = 3, color = "#00FF00"), folder("c", "Zoo", order = 1, created = 2, color = "#0000FF"))
        fun names(o: SortOption) = Sorting.folders(list, o).map { it.name }
        assertEquals(listOf("home", "Zoo", "Work"), names(SortOption.FOLDERS_DEFAULT))
        assertEquals(listOf("Work", "Zoo", "home"), names(SortOption(SortKey.MANUAL, true)))
        assertEquals(listOf("home", "Work", "Zoo"), names(SortOption(SortKey.NAME, false)))
        assertEquals(listOf("Work", "Zoo", "home"), names(SortOption(SortKey.CREATED, false)))
        assertEquals(listOf("home", "Zoo", "Work"), names(SortOption(SortKey.CREATED, true)))
        assertEquals(listOf("Work", "home", "Zoo"), names(SortOption(SortKey.COLOR, false)))
        assertEquals(listOf("Zoo", "home", "Work"), names(SortOption(SortKey.COLOR, true)))
    }

    @Test
    fun `folders with the same order keep the creation order`() {
        val list = listOf(folder("b", "B", order = 0, created = 2), folder("a", "A", order = 0, created = 1))
        assertEquals(listOf("A", "B"), Sorting.folders(list, SortOption.FOLDERS_DEFAULT).map { it.name })
    }
}
