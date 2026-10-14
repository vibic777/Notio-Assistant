package com.notebudget.app.feature.notes.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every button of the Markdown toolbar, followed by what the View shows (user, 2026-09-23: "check all the Markdown
 * commands"), and the web-address crash (D207).
 */
class MarkdownCommandsTest {

    /** The typed text [word] selected, a toolbar action applied, the View's text of the result. */
    private fun apply(text: String, from: Int, to: Int, action: (EditState) -> EditState): String = action(EditState(text, from, to)).text

    private fun AnnotatedString.styleOf(part: String) = spanStyles.filter { text.substring(it.start, it.end).contains(part) }.map { it.item }

    @Test
    fun `bold italic and strike made by the buttons show styled, without markers`() {
        val bold = MarkdownRender.inline(apply("жирный текст", 0, 6) { MarkdownEdit.toggleInline(it, "**") })
        assertEquals("жирный текст", bold.text)
        assertEquals(FontWeight.ExtraBold, bold.styleOf("жирный").single().fontWeight)

        val italic = MarkdownRender.inline(apply("курсив", 0, 6) { MarkdownEdit.toggleInline(it, "*") })
        assertEquals("курсив", italic.text)
        assertEquals(FontStyle.Italic, italic.styleOf("курсив").single().fontStyle)

        val strike = MarkdownRender.inline(apply("old", 0, 3) { MarkdownEdit.toggleInline(it, "~~") })
        assertEquals(TextDecoration.LineThrough, strike.styleOf("old").single().textDecoration)

        val code = MarkdownRender.inline(apply("x = 1", 0, 5) { MarkdownEdit.toggleInline(it, "`") })
        assertEquals("x = 1", code.text)
        assertEquals(FontFamily.Monospace, code.styleOf("x = 1").single().fontFamily)
    }

    @Test
    fun `a word selected with the space after it still becomes bold`() {
        val typed = apply("word next", 0, 5) { MarkdownEdit.toggleInline(it, "**") }
        assertEquals("**word** next", typed)
        assertEquals("word next", MarkdownRender.inline(typed).text)
    }

    @Test
    fun `bold and italic together, and several lines at once`() {
        val both = apply(apply("both", 0, 4) { MarkdownEdit.toggleInline(it, "**") }, 2, 6) { MarkdownEdit.toggleInline(it, "*") }
        assertEquals("***both***", both)
        val shown = MarkdownRender.inline(both)
        assertEquals("both", shown.text)
        assertEquals(FontWeight.ExtraBold, shown.spanStyles.single().item.fontWeight)
        assertEquals(FontStyle.Italic, shown.spanStyles.single().item.fontStyle)

        val lines = apply("one\ntwo\n\nthree", 0, 14) { MarkdownEdit.toggleInline(it, "**") }
        assertEquals("**one**\n**two**\n\n**three**", lines)
        assertEquals("one\ntwo", MarkdownRender.blocks(lines).first().let { (it as MdBlock.Paragraph).text }.let { MarkdownRender.inline(it).text })
    }

    @Test
    fun `line commands show as headings, lists, check boxes, quotes, code and rules`() {
        val blocks = MarkdownRender.blocks(
            listOf(
                apply("Title", 0, 0, MarkdownEdit::heading),
                apply("milk", 0, 0, MarkdownEdit::bullets),
                apply("first", 0, 0, MarkdownEdit::numbered),
                apply("call", 0, 0, MarkdownEdit::checklist),
                apply("said", 0, 0, MarkdownEdit::quote),
            ).joinToString("\n"),
        )
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Bullet)
        assertTrue(blocks[2] is MdBlock.Numbered)
        assertTrue(blocks[3] is MdBlock.Check)
        assertTrue(blocks[4] is MdBlock.Quote)
        assertTrue(MarkdownRender.blocks(apply("", 0, 0, MarkdownEdit::codeBlock)).single() is MdBlock.Code)
        assertTrue(MarkdownRender.blocks(apply("", 0, 0, MarkdownEdit::rule)).any { it is MdBlock.Rule })
    }

    @Test
    fun `web addresses are clickable and never loop (the crash of 2026-09-23)`() {
        val bare = MarkdownRender.inline("see https://example.com/a?b=1 now")
        assertEquals("see https://example.com/a?b=1 now", bare.text)
        assertEquals("https://example.com/a?b=1", (bare.getLinkAnnotations(0, bare.length).single().item as LinkAnnotation.Url).url)

        val named = MarkdownRender.inline("[**site**](https://x.ru) and [https://y.ru](https://z.ru)")
        assertEquals("site and https://y.ru", named.text)
        assertEquals(2, named.getLinkAnnotations(0, named.length).size)

        // The shapes that used to overflow the stack.
        MarkdownRender.preview("https://a.b\n**https://c.d**\n*http://e.f*")
        MarkdownRender.highlight("https://a.b [https://c.d](https://e.f)")
    }
}
