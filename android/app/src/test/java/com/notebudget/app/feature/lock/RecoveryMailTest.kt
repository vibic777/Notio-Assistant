package com.notebudget.app.feature.lock

import com.notio.common.RecoveryMail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The letter of the recovery code as the last resort (D350). */
class RecoveryMailTest {
    private fun body(database: String?, backup: String?) = RecoveryMail.body(
        "Your code: 123456", database, backup, "The passwords kept on this device.",
        databaseLine = { "Database: $it" }, backupLine = { "Backups: $it" },
    )

    @Test
    fun `the letter carries the passwords the device has, after the code`() {
        assertEquals(
            "Your code: 123456\n\nThe passwords kept on this device.\n\nDatabase: db-secret\nBackups: zip-secret",
            body("db-secret", "zip-secret"),
        )
    }

    @Test
    fun `a password the device does not have is not named`() {
        val onlyBackup = body(null, "zip-secret")
        assertTrue(onlyBackup.endsWith("The passwords kept on this device.\n\nBackups: zip-secret"))
        assertFalse(onlyBackup.contains("Database"))
        assertFalse(body("db-secret", "  ").contains("Backups"))
    }

    @Test
    fun `without any password the letter of the code is as it was`() {
        assertEquals("Your code: 123456", body(null, null))
        assertEquals("Your code: 123456", body("", ""))
    }
}
