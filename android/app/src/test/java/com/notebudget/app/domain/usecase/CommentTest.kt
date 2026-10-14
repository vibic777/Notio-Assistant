package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.Comment
import com.notebudget.app.domain.model.CommentDisplay
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.budget.BudgetLayout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comments on notes, folders and budget rows (D177): what is stored and what a card shows. */
class CommentTest {
    private val env = TestEnv()

    @Test
    fun `a card shows the first line cut to 16 characters, with an ellipsis as the last one`() {
        assertNull(Comment.short(null))
        assertNull(Comment.short("   \n  "))
        assertEquals("buy milk", Comment.short("buy milk"))
        assertEquals("1234567890123456", Comment.short("1234567890123456")) // exactly 16: whole
        assertEquals("123456789012345…", Comment.short("12345678901234567")) // 17: 15 + the ellipsis = 16
        assertEquals(16, Comment.short("a comment that is much longer than sixteen")!!.length)
        // Only the first non-empty line; spaces collapsed.
        assertEquals("first line", Comment.short("\n  first    line \nsecond"))
        // An emoji is never cut in the middle.
        assertEquals("😀".repeat(15) + "…", Comment.short("😀".repeat(20)))
    }

    @Test
    fun `a comment is trimmed, limited to 500 characters and blank means none`() {
        assertNull(Comment.clean(null))
        assertNull(Comment.clean("  \n "))
        assertEquals("hello", Comment.clean("  hello \n"))
        assertEquals(500, Comment.clean("x".repeat(900))!!.length)
    }

    @Test
    fun `a note and a folder keep their comment, and clearing it removes it`() = runTest {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "N", NoteType.TEXT) as DomainResult.Success).value

        env.notes.setComment(note.id, "  call the bank ")
        env.folders.setComment(folder.id, "work")
        assertEquals("call the bank", env.noteRepo.getById(note.id)!!.comment)
        assertEquals("work", env.folderRepo.getById(folder.id)!!.comment)

        env.notes.setComment(note.id, "   ")
        env.folders.setComment(folder.id, null)
        assertNull(env.noteRepo.getById(note.id)!!.comment)
        assertNull(env.folderRepo.getById(folder.id)!!.comment)
    }

    @Test
    fun `saving a note from an editor does not wipe the comment`() = runTest {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "N", NoteType.TEXT) as DomainResult.Success).value
        env.notes.setComment(note.id, "keep me")

        // The editor holds an older copy of the note, without the comment.
        env.notes.save(note.copy(contentText = "typed text"))

        val saved = env.noteRepo.getById(note.id)!!
        assertEquals("typed text", saved.contentText)
        assertEquals("keep me", saved.comment)
    }

    @Test
    fun `the display switches are on by default and survive storage`() {
        assertTrue(CommentDisplay().notes && CommentDisplay().folders)
        val d = CommentDisplay(notes = false)
        assertEquals(d, CommentDisplay.decode(d.encode()))
        assertEquals(CommentDisplay(), CommentDisplay.decode("junk"))
        assertEquals(CommentDisplay(folders = false), CommentDisplay.decode("""{"folders":false,"x":1}"""))
    }

    @Test
    fun `a budget shows its comments unless it was told not to, and old layouts show them`() {
        assertTrue(BudgetLayout().showComments)
        assertTrue(BudgetLayout.parse("""{"totalAtBottom":true}""").showComments)
        val off = BudgetLayout(showComments = false)
        assertEquals(false, BudgetLayout.parse(off.toExtras()).showComments)
    }
}
