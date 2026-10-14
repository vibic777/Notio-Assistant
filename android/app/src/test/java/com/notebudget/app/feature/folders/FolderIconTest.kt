package com.notebudget.app.feature.folders

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderIconTest {
    private val books = "📚"
    private val house = "🏠"

    @Test
    fun `short text and a single emoji are kept whole`() {
        assertEquals("", FolderIcon.limit(""))
        assertEquals("A", FolderIcon.limit("A"))
        assertEquals("abc", FolderIcon.limit("abc"))
        assertEquals(books, FolderIcon.limit(books)) // one emoji is two UTF-16 units, but ONE character
    }

    @Test
    fun `more than three visible characters are cut, never in the middle of an emoji`() {
        assertEquals("abc", FolderIcon.limit("abcd"))
        assertEquals(books + house + books, FolderIcon.limit(books + house + books + house))
    }

    @Test
    fun `an emoji made of several parts (a family) counts as one character`() {
        val family = "👨‍👩‍👧"
        assertEquals(family + "ab", FolderIcon.limit(family + "ab"))
        assertEquals(family + "abc".take(2), FolderIcon.limit(family + "abc" + "d").take(family.length + 2))
    }

    @Test
    fun `an emoji with a skin tone counts as one character`() {
        val wave = "👋🏽"
        assertEquals(wave + "xy", FolderIcon.limit(wave + "xy"))
    }
}
