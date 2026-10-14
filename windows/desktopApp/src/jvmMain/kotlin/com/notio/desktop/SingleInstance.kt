package com.notio.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.util.Properties

/**
 * One Notio per data folder (DESKTOP-ONLY, AGENT.md D45; Settings -> General -> Window, on by default): the first Notio holds a lock on
 * `instance.lock` of the data folder and listens on a loopback port written into `instance.port`; a second one started on the same data
 * folder asks the first to show its window and ends. With the setting off, several can run (they share the database, so it is not
 * advised). The installed and the portable Notio have their own data folders, so each can run once.
 */
object SingleInstance {
    private const val KEY = "singleInstance"
    private const val SHOW = "show"
    /** The installer asks a running Notio to end before it replaces its files (D72). */
    const val QUIT = "quit"

    private var lock: FileLock? = null
    private var lockFile: RandomAccessFile? = null
    private var server: ServerSocket? = null

    /** What the running Notio does when another start asks it to show itself (set by the window). */
    @Volatile
    var onShow: () -> Unit = {}

    /** What the running Notio does when the installer asks it to end: the Exit of the tray menu (set by the window). */
    @Volatile
    var onQuit: () -> Unit = {}

    /** Whether the setting is on; read from `ui.properties` before the controller exists. */
    fun enabled(root: File): Boolean {
        val props = Properties().apply { runCatching { File(root, "ui.properties").inputStream().use { load(it) } } }
        return props.getProperty(KEY) != "false"
    }

    fun setEnabled(ui: Properties, value: Boolean) = ui.setProperty(KEY, value.toString())

    fun isEnabled(ui: Properties) = ui.getProperty(KEY) != "false"

    /**
     * `true`: this is the only Notio of [root], go on. `false`: another one runs and was asked to show its window; this one must end.
     * Any failure of the check itself lets Notio start (the lock is a convenience, never a reason not to open).
     */
    fun claim(root: File): Boolean {
        root.mkdirs()
        val file = runCatching { RandomAccessFile(File(root, "instance.lock"), "rw") }.getOrNull() ?: return true
        // The lock could not even be tried (the pass of 2026-10-05: that counted as "another Notio runs" and this one ended
        // without a word): the check failed, Notio starts.
        val got = try {
            file.channel.tryLock()
        } catch (e: java.nio.channels.OverlappingFileLockException) {
            null // held by this very process: taken, as by another one
        } catch (e: Exception) {
            runCatching { file.close() }
            return true
        }
        if (got == null) {
            runCatching { file.close() }
            otherAnswered = askTheOtherToShow(root)
            return false
        }
        lockFile = file
        lock = got
        listen(root)
        return true
    }

    /** After [claim] said `false`: whether the running Notio took the request to show its window (D95). */
    @Volatile
    var otherAnswered: Boolean = false
        private set

    /** `true` when the running Notio took the request; `false` when it could not be reached (it hangs, or it is ending). */
    private fun askTheOtherToShow(root: File): Boolean {
        val lines = runCatching { File(root, "instance.port").readLines() }.getOrNull() ?: return false
        val port = lines.firstOrNull()?.trim()?.toIntOrNull() ?: return false
        val token = lines.getOrNull(1)?.trim().orEmpty()
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
                s.getOutputStream().write((SHOW + "\n" + token + "\n").toByteArray())
            }
        }.isSuccess
    }

    private fun listen(root: File) {
        val socket = runCatching { ServerSocket(0, 4, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        server = socket
        // The port and a secret only this Windows user can read (the data folder): a request without the secret is ignored, so
        // another user or program on the computer cannot end or raise Notio (the code check of 2026-10-01). The request is
        // "command\nsecret\n"; a Notio of 0.9.25 or older reads only the first line, so the installer works with both.
        val token = java.util.HexFormat.of().formatHex(ByteArray(16).also(java.security.SecureRandom()::nextBytes))
        runCatching { com.notio.shared.data.SafeFile.writeText(File(root, "instance.port"), socket.localPort.toString() + "\n" + token + "\n") }
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { c ->
                        c.soTimeout = 2_000
                        val reader = c.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        val secret = reader.readLine().orEmpty()
                        if (!java.security.MessageDigest.isEqual(secret.toByteArray(), token.toByteArray())) return@use
                        when (line) {
                            SHOW -> javax.swing.SwingUtilities.invokeLater { onShow() }
                            // "ok" tells the installer this Notio ends by itself, so it waits instead of ending it the hard way.
                            QUIT -> { c.getOutputStream().write("ok\n".toByteArray()); javax.swing.SwingUtilities.invokeLater { onQuit() } }
                        }
                    }
                }
            }
        }, "notio-single-instance").apply { isDaemon = true }.start()
    }

    /** At the end of the app: the port and the lock are given back. */
    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { lockFile?.close() }
        server = null; lock = null; lockFile = null
    }
}
