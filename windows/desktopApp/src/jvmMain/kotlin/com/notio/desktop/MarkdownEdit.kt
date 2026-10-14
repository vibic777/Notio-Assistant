package com.notio.desktop

/** Text with a selection ([start] <= [end], equal when it is just a cursor). Pure, so it is unit-tested. */
data class EditState(val text: String, val start: Int, val end: Int) {
    init {
        require(start in 0..end && end <= text.length) { "selection $start..$end outside text of ${text.length}" }
    }
}

/**
 * The formatting actions of the Markdown toolbar. Each takes the current text and selection and
 * returns the new ones; applying the same action twice undoes it (bold on bold text removes the bold).
 */
object MarkdownEdit {

    // ---- inline ----

    /** Wraps the selection in [marker] (`**`, `*`, `~~`, `` ` ``), or removes the marker if it is already there. */
    fun toggleInline(s: EditState, marker: String): EditState {
        // Spaces and line breaks at the ends of the selection stay OUTSIDE the markers (user, 2026-09-23): Android selects a
        // word with the space after it, and "**word **" is not bold in Markdown — the text looked unchanged.
        val lead = s.text.substring(s.start, s.end).takeWhile { it.isWhitespace() }.length
        val trail = s.text.substring(s.start + lead, s.end).takeLastWhile { it.isWhitespace() }.length
        if (s.end - s.start > lead + trail && (lead > 0 || trail > 0)) {
            val inner = toggleInline(EditState(s.text, s.start + lead, s.end - trail), marker)
            val grown = inner.text.length - s.text.length
            return EditState(inner.text, s.start, s.end + grown)
        }
        // A style never crosses a line break: several lines are styled each on its own.
        val chosen = s.text.substring(s.start, s.end)
        if ('\n' in chosen) return toggleEachLine(s, marker)
        val before = s.text.substring(0, s.start)
        val selected = s.text.substring(s.start, s.end)
        val after = s.text.substring(s.end)
        if (s.start == s.end) {
            val text = before + marker + marker + after
            return EditState(text, s.start + marker.length, s.start + marker.length)
        }
        if (wrappedOutside(before, after, marker)) {
            val text = before.dropLast(marker.length) + selected + after.drop(marker.length)
            return EditState(text, s.start - marker.length, s.end - marker.length)
        }
        if (selected.length >= 2 * marker.length && selected.startsWith(marker) && selected.endsWith(marker) &&
            markerRunIsOdd(selected, marker)
        ) {
            val inner = selected.substring(marker.length, selected.length - marker.length)
            return EditState(before + inner + after, s.start, s.start + inner.length)
        }
        val text = before + marker + selected + marker + after
        return EditState(text, s.start + marker.length, s.end + marker.length)
    }

    /** [toggleInline] on every non-empty line of a selection of several lines; the whole changed text stays selected. */
    private fun toggleEachLine(s: EditState, marker: String): EditState {
        val parts = s.text.substring(s.start, s.end).split('\n')
        val changed = parts.joinToString("\n") { line ->
            if (line.isBlank()) line else toggleInline(EditState(line, 0, line.length), marker).text
        }
        val text = s.text.substring(0, s.start) + changed + s.text.substring(s.end)
        return EditState(text, s.start, s.start + changed.length)
    }

    /** For `*`, one star is italic but two are bold: only an odd run of stars around the text counts as italic. */
    private fun wrappedOutside(before: String, after: String, marker: String): Boolean {
        if (!before.endsWith(marker) || !after.startsWith(marker)) return false
        if (marker != "*") return true
        return before.takeLastWhile { it == '*' }.length % 2 == 1 && after.takeWhile { it == '*' }.length % 2 == 1
    }

    private fun markerRunIsOdd(selected: String, marker: String): Boolean =
        marker != "*" || (selected.takeWhile { it == '*' }.length % 2 == 1 && selected.takeLastWhile { it == '*' }.length % 2 == 1)

    // ---- line prefixes ----

    private val PREFIX = Regex("^(#{1,6} |- \\[[ xX]\\] |[-*+] |\\d+\\. |> )")

    private class Lines(val from: Int, val to: Int, val lines: List<String>)

    /** The whole lines touched by the selection. A selection ending right after a newline does not include the next line. */
    private fun linesOf(s: EditState): Lines {
        val from = s.text.lastIndexOf('\n', s.start - 1) + 1
        val endProbe = if (s.end > s.start && s.text[s.end - 1] == '\n') s.end - 1 else s.end
        val nl = s.text.indexOf('\n', endProbe)
        val to = if (nl < 0) s.text.length else nl
        return Lines(from, to, s.text.substring(from, to).split('\n'))
    }

    private fun replaceLines(s: EditState, l: Lines, newLines: List<String>): EditState {
        val joined = newLines.joinToString("\n")
        val text = s.text.substring(0, l.from) + joined + s.text.substring(l.to)
        return if (s.start == s.end) {
            // Keep the cursor where it was in the first line, shifted by the change of its prefix.
            val delta = newLines.first().length - l.lines.first().length
            val pos = (s.start + delta).coerceIn(l.from, l.from + newLines.first().length)
            EditState(text, pos, pos)
        } else {
            EditState(text, l.from, l.from + joined.length)
        }
    }

    private fun strip(line: String): String = line.replaceFirst(PREFIX, "")

    /** Adds [prefix] to every selected line, or removes it if all of them already have it. */
    fun toggleLinePrefix(s: EditState, prefix: String): EditState {
        val l = linesOf(s)
        val all = l.lines.all { it.startsWith(prefix) }
        val out = l.lines.map { if (all) it.removePrefix(prefix) else prefix + strip(it) }
        return replaceLines(s, l, out)
    }

    /** Bullet list. */
    fun bullets(s: EditState) = toggleLinePrefix(s, "- ")

    /** Checklist `- [ ] `. */
    fun checklist(s: EditState) = toggleLinePrefix(s, "- [ ] ")

    fun quote(s: EditState) = toggleLinePrefix(s, "> ")

    /** Numbered list: `1.`, `2.`, ... or removes the numbers if every line has one. */
    fun numbered(s: EditState): EditState {
        val l = linesOf(s)
        val number = Regex("^\\d+\\. ")
        val all = l.lines.all { number.containsMatchIn(it) }
        val out = if (all) l.lines.map { it.replaceFirst(number, "") }
        else l.lines.mapIndexed { i, line -> "${i + 1}. " + strip(line) }
        return replaceLines(s, l, out)
    }

    /** Cycles the heading level of the selected lines: none -> # -> ## -> ### -> none. */
    fun heading(s: EditState): EditState {
        val l = linesOf(s)
        val level = l.lines.first().takeWhile { it == '#' }.let { if (l.lines.first().startsWith("$it ")) it.length else 0 }
        val next = if (level >= 3) 0 else level + 1
        val out = l.lines.map { line ->
            val plain = line.replaceFirst(Regex("^#{1,6} "), "")
            if (next == 0) plain else "#".repeat(next) + " " + plain
        }
        return replaceLines(s, l, out)
    }

    // ---- blocks and links ----

    /** Wraps the selection (or the cursor line) in a fenced code block. */
    fun codeBlock(s: EditState): EditState {
        val selected = s.text.substring(s.start, s.end)
        val lead = if (s.start == 0 || s.text[s.start - 1] == '\n') "" else "\n"
        val insert = "$lead```\n$selected\n```"
        val text = s.text.substring(0, s.start) + insert + s.text.substring(s.end)
        val cursor = s.start + lead.length + 4 + selected.length
        return if (selected.isEmpty()) EditState(text, cursor, cursor) else EditState(text, s.start + lead.length + 4, cursor)
    }

    /** `[selection](url)` with the `url` placeholder selected, ready to be typed over. */
    fun link(s: EditState): EditState {
        val label = s.text.substring(s.start, s.end).ifEmpty { "text" }
        val insert = "[$label](url)"
        val text = s.text.substring(0, s.start) + insert + s.text.substring(s.end)
        val urlStart = s.start + label.length + 3
        return EditState(text, urlStart, urlStart + 3)
    }

    /** A horizontal rule on its own line after the current line. */
    fun rule(s: EditState): EditState {
        val nl = s.text.indexOf('\n', s.end)
        val at = if (nl < 0) s.text.length else nl
        val insert = (if (at == 0 || s.text[at - 1] == '\n') "" else "\n") + "---\n"
        val text = s.text.substring(0, at) + insert + s.text.substring(at)
        val cursor = at + insert.length
        return EditState(text, cursor, cursor)
    }

    // ---- typing ----

    private val LIST_LINE = Regex("^(\\s*)(- \\[[ xX]\\] |[-*+] |(\\d+)\\. |> )(.*)$")

    /**
     * Enter at the end of a list item starts the next item (same bullet, next number, a fresh
     * unchecked box); Enter on an empty item ends the list. Returns `null` when [new] is not
     * "the user pressed Enter after a list line", so ordinary typing is never touched.
     *
     * What counts as that Enter (D304): the cursor of [new] stands right after a line break, the text has one line more than
     * [old], and what follows the cursor is what followed it before. It used to be "exactly one character came, a line break":
     * a keyboard that finishes or corrects the word under the cursor in the same step as the Enter (a suggestion taken, a space
     * dropped) changes more than one character, and the new line then came without its "- [ ]". The item is read from [new],
     * after whatever the keyboard did to it.
     */
    fun continueList(old: EditState, new: EditState): EditState? {
        if (new.start != new.end) return null
        val p = new.start
        if (p == 0 || p > new.text.length || new.text[p - 1] != '\n') return null
        // Enter at the end of the line only.
        val tail = new.text.substring(p)
        if (tail.isNotEmpty() && tail[0] != '\n') return null
        // What follows the cursor is what followed the cursor (or the selection) before.
        val oldEnd = maxOf(old.start, old.end)
        if (old.text.length - tail.length != oldEnd || !old.text.endsWith(tail)) return null
        val lineStart = new.text.lastIndexOf('\n', p - 2) + 1
        // The lines before the item are untouched, and exactly one line came (not a pasted piece of several lines).
        if (lineStart > old.text.length || !old.text.regionMatches(0, new.text, 0, lineStart)) return null
        if (minOf(old.start, old.end) < lineStart) return null
        if (old.text.substring(lineStart, oldEnd).contains('\n')) return null
        val line = new.text.substring(lineStart, p - 1)
        if (line.contains('\n')) return null
        val m = LIST_LINE.matchEntire(line) ?: return null
        val indent = m.groupValues[1]
        val marker = m.groupValues[2]
        val content = m.groupValues[4]
        if (content.isBlank()) {
            // Empty item: remove its marker and stay on the line, which ends the list.
            val text = new.text.substring(0, lineStart) + tail
            return EditState(text, lineStart, lineStart)
        }
        val next = when {
            marker.startsWith("- [") -> "- [ ] "
            m.groupValues[3].isNotEmpty() -> "${m.groupValues[3].toInt() + 1}. "
            else -> marker
        }
        val insert = indent + next
        val text = new.text.substring(0, p) + insert + tail
        val cursor = p + insert.length
        return EditState(text, cursor, cursor)
    }

    /** Flips `[ ]` / `[x]` of the checklist item on [lineIndex] (used by the tappable boxes of the preview). */
    fun toggleChecklistLine(text: String, lineIndex: Int): String {
        val lines = text.split('\n').toMutableList()
        if (lineIndex !in lines.indices) return text
        val line = lines[lineIndex]
        val m = Regex("^(\\s*[-*+] \\[)([ xX])(\\].*)$").matchEntire(line) ?: return text
        lines[lineIndex] = m.groupValues[1] + (if (m.groupValues[2] == " ") "x" else " ") + m.groupValues[3]
        return lines.joinToString("\n")
    }

    // ---- checklist lines edited in the View (D235) ----

    private val CHECK_LINE = Regex("^\\s*[-*+] \\[[ xX]\\].*$")

    private fun indentOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }

    /**
     * Every checklist item of [text] outside code blocks: its [ChecklistItem.line], its indent, the [ChecklistItem.end]
     * (exclusive) of the lines that belong to it — the non-blank lines right under it that are indented deeper — and the line of
     * the checklist item it belongs to, if any.
     */
    fun checklistItems(text: String): List<ChecklistItem> {
        val lines = text.split('\n')
        val out = ArrayList<ChecklistItem>()
        // The items whose lines are still going on, innermost last: the parent of a new item is the innermost one of them.
        val open = ArrayDeque<ChecklistItem>()
        var inCode = false
        for ((i, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("```")) inCode = !inCode
            if (inCode || !CHECK_LINE.matches(line)) continue
            val indent = indentOf(line)
            var end = i + 1
            while (end < lines.size && lines[end].isNotBlank() && indentOf(lines[end]) > indent) end++
            while (open.isNotEmpty() && open.last().end <= i) open.removeLast()
            val item = ChecklistItem(i, indent, end, open.lastOrNull()?.line)
            out += item
            open.addLast(item)
        }
        return out
    }

    /**
     * The items [line] may change places with: the checklist items of its own level (same indent, same parent item) that follow
     * one another without other lines between them, [line] included, in text order. Empty when [line] is not an item.
     */
    fun checklistSiblings(items: List<ChecklistItem>, line: Int): List<ChecklistItem> {
        val item = items.firstOrNull { it.line == line } ?: return emptyList()
        fun sibling(of: ChecklistItem, other: ChecklistItem) = other.indent == of.indent && other.parent == of.parent
        val group = ArrayDeque(listOf(item))
        while (true) {
            val first = group.first()
            group.addFirst(items.firstOrNull { it.end == first.line && sibling(item, it) } ?: break)
        }
        while (true) {
            val last = group.last()
            group.addLast(items.firstOrNull { it.line == last.end && sibling(item, it) } ?: break)
        }
        return group.toList()
    }

    /**
     * Every item's sibling group (as [checklistSiblings] gives it), keyed by the item's line, found in one pass: the View asks
     * for it on every change of the text, and a long list must not cost a search per line.
     */
    fun checklistGroups(items: List<ChecklistItem>): Map<Int, List<ChecklistItem>> {
        val byLine = items.associateBy { it.line }
        val out = HashMap<Int, List<ChecklistItem>>(items.size * 2)
        for (item in items) {
            if (item.line in out) continue
            // Items come in text order, so the first one met of a group is its first line.
            val group = ArrayList<ChecklistItem>()
            var current: ChecklistItem? = item
            while (current != null) {
                group += current
                current = byLine[current.end]?.takeIf { it.indent == item.indent && it.parent == item.parent }
            }
            for (g in group) out[g.line] = group
        }
        return out
    }

    /**
     * Puts one group of sibling items in a new order: [order] is the first lines of the siblings of one group in the order they
     * must have. Each item moves with its sub-lines. Anything that is not exactly such a group leaves the text as it was.
     */
    fun reorderChecklist(text: String, order: List<Int>): String {
        if (order.size < 2) return text
        val items = checklistItems(text)
        val group = checklistSiblings(items, order.first())
        if (group.map { it.line }.sorted() != order.sorted()) return text
        val lines = text.split('\n')
        val byLine = group.associateBy { it.line }
        val from = group.first().line
        val to = group.last().end
        val moved = order.flatMap { lines.subList(it, byLine.getValue(it).end) }
        return (lines.subList(0, from) + moved + lines.subList(to, lines.size)).joinToString("\n")
    }

    /** Removes the checklist item on [line] together with its sub-lines; the text stays as it was when [line] is not one. */
    fun deleteChecklistItem(text: String, line: Int): String {
        val item = checklistItems(text).firstOrNull { it.line == line } ?: return text
        val lines = text.split('\n')
        return (lines.subList(0, item.line) + lines.subList(item.end, lines.size)).joinToString("\n")
    }
}

/** A checklist item of a Markdown text (D235): lines [line] until [end] (exclusive) are the item and its sub-lines. */
data class ChecklistItem(val line: Int, val indent: Int, val end: Int, val parent: Int?)
