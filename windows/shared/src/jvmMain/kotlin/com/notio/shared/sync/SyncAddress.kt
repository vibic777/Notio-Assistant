package com.notio.shared.sync

/**
 * An address of a paired device typed by hand (Android D280): used when a device got another address from the Wi-Fi (".152" one day,
 * ".153" the next) and the device is not found on the network by itself. Only where to knock changes: the pairing key stays,
 * and a device at that address that does not have it is refused by the handshake ([SyncSession]), so nothing leaves this device.
 */
object SyncAddress {
    /** The host as typed (spaces taken off), or `null` when it is not an IPv4 address or a plain host name. */
    fun host(text: String): String? {
        val h = text.trim()
        if (h.isEmpty() || h.length > 253) return null
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) }) {
            return h.takeIf { parts.all { p -> p.length <= 3 && p.toInt() in 0..255 } }
        }
        // A name such as "laptop.local": letters, digits, hyphens and dots, no part empty.
        val nameOk = parts.all { p -> p.isNotEmpty() && p.length <= 63 && p.all { it.isLetterOrDigit() || it == '-' } && !p.startsWith('-') && !p.endsWith('-') }
        return h.takeIf { nameOk && h.any { it.isLetter() } }
    }

    /** The port as typed, or `null` when it is not 1 - 65535. */
    fun port(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** The ports this device may be told to listen on (D135, Android D357): the ones below 1024 belong to the system. */
    val LISTEN_PORTS = 1024..65535

    /** The port to listen on as typed, or `null` when it is not one of [LISTEN_PORTS]. */
    fun listenPort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in LISTEN_PORTS }
}
