package com.notebudget.app.data.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The version of the sync protocol (D226); a device with another version is refused with a clear message. */
const val SYNC_PROTOCOL = 1

/** Why a connection was refused or broke, as a code the screens translate (and the diagnostics show). */
class SyncException(val reason: String, message: String = reason, cause: Throwable? = null) : IOException(message, cause)

/** The first, plain message of each side: who it is, its one-time ECDH key and what it wants. Never contains a secret. */
@Serializable
data class Hello(
    val v: Int = SYNC_PROTOCOL,
    /** "pair" (first connection, with a code) or "sync" (a stored pairing). */
    val purpose: String = "sync",
    val deviceId: String = "",
    val name: String = "",
    val pub: String = "",
    val nonce: String = "",
    val time: Long = 0,
    /** For "sync": the pairing both sides stored. */
    val pairingId: String? = null,
    /** Set by the answering side instead of the rest when it refuses: see [SyncReasons]. */
    val error: String? = null,
)

/** Everything said after the handshake, encrypted. [t] is the kind; the other fields belong to some kinds only. */
@Serializable
data class SyncMessage(
    val t: String,
    val pairingId: String? = null,
    val secret: String? = null,
    val direction: String? = null,
    val mode: String? = null,
    val folders: List<String>? = null,
    /** `false`: the reminders stay where they are (D319); absent (an older Notio) or `true`: they travel with their notes. */
    val reminders: Boolean? = null,
    /**
     * `true`: everything of this sync was set beforehand on the starting device, the answering one does not ask its person
     * (D322), a replacement excepted. Absent (an older Notio, or something was chosen in the dialog): the answering device asks.
     */
    val unattended: Boolean? = null,
    val folderList: List<FolderInfo>? = null,
    val ok: Boolean? = null,
    val reason: String? = null,
    val time: Long? = null,
    val text: String? = null,
)

@Serializable
data class FolderInfo(val id: String, val name: String)

/** The refusal codes of the protocol. */
object SyncReasons {
    const val NOT_WAITING = "not-waiting"
    const val UNKNOWN_PAIRING = "unknown-pairing"
    const val VERSION = "version"
    const val BUSY = "busy"
    const val WRONG_CODE = "wrong-code"
    const val DENIED = "denied"
    const val TIMEOUT = "timeout"
    const val BROKEN = "broken"
    /** The receiving device has too little free storage for the data and its import. */
    const val NO_SPACE = "no-space"
}

/**
 * One encrypted connection (D226) over a socket's streams. [handshake] runs the key exchange; after it [send] / [receive] carry
 * [SyncMessage]s and [sendData] / [receiveData] a backup stream in chunks, all sealed with AES-GCM.
 */
class SyncSession private constructor(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendDirection: Int,
    val peer: Hello,
    /** The other device's clock minus ours, as far as the hellos tell (ms). */
    val clockOffset: Long,
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    fun send(message: SyncMessage) = writeSealed(TYPE_MESSAGE, JSON.encodeToString(SyncMessage.serializer(), message).toByteArray())

    fun receive(): SyncMessage {
        val (type, body) = readSealed()
        if (type != TYPE_MESSAGE) throw SyncException(SyncReasons.BROKEN, "unexpected data")
        val message = JSON.decodeFromString(SyncMessage.serializer(), String(body))
        if (message.t == "error") throw SyncException(message.reason ?: SyncReasons.BROKEN)
        return message
    }

    /** Streams what [write] writes to the peer, in chunks of at most [CHUNK] bytes, then an end mark. Returns the size. */
    suspend fun sendData(write: suspend (OutputStream) -> Unit): Long {
        var total = 0L
        val stream = object : OutputStream() {
            private val buffer = java.io.ByteArrayOutputStream(CHUNK)
            override fun write(b: Int) { buffer.write(b); if (buffer.size() >= CHUNK) flush() }
            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off
                var left = len
                while (left > 0) {
                    val n = minOf(left, CHUNK - buffer.size())
                    buffer.write(b, o, n)
                    o += n; left -= n
                    if (buffer.size() >= CHUNK) flush()
                }
            }
            override fun flush() {
                if (buffer.size() == 0) return
                total += buffer.size()
                writeSealed(TYPE_DATA, buffer.toByteArray())
                buffer.reset()
            }
            override fun close() = flush()
        }
        stream.use { write(it) }
        writeSealed(TYPE_DATA_END, ByteArray(0))
        return total
    }

    /**
     * Writes the peer's data stream into [to] until its end mark; [maxBytes] guards the phone's storage. [freeBytes] is the free
     * storage now: the import copies the files out of [to] once more, so the stream stops when what is left would not hold what
     * came so far plus [SPACE_RESERVE] (a full phone misbehaves everywhere, not only here).
     */
    fun receiveData(to: File, maxBytes: Long = MAX_DATA, freeBytes: () -> Long = { Long.MAX_VALUE }): Long {
        var total = 0L
        var checkedAt = 0L
        to.outputStream().buffered().use { out ->
            while (true) {
                val (type, body) = readSealed()
                when (type) {
                    TYPE_DATA -> {
                        total += body.size
                        if (total > maxBytes) throw SyncException(SyncReasons.BROKEN, "too much data")
                        if (total - checkedAt >= SPACE_CHECK_EVERY || checkedAt == 0L) {
                            checkedAt = total
                            if (freeBytes() < total + SPACE_RESERVE) throw SyncException(SyncReasons.NO_SPACE, "after ${total / (1024 * 1024)} MB")
                        }
                        out.write(body)
                    }
                    TYPE_DATA_END -> return total
                    TYPE_MESSAGE -> {
                        val m = JSON.decodeFromString(SyncMessage.serializer(), String(body))
                        throw SyncException(m.reason ?: SyncReasons.BROKEN)
                    }
                    else -> throw SyncException(SyncReasons.BROKEN, "unknown frame")
                }
            }
        }
    }

    private fun writeSealed(type: Int, body: ByteArray) {
        val plain = ByteArray(body.size + 1).also { it[0] = type.toByte(); System.arraycopy(body, 0, it, 1, body.size) }
        val sealed = SyncCrypto.seal(sendKey, sendDirection, sendCounter++, plain)
        writeFrame(output, sealed)
    }

    private fun readSealed(): Pair<Int, ByteArray> {
        val sealed = readFrame(input, MAX_FRAME + 64)
        val plain = try {
            SyncCrypto.open(receiveKey, 1 - sendDirection, receiveCounter++, sealed)
        } catch (e: java.security.GeneralSecurityException) {
            throw SyncException(SyncReasons.BROKEN, "a frame failed its check", e)
        }
        if (plain.isEmpty()) throw SyncException(SyncReasons.BROKEN, "empty frame")
        return plain[0].toInt() to plain.copyOfRange(1, plain.size)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private const val TYPE_MESSAGE = 1
        private const val TYPE_DATA = 2
        private const val TYPE_DATA_END = 3
        const val CHUNK = 64 * 1024
        private const val MAX_FRAME = CHUNK + 1
        private const val MAX_HELLO = 4096
        /** The largest data stream accepted in one go (a backup with many videos). */
        const val MAX_DATA = 16L * 1024 * 1024 * 1024
        /** Free storage that a receiving device always keeps. */
        const val SPACE_RESERVE = 200L * 1024 * 1024
        private const val SPACE_CHECK_EVERY = 8L * 1024 * 1024

        private fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }

        private fun readFrame(input: DataInputStream, max: Int): ByteArray {
            val size = try { input.readInt() } catch (e: java.io.EOFException) { throw SyncException(SyncReasons.BROKEN, "the other device closed the connection", e) }
            if (size < 0 || size > max) throw SyncException(SyncReasons.BROKEN, "frame of $size bytes")
            return ByteArray(size).also { input.readFully(it) }
        }

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        private fun unb64(text: String): ByteArray = try { Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) { throw SyncException(SyncReasons.BROKEN, "bad hello", e) }

        private fun readHello(input: DataInputStream): Pair<Hello, ByteArray> {
            val bytes = readFrame(input, MAX_HELLO)
            val hello = try { JSON.decodeFromString(Hello.serializer(), String(bytes)) } catch (e: Exception) { throw SyncException(SyncReasons.BROKEN, "not a Notio device", e) }
            return hello to bytes
        }

        /**
         * The connecting side: says [hello] (without key fields, they are added here), checks the answer and the proof.
         * [pairingKey] is the stretched code (pairing) or the stored secret (sync) — computed from the transcript salt.
         */
        fun connect(
            rawIn: InputStream,
            rawOut: OutputStream,
            hello: Hello,
            pairingKey: (salt: ByteArray) -> ByteArray,
            now: () -> Long = System::currentTimeMillis,
        ): SyncSession {
            val input = DataInputStream(rawIn.buffered())
            val output = DataOutputStream(rawOut.buffered())
            val keys = SyncCrypto.newKeyPair()
            val mine = hello.copy(v = SYNC_PROTOCOL, pub = b64(SyncCrypto.publicBytes(keys)), nonce = b64(SyncCrypto.randomBytes(16)), time = now())
            val mineBytes = JSON.encodeToString(Hello.serializer(), mine).toByteArray()
            writeFrame(output, mineBytes)
            val (theirs, theirsBytes) = readHello(input)
            theirs.error?.let { throw SyncException(it) }
            if (theirs.v != SYNC_PROTOCOL) throw SyncException(SyncReasons.VERSION)
            val transcript = SyncCrypto.sha256(mineBytes, theirsBytes)
            val session = SyncCrypto.sessionKeys(SyncCrypto.agree(keys, unb64(theirs.pub)), pairingKey(transcript), transcript)
            // The answering side proves first; a wrong code or pairing shows here, before anything is sent.
            val proof = readFrame(input, 64)
            if (!SyncCrypto.constantTimeEquals(proof, SyncCrypto.hmac(session.serverProof, transcript))) {
                throw SyncException(if (hello.purpose == "pair") SyncReasons.WRONG_CODE else SyncReasons.UNKNOWN_PAIRING)
            }
            writeFrame(output, SyncCrypto.hmac(session.clientProof, transcript))
            return SyncSession(input, output, session.clientToServer, session.serverToClient, 0, theirs, theirs.time - now())
        }

        /**
         * The answering side: reads the hello, asks [keyFor] for the pairing key of what it wants (or a refusal code), proves itself
         * and checks the other's proof. [mine] is this device's hello (key fields are added here).
         */
        fun accept(
            rawIn: InputStream,
            rawOut: OutputStream,
            mine: Hello,
            keyFor: (hello: Hello, salt: ByteArray) -> Result<ByteArray>,
            now: () -> Long = System::currentTimeMillis,
        ): SyncSession {
            val input = DataInputStream(rawIn.buffered())
            val output = DataOutputStream(rawOut.buffered())
            val (theirs, theirsBytes) = readHello(input)
            fun refuse(reason: String): Nothing {
                writeFrame(output, JSON.encodeToString(Hello.serializer(), Hello(error = reason)).toByteArray())
                throw SyncException(reason)
            }
            if (theirs.v != SYNC_PROTOCOL) refuse(SyncReasons.VERSION)
            val keys = SyncCrypto.newKeyPair()
            val answer = mine.copy(v = SYNC_PROTOCOL, pub = b64(SyncCrypto.publicBytes(keys)), nonce = b64(SyncCrypto.randomBytes(16)), time = now())
            val answerBytes = JSON.encodeToString(Hello.serializer(), answer).toByteArray()
            val transcript = SyncCrypto.sha256(theirsBytes, answerBytes)
            val pairingKey = keyFor(theirs, transcript).getOrElse { refuse((it as? SyncException)?.reason ?: SyncReasons.BROKEN) }
            writeFrame(output, answerBytes)
            val session = SyncCrypto.sessionKeys(SyncCrypto.agree(keys, unb64(theirs.pub)), pairingKey, transcript)
            writeFrame(output, SyncCrypto.hmac(session.serverProof, transcript))
            val proof = try { readFrame(input, 64) } catch (e: SyncException) {
                // The other side hung up after our proof: it did not accept it (another code, another pairing).
                throw SyncException(if (theirs.purpose == "pair") SyncReasons.WRONG_CODE else SyncReasons.UNKNOWN_PAIRING, e.message ?: "", e)
            }
            if (!SyncCrypto.constantTimeEquals(proof, SyncCrypto.hmac(session.clientProof, transcript))) {
                throw SyncException(if (theirs.purpose == "pair") SyncReasons.WRONG_CODE else SyncReasons.UNKNOWN_PAIRING)
            }
            return SyncSession(input, output, session.serverToClient, session.clientToServer, 1, theirs, theirs.time - now())
        }
    }
}
