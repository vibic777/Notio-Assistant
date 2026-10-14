package com.notebudget.app.feature.notes.task

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.TaskDocument
import com.notebudget.app.domain.model.TaskState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Edit tab of a task hands every change on (D239). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TaskEditorTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun a_status_chosen_from_the_list_and_a_typed_priority_are_handed_on() {
        var extras by mutableStateOf<String?>(TaskDocument(comment = "c").encode())
        val note = Note(
            id = "t", folderId = "f", title = "Call", type = NoteType.TASK, colorHex = null, isPinned = false, isFavorite = false,
            isTitleBold = false, contentText = "", createdAt = 0, updatedAt = 0,
        )
        rule.setContent { MaterialTheme { TaskEditor(note, "", extras, { _, e -> extras = e }, Modifier) } }
        rule.onNodeWithContentDescription("Edit").performClick()
        // D337: the status is a button with the list of all statuses behind it; Done sets the tick an older Notio knows.
        rule.onNodeWithTag(TASK_STATE_BUTTON, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        TaskState.entries.forEach { rule.onNodeWithTag(TASK_STATE_ITEM + it.name).assertExists() }
        rule.onNodeWithTag(TASK_STATE_ITEM + TaskState.DONE.name).performClick()
        rule.waitForIdle()
        assertEquals(TaskState.DONE, TaskDocument.decode(extras).state)
        assertEquals(true, TaskDocument.decode(extras).done)
        assertEquals("c", TaskDocument.decode(extras).comment)
        // The priority takes digits alone, up to 65535.
        rule.onNodeWithTag(TASK_PRIORITY_FIELD).performTextReplacement("1x2")
        rule.waitForIdle()
        assertEquals(12, TaskDocument.decode(extras).priority)
        rule.onNodeWithTag(TASK_PRIORITY_FIELD).performTextReplacement("700000")
        rule.waitForIdle()
        assertEquals(65535, TaskDocument.decode(extras).priority)
        rule.onNodeWithTag(TASK_PRIORITY_FIELD).performTextReplacement("")
        rule.waitForIdle()
        assertEquals(null, TaskDocument.decode(extras).priority)
        assertEquals(TaskState.DONE, TaskDocument.decode(extras).state)
    }
}
