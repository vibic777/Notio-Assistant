package com.notio.shared.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The look of a piece of formatted text (D211): the second way of writing a text note, next to Markdown. `null` / `false`
 * everywhere is plain text. [size] is a multiple of the normal size (0.75 = small ... 2.0 = huge); [script] is 1 for
 * superscript, -1 for subscript; the colours are ARGB.
 */
@Serializable
data class RichStyle(
    @SerialName("b") val bold: Boolean = false,
    @SerialName("i") val italic: Boolean = false,
    @SerialName("u") val underline: Boolean = false,
    @SerialName("s") val strike: Boolean = false,
    @SerialName("sc") val script: Int = 0,
    @SerialName("sz") val size: Float? = null,
    @SerialName("c") val color: Int? = null,
    @SerialName("bg") val background: Int? = null,
) {
    val isPlain: Boolean get() = this == PLAIN

    companion object {
        val PLAIN = RichStyle()
        val SIZES = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

/** A stretch [start, end) of the text in one style. The runs of a document never overlap and plain text has none. */
@Serializable
data class RichRun(val start: Int, val end: Int, val style: RichStyle)

enum class RichAlign { START, CENTER, END }
enum class RichList { NONE, BULLET, NUMBER, CHECK }

/** What a whole line (paragraph) is: its alignment, how far it is indented, and whether it is an item of a list. */
@Serializable
data class RichPara(
    val align: RichAlign = RichAlign.START,
    val indent: Int = 0,
    val list: RichList = RichList.NONE,
    val checked: Boolean = false,
) {
    companion object {
        const val MAX_INDENT = 6
    }
}

/**
 * A formatted text note (D211): the text itself (kept in the note's `contentText` too, so search, cards and plain-text export
 * keep working), the [runs] of styled text and one [paras] entry per line. Stored as JSON in the note's `extras`; a text note
 * without it is a Markdown note. Pure Kotlin: every edit is a function from a document to a new one, and unit-tested.
 */
@Serializable
data class RichDocument(
    @kotlinx.serialization.Transient val text: String = "",
    val runs: List<RichRun> = emptyList(),
    val paras: List<RichPara> = listOf(RichPara()),
) {
    val lineCount: Int get() = text.count { it == '\n' } + 1

    /** The style of every character, from the runs. */
    fun styles(): Array<RichStyle> {
        val out = Array(text.length) { RichStyle.PLAIN }
        runs.forEach { r -> for (i in r.start.coerceAtLeast(0) until r.end.coerceAtMost(text.length)) out[i] = r.style }
        return out
    }

    /** The style a character typed at [index] gets: that of the character before it (or after it at the very start). */
    fun styleAt(index: Int): RichStyle {
        val at = (if (index > 0) index - 1 else index).coerceIn(0, (text.length - 1).coerceAtLeast(0))
        if (text.isEmpty()) return RichStyle.PLAIN
        return runs.firstOrNull { at >= it.start && at < it.end }?.style ?: RichStyle.PLAIN
    }

    /** Index of the line that holds character [index]. */
    fun lineOf(index: Int): Int {
        var line = 0
        for (i in 0 until index.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    /** Start offset of every line. */
    fun lineStarts(): IntArray {
        val out = IntArray(lineCount)
        var line = 1
        text.forEachIndexed { i, c -> if (c == '\n') out[line++] = i + 1 }
        return out
    }

    /** Numbers of the numbered items, per line (0 for other lines): a run of numbered lines of one indent counts 1, 2, 3 ... */
    fun numbers(): IntArray {
        val out = IntArray(paras.size)
        val counters = IntArray(RichPara.MAX_INDENT + 1)
        paras.forEachIndexed { i, p ->
            if (p.list == RichList.NUMBER) {
                counters[p.indent] += 1
                for (deeper in p.indent + 1..RichPara.MAX_INDENT) counters[deeper] = 0
                out[i] = counters[p.indent]
            } else if (p.list == RichList.NONE) {
                counters.fill(0)
            }
        }
        return out
    }

    fun encode(): String = JSON.encodeToString(Stored.serializer(), Stored(FORMAT, 1, this))

    /** The document is kept valid: one paragraph per line, runs inside the text, merged and without plain ones. */
    fun normalized(): RichDocument {
        val lines = lineCount
        val fixedParas = when {
            paras.size == lines -> paras
            paras.size > lines -> paras.take(lines)
            else -> paras + List(lines - paras.size) { RichPara() }
        }
        return RichDocument(text, compress(styles()), fixedParas)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

        /** What `extras` holds for a formatted note. */
        @Serializable
        // No defaults here on purpose: the JSON leaves out values equal to their default, and "format" must always be written.
        private data class Stored(val format: String, val version: Int, val document: RichDocument)

        const val FORMAT = "rich"

        /** Whether the `extras` of a text note say it is formatted text. */
        fun isRich(extras: String?): Boolean = extras != null && extras.contains("\"format\":\"$FORMAT\"")

        /** The document stored in [extras] for the text [content]; `null` when the note is Markdown. */
        fun decode(extras: String?, content: String): RichDocument? {
            if (!isRich(extras)) return null
            val stored = runCatching { JSON.decodeFromString(Stored.serializer(), extras!!) }.getOrNull() ?: return null
            // The text of the note is the truth (it may have been changed by an import); styles out of range are dropped.
            return stored.document.copy(text = content).normalized()
        }

        /** Runs from a style per character: neighbours of one style are merged, plain text has no run. */
        fun compress(styles: Array<RichStyle>): List<RichRun> {
            val out = ArrayList<RichRun>()
            var i = 0
            while (i < styles.size) {
                val st = styles[i]
                var j = i + 1
                while (j < styles.size && styles[j] == st) j++
                if (!st.isPlain) out += RichRun(i, j, st)
                i = j
            }
            return out
        }
    }
}

/** The edits of a formatted text. Every function returns a new, normalized document. */
object RichEdit {

    /**
     * The text changed from `doc.text` to [newText] (typing, deleting, pasting): the styles and the lines move with the text.
     * Characters typed get [typing] when it is given (the style chosen with nothing selected), else the style before them. A
     * new line starts with the kind of the line it was split from (a list goes on, as in any word processor), unchecked.
     */
    fun changeText(doc: RichDocument, newText: String, typing: RichStyle? = null): RichDocument {
        val old = doc.text
        if (old == newText) return doc
        var prefix = 0
        val maxPrefix = minOf(old.length, newText.length)
        while (prefix < maxPrefix && old[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < newText.length - prefix &&
            old[old.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) suffix++
        val removedEnd = old.length - suffix
        val insertedEnd = newText.length - suffix
        val inserted = newText.substring(prefix, insertedEnd)
        val removed = old.substring(prefix, removedEnd)

        val oldStyles = doc.styles()
        val newStyle = typing ?: doc.styleAt(prefix)
        val styles = Array(newText.length) { i ->
            when {
                i < prefix -> oldStyles[i]
                i < insertedEnd -> if (newText[i] == '\n') RichStyle.PLAIN else newStyle
                else -> oldStyles[i - insertedEnd + removedEnd]
            }
        }

        val line = doc.lineOf(prefix)
        val removedLines = removed.count { it == '\n' }
        val addedLines = inserted.count { it == '\n' }
        val paras = doc.normalized().paras
        val base = paras[line]
        val continued = base.copy(checked = false)
        val newParas = paras.subList(0, line + 1) + List(addedLines) { continued } + paras.subList(line + 1 + removedLines, paras.size)
        return RichDocument(newText, RichDocument.compress(styles), newParas).normalized()
    }

    /** Whether every character of [start, end) has what [has] asks (for the state of a toolbar button). */
    fun all(doc: RichDocument, start: Int, end: Int, has: (RichStyle) -> Boolean): Boolean {
        if (start >= end) return has(doc.styleAt(start))
        val styles = doc.styles()
        return (start until end.coerceAtMost(styles.size)).all { doc.text[it] == '\n' || has(styles[it]) }
    }

    /** Changes the style of [start, end) with [change]; line breaks stay plain. */
    fun style(doc: RichDocument, start: Int, end: Int, change: (RichStyle) -> RichStyle): RichDocument {
        if (start >= end) return doc
        val styles = doc.styles()
        for (i in start.coerceAtLeast(0) until end.coerceAtMost(styles.size)) if (doc.text[i] != '\n') styles[i] = change(styles[i])
        return doc.copy(runs = RichDocument.compress(styles)).normalized()
    }

    /** Bold, italic ...: on for all of [start, end) unless all of it already has it, then off. */
    fun toggle(doc: RichDocument, start: Int, end: Int, has: (RichStyle) -> Boolean, set: (RichStyle, Boolean) -> RichStyle): RichDocument {
        val on = !all(doc, start, end, has)
        return style(doc, start, end) { set(it, on) }
    }

    /** Changes every line that [start, end) touches (a cursor touches its own line). */
    fun lines(doc: RichDocument, start: Int, end: Int, change: (RichPara) -> RichPara): RichDocument {
        val d = doc.normalized()
        val first = d.lineOf(start)
        val last = d.lineOf(if (end > start && d.text.getOrNull(end - 1) == '\n') end - 1 else end)
        return d.copy(paras = d.paras.mapIndexed { i, p -> if (i in first..last) change(p) else p })
    }

    /** A list kind for the lines: set, or back to a plain line when all of them already are that kind. */
    fun toggleList(doc: RichDocument, start: Int, end: Int, kind: RichList): RichDocument {
        val d = doc.normalized()
        val first = d.lineOf(start)
        val last = d.lineOf(end)
        val allOfKind = (first..last).all { d.paras[it].list == kind }
        return lines(d, start, end) { it.copy(list = if (allOfKind) RichList.NONE else kind, checked = false) }
    }

    fun indent(doc: RichDocument, start: Int, end: Int, delta: Int) =
        lines(doc, start, end) { it.copy(indent = (it.indent + delta).coerceIn(0, RichPara.MAX_INDENT)) }

    /** Ticks or unticks the check box of [line]. */
    fun toggleChecked(doc: RichDocument, line: Int): RichDocument {
        val d = doc.normalized()
        if (line !in d.paras.indices) return d
        return d.copy(paras = d.paras.mapIndexed { i, p -> if (i == line) p.copy(checked = !p.checked) else p })
    }

    /** Removes every style of [start, end) and resets its lines. */
    fun clear(doc: RichDocument, start: Int, end: Int): RichDocument =
        lines(style(doc, start, end) { RichStyle.PLAIN }, start, end) { RichPara() }
}
