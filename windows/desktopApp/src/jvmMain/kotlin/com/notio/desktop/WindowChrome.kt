package com.notio.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The window frame of a Windows 11 app (AGENT.md D14): the app's own top bar IS the title bar — its colour, its title and its
 * buttons — while the minimize / maximize / close buttons, Snap layouts, the rounded corners and the shadow stay the system's.
 * This needs the JetBrains Runtime (the app is packaged with it); on any other Java the ordinary title bar is kept and the bar
 * below it looks the same.
 */
class WindowChrome private constructor(private val titleBar: WindowDecorations.CustomTitleBar?) {
    val native: Boolean get() = titleBar != null

    /** Width kept free on the right for the system buttons. */
    val rightInset: Dp get() = (titleBar?.rightInset ?: 0f).dp

    fun setDarkControls(dark: Boolean) {
        titleBar?.putProperty("controls.dark", dark)
    }

    companion object {
        const val HEIGHT = 56

        /** No window of its own (off-screen rendering in tests): a plain bar. */
        fun none() = WindowChrome(null)

        fun install(window: java.awt.Frame): WindowChrome {
            val bar = runCatching {
                if (!JBR.isAvailable() || !JBR.isWindowDecorationsSupported()) return@runCatching null
                val decorations = JBR.getWindowDecorations()
                decorations.createCustomTitleBar().also {
                    it.height = HEIGHT.toFloat()
                    decorations.setCustomTitleBar(window, it)
                }
            }.getOrNull()
            return WindowChrome(bar)
        }
    }

    /**
     * Clicks on the app's own buttons in the bar go to the app; everywhere else the bar drags the window, double-click
     * maximizes and right-click opens the system menu, as the system's own title bar does.
     */
    fun Modifier.titleBarHitTest(): Modifier {
        val bar = titleBar ?: return this
        return pointerInput(Unit) {
            val context = currentCoroutineContext()
            awaitPointerEventScope {
                var inControl = false
                while (context.isActive) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    event.changes.forEach {
                        if (!it.isConsumed && !inControl) {
                            bar.forceHitTest(false)
                        } else {
                            if (event.type == PointerEventType.Press) inControl = true
                            if (event.type == PointerEventType.Release) inControl = false
                            bar.forceHitTest(true)
                        }
                    }
                }
            }
        }
    }
}

/** The top bar of the window: [content] on the left and in the middle, the system buttons' room on the right. */
@Composable
fun TitleBarRow(chrome: WindowChrome, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    with(chrome) {
        Row(
            modifier.fillMaxWidth().height(WindowChrome.HEIGHT.dp).titleBarHitTest(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
            if (chrome.native) Spacer(Modifier.width(chrome.rightInset + 8.dp))
        }
    }
}

/** Remembers the chrome of this window for as long as the window lives. */
@Composable
fun WindowScope.rememberWindowChrome(): WindowChrome {
    val frame = window as java.awt.Frame
    val chrome = remember(frame) { WindowChrome.install(frame) }
    // The system buttons' width is known only once the window is shown; a recomposition picks it up.
    var tick by remember { mutableFloatStateOf(0f) }
    DisposableEffect(frame) {
        val listener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                tick = chrome.rightInset.value
            }
        }
        frame.addComponentListener(listener)
        onDispose { frame.removeComponentListener(listener) }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    return chrome
}
