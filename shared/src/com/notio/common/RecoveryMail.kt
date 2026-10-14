package com.notio.common

/**
 * The letter of the recovery code as the last resort (Android D350, Windows D126; owner, 2026-10-10: "the mail gets the data
 * for the entry, the password of the database and the password of the backup, so that everything can be recovered for sure").
 * The secret of the lock is kept only as a hash, so the letter carries a one-time code for it; the two passwords are kept
 * on the device and are added as they are. Sent only when the person presses "Forgot?" and asks for the code.
 */
object RecoveryMail {
    /**
     * [codeText] is the letter of the code as it was. [databasePassword] and [backupPassword] are added when the device has
     * them (`null` or blank: it has none), each with its own line ([databaseLine], [backupLine] take the password), after
     * [note], which says why they are there and that the letter should be deleted afterwards.
     */
    fun body(
        codeText: String,
        databasePassword: String?,
        backupPassword: String?,
        note: String,
        databaseLine: (String) -> String,
        backupLine: (String) -> String,
    ): String {
        val lines = listOfNotNull(
            databasePassword?.takeIf { it.isNotBlank() }?.let(databaseLine),
            backupPassword?.takeIf { it.isNotBlank() }?.let(backupLine),
        )
        if (lines.isEmpty()) return codeText
        return codeText + "\n\n" + note + "\n\n" + lines.joinToString("\n")
    }
}
