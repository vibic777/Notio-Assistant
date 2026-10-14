package com.notio.desktop

import com.notio.shared.data.AppPaths
import com.notio.shared.domain.NoteType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.resetMain

/** The eyes of the selection bar of a folder (D156, Android D364): only the picked notes that can hide change. */
class HideManyTest {
    @org.junit.After
    fun resetMain() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun waitFor(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!what()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun thePickedNotesThatCanHideAreHiddenAndShownAgain() {
        val home = Files.createTempDirectory("notio-hide-many").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var made = false
            controller.createFolder("Home", "#3366FF", "SOFT", null, { error(it.toString()) }) { made = true }
            waitFor { made && controller.state.value.folders.isNotEmpty() }
            val folder = controller.state.value.folders.first().folder
            for ((title, type) in listOf("Text" to NoteType.TEXT, "List" to NoteType.LIST, "Audio" to NoteType.AUDIO, "Other" to NoteType.TEXT)) {
                var noted = false
                controller.createNote(folder.id, title, type, { error(it.toString()) }) { noted = true }
                waitFor { noted }
            }
            controller.selectFolder(folder.id)
            waitFor { controller.state.value.notes.size == 4 }
            fun note(title: String) = controller.state.value.notes.single { it.title == title }
            val picked = setOf(note("Text").id, note("List").id, note("Audio").id)

            val toHide = HideMany.targets(controller.state.value.notes, picked, hidden = true)
            assertEquals(setOf(note("Text").id, note("List").id), toHide.toSet())
            controller.setNotesHidden(toHide, true)
            waitFor { note("Text").hidden && note("List").hidden }
            assertEquals(false, note("Audio").hidden)
            assertEquals(false, note("Other").hidden)
            assertEquals(emptyList(), HideMany.targets(controller.state.value.notes, picked, hidden = true))

            val toShow = HideMany.targets(controller.state.value.notes, picked, hidden = false)
            assertEquals(toHide.toSet(), toShow.toSet())
            controller.setNotesHidden(toShow, false)
            waitFor { !note("Text").hidden && !note("List").hidden }
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }
}
