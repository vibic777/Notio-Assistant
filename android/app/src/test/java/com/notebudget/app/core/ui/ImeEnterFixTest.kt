package com.notebudget.app.core.ui

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The keyboard's Enter key press in a multi-line field becomes a finished word and a typed line break (bug fix 2026-09-28). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImeEnterFixTest {
    private class Recording : BaseInputConnection(android.view.View(ApplicationProvider.getApplicationContext()), true) {
        val calls = mutableListOf<String>()
        override fun beginBatchEdit(): Boolean { calls += "begin"; return true }
        override fun endBatchEdit(): Boolean { calls += "end"; return true }
        override fun finishComposingText(): Boolean { calls += "finish"; return true }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { calls += "commit:" + text; return true }
        override fun sendKeyEvent(event: KeyEvent): Boolean { calls += "key:" + event.keyCode + ":" + event.action; return true }
    }

    @Test
    fun `enter press becomes finish word and a line break, its release does nothing`() {
        val target = Recording()
        val fixed = EnterAsText(target)
        assertTrue(fixed.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertTrue(fixed.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf("begin", "finish", "commit:\n", "end"), target.calls)
    }

    @Test
    fun `other keys go through as they are`() {
        val target = Recording()
        EnterAsText(target).sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("key:${KeyEvent.KEYCODE_DEL}:${KeyEvent.ACTION_DOWN}"), target.calls)
    }
}
