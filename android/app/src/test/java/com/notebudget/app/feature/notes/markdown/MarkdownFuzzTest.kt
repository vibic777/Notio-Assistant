package com.notebudget.app.feature.notes.markdown

import org.junit.Test
import kotlin.random.Random

/**
 * No text may make the Markdown of a card, the View or the editor throw (the search showed the cards of every note at once
 * and the app closed, 2026-09-22): thousands of random lines made of the characters Markdown cares about.
 */
class MarkdownFuzzTest {
    private val alphabet = "*_~`[]()#>-+. 1\n\t\\!|:/xяЁ😀h"

    /** Pieces that matter: web addresses (the crash of 2026-09-23 was a bare https:// link), link brackets, markers. */
    private val pieces = listOf("https://a.ru", "http://b", "[", "](", ")", "**", "***", "*", "~~", "`", " ", "\n", "x", "я")

    @Test
    fun randomTextNeverThrows() {
        val random = Random(42)
        repeat(20_000) {
            val text = buildString {
                repeat(random.nextInt(0, 40)) {
                    if (random.nextBoolean()) append(alphabet[random.nextInt(alphabet.length)]) else append(pieces[random.nextInt(pieces.size)])
                }
            }
            try {
                MarkdownRender.blocks(text)
                MarkdownRender.inline(text)
                MarkdownRender.highlight(text)
                MarkdownRender.preview(text)
                text.lineSequence().forEach { MarkdownRender.preview(it) }
            } catch (e: Throwable) {
                throw AssertionError("Markdown threw on " + text.replace("\n", "\\n").replace("\t", "\\t"), e)
            }
        }
    }
}
