package com.notebudget.app.feature.notes.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** One block of a note in preview. [line] is the index of its first source line (used to toggle checkboxes). */
sealed interface MdBlock {
    val line: Int

    data class Heading(override val line: Int, val level: Int, val text: String) : MdBlock
    data class Check(override val line: Int, val checked: Boolean, val text: String, val indent: Int) : MdBlock
    data class Bullet(override val line: Int, val text: String, val indent: Int) : MdBlock
    data class Numbered(override val line: Int, val number: Int, val text: String, val indent: Int) : MdBlock
    data class Quote(override val line: Int, val text: String) : MdBlock
    data class Code(override val line: Int, val text: String) : MdBlock
    data class Rule(override val line: Int) : MdBlock
    data class Paragraph(override val line: Int, val text: String) : MdBlock
}

/**
 * The small Markdown subset of §6.8: headings, bullet / numbered / checklist items, quotes, fenced
 * code, rules, paragraphs and the inline styles bold, italic, strike, code and links. Anything
 * else is shown as plain text, so no note is ever unreadable.
 */
object MarkdownRender {
    private val HEADING = Regex("^(#{1,6}) (.*)$")
    private val CHECK = Regex("^(\\s*)[-*+] \\[([ xX])\\] ?(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+] (.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d+)\\. (.*)$")
    private val RULE = Regex("^\\s*(---+|\\*\\*\\*+|___+)\\s*$")

    fun blocks(text: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                // An empty line is kept as an empty line (user, 2026-09-23): the View shows the text as it was typed. Empty
                // lines before the first text are dropped, and those after the last text are dropped below.
                line.isBlank() -> if (out.isNotEmpty()) out += MdBlock.Paragraph(i, "")
                line.trimStart().startsWith("```") -> {
                    val start = i
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) body += lines[i++]
                    out += MdBlock.Code(start, body.joinToString("\n"))
                }
                RULE.matches(line) -> out += MdBlock.Rule(i)
                HEADING.matchEntire(line) != null ->
                    HEADING.matchEntire(line)!!.let { out += MdBlock.Heading(i, it.groupValues[1].length, it.groupValues[2]) }
                CHECK.matchEntire(line) != null ->
                    CHECK.matchEntire(line)!!.let {
                        out += MdBlock.Check(i, it.groupValues[2] != " ", it.groupValues[3], it.groupValues[1].length / 2)
                    }
                BULLET.matchEntire(line) != null ->
                    BULLET.matchEntire(line)!!.let { out += MdBlock.Bullet(i, it.groupValues[2], it.groupValues[1].length / 2) }
                NUMBERED.matchEntire(line) != null ->
                    NUMBERED.matchEntire(line)!!.let {
                        out += MdBlock.Numbered(i, it.groupValues[2].toIntOrNull() ?: 1, it.groupValues[3], it.groupValues[1].length / 2)
                    }
                line.startsWith(">") -> out += MdBlock.Quote(i, line.removePrefix(">").trimStart())
                else -> {
                    // Consecutive plain lines form one paragraph, but each keeps its own line (user, 2026-09-23: five typed
                    // lines were shown as one line — Markdown's "soft break" rule, which a notes app must not follow).
                    val start = i
                    val body = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i])) body += lines[i++]
                    i-- // the outer loop advances
                    out += MdBlock.Paragraph(start, body.joinToString("\n"))
                }
            }
            i++
        }
        while (out.lastOrNull().let { it is MdBlock.Paragraph && it.text.isEmpty() }) out.removeAt(out.lastIndex)
        return out
    }

    private fun startsBlock(line: String) =
        line.trimStart().startsWith("```") || RULE.matches(line) || HEADING.matches(line) ||
            CHECK.matches(line) || BULLET.matches(line) || NUMBERED.matches(line) || line.startsWith(">")

    // A style marker never opens before a space or closes after one, so "2 * 3 * 4" stays text.
    // Groups: 1 bold italic (***x***), 2 bold, 3 strikethrough, 4 italic, 5 code, 6 + 7 [text](url), 8 a bare URL (user,
    // 2026-09-22: most people paste "https://…" directly rather than writing [text](url); both are clickable in View).
    private val INLINE = Regex(
        "\\*\\*\\*(?!\\s)(.+?)(?<!\\s)\\*\\*\\*|\\*\\*(?!\\s)(.+?)(?<!\\s)\\*\\*|~~(?!\\s)(.+?)(?<!\\s)~~|" +
            "\\*(?!\\s)(.+?)(?<!\\s)\\*|`([^`]+)`|\\[([^\\]]+)\\]\\(([^)]*)\\)|(https?://\\S+)",
    )

    /**
     * How the styles look (user, 2026-09-23: "bold looks the same as plain text"): bold is drawn extra-bold, so it stands out
     * on every phone font; code gets a grey background, not only another font. Used by the View, the cards and the editor.
     */
    private val BOLD = SpanStyle(fontWeight = FontWeight.ExtraBold)
    private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
    private val BOLD_ITALIC = SpanStyle(fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic)
    private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
    private val CODE = SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x33888888))

    /** Inline styles of one line of text. Unmatched markers stay as they are. */
    fun inline(text: String): AnnotatedString = safely(text) { buildAnnotatedString { appendInline(text) } }

    /**
     * A last line of defence (D207): whatever a text contains, drawing it must never close the app — if styling it fails,
     * it is shown as plain text. A web address once made the styling call itself forever and the app crashed on every card
     * that had one; that bug is fixed, and this keeps any other one from doing the same.
     */
    private inline fun safely(text: String, render: () -> AnnotatedString): AnnotatedString =
        try {
            render()
        } catch (e: Exception) {
            AnnotatedString(text)
        } catch (e: StackOverflowError) {
            AnnotatedString(text)
        }

    private val DIM = androidx.compose.ui.graphics.Color(0x99888888)

    /**
     * The SAME text with styles applied, for the editor: bold, italic, strikethrough, code and links are shown as you
     * type, the markers (`**`, `*`, `~~`, backticks) are dimmed instead of removed. The length never changes, so the
     * cursor and the selection keep their places (an identity offset mapping). Lines inside a fenced code block and
     * headings are handled too (headings are bold).
     */
    fun highlight(text: String): AnnotatedString = safely(text) { highlightUnsafe(text) }

    private fun highlightUnsafe(text: String): AnnotatedString = buildAnnotatedString {
        append(text)
        var offset = 0
        var inFence = false
        for (line in text.split('\n')) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                addStyle(SpanStyle(color = DIM), offset, offset + line.length)
            } else if (inFence) {
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace), offset, offset + line.length)
            } else {
                if (HEADING.matches(line)) addStyle(BOLD, offset, offset + line.length)
                for (m in INLINE.findAll(line)) {
                    val start = offset + m.range.first
                    val end = offset + m.range.last + 1
                    val (style, marker) = when {
                        m.groups[1] != null -> BOLD_ITALIC to 3
                        m.groups[2] != null -> BOLD to 2
                        m.groups[3] != null -> STRIKE to 2
                        m.groups[4] != null -> ITALIC to 1
                        m.groups[5] != null -> CODE to 1
                        else -> SpanStyle(textDecoration = TextDecoration.Underline) to 0
                    }
                    addStyle(style, start, end)
                    if (marker > 0) {
                        addStyle(SpanStyle(color = DIM), start, start + marker)
                        addStyle(SpanStyle(color = DIM), end - marker, end)
                    }
                }
            }
            offset += line.length + 1
        }
    }

    /**
     * A short rendered preview for a note card: the first [maxBlocks] blocks without any syntax - headings bold,
     * check boxes as ☐ / ☑, bullets as •, inline styles applied. Used for text notes and for lists (whose text
     * copy is made of `- [ ]` lines).
     */
    fun preview(text: String, maxBlocks: Int = 3): AnnotatedString = safely(text) { previewUnsafe(text, maxBlocks) }

    private fun previewUnsafe(text: String, maxBlocks: Int): AnnotatedString = buildAnnotatedString {
        blocks(text).take(maxBlocks).forEachIndexed { index, block ->
            if (index > 0) append("\n")
            when (block) {
                is MdBlock.Heading -> withStyle(BOLD) { appendInline(block.text) }
                is MdBlock.Check -> { append(if (block.checked) "☑ " else "☐ "); appendInline(block.text) }
                is MdBlock.Bullet -> { append("• "); appendInline(block.text) }
                is MdBlock.Numbered -> { append("${block.number}. "); appendInline(block.text) }
                is MdBlock.Quote -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(block.text) }
                is MdBlock.Code -> withStyle(CODE) { append(block.text.lineSequence().firstOrNull().orEmpty()) }
                is MdBlock.Rule -> append("—")
                is MdBlock.Paragraph -> appendInline(block.text)
            }
        }
    }

    /**
     * [links] is `false` inside a link: its label is styled (bold, italic ...) but never searched for another link. Before
     * (0.9.24 - 0.9.29) a bare `https://…` was a link whose label is the same `https://…`, which was a link again, and so on
     * until the stack overflowed: the app closed whenever a card or the View of a note with a web address was drawn
     * (the user's crashes in the search and while scrolling the notes, 2026-09-23, D207).
     */
    private fun AnnotatedString.Builder.appendInline(text: String, links: Boolean = true) {
        var at = 0
        for (m in INLINE.findAll(text)) {
            append(text.substring(at, m.range.first))
            val g = m.groupValues
            when {
                m.groups[1] != null -> withStyle(BOLD_ITALIC) { appendInline(g[1], links) }
                m.groups[2] != null -> withStyle(BOLD) { appendInline(g[2], links) }
                m.groups[3] != null -> withStyle(STRIKE) { appendInline(g[3], links) }
                m.groups[4] != null -> withStyle(ITALIC) { appendInline(g[4], links) }
                m.groups[5] != null -> withStyle(CODE) { append(g[5]) }
                // Inside a link: another link is just its text.
                !links -> append(if (m.groups[7] != null) g[6] else g[8])
                // [text](url) — clickable, opens in the browser (user, 2026-09-22).
                m.groups[7] != null -> appendLink(g[7]) { appendInline(g[6], links = false) }
                // A bare https://… pasted with no [text](...) around it — just as clickable, shown as it was typed.
                else -> appendLink(g[8]) { append(g[8]) }
            }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }

    /** A clickable link (user, 2026-09-22): [LinkAnnotation.Url] makes `Text()` open it in the browser on tap, no extra wiring needed. */
    private fun AnnotatedString.Builder.appendLink(url: String, label: AnnotatedString.Builder.() -> Unit) {
        withLink(LinkAnnotation.Url(url)) {
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { label() }
        }
    }
}
