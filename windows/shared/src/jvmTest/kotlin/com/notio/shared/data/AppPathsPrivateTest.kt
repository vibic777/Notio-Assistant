package com.notio.shared.data

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The folder of the data is for its person alone (D134). */
class AppPathsPrivateTest {
    @Test
    fun `the data folder is made, and on a system with such rights nobody else reads it`() {
        val root = File("build/tmp/private-test-" + System.nanoTime() + "/notio")
        try {
            val paths = AppPaths(root)
            paths.keepPrivate()
            assertTrue(root.isDirectory)
            val posix = Files.getFileAttributeView(root.toPath(), PosixFileAttributeView::class.java) != null
            if (posix) {
                // One that an earlier version left open to everybody is closed at the next start.
                Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
                paths.keepPrivate()
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.toPath())))
            }
        } finally {
            root.parentFile.deleteRecursively()
        }
    }
}
