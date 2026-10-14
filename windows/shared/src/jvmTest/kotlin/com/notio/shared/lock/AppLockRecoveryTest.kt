package com.notio.shared.lock

import com.notio.shared.domain.LockMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom timeout and the two new ways to recover a forgotten lock: a code by e-mail and an authenticator app (D129). */
class AppLockRecoveryTest {
    private class MemoryStore(var config: AppLockConfig = AppLockConfig()) : AppLockStore {
        override suspend fun load() = config
        override suspend fun save(config: AppLockConfig) { this.config = config }
    }

    private class FakeMailer(var result: MailResult = MailResult.Sent) : MailSender {
        val sent = mutableListOf<Triple<SmtpSettings, String, String>>()
        override suspend fun send(settings: SmtpSettings, subject: String, body: String): MailResult {
            sent += Triple(settings, subject, body)
            return result
        }
    }

    private class MemorySmtp(var value: SmtpSettings? = null) : SmtpStore {
        override suspend fun load() = value
        override suspend fun save(settings: SmtpSettings?) { value = settings }
    }

    private val clock = TestClock(1_000_000L)
    private val store = MemoryStore()
    private val mailer = FakeMailer()
    private val smtpStore = MemorySmtp()
    private val lock = AppLockUseCases(store, CredentialHasher(iterations = 1_000), clock, PlainSecretBox(), mailer, smtpStore)
    private val goodSmtp = SmtpSettings("smtp.example.com", 587, SmtpSecurity.STARTTLS, "kate", "pw", "notio@example.com", "kate@example.org")

    private fun password(secret: String = "hunter2", question: String? = null, answer: String? = null) =
        LockSetup(LockMethod.PASSWORD, secret, question = question, answer = answer)

    private fun codeOf(text: String) = Regex("\\d{6}").find(text)!!.value
    private fun letter(code: String, minutes: Int) = "Code $code for $minutes minutes"

    @Test
    fun `any minutes and seconds make a timeout, the presets stay, and impossible values are refused`() = runBlocking {
        assertEquals(600, LockTimeouts.fromMinutesAndSeconds(10, 0))
        assertEquals(30, LockTimeouts.fromMinutesAndSeconds(0, 30))
        assertEquals(0, LockTimeouts.fromMinutesAndSeconds(0, 0))
        assertEquals(86_399, LockTimeouts.fromMinutesAndSeconds(1439, 59))
        assertNull(LockTimeouts.fromMinutesAndSeconds(1440, 0))
        assertNull(LockTimeouts.fromMinutesAndSeconds(1, 60))
        assertNull(LockTimeouts.fromMinutesAndSeconds(-1, 5))
        assertTrue(LockTimeouts.isPreset(60) && LockTimeouts.isPreset(-1) && !LockTimeouts.isPreset(61))
        assertEquals(10, LockTimeouts.minutesOf(605))
        assertEquals(5, LockTimeouts.secondsOf(605))

        lock.enable(password())
        lock.updateOptions(false, 605)
        assertEquals(605, store.config.timeoutSeconds)
        lock.updateOptions(false, -1)
        assertEquals(-1, store.config.timeoutSeconds)
        val on = AppLockConfig(enabled = true, timeoutSeconds = 605)
        assertTrue("asked for after exactly that long", LockPolicy.shouldLock(on, 1_000_000 + 605_000, 1_000_000))
        assertFalse(LockPolicy.shouldLock(on, 1_000_000 + 604_999, 1_000_000))
    }

    @Test
    fun `e-mail recovery is on exactly when complete server settings are saved`() = runBlocking {
        lock.enable(password())
        assertFalse(store.config.emailRecovery)
        lock.saveSmtp(goodSmtp.copy(to = "nobody"))
        assertFalse("an incomplete setup does not switch it on", store.config.emailRecovery)
        lock.saveSmtp(goodSmtp)
        assertTrue(store.config.emailRecovery)
        assertTrue(RecoveryMethod.EMAIL in lock.recoveryMethods(store.config))
        lock.saveSmtp(null)
        assertFalse(store.config.emailRecovery)
        assertNull(smtpStore.value)
    }

    @Test
    fun `a code is mailed, only its hash is kept, and the right code sets a new password once`() = runBlocking {
        lock.enable(password())
        lock.saveSmtp(goodSmtp)

        assertEquals(EmailCodeResult.Sent, lock.sendRecoveryCode("subject", ::letter))
        val code = codeOf(mailer.sent.single().third)
        assertEquals("kate@example.org", mailer.sent.single().first.to)
        assertFalse("the code itself is not stored", Json.encodeToString(AppLockConfig.serializer(), store.config).contains(code))

        val (wrong, _) = lock.recoverWithEmail(if (code == "111111") "222222" else "111111", password("brandnew"))
        assertTrue(wrong is UnlockResult.Wrong)
        assertEquals(UnlockResult.Success, lock.verify("hunter2"))

        val (ok, error) = lock.recoverWithEmail(" $code ", password("brandnew"))
        assertEquals(UnlockResult.Success, ok)
        assertNull(error)
        assertEquals(UnlockResult.Success, lock.verify("brandnew"))
        assertTrue(lock.verify("hunter2") is UnlockResult.Wrong)

        val (again, reuse) = lock.recoverWithEmail(code, password("another1"))
        assertTrue("a used code is gone", again is UnlockResult.Wrong && reuse == LockSetupError.RECOVERY_NOT_SET)
    }

    @Test
    fun `the code expires after ten minutes, dies after five wrong tries, and a new one waits a minute`() = runBlocking {
        lock.enable(password())
        lock.saveSmtp(goodSmtp)
        lock.sendRecoveryCode("s", ::letter)
        val first = codeOf(mailer.sent.last().third)
        assertTrue(lock.sendRecoveryCode("s", ::letter) is EmailCodeResult.TooSoon)
        assertEquals("nothing more was sent", 1, mailer.sent.size)

        clock.current += 10 * 60_000 + 1
        val (late, expired) = lock.recoverWithEmail(first, password("brandnew"))
        assertTrue(late is UnlockResult.Wrong && expired == LockSetupError.CODE_EXPIRED)

        assertEquals(EmailCodeResult.Sent, lock.sendRecoveryCode("s", ::letter))
        val second = codeOf(mailer.sent.last().third)
        val wrongCode = if (second == "123456") "654321" else "123456"
        repeat(AppLockUseCases.CODE_TRIES) { lock.recoverWithEmail(wrongCode, password("brandnew")); clock.current += 60 * 60_000 }
        val (dead, error) = lock.recoverWithEmail(second, password("brandnew"))
        assertTrue("five wrong tries throw the code away", dead is UnlockResult.Wrong && error == LockSetupError.RECOVERY_NOT_SET)
    }

    @Test
    fun `a failed send keeps no code and says why, and nothing is sent when e-mail is not set up`() = runBlocking {
        lock.enable(password())
        assertEquals(EmailCodeResult.NotConfigured, lock.sendRecoveryCode("s", ::letter))
        lock.saveSmtp(goodSmtp)
        mailer.result = MailResult.Failed(MailError.AUTH, "535")
        assertEquals(EmailCodeResult.Failed(MailError.AUTH, "535"), lock.sendRecoveryCode("s", ::letter))
        assertNull(store.config.emailCodeHash)
        assertEquals("a failed try does not start the one-minute wait", 0L, store.config.emailLastSentAt)
    }

    @Test
    fun `a test letter uses the settings as typed, before they are saved`() = runBlocking {
        assertEquals(MailResult.Sent, lock.sendTestMail(goodSmtp, "t", "b"))
        assertEquals(goodSmtp, mailer.sent.single().first)
        val without = AppLockUseCases(store, CredentialHasher(iterations = 1_000), clock)
        assertEquals(MailError.NOT_CONFIGURED, (without.sendTestMail(goodSmtp, "t", "b") as MailResult.Failed).error)
    }

    @Test
    fun `the authenticator key is only kept after a right code, is stored encrypted, and its code recovers access`() = runBlocking {
        lock.enable(password())
        val setup = lock.startTotp("Notio by vibic", "app lock")
        assertNull("nothing saved yet", store.config.totpBox)

        val notNow = if (Totp.verify(setup.secret, "000000", clock.now())) "111111" else "000000"
        assertFalse(lock.confirmTotp(setup.secret, notNow))
        assertNull(store.config.totpBox)
        assertTrue(lock.confirmTotp(setup.secret, Totp.code(setup.secret, clock.now())))
        val stored = Json.encodeToString(AppLockConfig.serializer(), store.config)
        assertFalse("the key is not readable in the stored text", stored.contains(setup.secret))
        assertTrue(RecoveryMethod.AUTHENTICATOR in lock.recoveryMethods(store.config))

        val (bad, _) = lock.recoverWithTotp(notNow, password("brandnew"))
        assertTrue(bad is UnlockResult.Wrong)
        val (ok, error) = lock.recoverWithTotp(Totp.code(setup.secret, clock.now()), password("brandnew"))
        assertEquals(UnlockResult.Success, ok)
        assertNull(error)
        assertEquals(UnlockResult.Success, lock.verify("brandnew"))

        lock.removeTotp()
        assertNull(store.config.totpBox)
        val (gone, why) = lock.recoverWithTotp(Totp.code(setup.secret, clock.now()), password("third123"))
        assertTrue(gone is UnlockResult.Wrong && why == LockSetupError.RECOVERY_NOT_SET)
    }

    @Test
    fun `the question can be added, changed and removed later, and wrong answers make the app wait`() = runBlocking {
        lock.enable(password())
        assertTrue(lock.recoveryMethods(store.config).isEmpty())
        assertEquals(LockSetupError.ANSWER_BLANK, lock.setQuestion("Pet?", "  "))
        assertNull(lock.setQuestion("Pet?", "Rex"))
        assertEquals(setOf(RecoveryMethod.QUESTION), lock.recoveryMethods(store.config))
        assertNull(lock.setQuestion(null, null))
        assertTrue(lock.recoveryMethods(store.config).isEmpty())

        lock.setQuestion("Pet?", "Rex")
        repeat(AppLockUseCases.FREE_TRIES) { lock.recover("cat", password("brandnew")) }
        assertTrue(lock.verify("hunter2") is UnlockResult.Blocked)
    }

    @Test
    fun `turning the lock off forgets every recovery way`() = runBlocking {
        lock.enable(password("hunter2", "Pet?", "rex"))
        lock.saveSmtp(goodSmtp)
        val setup = lock.startTotp("x", "y")
        lock.confirmTotp(setup.secret, Totp.code(setup.secret, clock.now()))
        assertEquals(3, lock.recoveryMethods(store.config).size)
        lock.disable("hunter2")
        assertTrue(lock.recoveryMethods(store.config).isEmpty())
        assertNull(store.config.totpBox)
    }
}
