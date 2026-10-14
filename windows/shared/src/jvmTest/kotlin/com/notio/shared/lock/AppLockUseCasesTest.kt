package com.notio.shared.lock

import com.notio.shared.domain.LockMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app lock (§6.1, D124): setting it up, checking it, waiting after wrong tries, recovering, and when it asks. */
class AppLockUseCasesTest {
    private class MemoryStore(var config: AppLockConfig = AppLockConfig()) : AppLockStore {
        override suspend fun load() = config
        override suspend fun save(config: AppLockConfig) { this.config = config }
    }

    private val clock = TestClock(1_000_000L)
    private val store = MemoryStore()
    // A few iterations: the real hasher is deliberately slow.
    private val lock = AppLockUseCases(store, CredentialHasher(iterations = 1_000), clock)

    private fun password(secret: String = "hunter2", question: String? = null, answer: String? = null) =
        LockSetup(LockMethod.PASSWORD, secret, question = question, answer = answer)

    @Test
    fun `a password turns the lock on and only that password opens it`() = runBlocking {
        assertNull(lock.enable(password()))
        assertTrue(store.config.enabled)
        assertEquals(UnlockResult.Success, lock.verify("hunter2"))
        assertTrue(lock.verify("hunter3") is UnlockResult.Wrong)
        assertEquals(UnlockResult.Success, lock.verify("hunter2"))
    }

    @Test
    fun `nothing readable is stored, only salted hashes`() = runBlocking {
        lock.enable(password("correct horse", "First pet?", "Rex"))
        val text = Json.encodeToString(AppLockConfig.serializer(), store.config)
        assertFalse(text.contains("correct horse"))
        assertFalse(text.lowercase().contains("rex"))
        assertNotNull(store.config.salt)
        assertNotNull(store.config.hash)
        assertNotNull(store.config.answerHash)
        // the same password twice gives different hashes (a fresh salt each time)
        val first = store.config.hash
        lock.enable(password("correct horse", "First pet?", "Rex"))
        assertTrue(first != store.config.hash)
    }

    @Test
    fun `a hash made with other rounds still opens, and is made again with today's rounds (O4)`() = runBlocking {
        val older = CredentialHasher(iterations = 2_000).hash("hunter2".toCharArray())
        store.config = AppLockConfig(enabled = true, method = LockMethod.PASSWORD, salt = older.salt, hash = older.hash, iterations = 2_000)
        assertTrue(lock.verify("hunter3") is UnlockResult.Wrong)
        assertEquals(2_000, store.config.iterations) // a wrong try changes nothing
        assertEquals(UnlockResult.Success, lock.verify("hunter2"))
        assertEquals(1_000, store.config.iterations)
        assertTrue(store.config.hash != older.hash)
        assertEquals(UnlockResult.Success, lock.verify("hunter2"))
    }

    @Test
    fun `a lock saved before the rounds were kept reads as 210 000 rounds`() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AppLockConfig.serializer(), """{"enabled":true,"salt":"c2FsdA==","hash":"aGFzaA=="}""")
        assertEquals(210_000, old.iterations)
        assertEquals(210_000, old.answerIterations)
    }

    @Test
    fun `a damaged count of rounds is refused, not run`() = runBlocking {
        val hashed = CredentialHasher(iterations = 1_000).hash("hunter2".toCharArray())
        store.config = AppLockConfig(enabled = true, method = LockMethod.PASSWORD, salt = hashed.salt, hash = hashed.hash, iterations = Int.MAX_VALUE)
        assertTrue(lock.verify("hunter2") is UnlockResult.Wrong)
    }

    /** O12: a lock that also knows the clock since boot, which the person cannot set. */
    private var sinceBoot = 50_000L
    private val guarded = AppLockUseCases(store, CredentialHasher(iterations = 1_000), clock, elapsed = { sinceBoot })

    private suspend fun blockOnce() {
        guarded.enable(password())
        guarded.updateLockoutPolicy(freeTries = 1, baseWaitSeconds = 60, maxAttempts = 0, lockoutMode = LockoutMode.RECOVERY_ONLY)
        assertTrue(guarded.verify("nope") is UnlockResult.Blocked) // a 60 s wait from now
    }

    @Test
    fun `moving the phone's time forward does not end the wait`() = runBlocking {
        blockOnce()
        clock.current += 3_600_000 // an hour ahead on the wall clock, 1 s on the clock since boot
        sinceBoot += 1_000
        assertTrue(guarded.verify("hunter2") is UnlockResult.Blocked)
        assertTrue(guarded.waitingUntil() > clock.current)
        sinceBoot += 60_000 // the wait really passed
        assertEquals(UnlockResult.Success, guarded.verify("hunter2"))
    }

    @Test
    fun `moving the phone's time back starts the wait again`() = runBlocking {
        blockOnce()
        clock.current -= 3_600_000
        sinceBoot += 61_000 // the clock since boot is done, the wall clock was set back before the start
        assertTrue(guarded.verify("hunter2") is UnlockResult.Blocked)
    }

    @Test
    fun `after a reboot the wall clock alone ends the wait`() = runBlocking {
        blockOnce()
        sinceBoot = 5_000 // a new boot: smaller than at the start of the wait
        clock.current += 61_000
        assertEquals(UnlockResult.Success, guarded.verify("hunter2"))
    }

    @Test
    fun `wrong tries made at the same moment are all counted`() = runBlocking {
        lock.enable(password())
        lock.updateLockoutPolicy(freeTries = 100, baseWaitSeconds = 1, maxAttempts = 0, lockoutMode = LockoutMode.RECOVERY_ONLY)
        (1..20).map { async(Dispatchers.Default) { lock.verify("wrong$it") } }.forEach { it.await() }
        assertEquals(20, store.config.failedAttempts)
    }

    @Test
    fun `a password must be 4 to 64 characters`() = runBlocking {
        assertEquals(LockSetupError.SECRET_LENGTH, lock.enable(password("abc")))
        assertEquals(LockSetupError.SECRET_LENGTH, lock.enable(password("x".repeat(65))))
        assertNull(lock.enable(password("abcd")))
        assertNull(lock.enable(password("x".repeat(64))))
    }

    @Test
    fun `a pattern needs 4 different dots of the 3 by 3 pad and is checked as the same sequence`() = runBlocking {
        assertEquals(LockSetupError.PATTERN_INVALID, lock.enable(LockSetup(LockMethod.PATTERN_3X3, "0,1,2")))
        assertEquals(LockSetupError.PATTERN_INVALID, lock.enable(LockSetup(LockMethod.PATTERN_3X3, "0,1,1,2")))
        assertEquals(LockSetupError.PATTERN_INVALID, lock.enable(LockSetup(LockMethod.PATTERN_3X3, "0,1,2,9")))
        assertEquals(LockSetupError.PATTERN_INVALID, lock.enable(LockSetup(LockMethod.PATTERN_3X3, "a,b,c,d")))
        assertNull(lock.enable(LockSetup(LockMethod.PATTERN_3X3, PatternGrid.encode(listOf(0, 3, 6, 7, 8)))))
        assertEquals(UnlockResult.Success, lock.verify("0,3,6,7,8"))
        assertTrue("the same dots in another order are another pattern", lock.verify("8,7,6,3,0") is UnlockResult.Wrong)
    }

    @Test
    fun `login and password both have to be right`() = runBlocking {
        assertEquals(LockSetupError.LOGIN_INVALID, lock.enable(LockSetup(LockMethod.LOGIN_PASSWORD, "hunter2", login = "  ")))
        assertNull(lock.enable(LockSetup(LockMethod.LOGIN_PASSWORD, "hunter2", login = " kate ")))
        assertEquals(UnlockResult.Success, lock.verify("hunter2", "kate"))
        assertTrue(lock.verify("hunter2", "someone") is UnlockResult.Wrong)
        assertTrue(lock.verify("wrong-pass", "kate") is UnlockResult.Wrong)
        assertTrue(lock.verify("hunter2", null) is UnlockResult.Wrong)
    }

    @Test
    fun `an unusable method is refused`() = runBlocking {
        assertEquals(LockSetupError.INVALID_METHOD, lock.enable(LockSetup(LockMethod.BIOMETRIC, "whatever")))
        assertEquals(LockSetupError.INVALID_METHOD, lock.enable(LockSetup(LockMethod.NONE, "whatever")))
    }

    @Test
    fun `after five wrong tries the person waits, longer each time, and the right secret is not accepted meanwhile`() = runBlocking {
        lock.enable(password())
        repeat(4) { i -> assertEquals(UnlockResult.Wrong(4 - i), lock.verify("nope")) }
        val blocked = lock.verify("nope") as UnlockResult.Blocked
        assertEquals(clock.current + 10_000, blocked.untilMillis)
        // even the right password waits
        assertTrue(lock.verify("hunter2") is UnlockResult.Blocked)
        clock.current += 10_001
        assertEquals(UnlockResult.Success, lock.verify("hunter2"))
        assertEquals("a right try starts the count again", 0, store.config.failedAttempts)

        repeat(5) { lock.verify("nope") }
        clock.current += 10_001
        val second = lock.verify("nope") as UnlockResult.Blocked // the 6th wrong try in this run waits 20 s
        assertEquals(clock.current + 20_000, second.untilMillis)
    }

    @Test
    fun `the wait grows and stops at fifteen minutes`() {
        assertEquals(0L, AppLockUseCases.waitAfter(1))
        assertEquals(0L, AppLockUseCases.waitAfter(4))
        assertEquals(10_000L, AppLockUseCases.waitAfter(5))
        assertEquals(20_000L, AppLockUseCases.waitAfter(6))
        assertEquals(40_000L, AppLockUseCases.waitAfter(7))
        assertEquals(15 * 60_000L, AppLockUseCases.waitAfter(20))
        assertEquals(15 * 60_000L, AppLockUseCases.waitAfter(1000))
    }

    @Test
    fun `the wait policy is configurable per person`() = runBlocking {
        lock.enable(password())
        lock.updateLockoutPolicy(freeTries = 2, baseWaitSeconds = 5, maxAttempts = 0, lockoutMode = LockoutMode.RECOVERY_ONLY)
        assertTrue(lock.verify("nope") is UnlockResult.Wrong) // 1st: still free
        val blocked = lock.verify("nope") as UnlockResult.Blocked // 2nd: freeTries=2 reached
        assertEquals(clock.current + 5_000, blocked.untilMillis)
    }

    @Test
    fun `too many wrong tries in a row locks the person out of guessing, recovery only`() = runBlocking {
        lock.enable(password(question = "Pet?", answer = "cat"))
        lock.updateLockoutPolicy(freeTries = 1, baseWaitSeconds = 1, maxAttempts = 2, lockoutMode = LockoutMode.RECOVERY_ONLY)
        assertTrue(lock.verify("nope") is UnlockResult.Blocked) // 1st: past freeTries=1, but under maxAttempts=2
        clock.current += 10_000
        assertEquals(UnlockResult.LockedOut, lock.verify("nope")) // 2nd: maxAttempts reached
        // Even the right password no longer works: only recovery does.
        assertEquals(UnlockResult.LockedOut, lock.verify("hunter2"))
        val (result, error) = lock.recover("cat", password("newpass"))
        assertEquals(UnlockResult.Success, result)
        assertNull(error)
        assertEquals("recovery clears the lockout", false, store.config.lockedOut)
        assertEquals(UnlockResult.Success, lock.verify("newpass"))
    }

    @Test
    fun `wipe mode calls the wiper, and falls back to a recoverable lockout when none is wired`() = runBlocking {
        var wiped = false
        val withWiper = AppLockUseCases(MemoryStore(), CredentialHasher(iterations = 1_000), clock, wiper = { wiped = true })
        withWiper.enable(password())
        withWiper.updateLockoutPolicy(freeTries = 1, baseWaitSeconds = 1, maxAttempts = 2, lockoutMode = LockoutMode.WIPE)
        withWiper.verify("nope")
        clock.current += 10_000
        assertEquals(UnlockResult.Wiped, withWiper.verify("nope"))
        assertTrue("the wiper was actually invoked", wiped)

        // No wiper wired (this test class's default `lock`): the same policy must not crash or guess, it locks out instead.
        lock.enable(password())
        lock.updateLockoutPolicy(freeTries = 1, baseWaitSeconds = 1, maxAttempts = 2, lockoutMode = LockoutMode.WIPE)
        lock.verify("nope")
        clock.current += 10_000
        assertEquals(UnlockResult.LockedOut, lock.verify("nope"))
    }

    @Test
    fun `a PIN is digits only, within length, and unlocks like any other secret`() = runBlocking {
        assertEquals(LockSetupError.SECRET_LENGTH, lock.enable(LockSetup(LockMethod.PIN, "12a4")))
        assertEquals(LockSetupError.SECRET_LENGTH, lock.enable(LockSetup(LockMethod.PIN, "12")))
        assertNull(lock.enable(LockSetup(LockMethod.PIN, "1234")))
        assertEquals(UnlockResult.Success, lock.verify("1234"))
        assertTrue(lock.verify("9999") is UnlockResult.Wrong)
    }

    @Test
    fun `the lock is turned off only with the current secret`() = runBlocking {
        lock.enable(password())
        assertTrue(lock.disable("wrong").let { it is UnlockResult.Wrong })
        assertTrue(store.config.enabled)
        assertEquals(UnlockResult.Success, lock.disable("hunter2"))
        assertFalse(store.config.enabled)
        assertNull(store.config.hash)
        assertEquals("with the lock off everything opens", UnlockResult.Success, lock.verify("anything"))
    }

    @Test
    fun `the secret is changed only with the old one, and the question, biometrics and timeout stay`() = runBlocking {
        lock.enable(LockSetup(LockMethod.PASSWORD, "hunter2", biometric = true, timeoutSeconds = 300, question = "Pet?", answer = "rex"))
        val (wrong, _) = lock.changeSecret("nope", null, password("newpass1"))
        assertTrue(wrong is UnlockResult.Wrong)
        assertEquals(UnlockResult.Success, lock.verify("hunter2"))

        val (ok, error) = lock.changeSecret("hunter2", null, LockSetup(LockMethod.PATTERN_3X3, "0,1,2,4"))
        assertEquals(UnlockResult.Success, ok)
        assertNull(error)
        assertEquals(LockMethod.PATTERN_3X3, store.config.method)
        assertEquals(UnlockResult.Success, lock.verify("0,1,2,4"))
        assertTrue(store.config.biometric)
        assertEquals(300, store.config.timeoutSeconds)
        assertEquals("Pet?", store.config.question)
    }

    @Test
    fun `the right answer to the question lets the person set a new secret, a wrong one counts as a wrong try`() = runBlocking {
        lock.enable(password("hunter2", "First pet?", "Rex the Dog"))
        val (wrong, _) = lock.recover("cat", password("brandnew"))
        assertEquals(UnlockResult.Wrong(4), wrong)
        assertEquals("the old secret still works", UnlockResult.Success, lock.verify("hunter2"))

        val (ok, error) = lock.recover("  rex   THE dog ", password("brandnew"))
        assertEquals(UnlockResult.Success, ok)
        assertNull(error)
        assertEquals(UnlockResult.Success, lock.verify("brandnew"))
        assertTrue(lock.verify("hunter2") is UnlockResult.Wrong)
    }

    @Test
    fun `recovery is refused when no question was set, and a bad new secret is refused after a right answer`() = runBlocking {
        lock.enable(password())
        val (result, error) = lock.recover("anything", password("brandnew"))
        assertTrue(result is UnlockResult.Wrong)
        assertEquals(LockSetupError.RECOVERY_NOT_SET, error)

        lock.enable(password("hunter2", "Pet?", "rex"))
        val (_, tooShort) = lock.recover("rex", password("ab"))
        assertEquals(LockSetupError.SECRET_LENGTH, tooShort)
        assertEquals("nothing changed", UnlockResult.Success, lock.verify("hunter2"))
    }

    @Test
    fun `a question without an answer is refused`() = runBlocking {
        assertEquals(LockSetupError.ANSWER_BLANK, lock.enable(password("hunter2", "Pet?", "   ")))
        assertFalse(store.config.enabled)
        assertNull("no question at all is allowed", lock.enable(password("hunter2", "  ", null)))
        assertNull(store.config.question)
    }

    @Test
    fun `biometrics and the timeout can be changed without the secret, an impossible timeout becomes the default`() = runBlocking {
        lock.enable(password())
        lock.updateOptions(biometric = true, timeoutSeconds = 900)
        assertTrue(store.config.biometric)
        assertEquals(900, store.config.timeoutSeconds)
        lock.updateOptions(biometric = false, timeoutSeconds = 100_000)
        assertEquals(LockTimeout.DEFAULT.seconds, store.config.timeoutSeconds)
        store.config = AppLockConfig()
        lock.updateOptions(biometric = true, timeoutSeconds = 0)
        assertFalse("nothing to change while the lock is off", store.config.biometric)
    }

    @Test
    fun `the lock asks on a fresh start, after the timeout, and never when it is off`() {
        val on = AppLockConfig(enabled = true, timeoutSeconds = 60)
        assertFalse(LockPolicy.shouldLock(AppLockConfig(), 10_000, null))
        assertTrue("a fresh start always asks", LockPolicy.shouldLock(on, 10_000, null))
        assertFalse(LockPolicy.shouldLock(on, 60_000, 10_001))
        assertTrue(LockPolicy.shouldLock(on, 70_000, 10_000))
        assertTrue("0 = as soon as the app was left", LockPolicy.shouldLock(on.copy(timeoutSeconds = 0), 10_000, 10_000))
        assertFalse("never = only at a fresh start", LockPolicy.shouldLock(on.copy(timeoutSeconds = -1), 999_999_999, 10_000))
        assertTrue(LockPolicy.shouldLock(on.copy(timeoutSeconds = -1), 10_000, null))
    }

    @Test
    fun `the pattern pad finds the dot under a finger and nothing between the dots`() {
        assertEquals(0, PatternGrid.dotAt(50f, 50f, 300f, 30f))
        assertEquals(4, PatternGrid.dotAt(150f, 150f, 300f, 30f))
        assertEquals(8, PatternGrid.dotAt(250f, 260f, 300f, 30f))
        assertNull(PatternGrid.dotAt(100f, 100f, 300f, 30f))
        assertEquals(50f to 250f, PatternGrid.centre(6, 300f))
        assertEquals("0,3,6", PatternGrid.encode(listOf(0, 3, 6)))
    }

    @Test
    fun `a stored lock of an older or newer version still reads`() {
        val text = """{"enabled":true,"method":"PATTERN_3X3","salt":"s","hash":"h","somethingNew":1}"""
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val config = json.decodeFromString(AppLockConfig.serializer(), text)
        assertEquals(LockMethod.PATTERN_3X3, config.method)
        assertEquals(LockTimeout.DEFAULT.seconds, config.timeoutSeconds)
        assertEquals(0, config.failedAttempts)
    }
}
