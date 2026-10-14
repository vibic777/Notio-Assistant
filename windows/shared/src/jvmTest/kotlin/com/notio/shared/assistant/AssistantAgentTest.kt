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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One request from the words to the answer (D145; the plan, section 4), with a model played by the test: it asks for commands,
 * gets their results and answers. And the talk with a real server of the OpenAI-compatible kind, played by a server of the test.
 */
class AssistantAgentTest {
    private val dir = Files.createTempDirectory("notio-agent").toFile()
    private var now = 1_000L
    private val clock = Clock { now++ }
    private val ids = SequentialIds()
    private val db = NotioDatabase.inMemory()
    private val editor = NotioEditor(db, AttachmentStore(dir), clock, ids)
    private val journal = AssistantJournal(db, clock, ids)
    private val commands = AssistantCommands(db, editor, journal, { AssistantRight.entries.toSet() })
    private val work = editor.createFolder("Work", "#3366FF", "SOFT")
    private val report = editor.createNote(work.id, "Report", NoteType.TEXT, "the figures")
    private var server: HttpServer? = null

    init { db.setAssistantAccess(listOf(work.id, report.id), AssistantAccess.ASSISTANT) }

    @AfterTest
    fun cleanUp() {
        server?.stop(0)
        db.close()
        dir.deleteRecursively()
    }

    /** A model that answers by a script: each step sees the talk so far. */
    private class Scripted(private vararg val steps: (List<ChatMessage>) -> ChatMessage) : ModelClient {
        val asked = ArrayList<List<ChatMessage>>()
        val offered = ArrayList<List<String>>()
        override fun models() = listOf("test")
        override fun chat(messages: List<ChatMessage>, tools: List<AssistantTool>): ChatMessage {
            asked += messages.toList()
            offered += tools.map { it.name }
            return steps[minOf(asked.size - 1, steps.size - 1)](messages)
        }
    }

    private fun calls(vararg calls: Pair<String, String>) = ChatMessage("assistant", null, calls.mapIndexed { i, (name, args) -> ToolCall("c$i", name, args) })
    private fun says(text: String) = ChatMessage("assistant", text)

    @Test
    fun theModelAsksForCommandsGetsTheirResultsAndTheChangesAreOneRecord() {
        val model = Scripted(
            { calls("list_folders" to "{}") },
            { talk ->
                // The result of the command came back to the model as it is.
                assertTrue(talk.last().role == "tool" && talk.last().content!!.contains("\"Work\""))
                calls("rename_note" to """{"note_id":"${report.id}","title":"Report of March"}""", "append_note_text" to """{"note_id":"${report.id}","text":"and April"}""")
            },
            { says("Renamed and added.") },
        )
        val reply = AssistantAgent(commands, journal, model).ask(emptyList(), "rename the report and add April", Channel.CHAT)

        assertEquals("Renamed and added.", reply.text)
        assertEquals(3, reply.commands)
        assertEquals("Report of March", db.note(report.id)!!.title)
        val entry = assertNotNull(reply.entry)
        assertEquals("rename the report and add April", entry.request)
        assertEquals(2, entry.body.ops.size)
        // The model was told the rules first and offered the allowed commands only.
        assertEquals("system", model.asked.first().first().role)
        assertTrue("rename_note" in model.offered.first())
        // The person rolls the request back.
        assertTrue(journal.rollback(entry.id).done)
        assertEquals("Report", db.note(report.id)!!.title)
        assertEquals("the figures", db.note(report.id)!!.contentText)
    }

    @Test
    fun aQuestionChangesNothingAndLeavesNoRecord() {
        val model = Scripted({ calls("read_note" to """{"note_id":"${report.id}"}""") }, { says("It holds the figures.") })
        val reply = AssistantAgent(commands, journal, model).ask(listOf(ChatMessage("user", "hello"), says("Hello.")), "what is in the report?", Channel.CHAT)
        assertEquals("It holds the figures.", reply.text)
        assertNull(reply.entry)
        // The earlier words of the talk went to the model too.
        assertEquals(listOf("system", "user", "assistant", "user"), model.asked.first().map { it.role })
    }

    @Test
    fun wrongArgumentsAndUnknownCommandsComeBackToTheModelAsErrors() {
        val model = Scripted(
            { calls("rename_note" to "not json", "format_disk" to "{}") },
            { talk ->
                val results = talk.takeLast(2).map { it.content.orEmpty() }
                assertTrue(results.all { it.contains("error") })
                says("I could not do it.")
            },
        )
        val reply = AssistantAgent(commands, journal, model).ask(emptyList(), "do something", Channel.CHAT)
        assertEquals("I could not do it.", reply.text)
        assertNull(reply.entry)
        assertEquals("Report", db.note(report.id)!!.title)
    }

    @Test
    fun aModelThatNeverAnswersIsStoppedAndWhatItChangedCanStillBeRolledBack() {
        var n = 0
        val model = Scripted({ calls("rename_note" to """{"note_id":"${report.id}","title":"Name ${n++}"}""") })
        val reply = AssistantAgent(commands, journal, model, rounds = 3).ask(emptyList(), "loop", Channel.CHAT)
        assertTrue(reply.failed)
        assertEquals(AssistantAgent.TOO_LONG, reply.text)
        assertEquals("Name 2", db.note(report.id)!!.title)
        assertTrue(journal.rollback(assertNotNull(reply.entry).id).done)
        assertEquals("Report", db.note(report.id)!!.title)

        // Stop of the person, and a model that cannot be reached: both end the request with words for the person.
        assertEquals(AssistantAgent.STOPPED, AssistantAgent(commands, journal, model).ask(emptyList(), "x", Channel.CHAT) { true }.text)
        val broken = object : ModelClient {
            override fun models() = emptyList<String>()
            override fun chat(messages: List<ChatMessage>, tools: List<AssistantTool>): ChatMessage = throw ModelException("The server of the model does not answer at this address.")
        }
        val failed = AssistantAgent(commands, journal, broken).ask(emptyList(), "x", Channel.CHAT)
        assertTrue(failed.failed)
        assertTrue(failed.text.contains("does not answer"))
    }

    @Test
    fun stopBreaksOffAQuestionTheModelHasNotAnsweredYet() {
        // A model that thinks for a long time: Stop of the chat interrupts the thread that waits for it.
        val slow = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        slow.createContext("/v1/chat/completions") { ex -> ex.requestBody.readBytes(); Thread.sleep(30_000) }
        slow.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        slow.start()
        server = slow
        val client = HttpModelClient(ModelSettings("http://127.0.0.1:${slow.address.port}/v1", model = "m"))
        var reply: AgentReply? = null
        val worker = Thread { reply = AssistantAgent(commands, journal, client).ask(emptyList(), "think", Channel.CHAT) }
        worker.start()
        Thread.sleep(500)
        val began = System.currentTimeMillis()
        worker.interrupt()
        worker.join(10_000)
        assertTrue(System.currentTimeMillis() - began < 5_000, "the request did not end at Stop")
        assertTrue(assertNotNull(reply).failed)
        assertTrue(reply!!.text.contains("stopped"))
    }

    @Test
    fun theAddressTellsWhereTheModelStands() {
        assertEquals(ModelPlace.THIS_COMPUTER, ModelPlace.of("http://127.0.0.1:1234/v1"))
        assertEquals(ModelPlace.THIS_COMPUTER, ModelPlace.of("http://localhost:8080/v1"))
        assertEquals(ModelPlace.THIS_COMPUTER, ModelPlace.of("http://[::1]:1234/v1"))
        assertEquals(ModelPlace.HOME_NETWORK, ModelPlace.of("http://192.168.1.20:1234/v1"))
        assertEquals(ModelPlace.HOME_NETWORK, ModelPlace.of("http://10.8.0.2:1234/v1"))
        assertEquals(ModelPlace.HOME_NETWORK, ModelPlace.of("http://100.100.5.7:1234/v1"))
        assertEquals(ModelPlace.OUTSIDE, ModelPlace.of("https://openrouter.ai/api/v1"))
        assertEquals(ModelPlace.OUTSIDE, ModelPlace.of("http://8.8.8.8/v1"))
        // A name is not looked up, and what is not an address is the careful answer.
        assertEquals(ModelPlace.OUTSIDE, ModelPlace.of("http://my-pc.local:1234/v1"))
        assertEquals(ModelPlace.OUTSIDE, ModelPlace.of("not an address"))
    }

    @Test
    fun aServerOfTheOpenAiKindIsAskedAndRead() {
        val seen = ArrayList<Triple<String, String?, String>>()
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun answer(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        fake.createContext("/v1/models") { ex ->
            seen += Triple(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), "")
            if (ex.requestHeaders.getFirst("Authorization") != "Bearer the-key") answer(ex, 401, """{"error":{"message":"An API token is required"}}""")
            else answer(ex, 200, """{"data":[{"id":"model-a"},{"id":"model-b"}]}""")
        }
        fake.createContext("/v1/chat/completions") { ex ->
            seen += Triple(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readBytes().decodeToString())
            answer(ex, 200, """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"list_folders","arguments":"{}"}}]}}]}""")
        }
        fake.start()
        server = fake
        val url = "http://127.0.0.1:${fake.address.port}/v1/"

        // Without the key the server refuses, and the person is told so.
        val refused = assertFailsWith<ModelException> { HttpModelClient(ModelSettings(url)).models() }
        assertTrue(refused.message!!.contains("key"))
        val client = HttpModelClient(ModelSettings(url, " the-key ", "model-b"))
        assertEquals(listOf("model-a", "model-b"), client.models())

        val reply = client.chat(listOf(ChatMessage("system", "rules"), ChatMessage("user", "list the folders")), commands.tools())
        assertEquals(listOf(ToolCall("call_1", "list_folders", "{}")), reply.calls)
        val sent = Json.parseToJsonElement(seen.last().third).jsonObject
        assertEquals("model-b", sent.getValue("model").jsonPrimitive.content)
        assertEquals(listOf("system", "user"), sent.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        val tools = sent.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
        assertTrue("list_folders" in tools && "trash_note" in tools)
        assertEquals("Bearer the-key", seen.last().second)

        // Nothing listens there: words for the person, not an exception of the network.
        val nowhere = assertFailsWith<ModelException> { HttpModelClient(ModelSettings("http://127.0.0.1:1/v1")).models() }
        assertTrue(nowhere.message!!.contains("does not answer"))
        assertFailsWith<ModelException> { HttpModelClient(ModelSettings("ftp://127.0.0.1/v1")).models() }
    }
}
