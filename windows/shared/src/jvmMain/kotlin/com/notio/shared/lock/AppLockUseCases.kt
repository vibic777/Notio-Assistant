package com.notio.shared.lock

import com.notio.shared.domain.LockMethod
import com.notio.shared.domain.Clock
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the lock is kept (the phone's private storage in the app, memory in tests). */
interface AppLockStore {
    suspend fun load(): AppLockConfig
    suspend fun save(config: AppLockConfig)
}

/** The mail server settings (with the password encrypted in storage). */
interface SmtpStore {
    suspend fun load(): SmtpSettings?
    suspend fun save(settings: SmtpSettings?)
}

/** What a person chooses when turning the lock on or changing it. [secret] is the password, or a pattern as `0,1,2,5`. */
data class LockSetup(
    val method: LockMethod,
    val secret: String,
    val login: String? = null,
    val biometric: Boolean = false,
    val timeoutSeconds: Int = LockTimeout.DEFAULT.seconds,
    val question: String? = null,
    val answer: String? = null,
)

/**
 * The rules of the app lock (§6.1, D124): turning it on and off, checking the secret, waiting after too many wrong
 * tries, and recovery with a security question. Only salted hashes are stored (INV-9); nothing secret is logged.
 */
class AppLockUseCases(
    private val store: AppLockStore,
    private val hasher: CredentialHasher = CredentialHasher(),
    private val clock: Clock = Clock.System,
    private val secrets: SecretBox = PlainSecretBox(),
    private val mailer: MailSender? = null,
    private val smtp: SmtpStore? = null,
    private val random: SecureRandom = SecureRandom(),
    /** Only used when a person opted into [LockoutMode.WIPE] (D-lockout); `null` is a safe fallback to [UnlockResult.LockedOut] instead of guessing. */
    private val wiper: AppDataWiper? = null,
    /**
     * Milliseconds since boot, sleep included (`SystemClock.elapsedRealtime` in the app): a clock the person cannot set, so a
     * wait after wrong tries is not ended by moving the time forward (O12). `null` = the wall clock only (tests of other things).
     */
    private val elapsed: (() -> Long)? = null,
) {
    /** One check of a secret at a time: two quick taps must not both read the same count of wrong tries and lose one. */
    private val checking = Mutex()

    /** The key stretching takes a noticeable moment: never on the main thread. */
    private suspend fun matches(secret: CharArray, salt: String, hash: String, rounds: Int): Boolean =
        withContext(Dispatchers.Default) { runCatching { hasher.verify(secret, salt, hash, rounds) }.getOrDefault(false) }

    private suspend fun hashOf(secret: CharArray): CredentialHasher.Hashed = withContext(Dispatchers.Default) { hasher.hash(secret) }

    suspend fun config(): AppLockConfig = store.load()

    /** Turns the lock on with a new secret (or replaces the whole lock); `null` = done. */
    suspend fun enable(setup: LockSetup): LockSetupError? {
        validate(setup.method, setup.secret, setup.login)?.let { return it }
        val secret = hashOf(setup.secret.toCharArray())
        var question: String? = null
        var answerSalt: String? = null
        var answerHash: String? = null
        var answerIterations = hasher.currentIterations
        setup.question?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            val answer = normalizeAnswer(setup.answer)
            if (answer.isEmpty()) return LockSetupError.ANSWER_BLANK
            val hashed = hashOf(answer.toCharArray())
            question = q
            answerSalt = hashed.salt
            answerHash = hashed.hash
            answerIterations = hashed.iterations
        }
        store.save(
            AppLockConfig(
                enabled = true,
                method = setup.method,
                login = setup.login?.trim().takeIf { setup.method == LockMethod.LOGIN_PASSWORD },
                salt = secret.salt,
                hash = secret.hash,
                iterations = secret.iterations,
                biometric = setup.biometric,
                timeoutSeconds = LockTimeouts.normalize(setup.timeoutSeconds),
                question = question,
                answerSalt = answerSalt,
                answerHash = answerHash,
                answerIterations = answerIterations,
                pinLength = setup.secret.length.takeIf { setup.method == LockMethod.PIN },
            ),
        )
        return null
    }

    /** Checks the secret (and the login, for that method). Wrong tries are counted and, from [AppLockConfig.freeTries], slow the person down. */
    suspend fun verify(secret: String, login: String? = null): UnlockResult = checking.withLock {
        val config = store.load()
        if (!config.enabled || config.hash == null || config.salt == null) return UnlockResult.Success
        if (config.lockedOut) return UnlockResult.LockedOut
        blockedNow(config)?.let { return it }
        val secretOk = matches(secret.toCharArray(), config.salt, config.hash, config.iterations)
        val loginOk = config.method != LockMethod.LOGIN_PASSWORD || login?.trim() == config.login
        return if (secretOk && loginOk) {
            // A hash made with other rounds than today's is made again now, while the secret is at hand (O4).
            val renewed = if (config.iterations != hasher.currentIterations) hashOf(secret.toCharArray()) else null
            store.save(
                config.copy(failedAttempts = 0, blockedUntil = 0, blockedAtElapsed = 0, blockedWait = 0).let { c ->
                    renewed?.let { c.copy(salt = it.salt, hash = it.hash, iterations = it.iterations) } ?: c
                },
            )
            UnlockResult.Success
        } else {
            failed(config)
        }
    }

    /** Until when (wall clock) the next try has to wait, by both clocks (O12); `0` = no wait. For the lock screen's countdown. */
    suspend fun waitingUntil(): Long = blockedNow(store.load())?.untilMillis ?: 0L

    /** Turns the lock off; needs the current secret. */
    suspend fun disable(secret: String, login: String? = null): UnlockResult {
        val result = verify(secret, login)
        if (result is UnlockResult.Success) store.save(AppLockConfig())
        return result
    }

    /** Changes the secret; needs the current one. The question, the biometric choice and the timeout stay. */
    suspend fun changeSecret(oldSecret: String, oldLogin: String?, new: LockSetup): Pair<UnlockResult, LockSetupError?> {
        val result = verify(oldSecret, oldLogin)
        if (result !is UnlockResult.Success) return result to null
        return result to setNewSecret(new)
    }

    /** Biometrics and timeout can be changed from the settings without the secret: the person is inside already. */
    suspend fun updateOptions(biometric: Boolean, timeoutSeconds: Int) {
        val config = store.load()
        if (!config.enabled) return
        store.save(config.copy(biometric = biometric, timeoutSeconds = LockTimeouts.normalize(timeoutSeconds)))
    }

    /**
     * The wrong-try policy (D-lockout), changeable from Settings without the secret, same as [updateOptions]. [freeTries]
     * and [baseWaitSeconds] shape the existing wait-and-double behaviour; `maxAttempts = 0` keeps it the only behaviour
     * (unlimited slowed-down tries, never a hard lockout). [LockoutMode.WIPE] is irreversible — the caller (the Settings
     * screen) must have already gotten its own explicit confirmation before calling this with that mode; this method
     * itself does not ask again.
     */
    suspend fun updateLockoutPolicy(freeTries: Int, baseWaitSeconds: Int, maxAttempts: Int, lockoutMode: LockoutMode) {
        val config = store.load()
        if (!config.enabled) return
        store.save(
            config.copy(
                freeTries = freeTries.coerceIn(1, 20),
                baseWaitSeconds = baseWaitSeconds.coerceIn(1, 3600),
                maxAttempts = maxAttempts.coerceIn(0, 100),
                lockoutMode = lockoutMode,
            ),
        )
    }

    /** "I forgot it": the right answer to the security question lets the person set a new secret. */
    suspend fun recover(answer: String, new: LockSetup): Pair<UnlockResult, LockSetupError?> = checking.withLock {
        val config = store.load()
        if (!config.enabled || config.question == null || config.answerHash == null || config.answerSalt == null) {
            return UnlockResult.Wrong(0) to LockSetupError.RECOVERY_NOT_SET
        }
        blockedNow(config)?.let { return it to null }
        val ok = matches(normalizeAnswer(answer).toCharArray(), config.answerSalt, config.answerHash, config.answerIterations)
        if (!ok) return failed(config) to null
        validate(new.method, new.secret, new.login)?.let { return UnlockResult.Success to it }
        return UnlockResult.Success to setNewSecret(new)
    }

    // ---------------- more ways to recover (D129) ----------------

    /** Sets or removes the security question. */
    suspend fun setQuestion(question: String?, answer: String?): LockSetupError? {
        val config = store.load()
        val q = question?.trim().orEmpty()
        if (q.isEmpty()) {
            store.save(config.copy(question = null, answerSalt = null, answerHash = null))
            return null
        }
        val normalized = normalizeAnswer(answer)
        if (normalized.isEmpty()) return LockSetupError.ANSWER_BLANK
        val hashed = hashOf(normalized.toCharArray())
        store.save(config.copy(question = q, answerSalt = hashed.salt, answerHash = hashed.hash, answerIterations = hashed.iterations))
        return null
    }

    suspend fun smtpSettings(): SmtpSettings? = smtp?.load()

    /** Saves the mail settings; recovery by e-mail is on exactly when they are complete. `null` removes them. */
    suspend fun saveSmtp(settings: SmtpSettings?) {
        smtp?.save(settings)
        val config = store.load()
        store.save(
            config.copy(
                emailRecovery = settings?.isComplete == true,
                emailCodeSalt = null, emailCodeHash = null, emailCodeExpiresAt = 0, emailCodeTries = 0,
            ),
        )
    }

    /** Sends a test letter with the given settings (they need not be saved yet). */
    suspend fun sendTestMail(settings: SmtpSettings, subject: String, body: String): MailResult =
        mailer?.send(settings, subject, body) ?: MailResult.Failed(MailError.NOT_CONFIGURED)

    /**
     * Makes a 6-digit code, keeps only its hash (valid [CODE_LIFETIME_MILLIS], [CODE_TRIES] tries) and mails the code to
     * the person's own address. A new code cannot be asked for sooner than [RESEND_MILLIS] after the last.
     */
    suspend fun sendRecoveryCode(subject: String, body: (code: String, minutes: Int) -> String): EmailCodeResult {
        val config = store.load()
        val settings = smtp?.load()
        val sender = mailer
        if (!config.enabled || !config.emailRecovery || settings == null || !settings.isComplete || sender == null) return EmailCodeResult.NotConfigured
        val wait = config.emailLastSentAt + RESEND_MILLIS - clock.now()
        if (wait > 0) return EmailCodeResult.TooSoon(wait)
        val code = (random.nextInt(1_000_000)).toString().padStart(6, '0')
        val minutes = (CODE_LIFETIME_MILLIS / 60_000).toInt()
        return when (val result = sender.send(settings, subject, body(code, minutes))) {
            MailResult.Sent -> {
                val hashed = hashOf(code.toCharArray())
                store.save(
                    config.copy(
                        emailCodeSalt = hashed.salt, emailCodeHash = hashed.hash, emailCodeIterations = hashed.iterations,
                        emailCodeExpiresAt = clock.now() + CODE_LIFETIME_MILLIS, emailCodeTries = 0, emailLastSentAt = clock.now(),
                    ),
                )
                EmailCodeResult.Sent
            }
            is MailResult.Failed -> EmailCodeResult.Failed(result.error, result.detail)
        }
    }

    /** The code from the letter lets the person set a new secret. The code works once, and only for a few tries. */
    suspend fun recoverWithEmail(code: String, new: LockSetup): Pair<UnlockResult, LockSetupError?> = checking.withLock {
        val config = store.load()
        if (!config.enabled || config.emailCodeHash == null || config.emailCodeSalt == null) return UnlockResult.Wrong(0) to LockSetupError.RECOVERY_NOT_SET
        blockedNow(config)?.let { return it to null }
        if (clock.now() > config.emailCodeExpiresAt) {
            store.save(config.copy(emailCodeHash = null, emailCodeSalt = null, emailCodeExpiresAt = 0))
            return UnlockResult.Wrong(0) to LockSetupError.CODE_EXPIRED
        }
        val typed = code.filter { !it.isWhitespace() }
        val ok = matches(typed.toCharArray(), config.emailCodeSalt, config.emailCodeHash, config.emailCodeIterations)
        if (!ok) {
            val tries = config.emailCodeTries + 1
            // After too many tries the code is thrown away: a new one must be asked for.
            val next = if (tries >= CODE_TRIES) config.copy(emailCodeHash = null, emailCodeSalt = null, emailCodeExpiresAt = 0, emailCodeTries = 0)
            else config.copy(emailCodeTries = tries)
            store.save(next)
            return failed(next) to null
        }
        validate(new.method, new.secret, new.login)?.let { return UnlockResult.Success to it }
        val error = setNewSecret(new)
        if (error == null) store.save(store.load().copy(emailCodeHash = null, emailCodeSalt = null, emailCodeExpiresAt = 0, emailCodeTries = 0))
        return UnlockResult.Success to error
    }

    /** A new authenticator key and the address for its QR code; nothing is saved until [confirmTotp]. */
    fun startTotp(issuer: String, account: String): TotpSetup {
        val secret = Totp.newSecret(random)
        return TotpSetup(secret, Totp.uri(issuer, account, secret))
    }

    /** Saves the key (encrypted) when the code from the authenticator app proves that it was added correctly. */
    suspend fun confirmTotp(secret: String, code: String): Boolean {
        if (!Totp.verify(secret, code, clock.now())) return false
        store.save(store.load().copy(totpBox = secrets.seal(secret)))
        return true
    }

    suspend fun removeTotp() {
        store.save(store.load().copy(totpBox = null))
    }

    /** A right code from the authenticator app lets the person set a new secret. */
    suspend fun recoverWithTotp(code: String, new: LockSetup): Pair<UnlockResult, LockSetupError?> = checking.withLock {
        val config = store.load()
        val secret = config.totpBox?.let { secrets.open(it) }
        if (!config.enabled || secret == null) return UnlockResult.Wrong(0) to LockSetupError.RECOVERY_NOT_SET
        blockedNow(config)?.let { return it to null }
        if (!Totp.verify(secret, code, clock.now())) return failed(config) to null
        validate(new.method, new.secret, new.login)?.let { return UnlockResult.Success to it }
        return UnlockResult.Success to setNewSecret(new)
    }

    /** Which ways back in are set up. */
    fun recoveryMethods(config: AppLockConfig): Set<RecoveryMethod> = buildSet {
        if (config.question != null && config.answerHash != null) add(RecoveryMethod.QUESTION)
        if (config.emailRecovery && config.enabled) add(RecoveryMethod.EMAIL)
        if (config.totpBox != null) add(RecoveryMethod.AUTHENTICATOR)
    }

    private suspend fun setNewSecret(new: LockSetup): LockSetupError? {
        validate(new.method, new.secret, new.login)?.let { return it }
        val config = store.load()
        val hashed = hashOf(new.secret.toCharArray())
        store.save(
            config.copy(
                enabled = true,
                method = new.method,
                login = new.login?.trim().takeIf { new.method == LockMethod.LOGIN_PASSWORD },
                salt = hashed.salt,
                hash = hashed.hash,
                iterations = hashed.iterations,
                failedAttempts = 0,
                blockedUntil = 0,
                blockedAtElapsed = 0,
                blockedWait = 0,
                lockedOut = false,
                pinLength = new.secret.length.takeIf { new.method == LockMethod.PIN },
            ),
        )
        return null
    }

    /**
     * Still waiting? By the wall clock: until [AppLockConfig.blockedUntil], and a clock set back before the wait began starts it
     * again. By the clock since boot, in the same boot: until [AppLockConfig.blockedWait] has passed there. Both must be over; after a
     * reboot (the clock since boot is smaller than at the start) only the wall clock counts. The answer is a wall-clock time for the
     * screen's countdown.
     */
    private fun blockedNow(config: AppLockConfig): UnlockResult.Blocked? {
        val now = clock.now()
        val wait = config.blockedWait
        var left = 0L
        if (wait > 0) {
            val began = config.blockedUntil - wait
            left = if (now < began) wait else config.blockedUntil - now
            val since = elapsed?.invoke()
            if (since != null && config.blockedAtElapsed > 0 && since >= config.blockedAtElapsed) {
                left = maxOf(left, config.blockedAtElapsed + wait - since)
            }
        } else {
            left = config.blockedUntil - now
        }
        return if (left > 0) UnlockResult.Blocked(now + left) else null
    }

    /** Wrong tries are counted and, from [AppLockConfig.freeTries], slow the person down; past [AppLockConfig.maxAttempts]
     * (when set) [AppLockConfig.lockoutMode] decides what happens instead of one more wait. */
    private suspend fun failed(config: AppLockConfig): UnlockResult {
        val attempts = config.failedAttempts + 1
        if (config.maxAttempts > 0 && attempts >= config.maxAttempts) {
            return when (config.lockoutMode) {
                LockoutMode.RECOVERY_ONLY -> {
                    store.save(config.copy(failedAttempts = attempts, blockedUntil = 0, blockedAtElapsed = 0, blockedWait = 0, lockedOut = true))
                    UnlockResult.LockedOut
                }
                LockoutMode.WIPE -> {
                    store.save(config.copy(failedAttempts = attempts))
                    if (wiper != null) {
                        wiper.wipe()
                        UnlockResult.Wiped
                    } else {
                        // No wiper wired (e.g. a test build): fail safe into a recoverable lockout rather than guess.
                        store.save(store.load().copy(lockedOut = true))
                        UnlockResult.LockedOut
                    }
                }
            }
        }
        val wait = waitAfter(attempts, config.freeTries, config.baseWaitSeconds)
        val until = if (wait > 0) clock.now() + wait else 0L
        val sinceBoot = if (wait > 0) elapsed?.invoke() ?: 0L else 0L
        store.save(config.copy(failedAttempts = attempts, blockedUntil = until, blockedAtElapsed = sinceBoot, blockedWait = if (wait > 0) wait else 0L))
        return if (wait > 0) UnlockResult.Blocked(until) else UnlockResult.Wrong((config.freeTries - attempts).coerceAtLeast(0))
    }

    private fun validate(method: LockMethod, secret: String, login: String?): LockSetupError? = when (method) {
        LockMethod.PASSWORD -> secretLength(secret)
        LockMethod.LOGIN_PASSWORD -> secretLength(secret) ?: if (login?.trim().orEmpty().length in 1..MAX_LOGIN) null else LockSetupError.LOGIN_INVALID
        LockMethod.PATTERN_3X3 -> {
            val dots = secret.split(',').map { it.trim().toIntOrNull() }
            if (dots.any { it == null } || !CredentialHasher.isValidPattern(dots.filterNotNull())) LockSetupError.PATTERN_INVALID else null
        }
        LockMethod.PIN -> if (secret.length in MIN_PIN..MAX_PIN && secret.all(Char::isDigit)) null else LockSetupError.SECRET_LENGTH
        else -> LockSetupError.INVALID_METHOD
    }

    private fun secretLength(secret: String) = if (secret.length in MIN_SECRET..MAX_SECRET) null else LockSetupError.SECRET_LENGTH

    /** "  Blue   Car " and "blue car" are the same answer. */
    private fun normalizeAnswer(answer: String?): String = answer.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    companion object {
        const val MIN_SECRET = 4
        const val MAX_SECRET = 64
        const val MAX_LOGIN = 32
        const val MIN_PIN = 4
        const val MAX_PIN = 8

        /** Wrong tries that cost nothing by default; the next one makes the person wait. User-configurable per person, see [AppLockConfig.freeTries]. */
        const val FREE_TRIES = 5

        /** The default first wait, in seconds, before it starts doubling. User-configurable, see [AppLockConfig.baseWaitSeconds]. */
        const val DEFAULT_BASE_WAIT_SECONDS = 10

        const val MAX_WAIT_MILLIS = 15 * 60_000L

        /** A code from a letter works for ten minutes, five tries, and a new one at most every minute. */
        const val CODE_LIFETIME_MILLIS = 10 * 60_000L
        const val CODE_TRIES = 5
        const val RESEND_MILLIS = 60_000L

        /** How long the person waits after [attempts] wrong tries in a row: 0 until [freeTries], then [baseWaitSeconds], doubling, at most 15 minutes. */
        fun waitAfter(attempts: Int, freeTries: Int = FREE_TRIES, baseWaitSeconds: Int = DEFAULT_BASE_WAIT_SECONDS): Long {
            val free = freeTries.coerceAtLeast(1)
            if (attempts < free) return 0
            // Capped well past the point base*2^steps must already exceed MAX_WAIT_MILLIS for any configurable base
            // (1 s to 1 h): the cap here only guards the left shift from overflowing, minOf below does the real clamping.
            val steps = (attempts - free).coerceAtMost(20)
            return minOf(baseWaitSeconds.coerceAtLeast(1) * 1000L shl steps, MAX_WAIT_MILLIS)
        }
    }
}
