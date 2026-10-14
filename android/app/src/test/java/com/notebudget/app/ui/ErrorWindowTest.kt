package com.notebudget.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.core.common.CrashLog
import com.notebudget.app.core.common.ErrorActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The error window (D224): the text of the error, Copy error text puts it on the clipboard, Close forgets it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ErrorWindowTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun theErrorWindowShowsTheTextCopiesItAndForgetsItOnClose() {
        val text = "Notio 0.9.40\n\njava.lang.IllegalStateException: boom\n\tat com.notebudget.app.X.y(X.kt:1)"
        File(context.filesDir, "last_error.txt").writeText(text)
        ActivityScenario.launch(ErrorActivity::class.java).use {
            rule.onNodeWithText("Notio closed because of an error").assertExists()
            rule.onNodeWithText(text).assertExists()
            rule.onNodeWithText("Copy error text").performClick()
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            rule.onNodeWithText("Close").performClick()
            assertNull(CrashLog.read(context))
        }
    }
}
