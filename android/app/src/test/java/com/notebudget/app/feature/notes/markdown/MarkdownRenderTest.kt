package com.notebudget.app.feature.notes.markdown

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderTest {
    @Test
    fun `every kind of block is recognised with its source line`() {
        val text = listOf(
            "# Title", "text a", "text b", "", "- bullet", "1. first", "- [ ] todo", "- [x] done",
            "> quote", "---", "```", "code line", "```",
        ).joinToString("\n")
        val blocks = MarkdownRender.blocks(text)
        assertEquals(
            listOf(
                MdBlock.Heading(0, 1, "Title"),
                MdBlock.Paragraph(1, "text a\ntext b"), // each typed line stays a line
                MdBlock.Paragraph(3, ""), // and so does an empty line
                MdBlock.Bullet(4, "bullet", 0),
                MdBlock.Numbered(5, 1, "first", 0),
                MdBlock.Check(6, false, "todo", 0),
                MdBlock.Check(7, true, "done", 0),
                MdBlock.Quote(8, "quote"),
                MdBlock.Rule(9),
                MdBlock.Code(10, "code line"),
            ),
            blocks,
        )
    }

    @Test
    fun `plain text and an unfinished code fence never make a note unreadable`() {
        assertEquals(listOf(MdBlock.Paragraph(0, "just words")), MarkdownRender.blocks("just words"))
        assertEquals(listOf(MdBlock.Code(0, "a\nb")), MarkdownRender.blocks("```\na\nb"))
        assertTrue(MarkdownRender.blocks("").isEmpty())
        assertTrue(MarkdownRender.blocks("\n\n").isEmpty())
    }

    @Test
    fun `typed lines are shown as typed, empty lines kept, none at the ends`() {
        // The user, 2026-09-23: five lines typed in a text note were shown in View as one line.
        val blocks = MarkdownRender.blocks("\none\ntwo\nthree\n\n\nfour\nfive\n\n")
        assertEquals(
            listOf(
                MdBlock.Paragraph(1, "one\ntwo\nthree"),
                MdBlock.Paragraph(4, ""),
                MdBlock.Paragraph(5, ""),
                MdBlock.Paragraph(6, "four\nfive"),
            ),
            blocks,
        )
        assertEquals("one\ntwo\nthree", MarkdownRender.inline("one\ntwo\nthree").text)
    }

    @Test
    fun `a nested item keeps its depth and checklist boxes point at their source line`() {
        val blocks = MarkdownRender.blocks("- a\n  - [x] b")
        assertEquals(MdBlock.Bullet(0, "a", 0), blocks[0])
        assertEquals(MdBlock.Check(1, true, "b", 1), blocks[1])
    }

    @Test
    fun `inline styles become spans and the markers disappear`() {
        val bold = MarkdownRender.inline("a **b** c")
        assertEquals("a b c", bold.text)
        assertEquals(FontWeight.ExtraBold, bold.spanStyles.single().item.fontWeight)
        assertEquals(2 until 3, bold.spanStyles.single().start until bold.spanStyles.single().end)

        assertEquals(FontStyle.Italic, MarkdownRender.inline("*x*").spanStyles.single().item.fontStyle)
        assertEquals(TextDecoration.LineThrough, MarkdownRender.inline("~~x~~").spanStyles.single().item.textDecoration)
        val link = MarkdownRender.inline("see [docs](http://x)")
        assertEquals("see docs", link.text)
        assertEquals(TextDecoration.Underline, link.spanStyles.single().item.textDecoration)
        assertEquals("code", MarkdownRender.inline("`code`").text)
    }

    @Test
    fun `unmatched markers stay as typed`() {
        assertEquals("2 * 3 = 6 and **open", MarkdownRender.inline("2 * 3 = 6 and **open").text)
        assertTrue(MarkdownRender.inline("2 * 3 = 6").spanStyles.isEmpty())
    }
}
