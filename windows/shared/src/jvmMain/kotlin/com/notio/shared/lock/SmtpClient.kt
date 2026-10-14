package com.notio.shared.lock

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** How the connection to the mail server is protected. */
enum class SmtpSecurity { NONE, STARTTLS, SSL_TLS }

/**
 * Everything needed to send a letter through the person's OWN mail server (D129): the server, the port, the protection
 * (none, STARTTLS, or SSL/TLS from the start), the login and password, and the two addresses. The app has no server of its own.
 */
@Serializable
data class SmtpSettings(
    val host: String = "",
    val port: Int = 587,
    val security: SmtpSecurity = SmtpSecurity.STARTTLS,
    val username: String = "",
    val password: String = "",
    /** The "From" address; when empty the login is used if it looks like an address. */
    val from: String = "",
    /** Where the recovery code goes. */
    val to: String = "",
) {
    val sender: String get() = from.trim().ifEmpty { username.trim() }

    /** Can a letter be sent with these? */
    val isComplete: Boolean
        get() = host.isNotBlank() && port in 1..65535 && sender.contains('@') && to.contains('@') && ADDRESS.matches(to.trim()) && ADDRESS.matches(sender)

    companion object {
        private val ADDRESS = Regex("^[^\\s@<>\"]+@[^\\s@<>\"]+\\.[^\\s@<>\"]+$")

        fun isAddress(text: String) = ADDRESS.matches(text.trim())

        /** The usual port for a protection: 25, 587 (STARTTLS) or 465 (SSL/TLS). */
        fun defaultPort(security: SmtpSecurity) = when (security) {
            SmtpSecurity.NONE -> 25
            SmtpSecurity.STARTTLS -> 587
            SmtpSecurity.SSL_TLS -> 465
        }
    }
}

enum class MailError { NOT_CONFIGURED, CONNECTION, TIMEOUT, TLS, AUTH, REJECTED, PROTOCOL }

sealed interface MailResult {
    data object Sent : MailResult

    /** [detail] is what the server said (or the exception), for the person to read; never contains the password. */
    data class Failed(val error: MailError, val detail: String = "") : MailResult
}

/** Sends one plain-text letter. An interface so the recovery logic is tested without a network. */
interface MailSender {
    suspend fun send(settings: SmtpSettings, subject: String, body: String): MailResult
}

/**
 * A small SMTP client on plain sockets: greeting, EHLO, STARTTLS when asked, AUTH PLAIN or LOGIN, MAIL FROM, RCPT TO, DATA
 * (the body is base64, so nothing in the text can end the message early), QUIT. With the two TLS modes the server's
 * certificate AND its name are checked, so a fake server cannot be told from the real one only by having a certificate.
 */
class SmtpClient(
    private val timeoutMillis: Int = 15_000,
    private val tlsFactory: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
    private val connect: (host: String, port: Int, timeout: Int) -> Socket = { host, port, timeout ->
        Socket().also { it.connect(InetSocketAddress(host, port), timeout) }
    },
    private val clientName: String = "notio.local",
) : MailSender {

    override suspend fun send(settings: SmtpSettings, subject: String, body: String): MailResult = withContext(Dispatchers.IO) {
        if (!settings.isComplete || listOf(settings.sender, settings.to, settings.username).any { it.contains('\r') || it.contains('\n') }) {
            return@withContext MailResult.Failed(MailError.NOT_CONFIGURED)
        }
        var socket: Socket? = null
        try {
            socket = connect(settings.host.trim(), settings.port, timeoutMillis)
            socket.soTimeout = timeoutMillis
            if (settings.security == SmtpSecurity.SSL_TLS) socket = wrapTls(socket, settings.host.trim(), settings.port)
            var io = Session(socket)
            expect(io.reply(), 220)

            var capabilities = ehlo(io)
            if (settings.security == SmtpSecurity.STARTTLS) {
                if ("STARTTLS" !in capabilities) return@withContext MailResult.Failed(MailError.TLS, "STARTTLS")
                io.command("STARTTLS")
                expect(io.reply(), 220)
                socket = wrapTls(socket, settings.host.trim(), settings.port)
                io = Session(socket)
                capabilities = ehlo(io)
            }
            if (settings.username.isNotBlank()) authenticate(io, capabilities, settings)

            io.command("MAIL FROM:<${settings.sender}>")
            expect(io.reply(), 250)
            io.command("RCPT TO:<${settings.to.trim()}>")
            expect(io.reply(), 250, 251)
            io.command("DATA")
            expect(io.reply(), 354)
            io.raw(message(settings, subject, body) + "\r\n.\r\n")
            expect(io.reply(), 250)
            runCatching { io.command("QUIT") }
            MailResult.Sent
        } catch (e: Rejected) {
            MailResult.Failed(if (e.authentication) MailError.AUTH else MailError.REJECTED, e.line)
        } catch (e: SocketTimeoutException) {
            MailResult.Failed(MailError.TIMEOUT, e.message.orEmpty())
        } catch (e: javax.net.ssl.SSLException) {
            MailResult.Failed(MailError.TLS, e.message.orEmpty())
        } catch (e: java.io.IOException) {
            MailResult.Failed(MailError.CONNECTION, e.message.orEmpty())
        } catch (e: Exception) {
            MailResult.Failed(MailError.PROTOCOL, e.message.orEmpty())
        } finally {
            runCatching { socket?.close() }
        }
    }

    // ---- protocol pieces ----

    private class Rejected(val line: String, val authentication: Boolean) : Exception(line)

    private inner class Session(val socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val out: OutputStream = socket.getOutputStream()

        fun command(line: String) = raw(line + "\r\n")

        fun raw(text: String) {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        /** One reply, possibly several lines (`250-...` continues, `250 ...` ends): its code and all its lines. */
        fun reply(): Pair<Int, List<String>> {
            val lines = ArrayList<String>()
            while (true) {
                val line = reader.readLine() ?: throw java.io.IOException("connection closed")
                lines += line
                if (line.length < 4 || line[3] != '-') break
            }
            val code = lines.last().take(3).toIntOrNull() ?: throw IllegalStateException("not an SMTP reply: ${lines.last().take(40)}")
            return code to lines
        }
    }

    private fun expect(reply: Pair<Int, List<String>>, vararg codes: Int) {
        if (reply.first !in codes) throw Rejected(reply.second.last(), authentication = false)
    }

    private fun ehlo(io: Session): Set<String> {
        io.command("EHLO $clientName")
        val reply = io.reply()
        expect(reply, 250)
        return reply.second.drop(1).map { it.drop(4).trim().uppercase() }.toSet()
            .plus(reply.second.drop(1).flatMap { it.drop(4).trim().uppercase().split(' ') })
    }

    private fun authenticate(io: Session, capabilities: Set<String>, settings: SmtpSettings) {
        fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val plain = capabilities.any { it == "PLAIN" || it.startsWith("AUTH PLAIN") || it.startsWith("AUTH=PLAIN") } || "PLAIN" in capabilities
        val login = "LOGIN" in capabilities || capabilities.any { it.contains("LOGIN") }
        when {
            plain -> {
                io.command("AUTH PLAIN " + b64("\u0000${settings.username}\u0000${settings.password}"))
                val reply = io.reply()
                if (reply.first != 235) throw Rejected(reply.second.last(), authentication = true)
            }
            login -> {
                io.command("AUTH LOGIN")
                if (io.reply().first != 334) throw Rejected("AUTH LOGIN refused", authentication = true)
                io.command(b64(settings.username))
                if (io.reply().first != 334) throw Rejected("login refused", authentication = true)
                io.command(b64(settings.password))
                val reply = io.reply()
                if (reply.first != 235) throw Rejected(reply.second.last(), authentication = true)
            }
            else -> throw Rejected("the server offers no PLAIN or LOGIN authentication", authentication = true)
        }
    }

    private fun wrapTls(socket: Socket, host: String, port: Int): Socket {
        val secured = tlsFactory().createSocket(socket, host, port, true) as SSLSocket
        secured.soTimeout = timeoutMillis
        // The server's NAME must match its certificate, not only the certificate be valid.
        secured.sslParameters = secured.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        secured.startHandshake()
        return secured
    }

    private fun message(settings: SmtpSettings, subject: String, body: String): String {
        fun encodedWord(text: String) =
            if (text.all { it.code in 32..126 }) text else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val encodedBody = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8))
        return listOf(
            "From: <${settings.sender}>",
            "To: <${settings.to.trim()}>",
            "Subject: ${encodedWord(subject.replace('\r', ' ').replace('\n', ' '))}",
            "Date: $date",
            "Message-ID: <${UUID.randomUUID()}@$clientName>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: base64",
            "",
            encodedBody,
        ).joinToString("\r\n")
    }
}
