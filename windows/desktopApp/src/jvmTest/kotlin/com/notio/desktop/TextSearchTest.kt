package com.notio.desktop

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding and marking words inside a note (D238). */
class TextSearchTest {

    @Test
    fun `matches ignore case and do not overlap`() {
        assertEquals(listOf(0..2, 9..11), TextSearch.ranges("Milk and MILK", "mil"))
        assertEquals(listOf(0..1, 2..3), TextSearch.ranges("aaaa", "aa"))
        assertEquals(listOf(7..12), TextSearch.ranges("Купить Молоко", "молоко"))
        assertTrue(TextSearch.ranges("anything", "  ").isEmpty())
        assertTrue(TextSearch.ranges("", "a").isEmpty())
        assertTrue(TextSearch.ranges("ab", "abc").isEmpty())
    }

    @Test
    fun `hits are numbered in each text`() {
        assertEquals(listOf(SearchHit("x", 0), SearchHit("x", 1)), TextSearch.hits("x", "tea, tea", "tea"))
    }

    @Test
    fun `the current match is marked stronger than the others`() {
        val marked = TextSearch.mark(AnnotatedString("tea and tea"), "tea", "row", SearchHit("row", 1))
        val styles = marked.spanStyles.sortedBy { it.start }
        assertEquals(2, styles.size)
        assertEquals(TextSearch.MATCH, styles[0].item.background)
        assertEquals(TextSearch.CURRENT, styles[1].item.background)
        // Another text: none of its matches is the current one.
        assertFalse(TextSearch.mark(AnnotatedString("tea"), "tea", "other", SearchHit("row", 0)).spanStyles.any { it.item.background == TextSearch.CURRENT })
    }

    @Test
    fun `the search state steps through the matches and wraps around`() {
        val s = NoteSearch()
        s.open = true
        s.type("a")
        s.report(listOf(SearchHit(1, 0), SearchHit(2, 0), SearchHit(3, 0)))
        assertEquals(SearchHit(1, 0), s.currentHit)
        s.next(); s.next(); s.next()
        assertEquals(SearchHit(1, 0), s.currentHit)
        s.previous()
        assertEquals(SearchHit(3, 0), s.currentHit)
        // Fewer matches after a change: the current one stays within them.
        s.report(listOf(SearchHit(1, 0)))
        assertEquals(SearchHit(1, 0), s.currentHit)
        s.close()
        assertEquals(null, s.currentHit)
        assertFalse(s.open)
    }

    @Test
    fun `recordings, photos and videos are searched in their names as shown and their comments (D248)`() {
        val items = listOf(
            SearchableItem("a", "Recording 1", ""),
            SearchableItem("b", "Beach", "sunset at the beach"),
        )
        val hits = itemHits(items, com.notio.shared.domain.TextPattern.of("beach"))
        assertEquals(listOf(SearchHit(ItemHitKey("b", 0), 0), SearchHit(ItemHitKey("b", 1), 0)), hits)
        assertEquals(listOf(SearchHit(ItemHitKey("a", 0), 0)), itemHits(items, com.notio.shared.domain.TextPattern.of("record")))
        assertTrue(itemHits(items, null).isEmpty())
    }

    @Test
    fun `a snippet shows the piece around the current match, marked, with dots where it is cut (D253)`() {
        val text = "x".repeat(100) + " tea\nmore " + "y".repeat(100) + " tea end"
        val pattern = com.notio.shared.domain.TextPattern.of("tea")
        val first = TextSearch.snippet(text, pattern, "k", null, before = 5, after = 10)!!
        assertEquals("…xxxx tea more yyyy…", first.text)
        assertEquals(listOf(6 until 9), first.spanStyles.map { it.start until it.end })
        val second = TextSearch.snippet(text, pattern, "k", SearchHit("k", 1), before = 5, after = 10)!!
        assertEquals("…yyyy tea end", second.text)
        assertEquals(androidx.compose.ui.graphics.Color.Black, second.spanStyles.single().item.color)
        assertEquals(null, TextSearch.snippet("nothing", pattern, "k", null))
    }
}
