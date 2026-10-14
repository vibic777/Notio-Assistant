package com.notio.shared.assistant

import com.notio.shared.SequentialIds
import com.notio.shared.data.AttachmentStore
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.NotioEditor
import com.notio.shared.domain.Clock
import com.notio.shared.domain.NoteType
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Telegram bot of the assistant (D148; the plan, section 6): Telegram is played by a server of the test, the model by the
 * test. Whom the bot listens to, what it sees, `/undo` and `/yes`.
 */
class TelegramBotTest {
    private val dir = Files.createTempDirectory("notio-bot").toFile()
    private var now = 1_000L
    private val clock = Clock { now++ }
    private val ids = SequentialIds()
    private val db = NotioDatabase.inMemory()
    private val editor = NotioEditor(db, AttachmentStore(dir), clock, ids)
    private val journal = AssistantJournal(db, clock, ids)
    private val commands = AssistantCommands(db, editor, journal, { AssistantRight.entries.toSet() })
    private val work = editor.createFolder("Work", "#3366FF", "SOFT")
    private val open = editor.createNote(work.id, "For Telegram", NoteType.TEXT, "hello")
    private val local = editor.createNote(work.id, "Computer only", NoteType.TEXT, "private")

    private val sent = ArrayList<Pair<Long, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        fun answer(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        createContext("/botKEY/getMe") { answer(it, 200, """{"ok":true,"result":{"id":1,"username":"my_notio_bot"}}""") }
        createContext("/botKEY/getUpdates") { ex ->
            ex.requestBody.readBytes()
            answer(ex, 200, """{"ok":true,"result":[{"update_id":7,"message":{"chat":{"id":42,"type":"private"},"from":{"id":42,"first_name":"Vic","last_name":"B","username":"vic"},"text":"hi"}},{"update_id":8,"message":{"chat":{"id":-5,"type":"group"},"from":{"id":9},"text":"x"}}]}""")
        }
        createContext("/botKEY/sendMessage") { ex ->
            val body = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
            synchronized(sent) { sent += body.getValue("chat_id").jsonPrimitive.content.toLong() to body.getValue("text").jsonPrimitive.content }
            answer(ex, 200, """{"ok":true,"result":{}}""")
        }
        createContext("/") { answer(it, 404, """{"ok":false,"description":"Not Found"}""") }
        start()
    }
    private val api = TelegramApi("KEY", "http://127.0.0.1:${server.address.port}")

    private var owner: Long? = null
    private var ownerName = ""
    private var code = "123456"
    private var codeUntil = Long.MAX_VALUE
    private var time = 0L
    private var strangers = 0
    private var locked = false
    private var answerWhenLocked = false
    private var outsideModel = false
    private var outsideAllowed = false
    /** What the model does with a request: the calls it asks for first, then its words. */
    private var script: (String) -> List<ChatMessage> = { listOf(ChatMessage("assistant", "ok")) }
    private val asked = ArrayList<Triple<String, Channel, Boolean>>()

    private val bot = TelegramBot(
        api,
        settings = { BotSettings(owner, code, codeUntil, answerWhenLocked, outsideAllowed, outsideModel, ready = true) },
        bind = { id, name -> owner = id; ownerName = name; code = "" },
        words = { word, arg -> word.name + (if (arg.isEmpty()) "" else ":$arg") },
        locked = { locked },
        ask = { history, request, channel, confirmed ->
            asked += Triple(request, channel, confirmed)
            val steps = script(request).iterator()
            val model = object : ModelClient {
                override fun models() = emptyList<String>()
                override fun chat(messages: List<ChatMessage>, tools: List<AssistantTool>) = steps.next()
            }
            AssistantAgent(commands, journal, model).ask(history, request, channel, confirmed)
        },
        journal = journal,
        burnCode = { code = "" },
        stranger = { strangers++ },
        clock = { time },
    )

    init {
        db.setAssistantAccess(listOf(work.id, open.id), AssistantAccess.ASSISTANT or AssistantAccess.TELEGRAM)
        db.setAssistantAccess(listOf(local.id), AssistantAccess.ASSISTANT)
    }

    @AfterTest
    fun cleanUp() {
        bot.stop()
        server.stop(0)
        db.close()
        dir.deleteRecursively()
    }

    private fun message(text: String, user: Long = 42, private: Boolean = true, name: String = "") = TelegramMessage(1, if (private) user else -5, user, text, private, name)
    private fun said() = synchronized(sent) { sent.map { it.second } }
    private fun calls(vararg calls: Pair<String, String>) = ChatMessage("assistant", null, calls.mapIndexed { i, (name, args) -> ToolCall("c$i", name, args) })

    @Test
    fun telegramIsAskedAndRead() {
        assertEquals("@my_notio_bot", api.me())
        val updates = api.updates(0, 0)
        assertEquals(listOf(TelegramMessage(7, 42, 42, "hi", true, "Vic B @vic"), TelegramMessage(8, -5, 9, "x", false)), updates)
        api.send(42, "a".repeat(9_000))
        assertEquals(listOf(4_000, 4_000, 1_000), said().map { it.length })
        // A wrong key: words for the person, and the key is in none of them.
        val wrong = assertFailsWith<TelegramException> { TelegramApi("SECRET-KEY", "http://127.0.0.1:${server.address.port}").me() }
        assertTrue(wrong.message!!.contains("key") && !wrong.message!!.contains("SECRET"))
    }

    @Test
    fun theBotListensToOnePersonBoundByTheCode() {
        // Nobody is bound: only the code is heard; anything else gets no answer at all.
        bot.handle(message("hello"))
        bot.handle(message("123456", private = false))
        assertEquals(emptyList(), said())
        bot.handle(message("123456", name = "Vic @vic"))
        assertEquals(42L, owner)
        assertEquals("Vic @vic", ownerName)
        assertEquals(listOf("BOUND"), said())
        // Another person, and a group: silence, and the model is not asked; Notio counts the person, not the group.
        bot.handle(message("show me the notes", user = 77))
        bot.handle(message("show me the notes", private = false))
        bot.handle(message("123456", user = 77))
        assertEquals(listOf("BOUND"), said())
        assertEquals(0, asked.size)
        assertEquals(42L, owner)
        assertEquals(2, strangers)
    }

    @Test
    fun theCodeBurnsAfterFiveWrongTriesOfDigits() {
        // Words and commands are not tries: the person may greet the bot first.
        repeat(10) { bot.handle(message("/start", user = 77)); bot.handle(message("hello", user = 77)) }
        assertEquals("123456", code)
        repeat(TelegramBot.CODE_TRIES - 1) { n -> bot.handle(message("00000$n", user = 77)) }
        assertEquals("123456", code)
        bot.handle(message("999999", user = 77))
        assertEquals("", code)
        // A burnt code is not heard, even when it is right.
        bot.handle(message("123456"))
        assertEquals(null, owner)
        // A new code starts its tries anew.
        code = "654321"
        repeat(TelegramBot.CODE_TRIES - 1) { bot.handle(message("111111", user = 77)) }
        bot.handle(message("654321"))
        assertEquals(42L, owner)
        assertEquals(listOf("BOUND"), said())
    }

    @Test
    fun theCodeWorksOnlyItsTime() {
        codeUntil = 600_000
        time = 600_001
        bot.handle(message("123456"))
        assertEquals(null, owner)
        assertEquals(emptyList(), said())
        time = 600_000
        bot.handle(message("123456"))
        assertEquals(42L, owner)
    }

    @Test
    fun theBoundAccountIsItsNumberNotItsName() {
        owner = 42
        script = { listOf(ChatMessage("assistant", "ok")) }
        // Somebody else who took the same name is a stranger; the person with a new name is still the person.
        bot.handle(message("what is there?", user = 77, name = "Vic @vic"))
        assertEquals(0, asked.size)
        assertEquals(1, strangers)
        bot.handle(message("what is there?", user = 42, name = "Victor @new_name"))
        assertEquals(1, asked.size)
    }

    @Test
    fun aRequestSeesOnlyWhatIsOpenToTelegramAndUndoRollsItBack() {
        owner = 42
        script = { listOf(calls("list_notes" to """{"folder_id":"${work.id}"}""", "rename_note" to """{"note_id":"${open.id}","title":"Renamed from the phone"}""", "rename_note" to """{"note_id":"${local.id}","title":"Stolen"}"""), ChatMessage("assistant", "Renamed.")) }
        bot.handle(message("rename it"))
        // The note opened to Telegram is changed; the one of the computer only "is not there".
        assertEquals("Renamed from the phone", db.note(open.id)!!.title)
        assertEquals("Computer only", db.note(local.id)!!.title)
        assertTrue(asked.single().second.telegram)
        assertEquals("Renamed.\n\nCHANGED:1", said().single())
        assertEquals("telegram", journal.entries().single().source)

        bot.handle(message("/undo"))
        assertEquals("For Telegram", db.note(open.id)!!.title)
        assertEquals("UNDONE:rename it", said().last())
        bot.handle(message("/undo"))
        assertEquals("NOTHING_TO_UNDO", said().last())
    }

    @Test
    fun aRequestThatNeedsAYesWaitsForIt() {
        owner = 42
        val notes = (1..7).map { n -> editor.createNote(work.id, "N$n", NoteType.TEXT, "").also { db.setAssistantAccess(listOf(it.id), AssistantAccess.ASSISTANT or AssistantAccess.TELEGRAM) } }
        script = { listOf(ChatMessage("assistant", null, notes.mapIndexed { i, n -> ToolCall("c$i", "trash_note", """{"note_id":"${n.id}"}""") }), ChatMessage("assistant", "Five are in the trash, two are left.")) }
        bot.handle(message("/yes"))
        assertEquals("NOTHING_TO_CONFIRM", said().last())
        bot.handle(message("trash them all"))
        assertEquals(5, notes.count { db.note(it.id)!!.deletedAt != null })
        assertTrue(said().last().endsWith("CONFIRM"))
        bot.handle(message("/yes"))
        assertEquals(7, notes.count { db.note(it.id)!!.deletedAt != null })
        assertEquals(listOf(false, true), asked.map { it.third })
    }

    @Test
    fun aLockedNotioAndAnOutsideModelStopTheBotUnlessThePersonChoseOtherwise() {
        owner = 42
        locked = true
        bot.handle(message("what is there?"))
        assertEquals(listOf("LOCKED"), said())
        assertEquals(0, asked.size)
        answerWhenLocked = true
        bot.handle(message("what is there?"))
        assertEquals(1, asked.size)
        locked = false

        outsideModel = true
        bot.handle(message("what is there?"))
        assertEquals("OUTSIDE_OFF", said().last())
        assertEquals(1, asked.size)
        outsideAllowed = true
        bot.handle(message("what is there?"))
        assertTrue(asked.last().second.externalModel && asked.last().second.telegram)
        bot.handle(message("/help"))
        assertEquals("HELP", said().last())
    }

    /** D151: through the bot program - the person knew the code, but only the people allowed in Notio are answered. */
    @Test
    fun throughTheBotProgramOnlyThePeopleAllowedInNotioAreAnswered() {
        var people = setOf<Long>()
        val knocked = ArrayList<Pair<Long, String>>()
        val relayed = TelegramBot(
            api,
            settings = { BotSettings(null, "", 0, false, false, false, ready = true, people = people) },
            bind = { _, _ -> error("no binding by a code through the program") },
            words = { word, _ -> word.name },
            locked = { false },
            ask = { history, request, channel, confirmed ->
                asked += Triple(request, channel, confirmed)
                val model = object : ModelClient {
                    override fun models() = emptyList<String>()
                    override fun chat(messages: List<ChatMessage>, tools: List<AssistantTool>) = ChatMessage("assistant", "ok")
                }
                AssistantAgent(commands, journal, model).ask(history, request, channel, confirmed)
            },
            journal = journal,
            knock = { id, name -> knocked += id to name },
        )
        relayed.handle(message("/start", user = 42, name = "Vic @vic"))
        relayed.handle(message("what is there?", user = 42, name = "Vic @vic"))
        assertEquals(listOf("PENDING", "PENDING"), said())
        assertEquals(listOf(42L to "Vic @vic", 42L to "Vic @vic"), knocked)
        assertEquals(0, asked.size)
        people = setOf(42)
        relayed.handle(message("/start", user = 42))
        assertEquals("HELP_RELAY", said().last())
        relayed.handle(message("what is there?", user = 42))
        assertEquals(1, asked.size)
        assertTrue(asked.single().second.telegram)
        // A reminder goes to every allowed person.
        people = setOf(42, 43)
        relayed.remind("Reminder")
        assertEquals(listOf(42L to "Reminder", 43L to "Reminder"), synchronized(sent) { sent.takeLast(2) })
    }

    /** D151: two allowed people do not share the talk, the request that waits for a yes, or /undo. */
    @Test
    fun twoPeopleThroughTheBotProgramAreKeptApart() {
        val histories = ArrayList<Pair<String, List<String?>>>()
        script = { request ->
            if (request.startsWith("rename")) listOf(calls("rename_note" to """{"note_id":"${open.id}","title":"By A"}"""), ChatMessage("assistant", "Renamed."))
            else listOf(ChatMessage("assistant", "ok"))
        }
        val relayed = TelegramBot(
            api,
            settings = { BotSettings(null, "", 0, false, false, false, ready = true, people = setOf(42, 43)) },
            bind = { _, _ -> },
            words = { word, _ -> word.name },
            locked = { false },
            ask = { history, request, channel, confirmed ->
                histories += request to history.map { it.content }
                val steps = script(request).iterator()
                val model = object : ModelClient {
                    override fun models() = emptyList<String>()
                    override fun chat(messages: List<ChatMessage>, tools: List<AssistantTool>) = steps.next()
                }
                AssistantAgent(commands, journal, model).ask(history, request, channel, confirmed)
            },
            journal = journal,
        )
        relayed.handle(message("rename the note, my secret is 1234", user = 42))
        assertEquals("By A", db.note(open.id)!!.title)
        // B gets none of A's words, and cannot roll back A's change.
        relayed.handle(message("hello", user = 43))
        assertEquals(emptyList(), histories.last().second)
        relayed.handle(message("/undo", user = 43))
        assertEquals("NOTHING_TO_UNDO", said().last())
        assertEquals("By A", db.note(open.id)!!.title)
        relayed.handle(message("/yes", user = 43))
        assertEquals("NOTHING_TO_CONFIRM", said().last())
        // A's own talk goes on, and A's /undo rolls back A's change.
        relayed.handle(message("and now?", user = 42))
        assertTrue(histories.last().second.contains("rename the note, my secret is 1234"))
        relayed.handle(message("/undo", user = 42))
        assertEquals("For Telegram", db.note(open.id)!!.title)
    }

    @Test
    fun theBotProgramIsAskedWithTheCodeOfNotio() {
        val got = ArrayList<String>()
        val program = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            fun answer(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            createContext("/notio/v1/") { ex ->
                val auth = ex.requestHeaders.getFirst("Authorization")
                val body = ex.requestBody.readBytes().decodeToString()
                if (auth != "Notio ABCD-EFGH-JKMN") return@createContext answer(ex, 401, """{"error":"unknown code"}""")
                synchronized(got) { got += ex.requestURI.toString() + " " + body }
                when (ex.requestURI.path) {
                    "/notio/v1/hello" -> answer(ex, 200, """{"ok":true,"bot":"@my_bot","name":"Home"}""")
                    "/notio/v1/updates" -> answer(ex, 200, """{"ok":true,"updates":[{"update_id":3,"chat_id":42,"user_id":42,"name":"Vic @vic","text":"hi"}]}""")
                    "/notio/v1/send" -> if (body.contains("\"chat_id\":77")) answer(ex, 403, "{}") else answer(ex, 200, """{"ok":true}""")
                    else -> answer(ex, 404, "{}")
                }
            }
            start()
        }
        try {
            val base = "http://127.0.0.1:${program.address.port}/"
            val relay = RelayApi(base, "ABCD-EFGH-JKMN")
            assertEquals("@my_bot (Home)", relay.me())
            assertEquals(listOf(TelegramMessage(3, 42, 42, "hi", true, "Vic @vic")), relay.updates(3, 0))
            relay.send(42, "answer")
            assertTrue(synchronized(got) { got.any { it.startsWith("/notio/v1/updates?offset=3&wait=0") } && got.last().contains("\"text\":\"answer\"") })
            assertFalse(assertFailsWith<TelegramException> { relay.send(77, "spam") }.fatal)
            // A code the program does not know stops the bot, and the code is in none of the words.
            val wrong = assertFailsWith<TelegramException> { RelayApi(base, "ZZZZ-ZZZZ-ZZZZ").me() }
            assertTrue(wrong.fatal && !wrong.message!!.contains("ZZZZ"))
        } finally {
            program.stop(0)
        }
    }

    @Test
    fun aReminderGoesToThePersonOfTheBotOnly() {
        bot.remind("Reminder: Call the bank")
        assertEquals(emptyList(), said())
        owner = 42
        bot.remind("Reminder: Call the bank")
        assertEquals(listOf(42L to "Reminder: Call the bank"), synchronized(sent) { sent.toList() })
    }
}
