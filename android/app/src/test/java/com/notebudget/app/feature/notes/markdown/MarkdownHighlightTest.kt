package com.notebudget.app.feature.notes.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor shows bold, italic and strikethrough while typing, and the note card shows rendered text (D99). */
class MarkdownHighlightTest {
    private fun styles(text: String) = MarkdownRender.highlight(text).spanStyles.map { Triple(it.start, it.end, it.item) }

    @Test
    fun `highlighting never changes the text or its length, so the cursor keeps its place`() {
        val samples = listOf(
            "", "plain", "**bold**", "*it* and **b** and ~~s~~ and `c`", "**итого**  *наклон*  ~~зачёркнуто~~",
            "# Heading **x**\n- [ ] item\n```\ncode **not bold**\n```\nafter", "2 * 3 * 4", "**unclosed", "[link](http://x) tail",
        )
        for (t in samples) assertEquals(t, MarkdownRender.highlight(t).text)
    }

    @Test
    fun `bold, italic and strikethrough are styled where they are typed`() {
        val bold = styles("**итого** 5").filter { it.third.fontWeight == FontWeight.ExtraBold }
        assertEquals(listOf(0 to 9), bold.map { it.first to it.second }) // the whole "**итого**"
        val italic = styles("a *наклон* b").single { it.third.fontStyle == FontStyle.Italic }
        assertEquals(2 to 10, italic.first to italic.second)
        val strike = styles("~~x~~").single { it.third.textDecoration == TextDecoration.LineThrough }
        assertEquals(0 to 5, strike.first to strike.second)
    }

    @Test
    fun `the markers are dimmed, not removed`() {
        val dim = styles("**b**").filter { it.third.color != Color.Unspecified }
        assertEquals(listOf(0 to 2, 3 to 5), dim.map { it.first to it.second })
    }

    @Test
    fun `headings are bold, code is monospace, and a fenced block is not styled as markdown`() {
        assertTrue(styles("# Title").any { it.third.fontWeight == FontWeight.ExtraBold && it.first == 0 && it.second == 7 })
        assertTrue(styles("a `b` c").any { it.third.fontFamily == FontFamily.Monospace })
        val fenced = styles("```\n**not bold**\n```")
        assertTrue(fenced.none { it.third.fontWeight == FontWeight.ExtraBold })
        assertTrue(fenced.any { it.third.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `plain arithmetic with stars is left alone`() {
        assertTrue(styles("2 * 3 * 4 = 24").none { it.third.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `the card preview shows no syntax, only the rendered start of the note`() {
        val preview = MarkdownRender.preview("# Shopping\n- [x] milk\n- [ ] **bread**\nignored fourth block")
        assertEquals("Shopping\n☑ milk\n☐ bread", preview.text)
        assertTrue(preview.spanStyles.any { it.item.fontWeight == FontWeight.ExtraBold && it.start == 0 && it.end == 8 })
        assertTrue(preview.text.none { it == '*' || it == '#' })
    }

    @Test
    fun `the card preview of a list text copy shows boxes, quantities and totals`() {
        val preview = MarkdownRender.preview("- [ ] Milk — 2 L (whole)\n- [x] Flour — 1.5 kg\n= Kilos: 1.5 kg")
        assertEquals("☐ Milk — 2 L (whole)\n☑ Flour — 1.5 kg\n= Kilos: 1.5 kg", preview.text)
    }

    @Test
    fun `an empty note has an empty preview`() {
        assertEquals("", MarkdownRender.preview("").text)
        assertEquals("", MarkdownRender.preview("\n \n").text)
    }
}
