package com.notebudget.app.data.sync

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of device sync (D226), all from the platform (no library):
 * - a fresh ECDH P-256 key pair per connection (forward secrecy: a stolen pairing secret does not open recorded sessions);
 * - the shared secret is bound to the pairing: the pairing code (stretched with PBKDF2) when pairing, the stored 32-byte
 *   pairing secret afterwards; keys come from HKDF-SHA256 over the ECDH secret, that secret and the hash of both hellos;
 * - both sides prove the keys with an HMAC before any data (a wrong code or an unknown device is refused at the handshake);
 * - every frame after that is AES-256-GCM with a counter nonce per direction (replayed / reordered / changed frames fail).
 */
object SyncCrypto {
    private val random = SecureRandom()

    /** Pairing code alphabet: Crockford base32 without the look-alikes I, L, O, U. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LENGTH = 12

    /** A new pairing code, 12 characters (60 bits) shown as `XXXX-XXXX-XXXX`. */
    fun newCode(): String {
        val raw = (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return raw.chunked(4).joinToString("-")
    }

    /** What the person typed, in the form the code was made in: upper case, look-alikes mapped, spaces and dashes dropped. */
    fun normalizeCode(typed: String): String = typed.uppercase()
        .replace('O', '0').replace('I', '1').replace('L', '1')
        .filter { it in ALPHABET }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun publicBytes(pair: KeyPair): ByteArray = pair.public.encoded

    fun agree(own: KeyPair, peerPublic: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublic))
        return KeyAgreement.getInstance("ECDH").apply { init(own.private); doPhase(key, true) }.generateSecret()
    }

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach { update(it) } }.doFinal()

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block, info.toByteArray(), byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /**
     * The pairing code as key material: PBKDF2 (50 000 rounds) with a salt from both hellos, so guessing codes against a
     * recorded handshake costs that much per guess on top of the 60 bits of the code.
     */
    fun stretchCode(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(normalizeCode(code).toCharArray(), salt, 50_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** The keys of one connection, from the ECDH secret, the pairing key and the transcript (hash of both hellos). */
    class SessionKeys(val clientToServer: ByteArray, val serverToClient: ByteArray, val clientProof: ByteArray, val serverProof: ByteArray)

    fun sessionKeys(ecdh: ByteArray, pairingKey: ByteArray, transcript: ByteArray): SessionKeys {
        val material = hkdf(ecdh + pairingKey, transcript, "notio-sync-v1", 128)
        return SessionKeys(
            clientToServer = material.copyOfRange(0, 32),
            serverToClient = material.copyOfRange(32, 64),
            clientProof = material.copyOfRange(64, 96),
            serverProof = material.copyOfRange(96, 128),
        )
    }

    /** AES-256-GCM of one frame; the nonce is the direction and the frame counter, never reused within a connection. */
    fun seal(key: ByteArray, direction: Int, counter: Long, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(direction, counter))) }
            .doFinal(plain)

    fun open(key: ByteArray, direction: Int, counter: Long, sealed: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(direction, counter))) }
            .doFinal(sealed)

    private fun nonce(direction: Int, counter: Long): ByteArray {
        val n = ByteArray(12)
        n[0] = direction.toByte()
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return n
    }
}
