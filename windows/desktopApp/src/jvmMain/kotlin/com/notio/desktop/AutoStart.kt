package com.notio.desktop

import com.notio.shared.data.AppPaths
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * Start with the system (DESKTOP-ONLY, AGENT.md D61; user, 2026-09-28). On Windows: a value under the user's `HKCU\...\Run` key,
 * so no admin rights are asked. On Linux (D129): a desktop entry in `~/.config/autostart`, which every desktop that follows the
 * XDG autostart rule starts at sign-in. The installed and the portable Notio have their own names, each can be on alone. The
 * registry value / the file itself is the setting: nothing is kept in the data folder, the portable build leaves no trace but
 * this one, which Off takes away again.
 */
object AutoStart {
    private const val RUN = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /** The argument the system starts Notio with; [startedBySystem] is true then. */
    const val ARG = "--autostart"

    @Volatile var startedBySystem: Boolean = false

    private val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /**
     * What the system has to start. Windows: Notio.exe of the packaged app. Linux: the one-file build names itself
     * (`NOTIO_BIN`, set by its script: the app it unpacked lives in the cache and moves with every version), an AppImage is
     * named by its runtime (`APPIMAGE`), any other build is `bin/Notio`. Null when run from Gradle or tests (the switch is then
     * shown off and disabled).
     */
    val exe: File?
        get() {
            if (!onWindows) {
                for (name in listOf("NOTIO_BIN", "APPIMAGE")) {
                    System.getenv(name)?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }?.takeIf { it.isFile }?.let { return it }
                }
            }
            return System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile }?.takeIf { it.isFile }
        }

    /** A Notio that carries its data with it: the portable folder, and on Linux the one-file build (an AppImage does not). */
    private fun portable(exe: File): Boolean =
        File(exe.parentFile, AppPaths.PORTABLE_MARKER).isFile || File(exe.parentFile?.parentFile, AppPaths.PORTABLE_MARKER).isFile ||
            (!onWindows && System.getenv("NOTIO_BIN") != null)

    private val name: String?
        get() = exe?.let { if (portable(it)) "Notio (portable)" else "Notio" }

    private fun command(exe: File) = "\"${exe.path}\" $ARG"

    val available: Boolean get() = name != null

    /** "Windows" in the words of the switch is the name of this system. */
    fun systemName(text: String): String = if (onWindows) text else text.replace("Windows", "Linux")

    // ---- Linux: ~/.config/autostart/notio.desktop

    private fun linuxEntry(name: String): File {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        return File(File(config, "autostart"), if (name == "Notio") "notio.desktop" else "notio-portable.desktop")
    }

    internal fun linuxEntryText(name: String, exe: File): String =
        "[Desktop Entry]\nType=Application\nName=$name\nName[ru]=" + name.replace("Notio", SystemName.RUSSIAN) +
            "\nComment=Notio by vibic\nExec=${command(exe)}\nTerminal=false\nX-GNOME-Autostart-enabled=true\n"

    /** On when the value / the file is there (any path: a portable folder moved keeps its On, see [repair]). */
    fun isOn(): Boolean {
        val name = name ?: return false
        if (!onWindows) return linuxEntry(name).isFile
        return runCatching { Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, name) }.getOrDefault(false)
    }

    /** Turns it on or off; false when the system refused (the switch then shows what is really there). */
    fun set(on: Boolean): Boolean {
        val exe = exe ?: return false
        val name = name ?: return false
        return runCatching {
            if (!onWindows) {
                val entry = linuxEntry(name)
                if (on) { entry.parentFile.mkdirs(); entry.writeText(linuxEntryText(name, exe)) } else if (entry.isFile) check(entry.delete())
            } else if (on) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN, name, command(exe))
            else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, name)) Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN, name)
        }.onFailure { com.notio.shared.AppLog.e("app", "start with the system could not be changed", it) }.isSuccess
    }

    /** Set by the build's training run of the app image (D75): that Notio never touches the start with the system (D155). */
    const val KEEP = "notio.autostart.keep"

    /**
     * At start: an On that names a Notio no longer there (the portable folder or the file was moved) is pointed at this one.
     * An On that names another Notio still there is left alone (D155): a second copy — the build's training run in `build/`,
     * a test build, an unpacked ZIP — took the start of the installed Notio over, and after a restart Windows started the copy.
     */
    fun repair() {
        if (System.getProperty(KEEP) == "true") return
        val exe = exe ?: return
        val name = name ?: return
        runCatching {
            if (!onWindows) {
                val entry = linuxEntry(name)
                if (!entry.isFile) return
                val text = entry.readText()
                val want = linuxEntryText(name, exe)
                val now = text.lineSequence().firstOrNull { it.startsWith("Exec=") }?.removePrefix("Exec=")
                if (text != want && mayRepoint(now, exe)) entry.writeText(want)
                return
            }
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN, name)) return
            val want = command(exe)
            val now = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN, name)
            if (now != want && mayRepoint(now, exe)) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN, name, want)
        }
    }

    /** The program a start command names: the path in quotes, or up to the first space. */
    internal fun programOf(command: String): File? {
        val c = command.trim()
        val path = if (c.startsWith("\"")) c.drop(1).substringBefore('"') else c.substringBefore(' ')
        return path.takeIf { it.isNotBlank() }?.let(::File)
    }

    /** True when [now] names this very Notio (its words are refreshed) or a program that is gone (it was moved). */
    internal fun mayRepoint(now: String?, exe: File): Boolean {
        val named = now?.let(::programOf) ?: return true
        return named.absoluteFile == exe.absoluteFile || !named.isFile
    }
}
