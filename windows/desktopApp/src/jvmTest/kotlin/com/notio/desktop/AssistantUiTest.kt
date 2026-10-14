package com.notio.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.notio.shared.assistant.AssistantAccess
import com.notio.shared.assistant.AssistantRight
import com.notio.shared.data.AppPaths
import com.notio.shared.domain.NoteType
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.resetMain

/**
 * The assistant in the window (D145, the branch `assistant`): turned on in Settings, a request written in the chat, a model -
 * played by a server of the test - that asks for a command, the note changed, and Roll back under the answer.
 */
@OptIn(ExperimentalTestApi::class)
class AssistantUiTest {
    @org.junit.After
    fun resetMain() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun aRequestOfTheChatChangesANoteThroughTheModelAndIsRolledBack() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-assistant-ui").toFile()
        val controller = NotioController(AppPaths(home))
        var noteId = ""
        var asked = 0
        // The model: first it asks for a command, then it answers with words.
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun answer(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        model.createContext("/v1/models") { answer(it, """{"data":[{"id":"test-model"}]}""") }
        model.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.readBytes()
            if (asked++ == 0) answer(ex, """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"rename_note","arguments":"{\"note_id\":\"$noteId\",\"title\":\"Renamed by the model\"}"}}]}}]}""")
            else answer(ex, """{"choices":[{"message":{"role":"assistant","content":"Done, I renamed it."}}]}""")
        }
        model.start()
        try {
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            var made = false
            controller.createFolder("Work", "#3366FF", "SOFT", null, { error(it.toString()) }) { made = true }
            waitUntil(timeoutMillis = 10_000) { made && controller.state.value.folders.isNotEmpty() }
            val folder = controller.state.value.folders.first().folder
            var noted = false
            controller.createNote(folder.id, "Report", NoteType.TEXT, { error(it.toString()) }) { noted = true }
            waitUntil(timeoutMillis = 10_000) { noted && controller.state.value.notes.isNotEmpty() }
            noteId = controller.state.value.notes.single().id
            fun tagged(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
            fun shown(text: String) = onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            fun title() = controller.state.value.notes.single().title
            val desk = controller.assistant

            // Off: the page has its switch and nothing else; turned on by the person, with nothing allowed and nothing open.
            controller.openCatalog("assistant")
            waitUntil(timeoutMillis = 10_000) { tagged(ASSISTANT_SWITCH) == 1 }
            assertEquals(0, tagged(ASSISTANT_RIGHT + AssistantRight.READ.name))
            onNodeWithTag(ASSISTANT_SWITCH, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { desk.enabled && tagged(ASSISTANT_RIGHT + AssistantRight.READ.name) == 1 }
            assertEquals(emptySet(), desk.rights)
            // The rights and the access are set on the page.
            onNodeWithTag(ASSISTANT_RIGHT + AssistantRight.EDIT.name, useUnmergedTree = true).performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { desk.rights == setOf(AssistantRight.EDIT) }
            onNodeWithTag(ASSISTANT_ACCESS + folder.id, useUnmergedTree = true).performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { desk.access[folder.id] == AssistantAccess.ASSISTANT }
            desk.applyToFolder(folder.id) {}
            waitUntil(timeoutMillis = 10_000) { desk.access[noteId] == AssistantAccess.ASSISTANT }
            // The address with its port, as the person writes it; Check finds the model of the server.
            desk.changeUrl("http://127.0.0.1:${model.address.port}/v1")
            desk.checkConnection()
            waitUntil(timeoutMillis = 10_000) { desk.model == "test-model" }

            // The chat: the request, the answer of the model, the note changed, and Roll back.
            controller.openCatalog("assistant_chat")
            waitUntil(timeoutMillis = 10_000) { tagged(ASSISTANT_FIELD) == 1 }
            onNodeWithTag(ASSISTANT_FIELD, useUnmergedTree = true).performTextInput("rename the report")
            // Ctrl+Enter sends (the start; Enter alone starts a new line, the choice is in Settings -> Assistant -> Chat).
            onNodeWithTag(ASSISTANT_FIELD, useUnmergedTree = true).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
            assertEquals(0, asked)
            onNodeWithTag(ASSISTANT_FIELD, useUnmergedTree = true).performKeyInput {
                withKeyDown(androidx.compose.ui.input.key.Key.CtrlLeft) { pressKey(androidx.compose.ui.input.key.Key.Enter) }
            }
            waitUntil(timeoutMillis = 20_000) { shown("Done, I renamed it.") && title() == "Renamed by the model" }
            assertEquals(1, desk.journal.size)
            assertEquals("rename the report", desk.journal.single().request)
            onNodeWithTag(ASSISTANT_ROLLBACK, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { title() == "Report" && desk.journal.single().rolledBack }
        } finally {
            model.stop(0)
            controller.close()
            home.deleteRecursively()
        }
    }

    /** D150: the chat is kept after a restart, the model goes on with the same talk, a message is deleted under the mouse, the chat is cleared. */
    @Test
    fun theChatIsKeptAfterARestartAndItsMessagesAreDeleted() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-assistant-chat").toFile()
        val bodies = java.util.Collections.synchronizedList(ArrayList<String>())
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        model.createContext("/v1/chat/completions") { ex ->
            bodies += ex.requestBody.readBytes().decodeToString()
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"Answer ${bodies.size}"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        model.start()
        var controller = NotioController(AppPaths(home))
        try {
            var desk = controller.assistant
            desk.turn(true)
            desk.changeUrl("http://127.0.0.1:${model.address.port}/v1")
            desk.changeModel("test-model")
            desk.send("remember the word apple")
            waitUntil(timeoutMillis = 20_000) { desk.lines.size == 2 && !desk.busy }
            assertEquals(true, desk.keepChat)
            controller.close()

            // Notio starts again: the chat is there, and the model gets the talk with the next request.
            controller = NotioController(AppPaths(home))
            desk = controller.assistant
            waitUntil(timeoutMillis = 10_000) { desk.lines.size == 2 }
            assertEquals(listOf("remember the word apple", "Answer 1"), desk.lines.map { it.text })
            desk.send("which word?")
            waitUntil(timeoutMillis = 20_000) { desk.lines.size == 4 && !desk.busy }
            assertEquals(true, bodies.last().contains("remember the word apple"))

            // Under the mouse a message shows Copy and Delete; Delete takes that one message away, from the model too.
            val shown = controller
            setContent { NotioApp(shown, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            shown.openCatalog("assistant_chat")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Answer 1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, onAllNodesWithTag(ASSISTANT_DELETE_LINE, useUnmergedTree = true).fetchSemanticsNodes().size)
            onAllNodesWithText("remember the word apple", useUnmergedTree = true)[0].performMouseInput { moveTo(center) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(ASSISTANT_DELETE_LINE, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            onNodeWithTag(ASSISTANT_DELETE_LINE, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { desk.lines.size == 3 }
            desk.send("and now?")
            waitUntil(timeoutMillis = 20_000) { desk.lines.size == 5 && !desk.busy }
            assertEquals(false, bodies.last().contains("remember the word apple"))

            // Clear the chat asks first, then every message goes - also from what is kept.
            onNodeWithTag(ASSISTANT_CLEAR_CHAT, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(controller.res.str("assistant_chat_clear_ask"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(5, desk.lines.size)
            // The button of the dialog: the one of the words "Clear the chat" that can be pressed and is not the button of the page.
            onAllNodes(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasText(controller.res.str("assistant_chat_clear")) and !androidx.compose.ui.test.hasTestTag(ASSISTANT_CLEAR_CHAT))
                .let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            waitUntil(timeoutMillis = 10_000) { desk.lines.isEmpty() }
            Thread.sleep(500)
            controller.close()
            controller = NotioController(AppPaths(home))
            val again = controller.assistant
            Thread.sleep(1_000)
            assertEquals(0, again.lines.size)
        } finally {
            model.stop(0)
            controller.close()
            home.deleteRecursively()
        }
    }
}
