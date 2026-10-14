package com.notio.shared.lock

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One-time codes (RFC 6238) and the mail client (D129). */
class SecurityCodesTest {

    // ---------------- Base32 and TOTP: the test values of RFC 4648 and RFC 6238 ----------------

    @Test
    fun `base32 encodes and decodes like the standard, ignoring spaces, case and padding`() {
        assertEquals("", Base32.encode(ByteArray(0)))
        assertEquals("MY", Base32.encode("f".toByteArray()))
        assertEquals("MZXW6YQ", Base32.encode("foob".toByteArray()))
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
        assertEquals("foobar", String(Base32.decode("mzxw 6ytb-oi======")!!))
        assertNull(Base32.decode("not base32!"))
    }

    private val rfcSecret = Base32.encode("12345678901234567890".toByteArray())

    @Test
    fun `the codes match the test vectors of RFC 6238`() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", rfcSecret)
        // the RFC lists 8 digits; the last 6 are what an authenticator app shows
        assertEquals("287082", Totp.code(rfcSecret, 59_000))
        assertEquals("081804", Totp.code(rfcSecret, 1_111_111_109_000))
        assertEquals("050471", Totp.code(rfcSecret, 1_111_111_111_000))
        assertEquals("005924", Totp.code(rfcSecret, 1_234_567_890_000))
        assertEquals("279037", Totp.code(rfcSecret, 2_000_000_000_000))
        assertEquals("353130", Totp.code(rfcSecret, 20_000_000_000_000))
    }

    @Test
    fun `a code is accepted one step early or late and no further, only as six digits`() {
        val now = 1_111_111_109_000L
        val right = Totp.code(rfcSecret, now)
        assertTrue(Totp.verify(rfcSecret, right, now))
        assertTrue(Totp.verify(rfcSecret, Totp.code(rfcSecret, now - 30_000), now))
        assertTrue(Totp.verify(rfcSecret, Totp.code(rfcSecret, now + 30_000), now))
        assertFalse(Totp.verify(rfcSecret, Totp.code(rfcSecret, now - 90_000), now))
        assertTrue("spaces are ignored", Totp.verify(rfcSecret, right.take(3) + " " + right.drop(3), now))
        assertFalse(Totp.verify(rfcSecret, "12345", now))
        assertFalse(Totp.verify(rfcSecret, "abcdef", now))
        assertFalse(Totp.verify("not base32!", right, now))
    }

    @Test
    fun `a new key is 32 base32 characters and the authenticator address carries it`() {
        val secret = Totp.newSecret()
        assertEquals(32, secret.length)
        assertTrue(Base32.decode(secret)!!.size == 20)
        val uri = Totp.uri("Notio by vibic", "app lock", secret)
        assertTrue(uri.startsWith("otpauth://totp/Notio%20by%20vibic:app%20lock?secret=$secret"))
        assertTrue(uri.contains("issuer=Notio%20by%20vibic") && uri.contains("digits=6") && uri.contains("period=30"))
    }

    @Test
    fun `the plain test box round trips and refuses foreign text`() {
        val box = PlainSecretBox()
        assertEquals("s3cret ✓", box.open(box.seal("s3cret ✓")))
        assertNull(box.open("garbage"))
    }

    // ---------------- SMTP against a small fake server on this computer ----------------

    private class FakeServer(
        val authOk: Boolean = true,
        val rcptCode: Int = 250,
        val offerAuth: String = "PLAIN LOGIN",
    ) : AutoCloseable {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        /** Written by the server thread, read by the test: the client may return before the server has noted its QUIT. */
        // Written by the thread of the server while the test reads it: a list that can be walked meanwhile.
        val commands: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        var data = ""
        private val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val out = socket.getOutputStream()
                    fun say(text: String) { out.write((text + "\r\n").toByteArray()); out.flush() }
                    say("220 fake ESMTP")
                    while (true) {
                        val line = reader.readLine() ?: break
                        commands += line
                        when {
                            line.startsWith("EHLO") -> { say("250-fake"); say("250-AUTH $offerAuth"); say("250 8BITMIME") }
                            line.startsWith("AUTH PLAIN") -> say(if (authOk) "235 ok" else "535 bad credentials")
                            line.startsWith("MAIL FROM") -> say("250 ok")
                            line.startsWith("RCPT TO") -> say(if (rcptCode == 250) "250 ok" else "$rcptCode no such user")
                            line == "DATA" -> {
                                say("354 go on")
                                val body = StringBuilder()
                                while (true) {
                                    val l = reader.readLine() ?: break
                                    if (l == ".") break
                                    body.append(l).append('\n')
                                }
                                data = body.toString()
                                say("250 queued")
                            }
                            line == "QUIT" -> { say("221 bye"); break }
                            else -> say("500 what")
                        }
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        override fun close() { runCatching { server.close() } }
    }

    private fun settings(port: Int) = SmtpSettings(
        host = "127.0.0.1", port = port, security = SmtpSecurity.NONE,
        username = "kate", password = "p@ss word", from = "notio@example.com", to = "kate@example.org",
    )

    private fun send(settings: SmtpSettings, subject: String = "Code", body: String = "Your code: 123456") =
        runBlocking { SmtpClient(timeoutMillis = 5_000).send(settings, subject, body) }

    @Test
    fun `a letter goes through greeting, login, addresses and data, and its body reads back`() {
        FakeServer().use { fake ->
            val result = send(settings(fake.port), "Notio: код", "Ваш код: 123456\n.\nline with a dot alone")
            assertEquals(MailResult.Sent, result)
            assertEquals("EHLO notio.local", fake.commands[0])
            val plain = fake.commands.first { it.startsWith("AUTH PLAIN") }.removePrefix("AUTH PLAIN ")
            assertEquals("\u0000kate\u0000p@ss word", String(Base64.getDecoder().decode(plain)))
            assertTrue(fake.commands.contains("MAIL FROM:<notio@example.com>"))
            assertTrue(fake.commands.contains("RCPT TO:<kate@example.org>"))
            val until = System.currentTimeMillis() + 2_000
            while (fake.commands.lastOrNull() != "QUIT" && System.currentTimeMillis() < until) Thread.sleep(5)
            assertEquals("QUIT", fake.commands.last())
            assertTrue(fake.data.contains("Subject: =?UTF-8?B?"))
            val encoded = fake.data.substringAfter("\n\n").lines().joinToString("")
            assertEquals("Ваш код: 123456\n.\nline with a dot alone", String(Base64.getMimeDecoder().decode(encoded)))
        }
    }

    @Test
    fun `a wrong password is an authentication failure and a refused address is a rejection`() {
        FakeServer(authOk = false).use { fake ->
            val r = send(settings(fake.port)) as MailResult.Failed
            assertEquals(MailError.AUTH, r.error)
            assertFalse("the password never appears in the message", r.detail.contains("p@ss"))
        }
        FakeServer(rcptCode = 550).use { fake ->
            assertEquals(MailError.REJECTED, (send(settings(fake.port)) as MailResult.Failed).error)
        }
    }

    @Test
    fun `login without a password mechanism the server offers is refused, and a closed port is a connection error`() {
        FakeServer(offerAuth = "CRAM-MD5").use { fake ->
            assertEquals(MailError.AUTH, (send(settings(fake.port)) as MailResult.Failed).error)
        }
        val closed = ServerSocket(0).use { it.localPort } // nothing listens there any more
        assertEquals(MailError.CONNECTION, (send(settings(closed)) as MailResult.Failed).error)
    }

    @Test
    fun `incomplete settings and addresses with line breaks are never sent`() {
        assertEquals(MailError.NOT_CONFIGURED, (send(SmtpSettings()) as MailResult.Failed).error)
        FakeServer().use { fake ->
            val evil = settings(fake.port).copy(to = "kate@example.org\r\nBcc: x@evil.test")
            assertEquals(MailError.NOT_CONFIGURED, (send(evil) as MailResult.Failed).error)
            assertTrue("nothing reached the server", fake.commands.isEmpty())
        }
    }

    @Test
    fun `the settings know when they are complete and the usual ports`() {
        assertTrue(settings(25).isComplete)
        assertFalse(settings(25).copy(host = " ").isComplete)
        assertFalse(settings(25).copy(to = "nobody").isComplete)
        assertFalse(settings(0).isComplete)
        assertTrue("the login is the sender when it is an address", settings(25).copy(from = "", username = "kate@example.com").isComplete)
        assertEquals(25, SmtpSettings.defaultPort(SmtpSecurity.NONE))
        assertEquals(587, SmtpSettings.defaultPort(SmtpSecurity.STARTTLS))
        assertEquals(465, SmtpSettings.defaultPort(SmtpSecurity.SSL_TLS))
    }
}
