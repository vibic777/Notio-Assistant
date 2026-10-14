package com.notio.shared.assistant

import com.notio.shared.SequentialIds
import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.NotioEditor
import com.notio.shared.domain.Clock
import com.notio.shared.domain.NoteType
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The commands of the assistant, its two checks and its journal with rollback (D145; docs/ASSISTANT_PLAN_RU.md): without a
 * model - the commands are called as a model would call them.
 */
class AssistantCommandsTest {
    private val dir = Files.createTempDirectory("notio-assistant").toFile()
    private var now = 1_000L
    private val clock = Clock { now++ }
    private val ids = SequentialIds()
    private val db = NotioDatabase.inMemory()
    private val editor = NotioEditor(db, AttachmentStore(dir), clock, ids)
    private var rights = AssistantRight.entries.toSet()
    private val sorts = HashMap<String, String?>()
    private var locked = false
    private val host = object : AssistantHost {
        override fun folderSort(folderId: String) = sorts[folderId]
        override fun setFolderSort(folderId: String, sort: String?) { sorts[folderId] = sort }
        override fun locked() = locked
    }
    private val journal = AssistantJournal(db, clock, ids, host)
    private val commands = AssistantCommands(db, editor, journal, { rights }, host)

    private val work = editor.createFolder("Work", "#3366FF", "SOFT")
    private val home = editor.createFolder("Home", "#3366FF", "SOFT")
    private val report = editor.createNote(work.id, "Report", NoteType.TEXT, "the figures of March")
    private val passwords = editor.createNote(work.id, "Passwords", NoteType.TEXT, "the secret word")
    private val shopping = editor.createNote(home.id, "Shopping", NoteType.TEXT, "- milk")

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun open(vararg idsToOpen: String, flags: Int = AssistantAccess.ASSISTANT) = db.setAssistantAccess(idsToOpen.toList(), flags)
    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> when (v) { is String -> put(k, v); is Int -> put(k, v); is Boolean -> put(k, v) } }
    }
    private fun JsonObject.error() = (this["error"] as? JsonPrimitive)?.content
    private fun JsonObject.titles() = (this["notes"] as JsonArray).map { it.jsonObject.getValue("title").jsonPrimitive.content }
    private fun session(channel: Channel = Channel.CHAT, request: String = "do it") = journal.begin(channel, request)
    private fun call(name: String, args: JsonObject = args(), channel: Channel = Channel.CHAT, s: AssistantJournal.Session = session(channel)) =
        commands.call(s, channel, name, args)

    @Test
    fun nothingIsOpenUntilThePersonOpensIt() {
        // Every note starts closed: the assistant sees no folder and no note, and a closed note "is not there".
        assertEquals(0, call("list_folders").getValue("folders").jsonArray.size)
        assertNotNull(call("read_note", args("note_id" to report.id)).error())
        assertNotNull(call("list_notes", args("folder_id" to work.id)).error())
        assertEquals(emptyList(), call("search_notes", args("query" to "secret")).titles())

        // The folder and one of its two notes are opened: the other is neither listed, nor counted, nor found, nor read.
        open(work.id, report.id)
        val folders = call("list_folders").getValue("folders").jsonArray
        assertEquals(listOf("Work"), folders.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(1, folders.single().jsonObject.getValue("notes").jsonPrimitive.content.toInt())
        assertEquals(listOf("Report"), call("list_notes", args("folder_id" to work.id)).titles())
        assertEquals("the figures of March", call("read_note", args("note_id" to report.id)).getValue("text").jsonPrimitive.content)
        assertEquals("There is no note with this id.", call("read_note", args("note_id" to passwords.id)).error())
        assertEquals(emptyList(), call("search_notes", args("query" to "secret")).titles())
        assertNotNull(call("rename_note", args("note_id" to passwords.id, "title" to "Mine now")).error())
        assertEquals("Passwords", db.note(passwords.id)!!.title)
        // An open note in a folder that is not open is not reached through the folder, and a note is not moved into a closed folder.
        open(shopping.id)
        assertNotNull(call("list_notes", args("folder_id" to home.id)).error())
        assertNotNull(call("move_note", args("note_id" to report.id, "folder_id" to home.id)).error())
        assertEquals(work.id, db.note(report.id)!!.folderId)
    }

    @Test
    fun aRightThatIsOffStopsTheCommandAndHidesItFromTheModel() {
        open(work.id, report.id)
        rights = emptySet()
        assertEquals(emptyList(), commands.tools())
        assertNotNull(call("list_folders").error())

        rights = setOf(AssistantRight.READ)
        assertEquals(setOf("list_folders", "list_notes", "search_notes", "read_note", "read_list", "read_budget"), commands.tools().map { it.name }.toSet())
        assertNull(call("list_folders").error())
        assertNotNull(call("rename_note", args("note_id" to report.id, "title" to "X")).error())
        assertNotNull(call("trash_note", args("note_id" to report.id)).error())
        assertEquals("Report", db.note(report.id)!!.title)
        // Nothing gives what is never given: there is no such command at all.
        rights = AssistantRight.entries.toSet()
        listOf("purge_note", "empty_trash", "set_rights", "set_access", "set_lock").forEach { assertNotNull(call(it).error()) }
        assertTrue(commands.tools().none { it.name.contains("purge") || it.name.contains("right") || it.name.contains("access") })
    }

    @Test
    fun aLockedAppDoesNothingAndARequestHasALimit() {
        open(work.id, report.id)
        locked = true
        assertNotNull(call("list_folders").error())
        locked = false
        val s = session()
        repeat(AssistantCommands.MAX_CALLS) { assertNull(call("list_folders", s = s).error()) }
        assertNotNull(call("list_folders", s = s).error())
    }

    @Test
    fun aHiddenNoteGivesItsTitleOnly() {
        open(work.id, passwords.id)
        editor.setNoteHidden(passwords.id, true)
        val read = call("read_note", args("note_id" to passwords.id))
        assertEquals("Passwords", read.getValue("title").jsonPrimitive.content)
        assertNull(read["text"])
        assertEquals(emptyList(), call("search_notes", args("query" to "secret")).titles())
        assertEquals(listOf("Passwords"), call("search_notes", args("query" to "passw")).titles())
        assertNotNull(call("set_note_text", args("note_id" to passwords.id, "text" to "gone")).error())
        assertEquals("the secret word", db.note(passwords.id)!!.contentText)
    }

    @Test
    fun telegramAndAnOutsideModelSeeOnlyWhatIsOpenedToThem() {
        open(work.id, flags = AssistantAccess.ASSISTANT or AssistantAccess.TELEGRAM or AssistantAccess.EXTERNAL)
        open(report.id) // the assistant only
        open(passwords.id, flags = AssistantAccess.ASSISTANT or AssistantAccess.TELEGRAM)
        val telegram = Channel.TELEGRAM
        val outside = Channel("chat", externalModel = true)
        val both = Channel("telegram", telegram = true, externalModel = true)
        assertEquals(listOf("Passwords", "Report"), call("list_notes", args("folder_id" to work.id)).titles().sorted())
        assertEquals(listOf("Passwords"), call("list_notes", args("folder_id" to work.id), telegram).titles())
        assertEquals(emptyList(), call("list_notes", args("folder_id" to work.id), outside).titles())
        // Telegram with an outside model needs both.
        assertEquals(emptyList(), call("list_notes", args("folder_id" to work.id), both).titles())
        open(passwords.id, flags = AssistantAccess.ASSISTANT or AssistantAccess.TELEGRAM or AssistantAccess.EXTERNAL)
        assertEquals(listOf("Passwords"), call("list_notes", args("folder_id" to work.id), both).titles())
        // "Reminders to Telegram" alone opens nothing.
        open(shopping.id, flags = AssistantAccess.REMINDERS_TELEGRAM)
        assertNotNull(call("read_note", args("note_id" to shopping.id), telegram).error())

        // A note made through Telegram is open to Telegram, so that the assistant sees what it made.
        val made = call("create_note", args("folder_id" to work.id, "title" to "From the phone"), telegram).getValue("note_id").jsonPrimitive.content
        assertEquals(AssistantAccess.ASSISTANT or AssistantAccess.TELEGRAM, db.assistantAccess()[made])
    }

    @Test
    fun oneRequestIsOneRecordAndARollbackPutsEverythingBack() {
        open(work.id, home.id, report.id, shopping.id)
        val s = session(request = "tidy up")
        assertNull(call("rename_note", args("note_id" to report.id, "title" to "Report of March"), s = s).error())
        assertNull(call("append_note_text", args("note_id" to report.id, "text" to "and of April"), s = s).error())
        assertNull(call("move_note", args("note_id" to report.id, "folder_id" to home.id), s = s).error())
        assertNull(call("trash_note", args("note_id" to shopping.id), s = s).error())
        val made = call("create_note", args("folder_id" to home.id, "title" to "Plan", "text" to "one"), s = s).getValue("note_id").jsonPrimitive.content
        assertNull(call("sort_notes", args("folder_id" to home.id, "by" to "name"), s = s).error())
        assertEquals("NAME:asc", sorts[home.id])
        val entry = assertNotNull(journal.finish(s))

        assertEquals("tidy up", entry.request)
        assertEquals("chat", entry.source)
        assertEquals(6, entry.body.ops.size)
        // Each note is kept once, as it was before the first of its changes.
        assertEquals(setOf(report.id, shopping.id), entry.body.before.map { it.id }.toSet())
        assertEquals("Report", entry.body.before.first { it.id == report.id }.title)
        assertEquals("the figures of March", entry.body.before.first { it.id == report.id }.contentText)
        assertEquals(listOf(made), entry.body.created)
        assertEquals("the figures of March\nand of April", db.note(report.id)!!.contentText)
        assertNotNull(db.note(shopping.id)!!.deletedAt)

        assertTrue(journal.rollback(entry.id).done)

        val back = db.note(report.id)!!
        assertEquals("Report", back.title)
        assertEquals("the figures of March", back.contentText)
        assertEquals(work.id, back.folderId)
        assertNull(db.note(shopping.id)!!.deletedAt)
        // What the request made is in the trash, not gone; the sort is the one the folder had (none of its own).
        assertNotNull(db.note(made)!!.deletedAt)
        assertNull(sorts[home.id])
        assertTrue(journal.entries().single().rolledBack)
        // Once only.
        assertTrue(journal.rollback(entry.id).missing)
    }

    @Test
    fun aNoteThePersonChangedAfterTheAssistantStopsTheRollback() {
        open(work.id, report.id)
        val s = session()
        call("rename_note", args("note_id" to report.id, "title" to "By the assistant"), s = s)
        val entry = assertNotNull(journal.finish(s))
        // The person edits the note after the assistant.
        editor.saveNote(db.note(report.id)!!.copy(contentText = "written by me"))

        val stopped = journal.rollback(entry.id)
        assertFalse(stopped.done)
        assertEquals(listOf("By the assistant"), stopped.conflicts)
        assertEquals("written by me", db.note(report.id)!!.contentText)
        assertFalse(journal.entries().single().rolledBack)

        assertTrue(journal.rollback(entry.id, force = true).done)
        assertEquals("Report", db.note(report.id)!!.title)
        assertEquals("the figures of March", db.note(report.id)!!.contentText)
    }

    @Test
    fun readingWritesNoRecordAndTheJournalKeepsItsNewestRecords() {
        open(work.id, report.id)
        val reading = session()
        call("list_folders", s = reading)
        call("read_note", args("note_id" to report.id), s = reading)
        assertNull(journal.finish(reading))
        assertEquals(0, journal.entries().size)

        val small = AssistantJournal(db, clock, ids, host, keep = 2)
        val few = AssistantCommands(db, editor, small, { rights }, host)
        repeat(3) { n ->
            val s = small.begin(Channel.CHAT, "request $n")
            few.call(s, Channel.CHAT, "rename_note", args("note_id" to report.id, "title" to "Name $n"))
            small.finish(s)
        }
        assertEquals(listOf("request 2", "request 1"), small.entries().map { it.request })
        small.clear()
        assertEquals(0, small.entries().size)
    }

    @Test
    fun whatAModelSendsWrongIsAnErrorItCanRead() {
        open(work.id, report.id)
        assertNotNull(call("read_note").error())
        assertNotNull(call("rename_note", args("note_id" to report.id, "title" to "   ")).error())
        assertNotNull(call("sort_notes", args("folder_id" to work.id, "by" to "weight")).error())
        assertNotNull(call("no_such_command").error())
        assertEquals("Report", db.note(report.id)!!.title)
    }

    /** D150: the kept chat - lines in their order, a line changed in place, the newest kept, one deleted, all deleted. */
    @Test
    fun theChatIsKeptBesideTheNotes() {
        db.putAssistantChat("a", 1, "first", keep = 3)
        db.putAssistantChat("b", 2, "second", keep = 3)
        db.putAssistantChat("a", 1, "first, rolled back", keep = 3)
        assertEquals(listOf("a" to "first, rolled back", "b" to "second"), db.assistantChat())
        db.putAssistantChat("c", 3, "third", keep = 3)
        db.putAssistantChat("d", 4, "fourth", keep = 3)
        assertEquals(listOf("b", "c", "d"), db.assistantChat().map { it.first })
        db.deleteAssistantChat("c")
        assertEquals(listOf("b", "d"), db.assistantChat().map { it.first })
        db.deleteAssistantChat(null)
        assertEquals(emptyList(), db.assistantChat())
    }
}
