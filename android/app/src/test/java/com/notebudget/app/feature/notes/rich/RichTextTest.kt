package com.notebudget.app.feature.notes.rich

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.notebudget.app.domain.model.RichAlign
import com.notebudget.app.domain.model.RichDocument
import com.notebudget.app.domain.model.RichEdit
import com.notebudget.app.domain.model.RichList
import com.notebudget.app.domain.model.RichMarkdown
import com.notebudget.app.domain.model.RichPara
import com.notebudget.app.domain.model.RichStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatted text (D211): the edits, the stored form, the way to and from Markdown and the editor's cursor mapping. */
class RichTextTest {

    private fun bold(doc: RichDocument, s: Int, e: Int) = RichEdit.toggle(doc, s, e, { it.bold }) { st, on -> st.copy(bold = on) }

    @Test
    fun `styles follow the text when it is typed, deleted and pasted around them`() {
        var doc = bold(RichDocument("hello world"), 0, 5)
        assertTrue(RichEdit.all(doc, 0, 5) { it.bold })
        doc = RichEdit.changeText(doc, "hello big world")       // typed after the bold word: plain
        assertFalse(doc.styles()[6].bold)
        doc = RichEdit.changeText(doc, ">> hello big world", typing = RichStyle.PLAIN) // typed at the start: the bold word moves
        assertTrue(RichEdit.all(doc, 3, 8) { it.bold })
        doc = RichEdit.changeText(doc, ">> hel big world")      // part of it deleted
        assertEquals(listOf(3 to 6), doc.runs.map { it.start to it.end })
        doc = RichEdit.changeText(doc, ">> helXX big world", typing = RichStyle(italic = true))
        assertTrue(doc.styles()[6].italic && !doc.styles()[6].bold)
    }

    @Test
    fun `a toggle removes the style when all of the selection has it`() {
        val doc = bold(RichDocument("abc"), 0, 3)
        assertTrue(RichEdit.all(doc, 0, 3) { it.bold })
        assertTrue(bold(doc, 0, 3).runs.isEmpty())
        assertTrue(RichEdit.all(bold(bold(RichDocument("abc"), 0, 1), 0, 3), 0, 3) { it.bold }) // partly bold -> all bold
    }

    @Test
    fun `lists continue on a new line, number themselves and survive storage`() {
        var doc = RichEdit.toggleList(RichDocument("one"), 0, 0, RichList.NUMBER)
        doc = RichEdit.changeText(doc, "one\ntwo\nthree")
        assertEquals(listOf(1, 2, 3), doc.numbers().toList())
        doc = RichEdit.lines(doc, 4, 4) { it.copy(align = RichAlign.CENTER, indent = 1) }
        doc = RichEdit.style(doc, 8, 13) { it.copy(color = 0xFFFF0000.toInt(), size = 1.5f) }
        val back = RichDocument.decode(doc.encode(), doc.text)!!
        assertEquals(doc, back)
        assertNull(RichDocument.decode(null, "x"))
        assertNull(RichDocument.decode("{\"structure\":1}", "x"))
    }

    @Test
    fun `lines joined by a deletion keep the first line's kind`() {
        var doc = RichDocument("a\nb\nc", paras = listOf(RichPara(list = RichList.BULLET), RichPara(list = RichList.CHECK), RichPara()))
        doc = RichEdit.changeText(doc, "ab\nc")
        assertEquals(listOf(RichList.BULLET, RichList.NONE), doc.paras.map { it.list })
    }

    @Test
    fun `to Markdown keeps bold, italic, strike and lists and tells what it would lose`() {
        var doc = RichDocument("Buy milk now\ndone")
        doc = bold(doc, 4, 8)
        doc = RichEdit.style(doc, 9, 12) { it.copy(italic = true, strike = true) }
        doc = RichEdit.toggleList(doc, 0, 0, RichList.BULLET)
        doc = RichEdit.toggleList(doc, 13, 13, RichList.CHECK).let { RichEdit.toggleChecked(it, 1) }
        assertEquals("- Buy **milk** ~~*now*~~\n- [x] done", RichMarkdown.toMarkdown(doc))
        assertFalse(RichMarkdown.losesFormatting(doc))
        assertTrue(RichMarkdown.losesFormatting(RichEdit.style(doc, 0, 3) { it.copy(color = 0xFF00FF00.toInt()) }))
        assertTrue(RichMarkdown.losesFormatting(RichEdit.lines(doc, 0, 0) { it.copy(align = RichAlign.END) }))
    }

    @Test
    fun `from Markdown to formatted text and back gives the same Markdown`() {
        val md = "# Title\nsome **bold** and *italic*\n- item\n1. first\n2. second\n- [x] done"
        val doc = RichRender.fromMarkdown(md)
        assertEquals("Title\nsome bold and italic\nitem\nfirst\nsecond\ndone", doc.text)
        assertTrue(RichEdit.all(doc, 0, 5) { it.bold && it.size == 2f })
        assertEquals(RichList.NUMBER, doc.paras[3].list)
        assertTrue(doc.paras[5].checked)
        assertEquals("# Title", RichMarkdown.toMarkdown(doc).lines().first())
        assertFalse(RichMarkdown.losesFormatting(doc))
        assertEquals("some **bold** and *italic*\n- item\n1. first\n2. second\n- [x] done", RichMarkdown.toMarkdown(doc).lines().drop(1).joinToString("\n"))
    }

    @Test
    fun `the editor steps the cursor over the list markers`() {
        val doc = RichEdit.toggleList(RichDocument("ab\ncd"), 0, 5, RichList.BULLET)
        val shown = RichRender.Transformation(doc, Color.Black).filter(AnnotatedString(doc.text))
        assertEquals("• ab\n• cd", shown.text.text)
        val map = shown.offsetMapping
        assertEquals(2, map.originalToTransformed(0))   // before "a": after "• "
        assertEquals(7, map.originalToTransformed(3))   // before "c"
        assertEquals(0, map.transformedToOriginal(0))   // inside a marker -> start of its line
        assertEquals(3, map.transformedToOriginal(5))
        assertEquals(5, map.transformedToOriginal(9))
    }
}
