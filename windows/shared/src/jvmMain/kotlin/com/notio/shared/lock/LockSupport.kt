package com.notio.shared.lock

/** Erases the app's own data (Android D-lockout WIPE); the desktop deletes its data folder and ends. */
fun interface AppDataWiper {
    fun wipe()
}

/** A secret kept for this user only (the phone's Keystore `SecretBox`); the desktop seals with DPAPI (`sync.SecretBox`). */
typealias SecretBox = com.notio.shared.sync.SecretBox

/** No real sealing: for tests. */
class PlainSecretBox : SecretBox {
    override fun seal(plain: String): String = "plain:" + java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
    override fun open(sealed: String): String? =
        sealed.takeIf { it.startsWith("plain:") }?.let { runCatching { String(java.util.Base64.getDecoder().decode(it.removePrefix("plain:")), Charsets.UTF_8) }.getOrNull() }
}
