package com.notio.shared.data

import com.notio.shared.SequentialIds
import com.notio.shared.domain.Clock
import com.notio.shared.domain.NoteType
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android editing rules (FolderUseCases, NoteUseCases, TrashUseCases) on the desktop database. */
class NotioEditorTest {
    private val dir = Files.createTempDirectory("notio-edit").toFile()
    private var now = 1_000L
    private val db = NotioDatabase.inMemory()
    private val files = AttachmentStore(dir)
    private val editor = NotioEditor(db, files, Clock { now }, SequentialIds())

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun error(block: () -> Unit) = assertFailsWith<EditException> { block() }.error

    @Test
    fun aNewNoteIsTheFirstOfItsFolderInTheOwnOrder() {
        // Android D257: before the one that was first.
        val folder = editor.createFolder("Дом", "#3366FF", "SOFT")
        val a = editor.createNote(folder.id, "A", com.notio.shared.domain.NoteType.TEXT)
        val b = editor.createNote(folder.id, "B", com.notio.shared.domain.NoteType.TEXT)
        val c = editor.createNote(folder.id, "C", com.notio.shared.domain.NoteType.TEXT)
        assertEquals(listOf("C", "B", "A"), db.notesOf(folder.id).sortedBy { it.sortOrder }.map { it.title })
        assertTrue(c.sortOrder < b.sortOrder && b.sortOrder < a.sortOrder)
    }

    @Test
    fun folderNamesAreUniqueIgnoringCaseAndCyrillic() {
        editor.createFolder("Работа", "#3366FF", "SOFT")
        assertEquals(EditError.DUPLICATE_FOLDER_NAME, error { editor.createFolder(" работа ", "#3366FF", "SOFT") })
        assertEquals(EditError.BLANK_NAME, error { editor.createFolder("  ", "#3366FF", "SOFT") })
        assertEquals(EditError.INVALID_COLOR, error { editor.createFolder("A", "blue", "SOFT") })
        assertEquals(EditError.INVALID_STYLE, error { editor.createFolder("A", "#3366FF", "FANCY") })
    }

    @Test
    fun theLastFolderStays() {
        val f = editor.createFolder("Only", "#3366FF", "SOFT")
        assertEquals(EditError.LAST_FOLDER_CANNOT_BE_DELETED, error { editor.deleteFolder(f.id, FolderDeleteMode.DELETE_NOTES) })
    }

    @Test
    fun folderGoesToTrashWithItsNotesAndComesBackWithExactlyThem() {
        val a = editor.createFolder("A", "#3366FF", "SOFT")
        editor.createFolder("B", "#3366FF", "SOFT")
        val n1 = editor.createNote(a.id, "one", NoteType.TEXT, "x")
        now = 2_000
        val n2 = editor.createNote(a.id, "two", NoteType.TEXT)
        editor.trashNote(n2.id) // trashed on its own before the folder
        now = 3_000
        editor.deleteFolder(a.id, FolderDeleteMode.DELETE_NOTES)
        assertEquals(3_000L, db.note(n1.id)!!.deletedAt)
        now = 4_000
        editor.restoreFolder(a.id)
        assertNull(db.note(n1.id)!!.deletedAt)
        assertEquals(2_000L, db.note(n2.id)!!.deletedAt) // it was not trashed with the folder
    }

    @Test
    fun deleteFolderMovingNotes() {
        val a = editor.createFolder("A", "#3366FF", "SOFT")
        val b = editor.createFolder("B", "#3366FF", "SOFT")
        val n = editor.createNote(a.id, "one", NoteType.LIST, "- [ ] x")
        assertEquals(EditError.TARGET_FOLDER_REQUIRED, error { editor.deleteFolder(a.id, FolderDeleteMode.MOVE_NOTES) })
        editor.deleteFolder(a.id, FolderDeleteMode.MOVE_NOTES, b.id)
        assertEquals(b.id, db.note(n.id)!!.folderId)
        assertNull(db.note(n.id)!!.deletedAt)
    }

    @Test
    fun marksDoNotMoveModifiedButEditsDo() {
        val f = editor.createFolder("A", "#3366FF", "SOFT")
        val n = editor.createNote(f.id, "t", NoteType.TEXT, "a")
        now = 5_000
        val pinned = editor.saveNote(n.copy(isPinned = true, isTitleBold = true))
        assertEquals(1_000L, pinned.updatedAt)
        assertEquals(5_000L, pinned.pinnedAt)
        now = 6_000
        assertEquals(6_000L, editor.saveNote(pinned.copy(contentText = "b")).updatedAt)
        assertEquals(EditError.BLANK_TITLE, error { editor.saveNote(pinned.copy(title = " ")) })
    }

    @Test
    fun budgetNoteGetsItsRootGroup() {
        val f = editor.createFolder("A", "#3366FF", "SOFT")
        val n = editor.createNote(f.id, "Money", NoteType.BUDGET)
        val nodes = db.budgetNodesOf(n.id)
        assertEquals(1, nodes.size)
        assertNull(nodes.single().parentId)
    }

    @Test
    fun trashingClearsTheFeaturedLinkAndPurgeRemovesFiles() {
        val f = editor.createFolder("A", "#3366FF", "SOFT")
        val n = editor.createNote(f.id, "pic", NoteType.DRAWING)
        db.upsertFolder(db.folders().single().copy(featuredNoteId = n.id, featuredMetricsMask = 7))
        files.write(n.id, "drawing.png", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        editor.trashNote(n.id)
        assertNull(db.folders().single().featuredNoteId)
        assertEquals(EditError.NOT_IN_TRASH, error { editor.purgeFolder(f.id) })
        editor.purgeNote(n.id)
        assertNull(db.note(n.id))
        assertTrue(files.list(n.id).isEmpty())
    }

    @Test
    fun restoreIntoAnotherFolderWhenItsOwnIsTrashed() {
        val a = editor.createFolder("A", "#3366FF", "SOFT")
        val b = editor.createFolder("B", "#3366FF", "SOFT")
        val n = editor.createNote(a.id, "one", NoteType.TEXT)
        editor.deleteFolder(a.id, FolderDeleteMode.DELETE_NOTES)
        assertEquals(EditError.FOLDER_NOT_FOUND, error { editor.restoreNote(n.id) })
        editor.restoreNote(n.id, b.id)
        assertEquals(b.id, db.note(n.id)!!.folderId)
        assertEquals(1, editor.emptyTrash())
        assertNotNull(db.folders().firstOrNull { it.id == b.id })
    }

    @Test
    fun tagsFollowThePhoneRules() {
        val f = editor.createFolder("A", "#3366FF", "SOFT")
        val n = editor.createNote(f.id, "t", NoteType.TEXT)
        val work = editor.createTag("  #Работа   поездка ")
        assertEquals("Работа поездка", work.name)
        assertEquals(EditError.DUPLICATE_TAG_NAME, error { editor.createTag("работа ПОЕЗДКА") })
        assertEquals(EditError.INVALID_TAG_NAME, error { editor.createTag("#") })
        assertEquals(work.id, editor.getOrCreateTag("#работа поездка").id)
        editor.setNoteTags(n.id, setOf(work.id))
        assertEquals(listOf("Работа поездка"), db.tagsOf(n.id).map { it.name })
        editor.deleteTag(work.id)
        assertTrue(db.tagsOf(n.id).isEmpty())
    }

    @Test
    fun dragAndDropKeepsTheOwnOrderWithoutTouchingTheDates() {
        val f = editor.createFolder("A", "#3366FF", "SOFT")
        val g = editor.createFolder("B", "#3366FF", "SOFT")
        val n1 = editor.createNote(f.id, "1", NoteType.TEXT)
        val n2 = editor.createNote(f.id, "2", NoteType.TEXT)
        val n3 = editor.createNote(f.id, "3", NoteType.TEXT)
        now = 9_000
        editor.reorderNotes(listOf(n3.id, n1.id, n2.id))
        assertEquals(listOf("3", "1", "2"), db.notesOf(f.id).sortedBy { it.sortOrder }.map { it.title })
        assertEquals(n1.updatedAt, db.note(n1.id)!!.updatedAt)
        editor.reorderFolders(listOf(g.id, f.id))
        assertEquals(listOf("B", "A"), db.folders().sortedBy { it.sortOrder }.map { it.name })
    }
}
