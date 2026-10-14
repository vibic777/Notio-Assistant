package com.notio.shared.lock

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileLockStoreTest {
    private val dir = Files.createTempDirectory("notio-lock").toFile()
    private val file = File(dir, "lock.json")
    private val store = FileLockStore(file)
    private val locked = AppLockConfig(enabled = true, salt = "c2FsdA==", hash = "aGFzaA==")

    @After
    fun cleanup() { dir.deleteRecursively() }

    @Test
    fun `a damaged lock file is read from its copy, not as no lock`() = runBlocking {
        store.save(locked)
        file.writeText("{ broken")
        assertEquals(locked, store.load())
        // The good text is put back.
        assertTrue(file.readText().contains("aGFzaA=="))
    }

    @Test
    fun `a lost lock file is read from its copy`() = runBlocking {
        store.save(locked)
        file.delete()
        assertTrue(store.load().enabled)
    }

    @Test
    fun `a lock of an older version gets its copy at the first read`() = runBlocking {
        store.save(locked)
        File(file.path + ".copy").delete()
        store.load()
        file.delete()
        assertTrue(store.load().enabled)
    }

    @Test
    fun `no files mean no lock`() = runBlocking {
        assertFalse(store.load().enabled)
    }
}
