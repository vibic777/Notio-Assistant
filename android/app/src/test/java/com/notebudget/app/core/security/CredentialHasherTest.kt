package com.notebudget.app.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialHasherTest {
    // Few iterations keep the test fast; production uses DEFAULT_ITERATIONS.
    private val hasher = CredentialHasher(iterations = 1_000)

    @Test
    fun `password is not stored in plaintext and verifies`() {
        val hashed = hasher.hash("correct horse".toCharArray())
        assertFalse(hashed.hash.contains("correct horse"))
        assertFalse(hashed.salt.contains("correct horse"))
        assertTrue(hasher.verify("correct horse".toCharArray(), hashed.salt, hashed.hash))
        assertFalse(hasher.verify("wrong".toCharArray(), hashed.salt, hashed.hash))
    }

    @Test
    fun `the same password gets a different salt and hash each time`() {
        val a = hasher.hash("secret".toCharArray())
        val b = hasher.hash("secret".toCharArray())
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.hash, b.hash)
    }

    @Test
    fun `pattern is hashed and verifies only for the same sequence`() {
        val pattern = listOf(0, 1, 2, 5, 8)
        val hashed = hasher.hashPattern(pattern)
        assertFalse(hashed.hash.contains("0,1,2"))
        assertTrue(hasher.verifyPattern(pattern, hashed.salt, hashed.hash))
        assertFalse(hasher.verifyPattern(pattern.reversed(), hashed.salt, hashed.hash))
    }

    @Test
    fun `pattern validation`() {
        assertTrue(CredentialHasher.isValidPattern(listOf(0, 1, 2, 3)))
        assertFalse(CredentialHasher.isValidPattern(listOf(0, 1, 2))) // too short
        assertFalse(CredentialHasher.isValidPattern(listOf(0, 1, 1, 2))) // repeated dot
        assertFalse(CredentialHasher.isValidPattern(listOf(0, 1, 2, 9))) // outside the grid
    }
}
