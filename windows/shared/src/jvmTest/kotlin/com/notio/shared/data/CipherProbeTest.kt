package com.notio.shared.data

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CipherProbeTest {
    @Test
    fun plainDatabaseIsEncryptedInPlaceAsSqlCipher4AndOpensWithTheKey() {
        val dir = Files.createTempDirectory("cipher").toFile()
        val file = File(dir, "t.db")
        DriverManager.getConnection("jdbc:sqlite:" + file.path).use { c ->
            c.createStatement().use { it.execute("CREATE TABLE t(x TEXT)"); it.execute("INSERT INTO t VALUES ('привет')") }
        }
        assertEquals("SQLite format 3", String(file.readBytes(), 0, 15, Charsets.ISO_8859_1))
        DriverManager.getConnection("jdbc:sqlite:" + file.path).use { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA cipher = 'sqlcipher'")
                st.execute("PRAGMA legacy = 4")
                st.executeQuery("PRAGMA rekey = 'secret-42'").close()
            }
        }
        assertTrue(String(file.readBytes(), 0, 15, Charsets.ISO_8859_1) != "SQLite format 3")
        assertFailsWith<Exception> {
            DriverManager.getConnection("jdbc:sqlite:" + file.path).use { c -> c.createStatement().use { it.executeQuery("SELECT x FROM t").use { rs -> rs.next() } } }
        }
        DriverManager.getConnection("jdbc:sqlite:file:" + file.invariantSeparatorsPath + "?cipher=sqlcipher&legacy=4&key=secret-42").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT x FROM t").use { rs -> rs.next(); assertEquals("привет", rs.getString(1)) } }
        }
        dir.deleteRecursively()
    }
}
