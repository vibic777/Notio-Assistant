package com.notio.desktop

import com.notio.shared.data.AppPaths
import com.notio.shared.domain.NoteType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Android D294 and D285 on the desktop (D78): nothing typed is lost by Exit, and a double Back leaves one screen. */
class SaveBeforeExitTest {
    @Test
    fun exitSavesWhatTheOpenNoteStillHolds() = runBlocking {
        val home = Files.createTempDirectory("notio-test").toFile()
        var controller = NotioController(AppPaths(home))
        try {
            val id = openATextNote(controller)
            val title = controller.state.value.detail!!.note.title
            // The editor on the screen: typed a moment ago, the autosave has not come yet.
            controller.addUnsaved { controller.saveNoteText(id, title, "typed right before Exit") }
            var ended = false
            controller.finishRecordingsThen { ended = true }
            withTimeout(5_000) { while (!ended) delay(10) }
            controller.close()

            controller = NotioController(AppPaths(home))
            controller.openNote(id)
            withTimeout(5_000) { while (controller.state.value.detail == null) delay(10) }
            assertEquals("typed right before Exit", controller.state.value.detail!!.note.contentText)
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    @Test
    fun savesOfOneNoteLandInTheOrderTheyWereAsked() = runBlocking {
        val home = Files.createTempDirectory("notio-test").toFile()
        var controller = NotioController(AppPaths(home))
        try {
            val id = openATextNote(controller)
            repeat(40) { i -> controller.saveNoteContent(id, "text $i", null) }
            var ended = false
            controller.finishRecordingsThen { ended = true }
            withTimeout(10_000) { while (!ended) delay(10) }
            controller.close()

            controller = NotioController(AppPaths(home))
            controller.openNote(id)
            withTimeout(5_000) { while (controller.state.value.detail == null) delay(10) }
            assertEquals("text 39", controller.state.value.detail!!.note.contentText)
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    @Test
    fun aDoubleBackLeavesOneScreen() = runBlocking {
        val home = Files.createTempDirectory("notio-test").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            val id = openATextNote(controller)
            controller.back()
            controller.openNote(id, from = Screen.Calendar)
            controller.backByPerson()
            controller.backByPerson()
            assertEquals<Screen>(Screen.Calendar, controller.state.value.screen)
            delay(350)
            controller.backByPerson()
            assertEquals<Screen>(Screen.Home, controller.state.value.screen)
            // The app's own steps back are never held up.
            controller.openNote(id, from = Screen.Calendar)
            controller.back()
            controller.back()
            assertEquals<Screen>(Screen.Home, controller.state.value.screen)
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    private suspend fun openATextNote(controller: NotioController): String {
        var loaded = false
        controller.loadDemo { it.getOrThrow(); loaded = true }
        withTimeout(20_000) { while (!loaded) delay(20) }
        controller.selectFolder("demo-work")
        withTimeout(10_000) { while (controller.state.value.notes.none { it.type == NoteType.TEXT }) delay(20) }
        val id = controller.state.value.notes.first { it.type == NoteType.TEXT }.id
        controller.openNote(id)
        withTimeout(5_000) { while (controller.state.value.detail?.note?.id != id) delay(10) }
        return id
    }
}
