package com.notebudget.app.core.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One-time codes of the "authenticator app" kind (RFC 6238, D129): 6 digits that change every 30 seconds, made from a secret
 * key that the phone's app (Google Authenticator and the like) and this app share. Used to recover a forgotten lock, not
 * to replace it. The key is stored encrypted (see [SecretBox]) and shown once, as a QR code and as text.
 */
object Totp {
    const val PERIOD_SECONDS = 30L
    const val DIGITS = 6
    private const val KEY_BYTES = 20

    /** A new random key, as the upper-case Base32 text authenticator apps take. */
    fun newSecret(random: SecureRandom = SecureRandom()): String =
        Base32.encode(ByteArray(KEY_BYTES).also(random::nextBytes))

    /** The code for the 30-second step that contains [timeMillis]. */
    fun code(secretBase32: String, timeMillis: Long): String {
        val key = Base32.decode(secretBase32) ?: return ""
        return hotp(key, timeMillis / 1000 / PERIOD_SECONDS)
    }

    /**
     * Is [code] right now? The step before and the step after are accepted too ([window] = 1), because the two clocks are
     * never exactly together. Spaces in the typed code are ignored.
     */
    fun verify(secretBase32: String, code: String, timeMillis: Long, window: Int = 1): Boolean {
        val typed = code.filter { !it.isWhitespace() }
        if (typed.length != DIGITS || typed.any { it !in '0'..'9' }) return false
        val key = Base32.decode(secretBase32) ?: return false
        val step = timeMillis / 1000 / PERIOD_SECONDS
        var ok = false
        // No early exit: the time taken does not tell which step matched.
        for (offset in -window..window) {
            if (constantTimeEquals(hotp(key, step + offset), typed)) ok = true
        }
        return ok
    }

    /** The address an authenticator app reads from the QR code. */
    fun uri(issuer: String, account: String, secretBase32: String): String {
        fun enc(text: String) = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secretBase32&issuer=${enc(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"
    }

    private fun hotp(key: ByteArray, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var modulus = 1
        repeat(DIGITS) { modulus *= 10 }
        return (binary % modulus).toString().padStart(DIGITS, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** Base32 (RFC 4648) without padding on output; input may have padding, spaces and lower case. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    /** `null` when [text] has a character that is not Base32. */
    fun decode(text: String): ByteArray? {
        val clean = text.filter { !it.isWhitespace() && it != '-' }.trimEnd('=').uppercase()
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
