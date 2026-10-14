package com.notebudget.app.core.ui.tip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where a pressed hint goes (D332; owner, 2026-10-08: "while the hint of a button is shown, a press on it goes to the part of
 * the help it belongs to"). A hint only says which section; the help itself is shown over the screen by
 * `feature/help/HelpOverlay`, which the app has once, at its root.
 */
object HelpLink {
    /** The section (or category) of `HelpMap` the help opens at; `null` is its beginning. */
    var topic: String? by mutableStateOf(null)
        private set

    var open: Boolean by mutableStateOf(false)
        private set

    fun open(topic: String?) {
        this.topic = topic
        open = true
    }

    fun close() {
        open = false
    }
}

/**
 * The section of the help the screen around is about. A button whose words do not say where it belongs (an icon button has a
 * hint, not a key) is explained by the help of its screen.
 */
val LocalHelpTopic = compositionLocalOf<String?> { null }

@Composable
fun HelpTopic(topic: String, content: @Composable () -> Unit) = CompositionLocalProvider(LocalHelpTopic provides topic, content = content)
