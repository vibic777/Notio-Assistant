package com.notebudget.app.core.ui

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Enter of the on-screen keyboard in a multi-line field goes in as text (bug fix, 2026-09-28).
 *
 * Some keyboards send Enter as a key press while the word being typed is still underlined (composing, e.g. with a suggestion
 * shown). Compose's text fields handle that key outside the keyboard's own channel: they end the underlined word and add the
 * line break, but the keyboard is not told that its word was finished — only that the cursor moved. It then still holds the
 * word as its own and puts it in again on the new line: the word shows twice as soon as the next one is typed. An ordinary
 * Android edit field has no such gap, which is why other apps did not show it.
 *
 * Here that key press becomes what the keyboard itself would send for a line break — "finish the word", "type a line break" —
 * through the same input connection as all its other edits, so the field and the keyboard stay in step. Only multi-line fields
 * are touched: in a one-line field Enter keeps its meaning (search, done, next).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImeEnterFix(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val fixed = object : PlatformTextInputMethodRequest by request {
                override fun createInputConnection(outAttributes: android.view.inputmethod.EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    return if (outAttributes.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) EnterAsText(connection) else connection
                }
            }
            nextHandler.startInputMethod(fixed)
        },
        content = content,
    )
}

/** An input connection that turns the keyboard's Enter key press into a finished word and a typed line break. */
internal class EnterAsText(target: InputConnection) : InputConnectionWrapper(target, false) {
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ENTER && event.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return super.sendKeyEvent(event)
        // One line break per press (and per repeat while it is held); the release has nothing more to do.
        if (event.action == KeyEvent.ACTION_DOWN) {
            beginBatchEdit()
            finishComposingText()
            commitText("\n", 1)
            endBatchEdit()
        }
        return true
    }
}
