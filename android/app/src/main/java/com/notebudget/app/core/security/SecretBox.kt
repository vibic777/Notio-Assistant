package com.notebudget.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps a small secret unreadable in storage (D129): the authenticator key and the e-mail password, which cannot be
 * hashed because they must be used again. The real box ([KeystoreSecretBox]) encrypts with a key that lives in the
 * phone's hardware-backed Keystore and never leaves it.
 */
interface SecretBox {
    fun seal(plain: String): String

    /** `null` when [sealed] cannot be opened (another key, damaged, or from another phone). */
    fun open(sealed: String): String?
}

/** AES-256-GCM with a Keystore key; the text is `base64(iv + ciphertext)`. */
class KeystoreSecretBox(private val alias: String = "notio_secret_box") : SecretBox {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    override fun open(sealed: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

/** For tests and previews only: NOT secret, just a reversible text form so the code paths run without a Keystore. */
class PlainSecretBox : SecretBox {
    override fun seal(plain: String): String = "plain:" + Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
    override fun open(sealed: String): String? =
        sealed.removePrefix("plain:").takeIf { sealed.startsWith("plain:") }
            ?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
}
