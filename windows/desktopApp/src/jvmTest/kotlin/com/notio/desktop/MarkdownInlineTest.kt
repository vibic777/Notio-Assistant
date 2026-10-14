package com.notio.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bold, italic, strikethrough, code, links and bare addresses in a line of the View (the whole-code pass made plain text a fast path). */
class MarkdownInlineTest {
    private fun inline(s: String) = Markdown.inline(s, Color.Blue, Color.LightGray)

    private fun urls(s: String) = inline(s).let { a -> a.getLinkAnnotations(0, a.length).map { (it.item as LinkAnnotation.Url).url } }

    @Test
    fun marksAreTakenAwayAndPlainTextStaysAsItIs() {
        assertEquals("bold italic gone code", inline("**bold** *italic* ~~gone~~ `code`").text.replace("  ", " ").replace(" code ", " code").trim())
        assertEquals("hello how [are] you * _ h", inline("hello how [are] you * _ h").text)
        assertEquals("", inline("").text)
    }

    @Test
    fun linksAndBareAddressesAreLinked() {
        assertEquals("see Notio and https://example.org/a now", inline("see [Notio](https://notio.app) and https://example.org/a now").text)
        assertEquals(listOf("https://notio.app", "https://example.org/a"), urls("see [Notio](https://notio.app) and https://example.org/a now"))
        assertEquals(emptyList(), urls("[not a link](ftp://x) http:/ bad"))
    }

    @Test
    fun aLongLineIsQuick() {
        val line = "plain words with an h here and there, and a [bracket] or two. ".repeat(2_000)
        inline(line) // warm up
        val t0 = System.nanoTime()
        val out = inline(line)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(line, out.text)
        assertTrue(ms < 500, "a line of ${line.length} characters took $ms ms")
    }
}
