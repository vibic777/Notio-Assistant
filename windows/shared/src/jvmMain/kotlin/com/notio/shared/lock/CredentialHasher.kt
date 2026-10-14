package com.notio.shared.lock

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2 hashing for passwords and 3x3 patterns (AGENT_EN.md §6.1, §13, INV-9).
 * Secrets are never stored or logged in plaintext; callers should wipe the `CharArray` after use.
 *
 * Note: a pattern has a tiny search space, so its hash only protects against casual reading of
 * storage. The hash itself must additionally live in Keystore-backed secure storage (Phase 5).
 */
class CredentialHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Base64 salt and hash and the rounds they were made with (O4), ready for [AppLockConfig]. */
    data class Hashed(val salt: String, val hash: String, val iterations: Int)

    /** The rounds new hashes get; a stored hash with fewer is made again at the next right try. */
    val currentIterations: Int get() = iterations

    fun hash(secret: CharArray): Hashed {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return Hashed(salt = encode(salt), hash = encode(derive(secret, salt, iterations)), iterations = iterations)
    }

    /** [rounds] are the ones the hash was made with (stored beside it); not the current ones. */
    fun verify(secret: CharArray, salt: String, expectedHash: String, rounds: Int = iterations): Boolean {
        val actual = derive(secret, decode(salt), rounds)
        // Constant-time comparison.
        return MessageDigest.isEqual(actual, decode(expectedHash))
    }

    fun hashPattern(pattern: List<Int>): Hashed = hash(patternToChars(pattern))

    fun verifyPattern(pattern: List<Int>, salt: String, expectedHash: String, rounds: Int = iterations): Boolean =
        verify(patternToChars(pattern), salt, expectedHash, rounds)

    private fun derive(secret: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        require(rounds in 1..MAX_ITERATIONS) { "rounds" }
        val spec = PBEKeySpec(secret, salt, rounds, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun patternToChars(pattern: List<Int>): CharArray = pattern.joinToString(",").toCharArray()

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        const val DEFAULT_ITERATIONS = 210_000

        /** What every hash stored before the count was kept (0.9.77) was made with. Never change it. */
        const val LEGACY_ITERATIONS = 210_000

        /** A stored count above this is not trusted (a damaged file would otherwise hang the check). */
        private const val MAX_ITERATIONS = 10_000_000

        /** Android's 3x3 grid: cells 0..8, no repeats, at least 4 dots. */
        const val MIN_PATTERN_LENGTH = 4

        fun isValidPattern(pattern: List<Int>): Boolean =
            pattern.size >= MIN_PATTERN_LENGTH &&
                pattern.all { it in 0..8 } &&
                pattern.toSet().size == pattern.size
    }
}
