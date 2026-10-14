package com.notebudget.app.feature.notes.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownEditTest {
    private fun s(text: String, start: Int = text.length, end: Int = start) = EditState(text, start, end)

    // ---- inline ----

    @Test
    fun `bold wraps the selection and applying it again removes the bold`() {
        val wrapped = MarkdownEdit.toggleInline(s("say hello now", 4, 9), "**")
        assertEquals(s("say **hello** now", 6, 11), wrapped)
        assertEquals(s("say hello now", 4, 9), MarkdownEdit.toggleInline(wrapped, "**"))
    }

    @Test
    fun `bold with only a cursor inserts a pair of markers and puts the cursor between`() {
        assertEquals(s("a****b", 3), MarkdownEdit.toggleInline(s("ab", 1), "**"))
    }

    @Test
    fun `selecting the markers themselves also removes the bold`() {
        assertEquals(s("hello", 0, 5), MarkdownEdit.toggleInline(s("**hello**", 0, 9), "**"))
    }

    @Test
    fun `italic on bold text adds a star pair instead of removing the bold`() {
        assertEquals(s("***a***", 3, 4), MarkdownEdit.toggleInline(s("**a**", 2, 3), "*"))
    }

    @Test
    fun `italic on italic text removes it`() {
        assertEquals(s("a", 0, 1), MarkdownEdit.toggleInline(s("*a*", 1, 2), "*"))
    }

    // ---- line prefixes ----

    @Test
    fun `bullets are added to every selected line and removed when all have them`() {
        val on = MarkdownEdit.bullets(s("a\nb", 0, 3))
        assertEquals(s("- a\n- b", 0, 7), on)
        assertEquals(s("a\nb", 0, 3), MarkdownEdit.bullets(on))
    }

    @Test
    fun `a bullet replaces another list prefix instead of stacking`() {
        assertEquals("- x", MarkdownEdit.bullets(s("1. x", 0)).text)
        assertEquals("- x", MarkdownEdit.bullets(s("> x", 0)).text)
    }

    @Test
    fun `numbering counts from one and toggles off`() {
        val on = MarkdownEdit.numbered(s("a\nb\nc", 0, 5))
        assertEquals("1. a\n2. b\n3. c", on.text)
        assertEquals("a\nb\nc", MarkdownEdit.numbered(on).text)
    }

    @Test
    fun `checklist and quote add their prefix and keep the cursor on the text`() {
        assertEquals(s("- [ ] task", 6), MarkdownEdit.checklist(s("task", 0)))
        assertEquals("> quoted", MarkdownEdit.quote(s("quoted", 3)).text)
    }

    @Test
    fun `only the touched lines change`() {
        val r = MarkdownEdit.bullets(s("one\ntwo\nthree", 5, 5)) // cursor in "two"
        assertEquals("one\n- two\nthree", r.text)
    }

    @Test
    fun `a selection ending right after a newline does not include the next line`() {
        assertEquals("- a\nb", MarkdownEdit.bullets(s("a\nb", 0, 2)).text)
    }

    @Test
    fun `heading cycles through three levels and back to plain text`() {
        var st = s("title", 5)
        val seen = ArrayList<String>()
        repeat(4) { st = MarkdownEdit.heading(st); seen += st.text }
        assertEquals(listOf("# title", "## title", "### title", "title"), seen)
    }

    // ---- blocks ----

    @Test
    fun `code block wraps the selection in a fence`() {
        assertEquals(s("```\ncode\n```", 4, 8), MarkdownEdit.codeBlock(s("code", 0, 4)))
    }

    @Test
    fun `link wraps the selection and selects the url placeholder`() {
        val r = MarkdownEdit.link(s("see", 0, 3))
        assertEquals("[see](url)", r.text)
        assertEquals("url", r.text.substring(r.start, r.end))
        assertEquals("[text](url)", MarkdownEdit.link(s("", 0)).text)
    }

    @Test
    fun `rule goes on its own line after the current one`() {
        val r = MarkdownEdit.rule(s("abc", 1))
        assertEquals("abc\n---\n", r.text)
        assertEquals(r.text.length, r.start)
    }

    // ---- typing: Enter continues lists ----

    private fun enter(old: EditState): EditState? {
        val typed = old.text.substring(0, old.start) + "\n" + old.text.substring(old.end)
        return MarkdownEdit.continueList(old, EditState(typed, old.start + 1, old.start + 1))
    }

    @Test
    fun `enter after a bullet starts the next bullet`() {
        assertEquals(s("- a\n- "), enter(s("- a")))
    }

    @Test
    fun `enter after a numbered item counts on`() {
        assertEquals(s("1. a\n2. "), enter(s("1. a")))
        assertEquals(s("9. a\n10. "), enter(s("9. a")))
    }

    @Test
    fun `enter after a checklist item starts a fresh unchecked box`() {
        assertEquals(s("- [x] a\n- [ ] "), enter(s("- [x] a")))
    }

    @Test
    fun `enter keeps the indent of a nested item`() {
        assertEquals(s("  - a\n  - "), enter(s("  - a")))
    }

    @Test
    fun `enter on an empty item ends the list`() {
        assertEquals(s("- a\n", 4), enter(s("- a\n- ")))
    }

    @Test
    fun `ordinary typing and enter in the middle of a line are left alone`() {
        assertNull(MarkdownEdit.continueList(s("ab", 2), s("abc", 3)))
        assertNull(enter(s("- ab", 3))) // between a and b
        assertNull(enter(s("plain text")))
    }

    // ---- preview checkboxes ----

    @Test
    fun `toggling a checklist line flips its box and leaves other lines alone`() {
        val text = "- [ ] a\n- [x] b\nplain"
        assertEquals("- [x] a\n- [x] b\nplain", MarkdownEdit.toggleChecklistLine(text, 0))
        assertEquals("- [ ] a\n- [ ] b\nplain", MarkdownEdit.toggleChecklistLine(text, 1))
        assertEquals(text, MarkdownEdit.toggleChecklistLine(text, 2))
        assertEquals(text, MarkdownEdit.toggleChecklistLine(text, 9))
    }

    // ---- checklist lines in the View (D235) ----

    private val nested = listOf(
        "Shopping", //            0
        "- [ ] milk", //          1
        "- [x] bread", //         2
        "  - [ ] white", //       3
        "  - [ ] rye", //         4
        "    note on rye", //     5
        "* [ ] eggs", //          6
        "", //                    7
        "- [ ] other list", //    8
    ).joinToString("\n")

    @Test
    fun `checklist items know their sub-lines and their parent`() {
        val items = MarkdownEdit.checklistItems(nested)
        assertEquals(listOf(1, 2, 3, 4, 6, 8), items.map { it.line })
        val bread = items.first { it.line == 2 }
        assertEquals(6, bread.end)
        assertEquals(null, bread.parent)
        assertEquals(2, items.first { it.line == 4 }.parent)
        assertEquals(6, items.first { it.line == 4 }.end)
    }

    @Test
    fun `siblings are the items of one level that follow one another`() {
        val items = MarkdownEdit.checklistItems(nested)
        assertEquals(listOf(1, 2, 6), MarkdownEdit.checklistSiblings(items, 2).map { it.line })
        assertEquals(listOf(3, 4), MarkdownEdit.checklistSiblings(items, 3).map { it.line })
        // An empty line ends a list: the item after it is in a list of its own.
        assertEquals(listOf(8), MarkdownEdit.checklistSiblings(items, 8).map { it.line })
        assertEquals(emptyList<Int>(), MarkdownEdit.checklistSiblings(items, 0).map { it.line })
    }

    @Test
    fun `the groups found in one pass are the siblings of every item`() {
        val items = MarkdownEdit.checklistItems(nested)
        val groups = MarkdownEdit.checklistGroups(items)
        for (item in items) {
            assertEquals(MarkdownEdit.checklistSiblings(items, item.line), groups.getValue(item.line))
        }
        // A long list stays one group and is found quickly.
        val long = (1..5_000).joinToString("\n") { "- [ ] item $it" }
        val longGroups = MarkdownEdit.checklistGroups(MarkdownEdit.checklistItems(long))
        assertEquals(5_000, longGroups.getValue(0).size)
    }

    @Test
    fun `an item moves among its siblings with its sub-lines`() {
        val moved = MarkdownEdit.reorderChecklist(nested, listOf(2, 6, 1))
        assertEquals(
            listOf("Shopping", "- [x] bread", "  - [ ] white", "  - [ ] rye", "    note on rye", "* [ ] eggs", "- [ ] milk", "", "- [ ] other list"),
            moved.split('\n'),
        )
        val inner = MarkdownEdit.reorderChecklist(nested, listOf(4, 3))
        assertEquals(listOf("  - [ ] rye", "    note on rye", "  - [ ] white"), inner.split('\n').subList(3, 6))
    }

    @Test
    fun `an order that is not one sibling group changes nothing`() {
        assertEquals(nested, MarkdownEdit.reorderChecklist(nested, listOf(3, 1))) // different levels
        assertEquals(nested, MarkdownEdit.reorderChecklist(nested, listOf(8, 1))) // different lists
        assertEquals(nested, MarkdownEdit.reorderChecklist(nested, listOf(2, 1))) // a sibling is missing
        assertEquals(nested, MarkdownEdit.reorderChecklist(nested, listOf(1)))
    }

    @Test
    fun `deleting an item removes its sub-lines and nothing else`() {
        assertEquals(
            listOf("Shopping", "- [ ] milk", "* [ ] eggs", "", "- [ ] other list"),
            MarkdownEdit.deleteChecklistItem(nested, 2).split('\n'),
        )
        assertEquals(nested.replace("\n  - [ ] white", ""), MarkdownEdit.deleteChecklistItem(nested, 3))
        assertEquals(nested, MarkdownEdit.deleteChecklistItem(nested, 0))
    }

    @Test
    fun `checklist lines inside a code block are not items`() {
        val text = "- [ ] a\n```\n- [ ] code\n```\n- [ ] b"
        assertEquals(listOf(0, 4), MarkdownEdit.checklistItems(text).map { it.line })
    }

    @Test
    fun `Enter continues the list when the keyboard changed the word in the same step`() {
        // D304: a suggestion taken together with the Enter ("молко" -> "молоко" and a line break at once).
        val old = s("- [ ] молко")
        val new = s("- [ ] молоко\n")
        val out = MarkdownEdit.continueList(old, new)!!
        assertEquals("- [ ] молоко\n- [ ] ", out.text)
        assertEquals(out.text.length, out.start)
        // A space dropped by the keyboard before the line break; the lines after the item stay.
        val before = EditState("1. one \nlast", 7, 7)
        val after = EditState("1. one\n\nlast", 7, 7)
        assertEquals("1. one\n2. \nlast", MarkdownEdit.continueList(before, after)!!.text)
        // A selection replaced by Enter at the end of an item.
        assertEquals("- a\n- ", MarkdownEdit.continueList(EditState("- abc", 3, 5), s("- a\n"))!!.text)
    }

    @Test
    fun `text of several lines put in at once is not an Enter`() {
        assertNull(MarkdownEdit.continueList(s("- a"), s("- a\n- b\n")))
        // Enter in the middle of an item is left alone, as before.
        assertNull(MarkdownEdit.continueList(EditState("- ab", 3, 3), EditState("- a\nb", 4, 4)))
    }
}
