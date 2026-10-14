package com.notio.shared.assistant

import com.notio.shared.SequentialIds
import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.DbBudgets
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.NotioEditor
import com.notio.shared.domain.BudgetEditor
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.Clock
import com.notio.shared.domain.DomainResult
import com.notio.shared.domain.FolderKind
import com.notio.shared.domain.ListDocument
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.TaskDocument
import com.notio.shared.domain.TaskState
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The commands of every right (D147; docs/ASSISTANT_PLAN_RU.md, sections 5 and 9): tags, colour and pin; lists; tasks; a budget;
 * reminders; folders; how the app looks and its settings - each done, written into the journal and rolled back; and the yes of
 * the person before many notes at once and before the sums of a budget.
 */
class AssistantAllRightsTest {
    private val dir = Files.createTempDirectory("notio-assistant-all").toFile()
    private var now = 1_000L
    private val clock = Clock { now++ }
    private val ids = SequentialIds()
    private val db = NotioDatabase.inMemory()
    private val editor = NotioEditor(db, AttachmentStore(dir), clock, ids)
    private val reminders = LinkedHashMap<String, Pair<String, Long>>()
    private val settings = linkedMapOf("theme" to "light", "preview_lines" to "2")
    private val host = object : AssistantHost {
        override fun reminders(noteId: String) = reminders.filterValues { it.first == noteId }.map { it.key to it.value.second }
        override fun addReminder(noteId: String, at: Long): String? = if (at < 0) null else ("r" + (reminders.size + 1)).also { reminders[it] = noteId to at }
        override fun removeReminder(reminderId: String) { reminders.remove(reminderId) }
        override fun settings(appearance: Boolean) = if (appearance) mapOf("theme" to (settings.getValue("theme") to "light, dark, system")) else mapOf("preview_lines" to (settings.getValue("preview_lines") to "0, 1, 2"))
        override fun setting(name: String) = settings[name]
        override fun setSetting(name: String, value: String): String? {
            val allowed = if (name == "theme") setOf("light", "dark", "system") else setOf("0", "1", "2")
            if (value !in allowed) return "The value must be one of: " + allowed.joinToString(", ") + "."
            settings[name] = value
            return null
        }
    }
    private val journal = AssistantJournal(db, clock, ids, host)
    private val commands = AssistantCommands(db, editor, journal, { AssistantRight.entries.toSet() }, host, zone = { ZoneOffset.UTC })

    private val work = editor.createFolder("Work", "#3366FF", "SOFT")
    private val tasks = editor.createFolder("Tasks", "#3366FF", "SOFT", null, FolderKind.TASKS)
    private val report = editor.createNote(work.id, "Report", NoteType.TEXT, "the figures")
    private val shopping = editor.createNote(work.id, "Shopping", NoteType.LIST, "")
    private val money = editor.createNote(work.id, "Money", NoteType.BUDGET)
    private val call = editor.createNote(tasks.id, "Call the bank", NoteType.TASK, "about the card")

    init { db.setAssistantAccess(listOf(work.id, tasks.id, report.id, shopping.id, money.id, call.id), AssistantAccess.ASSISTANT) }

    @AfterTest
    fun cleanUp() {
        db.close()
        dir.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v); is Int -> put(k, v); is Boolean -> put(k, v); is Double -> put(k, v)
                is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it.toString())) } })
            }
        }
    }
    private fun JsonObject.error() = (this["error"] as? JsonPrimitive)?.content
    private fun session(confirmed: Boolean = false) = journal.begin(Channel.CHAT, "do it", confirmed)
    private fun call(name: String, args: JsonObject = args(), s: AssistantJournal.Session = session()) = commands.call(s, Channel.CHAT, name, args)
    private fun done(name: String, args: JsonObject, s: AssistantJournal.Session): JsonObject = call(name, args, s).also { assertNull(it.error(), name) }

    @Test
    fun tagsColourAndPinAreSetAndRolledBack() {
        editor.setNoteTags(report.id, setOf(editor.getOrCreateTag("old").id))
        val s = session()
        done("set_note_tags", args("note_id" to report.id, "tags" to listOf("bank", "#urgent")), s)
        done("set_note_color", args("note_id" to report.id, "color" to "#ff8800"), s)
        done("pin_note", args("note_id" to report.id, "pinned" to true), s)
        assertEquals(listOf("bank", "urgent"), db.tagsOf(report.id).map { it.name })
        assertEquals("#FF8800", db.note(report.id)!!.colorHex)
        assertTrue(db.note(report.id)!!.isPinned)
        assertEquals(listOf("bank", "urgent"), call("read_note", args("note_id" to report.id)).getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        assertNotNull(call("set_note_color", args("note_id" to report.id, "color" to "orange")).error())

        assertTrue(journal.rollback(assertNotNull(journal.finish(s)).id).done)
        assertEquals(listOf("old"), db.tagsOf(report.id).map { it.name })
        assertNull(db.note(report.id)!!.colorHex)
        assertFalse(db.note(report.id)!!.isPinned)
    }

    @Test
    fun theItemsOfAListAreAddedTickedRemovedAndRolledBack() {
        val s = session()
        val milk = done("list_add_item", args("note_id" to shopping.id, "text" to "milk"), s).getValue("item_id").jsonPrimitive.content
        val bread = done("list_add_item", args("note_id" to shopping.id, "text" to "bread"), s).getValue("item_id").jsonPrimitive.content
        done("list_check_item", args("note_id" to shopping.id, "item_id" to milk, "done" to true), s)
        done("list_remove_item", args("note_id" to shopping.id, "item_id" to bread), s)
        val items = call("read_list", args("note_id" to shopping.id)).getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("milk" to true), items.map { it.getValue("text").jsonPrimitive.content to it.getValue("done").jsonPrimitive.content.toBoolean() })
        // The note keeps its structure as the app itself writes it.
        val stored = db.note(shopping.id)!!
        assertEquals(listOf("milk"), ListDocument.fromNote(stored.contentText, stored.extras).entries.map { it.text })
        // A command of a list on a note that is not a list, and an item that is not there.
        assertNotNull(call("list_add_item", args("note_id" to report.id, "text" to "x")).error())
        assertNotNull(call("list_check_item", args("note_id" to shopping.id, "item_id" to "nope", "done" to true)).error())

        assertTrue(journal.rollback(assertNotNull(journal.finish(s)).id).done)
        val back = db.note(shopping.id)!!
        assertEquals(0, ListDocument.fromNote(back.contentText, back.extras).entries.size)
    }

    @Test
    fun aTaskIsMadeChangedAndRolledBack() {
        val s = session()
        val made = done("create_task", args("folder_id" to tasks.id, "title" to "Pay the bill", "description" to "by Friday"), s).getValue("note_id").jsonPrimitive.content
        assertEquals(NoteType.TASK, db.note(made)!!.type)
        done("set_task", args("note_id" to call.id, "status" to "done", "start" to "2026-10-14T09:00", "end" to "2026-10-14T10:30", "priority" to 2, "comment" to "morning"), s)
        val task = TaskDocument.decode(db.note(call.id)!!.extras)
        assertEquals(TaskState.DONE, task.state)
        assertEquals(java.time.LocalDateTime.parse("2026-10-14T09:00").toInstant(ZoneOffset.UTC).toEpochMilli(), task.start)
        assertEquals(2, task.priority)
        assertEquals("morning", task.comment)
        val read = call("read_note", args("note_id" to call.id))
        assertEquals("done", read.getValue("status").jsonPrimitive.content)
        assertEquals("2026-10-14T10:30", read.getValue("end").jsonPrimitive.content)
        // An end before the start, a status that is none, a task in a folder of notes: refused.
        assertNotNull(call("set_task", args("note_id" to call.id, "end" to "2026-10-13T00:00")).error())
        assertNotNull(call("set_task", args("note_id" to call.id, "status" to "flying")).error())
        assertNotNull(call("create_task", args("folder_id" to work.id, "title" to "X")).error())

        assertTrue(journal.rollback(assertNotNull(journal.finish(s)).id).done)
        assertNull(TaskDocument.decode(db.note(call.id)!!.extras).start)
        assertNotNull(db.note(made)!!.deletedAt)
    }

    @Test
    fun theSumsOfABudgetNeedTheYesOfThePersonAndAreRolledBack() {
        val budget = BudgetEditor(DbBudgets(db), clock, ids, DbBudgets(db))
        val root = db.budgetNodesOf(money.id).single { it.parentId == null }
        val bread = (budget.addNode(money.id, root.id, "Bread", BudgetNodeKind.LEAF, sumMinor = 5_000) as DomainResult.Success).value
        // Reading needs no yes.
        val items = call("read_budget", args("note_id" to money.id)).getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(50.0, items.first { it.getValue("title").jsonPrimitive.content == "Bread" }.getValue("sum").jsonPrimitive.content.toDouble())

        // Without the yes the sum stays, and the request says that it waits for the person.
        val asking = session()
        assertNotNull(call("budget_change_sum", args("note_id" to money.id, "item_id" to bread.id, "mode" to "add", "amount" to 12.5), asking).error())
        assertTrue(asking.needsConfirm)
        assertEquals(5_000L, db.budgetNodesOf(money.id).first { it.id == bread.id }.manualSumMinor)
        assertNull(journal.finish(asking))

        val s = session(confirmed = true)
        done("budget_change_sum", args("note_id" to money.id, "item_id" to bread.id, "mode" to "add", "amount" to 12.5), s)
        val milk = done("budget_add_item", args("note_id" to money.id, "title" to "Milk", "sum" to 70), s).getValue("item_id").jsonPrimitive.content
        assertEquals(6_250L, db.budgetNodesOf(money.id).first { it.id == bread.id }.manualSumMinor)
        assertEquals(13_250L, db.budgetNodesOf(money.id).single { it.parentId == null }.cachedSumMinor)
        assertNotNull(call("budget_change_sum", args("note_id" to money.id, "item_id" to bread.id, "mode" to "double", "amount" to 1), session(true)).error())

        assertTrue(journal.rollback(assertNotNull(journal.finish(s)).id).done)
        val back = db.budgetNodesOf(money.id)
        assertEquals(5_000L, back.first { it.id == bread.id }.manualSumMinor)
        assertTrue(back.none { it.id == milk })
        assertEquals(5_000L, back.single { it.parentId == null }.cachedSumMinor)
    }

    @Test
    fun aBudgetThatHidesItsAmountsGivesNoneAndIsNotChanged() {
        editor.saveNote(db.note(money.id)!!.copy(extras = """{"hideSums":true}"""), withExtras = true)
        assertNotNull(call("read_budget", args("note_id" to money.id)).error())
        assertNotNull(call("budget_add_item", args("note_id" to money.id, "title" to "Milk"), session(true)).error())
    }

    @Test
    fun remindersFoldersAndSettingsAreMadeAndRolledBack() {
        val s = session()
        val reminder = done("add_reminder", args("note_id" to report.id, "at" to "2026-10-20T08:00"), s).getValue("reminder_id").jsonPrimitive.content
        assertEquals("2026-10-20T08:00", call("list_reminders", args("note_id" to report.id)).getValue("reminders").jsonArray.single().jsonObject.getValue("at").jsonPrimitive.content)
        val made = done("create_folder", args("name" to "Bills"), s).getValue("folder_id").jsonPrimitive.content
        // The assistant sees the folder it made, and can put a note into it.
        assertEquals(AssistantAccess.ASSISTANT, db.assistantAccess()[made])
        done("move_note", args("note_id" to report.id, "folder_id" to made), s)
        done("rename_folder", args("folder_id" to work.id, "name" to "Office"), s)
        done("set_appearance", args("name" to "theme", "value" to "dark"), s)
        done("set_setting", args("name" to "preview_lines", "value" to "0"), s)
        assertEquals("dark", settings["theme"])
        assertEquals("Office", db.folders().first { it.id == work.id }.name)
        // A value that is not allowed, a setting that is not offered, a setting of the other right.
        assertNotNull(call("set_appearance", args("name" to "theme", "value" to "pink")).error())
        assertNotNull(call("set_setting", args("name" to "trash_days", "value" to "1")).error())
        assertNotNull(call("set_setting", args("name" to "theme", "value" to "dark")).error())
        assertEquals(listOf("theme"), call("list_appearance").getValue("settings").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })

        assertTrue(journal.rollback(assertNotNull(journal.finish(s)).id).done)
        assertTrue(reminder !in reminders)
        assertEquals("light", settings["theme"])
        assertEquals("2", settings["preview_lines"])
        assertEquals("Work", db.folders().first { it.id == work.id }.name)
        assertEquals(work.id, db.note(report.id)!!.folderId)
        // The folder the request made is in the trash, not gone.
        assertNotNull(db.folders().first { it.id == made }.deletedAt)
    }

    @Test
    fun aRequestThatChangesManyNotesStopsForTheYesOfThePerson() {
        val notes = (1..8).map { n -> editor.createNote(work.id, "Note $n", NoteType.TEXT, "").also { db.setAssistantAccess(listOf(it.id), AssistantAccess.ASSISTANT) } }
        val s = session()
        val results = notes.map { call("rename_note", args("note_id" to it.id, "title" to it.title + " new"), s) }
        // Five are done, the sixth is refused and the request waits; the notes after it stay as they were.
        assertEquals(AssistantCommands.MASS_LIMIT, results.count { it.error() == null })
        assertTrue(s.needsConfirm)
        assertEquals("Note 6", db.note(notes[5].id)!!.title)
        // Reading is never stopped by it.
        assertNull(call("list_folders", s = s).error())
        assertNotNull(journal.finish(s))

        // With the yes the whole request goes through.
        val confirmed = session(confirmed = true)
        notes.drop(5).forEach { done("rename_note", args("note_id" to it.id, "title" to it.title + " new"), confirmed) }
        assertFalse(confirmed.needsConfirm)
        assertEquals("Note 8 new", db.note(notes[7].id)!!.title)
    }
}
