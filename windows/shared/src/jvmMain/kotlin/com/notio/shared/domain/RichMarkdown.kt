package com.notio.shared.domain

/**
 * Formatted text as Markdown (D211): for the switch back to Markdown and for the export of a formatted note (Markdown, TXT,
 * PDF). Markdown has bold, italic, strikethrough and lists; underline, super / subscript, sizes, colours, alignment and the
 * indent of a line that is not a list item have no Markdown form and are lost ([losesFormatting] tells).
 */
object RichMarkdown {

    fun toMarkdown(doc: RichDocument): String {
        val d = doc.normalized()
        val styles = d.styles()
        val starts = d.lineStarts()
        val numbers = d.numbers()
        val lines = d.text.split('\n')
        return lines.mapIndexed { i, line ->
            val para = d.paras[i]
            val pad = "  ".repeat(para.indent)
            val prefix = when (para.list) {
                RichList.NONE -> ""
                RichList.BULLET -> "$pad- "
                RichList.NUMBER -> "$pad${numbers[i]}. "
                RichList.CHECK -> pad + if (para.checked) "- [x] " else "- [ ] "
            }
            val level = headingLevel(styles, starts[i], line.length)
            if (level > 0 && para.list == RichList.NONE) {
                "#".repeat(level) + " " + inline(line, Array(styles.size) { k -> styles[k].copy(bold = false, size = null) }, starts[i])
            } else prefix + inline(line, styles, starts[i])
        }.joinToString("\n")
    }

    /**
     * A whole line in one bigger size and bold is a heading in Markdown: 2x = `#`, 1.5x = `##`, 1.25x = `###` (the sizes a
     * Markdown heading gets on the way in); 0 when the line is not a heading.
     */
    private fun headingLevel(styles: Array<RichStyle>, start: Int, length: Int): Int {
        if (length == 0) return 0
        val first = styles[start]
        if (!first.bold || first.size == null) return 0
        if ((start until start + length).any { styles[it].size != first.size || !styles[it].bold }) return 0
        return MdToRich.HEADING_SIZES.indexOf(first.size) + 1
    }

    /** One line with `**`, `*`, `~~` around its styled stretches; spaces at a stretch's ends stay outside its markers. */
    private fun inline(line: String, styles: Array<RichStyle>, offset: Int): String {
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            val st = styles[offset + i]
            var j = i + 1
            while (j < line.length && styles[offset + j].markdownKey() == st.markdownKey()) j++
            val piece = line.substring(i, j)
            val (open, close) = markers(st)
            if (open.isEmpty() || piece.isBlank()) {
                out.append(piece)
            } else {
                val lead = piece.takeWhile { it.isWhitespace() }
                val trail = piece.drop(lead.length).takeLastWhile { it.isWhitespace() }
                val core = piece.substring(lead.length, piece.length - trail.length)
                out.append(lead).append(open).append(core).append(close).append(trail)
            }
            i = j
        }
        return out.toString()
    }

    private fun RichStyle.markdownKey() = Triple(bold, italic, strike)

    private fun markers(st: RichStyle): Pair<String, String> {
        val emphasis = when {
            st.bold && st.italic -> "***"
            st.bold -> "**"
            st.italic -> "*"
            else -> ""
        }
        val strike = if (st.strike) "~~" else ""
        return (strike + emphasis) to (emphasis + strike)
    }

    /** Whether going to Markdown drops something the person made (colour, size, underline, alignment ...). */
    fun losesFormatting(doc: RichDocument): Boolean {
        val d = doc.normalized()
        val styles = d.styles()
        val starts = d.lineStarts()
        val lengths = d.text.split('\n').map { it.length }
        // A size is kept when it makes a heading.
        val headingChars = HashSet<Int>()
        starts.indices.forEach { i ->
            if (d.paras[i].list == RichList.NONE && headingLevel(styles, starts[i], lengths[i]) > 0) {
                for (k in starts[i] until starts[i] + lengths[i]) headingChars += k
            }
        }
        return styles.withIndex().any { (i, st) ->
            st.underline || st.script != 0 || st.color != null || st.background != null || (st.size != null && i !in headingChars)
        } || d.paras.any { it.align != RichAlign.START || (it.indent > 0 && it.list == RichList.NONE) }
    }
}

/** Pieces of a Markdown text on their way to formatted text (the parsing itself is in the screen layer, D211). */
object MdToRich {
    val HEADING_SIZES = listOf(2f, 1.5f, 1.25f)

    class Styled(val start: Int, val end: Int, val style: RichStyle)

    /** One line: its text, the styled stretches of it, a style for the whole line (a heading) and its paragraph. */
    data class Line(val text: String, val styled: List<Styled>, val whole: RichStyle = RichStyle.PLAIN, val para: RichPara = RichPara())

    fun join(lines: List<Line>): RichDocument {
        if (lines.isEmpty()) return RichDocument()
        val text = lines.joinToString("\n") { it.text }
        val styles = Array(text.length) { RichStyle.PLAIN }
        var offset = 0
        lines.forEach { line ->
            for (i in line.text.indices) {
                var st = line.whole
                line.styled.filter { i >= it.start && i < it.end }.forEach { s ->
                    st = st.copy(
                        bold = st.bold || s.style.bold, italic = st.italic || s.style.italic,
                        underline = st.underline || s.style.underline, strike = st.strike || s.style.strike,
                    )
                }
                styles[offset + i] = st
            }
            offset += line.text.length + 1
        }
        return RichDocument(text, RichDocument.compress(styles), lines.map { it.para }).normalized()
    }
}
