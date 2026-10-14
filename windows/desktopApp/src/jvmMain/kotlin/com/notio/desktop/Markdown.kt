package com.notio.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** One line of a note's View: how it is drawn and its text with the inline styles applied. */
data class MdLine(val kind: Kind, val text: AnnotatedString, val level: Int = 0, val checked: Boolean = false, val number: String = "") {
    enum class Kind { HEADING, BULLET, NUMBERED, CHECK, QUOTE, RULE, PLAIN, BLANK }
}

/**
 * The View of a Markdown note as the phone draws it: the lines as typed (Android D206), headings, lists, check boxes and quotes,
 * **bold** clearly bold, *italic*, ~~struck~~, `code` on a background (D208) and clickable links (D191).
 */
object Markdown {
    private val CHECK = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s?(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val URL = Regex("""https?://[^\s)]+""")
    private val LINK = Regex("""\[([^]]+)]\((https?://[^)\s]+)\)""")
    // Made once, not for every line (the whole-code pass).
    private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
    private val ANY_LINK = Regex("""\[([^]]+)]\([^)]+\)""")
    private val MARKS = Regex("""(\*\*\*|\*\*|__|~~|`)""")
    private val ITALIC = Regex("""(?<!\w)[*_](\S[^*_]*?)[*_](?!\w)""")

    fun lines(text: String, linkColor: Color, codeBackground: Color): List<MdLine> = text.lines().map { raw ->
        fun inl(s: String) = inline(s, linkColor, codeBackground)
        CHECK.matchEntire(raw)?.let { return@map MdLine(MdLine.Kind.CHECK, inl(it.groupValues[3]), indent(it.groupValues[1]), it.groupValues[2] != " ") }
        HEADING.matchEntire(raw)?.let { return@map MdLine(MdLine.Kind.HEADING, inl(it.groupValues[2]), it.groupValues[1].length) }
        NUMBERED.matchEntire(raw)?.let { return@map MdLine(MdLine.Kind.NUMBERED, inl(it.groupValues[3]), indent(it.groupValues[1]), number = it.groupValues[2] + ".") }
        BULLET.matchEntire(raw)?.let { return@map MdLine(MdLine.Kind.BULLET, inl(it.groupValues[2]), indent(it.groupValues[1])) }
        when {
            raw.isBlank() -> MdLine(MdLine.Kind.BLANK, AnnotatedString(""))
            raw.trim().matches(RULE) -> MdLine(MdLine.Kind.RULE, AnnotatedString(""))
            raw.startsWith(">") -> MdLine(MdLine.Kind.QUOTE, inl(raw.removePrefix(">").trimStart()))
            else -> MdLine(MdLine.Kind.PLAIN, inl(raw))
        }
    }

    private fun indent(spaces: String) = spaces.replace("\t", "    ").length / 2

    /** Bold, italic, strikethrough, code, `[text](url)` and bare web addresses. */
    fun inline(s: String, linkColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        val link = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        while (i < s.length) {
            // A plain character (most of them) is taken as it is: no copy of the rest of the line, no search in it (the whole-code
            // pass: a long line used to cost its length squared).
            val c = s[i]
            if (c != '*' && c != '_' && c != '~' && c != '`' && c != '[' && c != 'h') { append(c); i++; continue }
            val rest = s.substring(i)
            when {
                rest.startsWith("***") && rest.indexOf("***", 3) > 3 -> {
                    val end = rest.indexOf("***", 3)
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic)) { append(inline(rest.substring(3, end), linkColor, codeBackground)) }
                    i += end + 3
                }
                (rest.startsWith("**") || rest.startsWith("__")) && rest.indexOf(rest.take(2), 2) > 2 -> {
                    val end = rest.indexOf(rest.take(2), 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(inline(rest.substring(2, end), linkColor, codeBackground)) }
                    i += end + 2
                }
                rest.startsWith("~~") && rest.indexOf("~~", 2) > 2 -> {
                    val end = rest.indexOf("~~", 2)
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inline(rest.substring(2, end), linkColor, codeBackground)) }
                    i += end + 2
                }
                (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 2 && rest[1] != ' ' && rest.indexOf(rest[0], 1) > 1 -> {
                    val end = rest.indexOf(rest[0], 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(rest.substring(1, end), linkColor, codeBackground)) }
                    i += end + 1
                }
                rest.startsWith("`") && rest.indexOf('`', 1) > 1 -> {
                    val end = rest.indexOf('`', 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(" " + rest.substring(1, end) + " ") }
                    i += end + 1
                }
                rest.startsWith("[") && LINK.matchAt(s, i) != null -> {
                    val m = LINK.matchAt(s, i)!!
                    withLink(LinkAnnotation.Url(m.groupValues[2], link)) { append(m.groupValues[1]) }
                    i += m.value.length
                }
                URL.matchAt(s, i) != null -> {
                    // A web address is linked as it stands; its own text is never parsed again (Android D207).
                    val url = URL.matchAt(s, i)!!.value
                    withLink(LinkAnnotation.Url(url, link)) { append(url) }
                    i += url.length
                }
                else -> {
                    append(s[i])
                    i++
                }
            }
        }
    }

    /** A line for a card: what the View shows, without any syntax (Android D108, D159); [checked] is set for a check box line. */
    data class Preview(val text: String, val checked: Boolean? = null)

    fun preview(line: String): Preview {
        var t = line.trim()
        CHECK.matchEntire(t)?.let { return Preview(strip(it.groupValues[3]), checked = it.groupValues[2] != " ") }
        HEADING.matchEntire(t)?.let { t = it.groupValues[2] }
        NUMBERED.matchEntire(t)?.let { return Preview(it.groupValues[2] + ". " + strip(it.groupValues[3])) }
        BULLET.matchEntire(t)?.let { return Preview("• " + strip(it.groupValues[2])) }
        return Preview(strip(t.removePrefix(">").trim()))
    }

    private fun strip(s: String) = s
        .replace(ANY_LINK, "$1")
        .replace(MARKS, "")
        .replace(ITALIC, "$1")
}
