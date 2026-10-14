package com.notio.shared.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Files replaced whole or not at all (the whole-code pass, round 2). */
class SafeFileTest {
    @Test
    fun aWriteReplacesTheOldTextAndLeavesNothingBeside() {
        val dir = Files.createTempDirectory("notio-test").toFile()
        try {
            val f = dir.resolve("settings.json")
            SafeFile.writeText(f, "one")
            SafeFile.writeText(f, "two")
            assertEquals("two", SafeFile.readText(f))
            assertFalse(SafeFile.tempOf(f).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aWriteCutOffTheOldWayIsRecoveredFromItsNewCopy() {
        val dir = Files.createTempDirectory("notio-test").toFile()
        try {
            val f = dir.resolve("database-key.json")
            // The old way deleted the file first: a power cut left only the new copy.
            SafeFile.tempOf(f).writeText("{\"sealedKey\":\"x\"}")
            assertEquals("{\"sealedKey\":\"x\"}", SafeFile.readText(f))
            assertFalse(SafeFile.tempOf(f).exists())
            assertNull(SafeFile.readText(dir.resolve("none.json")))
            // The key store's own older copy (".tmp") comes back too.
            val keys = dir.resolve("key.json")
            dir.resolve("key.json.tmp").writeText("""{"sealedKey":"plain:secret"}""")
            assertEquals("secret", DatabaseKeyStore(keys, com.notio.shared.sync.SecretBox.Plain).passphrase())
        } finally {
            dir.deleteRecursively()
        }
    }
}
