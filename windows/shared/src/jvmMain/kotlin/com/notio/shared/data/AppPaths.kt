package com.notio.shared.data

import java.io.File

/**
 * Where the desktop app keeps its data (AGENT.md D07): `%LOCALAPPDATA%\Notio` on Windows, `~/.local/share/notio` elsewhere.
 * The portable build keeps it in `data` next to `Notio.exe` (D13); `-Dnotio.home=<dir>` overrides both (tests).
 */
class AppPaths(val root: File) {
    val database: File get() = File(root, "notio.db")
    val attachments: File get() = File(root, "attachments")
    val settings: File get() = File(root, "settings.json")
    val logs: File get() = File(root, "logs")

    /**
     * Makes the data folder and keeps it for its person alone (D134). Windows gives `%LOCALAPPDATA%` to its user only. A Linux
     * makes a new folder readable by every user of the computer, and the notes, the journal and the secrets (kept there
     * without the seal of a system store, D129) lie in it: the folder is closed to the others, at every start, so one made by
     * an earlier version is closed too. A file system without such rights (Windows, a FAT stick) is left as it is.
     */
    fun keepPrivate() {
        root.mkdirs()
        runCatching {
            val path = root.toPath()
            if (java.nio.file.Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java) != null) {
                java.nio.file.Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
        }
    }

    companion object {
        /** Marker file next to `Notio.exe` in the portable build (D13): the data then lives in `data\` beside it. */
        const val PORTABLE_MARKER = "portable.txt"

        fun default(): AppPaths {
            System.getProperty("notio.home")?.let { return AppPaths(File(it)) }
            // The one-file build of Linux says where its data is: beside the file (D88).
            System.getenv("NOTIO_HOME")?.takeIf { it.isNotBlank() }?.let { return AppPaths(File(it)) }
            // `jpackage.app-path` is set by the native launcher to the path of Notio.exe.
            // On Linux the launcher is Notio/bin/Notio: the marker and the data are one folder up, in Notio/ (D87).
            System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile }?.let { dir ->
                listOfNotNull(dir, dir.parentFile).firstOrNull { File(it, PORTABLE_MARKER).isFile }?.let { return AppPaths(File(it, "data")) }
            }
            val local = System.getenv("LOCALAPPDATA")
            val root = if (!local.isNullOrBlank()) File(local, "Notio")
            else File(System.getProperty("user.home"), ".local/share/notio")
            return AppPaths(root)
        }
    }
}
