package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.notio.shared.AppInfo
import com.notio.shared.AppLog
import com.notio.shared.CrashLog
import com.notio.shared.data.AppPaths
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * The journal and the black box (Android D203, D224, D230; AGENT.md D28), installed first thing: an error that would close the app
 * is written to the journal and to `last_error.txt`, and shown at once in a small window of its own with "Copy error text" and
 * "Open Notio again"; the app then ends. When even that window cannot be shown, the next start shows the text ([PreviousCrashDialog]).
 */
object CrashGuard {
    fun install(paths: AppPaths) {
        AppLog.install(paths.logs)
        CrashLog.install(paths.root)
        AppLog.i("app", "start " + AppInfo.SHOWN + ", " + System.getProperty("os.name") + " " + System.getProperty("os.version") + ", Java " + System.getProperty("java.runtime.version"))
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { AppLog.fatal("crash", "the app closes: an error on the thread " + thread.name, error) }
            val text = CrashLog.text(thread, error)
            CrashLog.write(text)
            val shown = runCatching { showNow(text) }.isSuccess
            if (shown) CrashLog.clear()
            exitProcess(10)
        }
    }

    /** The error window of the phone's ErrorActivity, in Swing: it must work when Compose itself is what failed. */
    private fun showNow(text: String) {
        val res = runCatching { AndroidRes.forLanguage(null) }.getOrNull()
        fun str(name: String, fallback: String) = res?.str(name)?.takeIf { it != name } ?: fallback
        val show = Runnable {
            val dialog = JDialog(null as java.awt.Frame?, str("crash_now_title", "Notio closed because of an error"), true)
            dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            val message = JLabel("<html><body style='width:420px'>" + str("crash_now_message", "Copy the error text and send it to the developer.") + "</body></html>")
            val area = JTextArea(text).apply { isEditable = false; lineWrap = false; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttons.add(JButton(str("db_encryption_copy_error", "Copy error text")).apply { addActionListener { copyToClipboard(text) } })
            buttons.add(JButton(str("crash_restart", "Open Notio again")).apply { addActionListener { restart(); dialog.dispose() } })
            buttons.add(JButton(str("action_close", "Close")).apply { addActionListener { dialog.dispose() } })
            dialog.contentPane.add(message, BorderLayout.NORTH)
            dialog.contentPane.add(JScrollPane(area).apply { preferredSize = Dimension(640, 320) }, BorderLayout.CENTER)
            dialog.contentPane.add(buttons, BorderLayout.SOUTH)
            dialog.pack()
            dialog.setLocationRelativeTo(null)
            dialog.isAlwaysOnTop = true
            dialog.isVisible = true
        }
        if (SwingUtilities.isEventDispatchThread()) show.run() else SwingUtilities.invokeAndWait(show)
    }

    /** Starts Notio.exe again (the packaged app); in a development run there is nothing to start. */
    private fun restart() {
        val exe = System.getProperty("jpackage.app-path") ?: return
        runCatching { ProcessBuilder(exe).start() }
    }
}

/** The error that closed the last run, shown once at the next start with "Copy error text" (Android D203). */
@Composable
fun PreviousCrashDialog() {
    var text by remember { mutableStateOf(CrashLog.read()) }
    val shown = text ?: return
    val close = { CrashLog.clear(); text = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource("crash_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource("crash_message"))
                SelectionContainer {
                    Text(
                        shown, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { copyToClipboard(shown) }) { Text(stringResource("db_encryption_copy_error")) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource("action_close")) } },
    )
}
