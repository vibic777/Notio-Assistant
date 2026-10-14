package com.notebudget.app.feature.notes.rich

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.notebudget.app.domain.model.MdToRich
import com.notebudget.app.domain.model.RichAlign
import com.notebudget.app.domain.model.RichDocument
import com.notebudget.app.domain.model.RichList
import com.notebudget.app.domain.model.RichPara
import com.notebudget.app.domain.model.RichStyle
import com.notebudget.app.feature.notes.markdown.MarkdownRender
import com.notebudget.app.feature.notes.markdown.MdBlock

/** How formatted text (D211) is drawn with Compose: in the View, in the editor field and on cards. */
object RichRender {
    /** Indent of one step, in sp. */
    const val INDENT_SP = 20

    fun span(s: RichStyle): SpanStyle {
        val decoration = when {
            s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            s.underline -> TextDecoration.Underline
            s.strike -> TextDecoration.LineThrough
            else -> null
        }
        val scale = (s.size ?: 1f) * if (s.script != 0) 0.7f else 1f
        return SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = decoration,
            baselineShift = when (s.script) {
                1 -> BaselineShift.Superscript
                -1 -> BaselineShift.Subscript
                else -> null
            },
            fontSize = if (scale != 1f) scale.em else TextUnit.Unspecified,
            color = s.color?.let { Color(it) } ?: Color.Unspecified,
            background = s.background?.let { Color(it) } ?: Color.Unspecified,
        )
    }

    fun align(a: RichAlign) = when (a) {
        RichAlign.START -> TextAlign.Start
        RichAlign.CENTER -> TextAlign.Center
        RichAlign.END -> TextAlign.End
    }

    /** What stands before a line: a bullet, its number, a check box; empty for a plain line. */
    fun marker(p: RichPara, number: Int): String = when (p.list) {
        RichList.NONE -> ""
        RichList.BULLET -> "•"
        RichList.NUMBER -> "$number."
        RichList.CHECK -> if (p.checked) "☑" else "☐"
    }

    /** The characters [start, end) of [doc] with their styles. */
    fun piece(doc: RichDocument, styles: Array<RichStyle>, start: Int, end: Int): AnnotatedString = buildAnnotatedString {
        append(doc.text.substring(start, end))
        var i = start
        while (i < end) {
            val st = styles[i]
            var j = i + 1
            while (j < end && styles[j] == st) j++
            if (!st.isPlain) addStyle(span(st), i - start, j - start)
            i = j
        }
    }

    /**
     * The editor field shows the text with its styles, a marker before list items, the indent and the alignment of every line
     * (D211). The markers are not part of the text: the offset mapping steps over them, so the cursor never lands inside one.
     */
    class Transformation(
        private val doc: RichDocument,
        private val markerColor: Color,
        /** The words of the note's search (D256): marked in the text, the [current] match stronger. */
        private val search: com.notebudget.app.domain.model.TextPattern? = null,
        private val current: com.notebudget.app.core.ui.SearchHit? = null,
    ) : VisualTransformation {
        override fun filter(text: AnnotatedString): TransformedText {
            // For one frame the field may be ahead of the document: show it plain rather than with styles of other letters.
            if (text.text != doc.text) return TransformedText(text, OffsetMapping.Identity)
            val starts = doc.lineStarts()
            val numbers = doc.numbers()
            val styles = doc.styles()
            val lines = starts.size
            val prefix = IntArray(lines)
            val out = buildAnnotatedString {
                for (i in 0 until lines) {
                    val start = starts[i]
                    val end = if (i + 1 < lines) starts[i + 1] - 1 else doc.text.length
                    val para = doc.paras[i]
                    val paraStart = length
                    val mark = marker(para, numbers[i]).let { if (it.isEmpty()) it else "$it " }
                    prefix[i] = mark.length
                    if (mark.isNotEmpty()) {
                        pushStyle(SpanStyle(color = markerColor, fontWeight = FontWeight.Bold))
                        append(mark)
                        pop()
                    }
                    append(piece(doc, styles, start, end))
                    if (i + 1 < lines) append('\n')
                    val indent = (para.indent * INDENT_SP).sp
                    addStyle(ParagraphStyle(textAlign = align(para.align), textIndent = TextIndent(indent, indent)), paraStart, length)
                }
            }
            val before = IntArray(lines + 1)
            for (i in 0 until lines) before[i + 1] = before[i] + prefix[i]
            val mapping = object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int {
                    val line = lineOf(starts, offset)
                    return offset + before[line + 1]
                }

                override fun transformedToOriginal(offset: Int): Int {
                    var line = 0
                    while (line + 1 < lines && starts[line + 1] + before[line + 1] <= offset) line++
                    val textStart = starts[line] + before[line + 1]
                    val lineEnd = if (line + 1 < lines) starts[line + 1] - 1 else doc.text.length
                    return (starts[line] + (offset - textStart).coerceAtLeast(0)).coerceAtMost(lineEnd)
                }
            }
            if (search == null) return TransformedText(out, mapping)
            // The matches are found in the text itself and marked where the markers moved them to.
            val marked = buildAnnotatedString {
                append(out)
                com.notebudget.app.core.ui.TextSearch.ranges(doc.text, search).forEachIndexed { k, r ->
                    val strong = current != null && current.key == RichRawKey && current.occurrence == k
                    addStyle(
                        SpanStyle(
                            background = if (strong) com.notebudget.app.core.ui.TextSearch.CURRENT else com.notebudget.app.core.ui.TextSearch.MATCH,
                            color = if (strong) Color.Black else Color.Unspecified,
                        ),
                        mapping.originalToTransformed(r.first), mapping.originalToTransformed(r.last + 1),
                    )
                }
            }
            return TransformedText(marked, mapping)
        }

        private fun lineOf(starts: IntArray, offset: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }

        override fun equals(other: Any?) =
            other is Transformation && other.doc == doc && other.markerColor == markerColor && other.search == search && other.current == current
        override fun hashCode() = (doc.hashCode() * 31 + markerColor.hashCode()) * 31 + search.hashCode() * 7 + current.hashCode()
    }

    /**
     * Markdown made into formatted text (the switch to "Formatted", D211): headings become bold and bigger, lists stay lists,
     * a quote is italic and indented, bold / italic / strikethrough / links (underlined) keep their look. Nothing is lost.
     */
    fun fromMarkdown(markdown: String): RichDocument {
        val lines = ArrayList<MdToRich.Line>()
        MarkdownRender.blocks(markdown).forEach { b ->
            when (b) {
                is MdBlock.Heading -> lines += line(b.text, MdToRich.HEADING_SIZES.getOrElse(b.level - 1) { 1.1f }, bold = true)
                is MdBlock.Check -> lines += line(b.text).copy(para = RichPara(indent = b.indent, list = RichList.CHECK, checked = b.checked))
                is MdBlock.Bullet -> lines += line(b.text).copy(para = RichPara(indent = b.indent, list = RichList.BULLET))
                is MdBlock.Numbered -> lines += line(b.text).copy(para = RichPara(indent = b.indent, list = RichList.NUMBER))
                is MdBlock.Quote -> lines += line(b.text, italic = true).copy(para = RichPara(indent = 1))
                is MdBlock.Code -> b.text.split('\n').forEach { lines += MdToRich.Line(it, emptyList()) }
                is MdBlock.Rule -> lines += MdToRich.Line("———", emptyList())
                is MdBlock.Paragraph -> if (b.text.isEmpty()) lines += MdToRich.Line("", emptyList())
                else b.text.split('\n').forEach { lines += line(it) }
            }
        }
        return MdToRich.join(lines)
    }

    private fun line(markdown: String, size: Float? = null, bold: Boolean = false, italic: Boolean = false): MdToRich.Line {
        val shown = MarkdownRender.inline(markdown)
        val runs = shown.spanStyles.map { r ->
            val s = r.item
            MdToRich.Styled(
                r.start, r.end,
                RichStyle(
                    bold = (s.fontWeight?.weight ?: 400) >= 600,
                    italic = s.fontStyle == FontStyle.Italic,
                    underline = s.textDecoration?.contains(TextDecoration.Underline) == true,
                    strike = s.textDecoration?.contains(TextDecoration.LineThrough) == true,
                ),
            )
        }
        return MdToRich.Line(shown.text, runs, whole = RichStyle(bold = bold, italic = italic, size = size))
    }
}

/** The search key of the whole text of a formatted note in Edit (D256). */
internal object RichRawKey

/** The search key of line [line] of a formatted note in View (D256). */
internal data class RichHitKey(val line: Int)
