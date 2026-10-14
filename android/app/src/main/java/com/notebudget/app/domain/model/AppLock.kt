package com.notebudget.app.domain.model

import kotlinx.serialization.Serializable

/**
 * How long the app may stay in the background before it asks for the unlock again (§6.1, D30). [IMMEDIATE] locks as soon
 * as the app is left; [NEVER] asks only when the app is started from scratch.
 */
enum class LockTimeout(val seconds: Int) {
    IMMEDIATE(0), SECONDS_30(30), MINUTE_1(60), MINUTES_5(300), MINUTES_15(900), NEVER(-1);

    companion object {
        val DEFAULT = MINUTE_1
        fun ofSeconds(seconds: Int): LockTimeout = entries.firstOrNull { it.seconds == seconds } ?: DEFAULT
    }
}

/**
 * The timeout as a number of seconds (D129): the presets above, or ANY time from 0 seconds to 23 h 59 min 59 s that the
 * person types as minutes and seconds; -1 still means "only when the app starts".
 */
object LockTimeouts {
    const val MAX_SECONDS = 24 * 3600 - 1
    const val NEVER = -1

    fun isValid(seconds: Int): Boolean = seconds == NEVER || seconds in 0..MAX_SECONDS

    /** Anything else than a valid time becomes the default one minute. */
    fun normalize(seconds: Int): Int = if (isValid(seconds)) seconds else LockTimeout.DEFAULT.seconds

    fun isPreset(seconds: Int): Boolean = LockTimeout.entries.any { it.seconds == seconds }

    fun minutesOf(seconds: Int): Int = seconds.coerceAtLeast(0) / 60
    fun secondsOf(seconds: Int): Int = seconds.coerceAtLeast(0) % 60

    /** Minutes and seconds typed by the person; `null` when they do not make a valid time. */
    fun fromMinutesAndSeconds(minutes: Int, seconds: Int): Int? {
        if (minutes < 0 || seconds < 0 || seconds > 59) return null
        val total = minutes.toLong() * 60 + seconds
        return if (total <= MAX_SECONDS) total.toInt() else null
    }
}

/**
 * The app lock as stored (§6.1, §7.7, D124). Only HASHES are kept, never a password, a pattern or an answer. Every field
 * has a default, so a file written by an older version still reads. It is never part of a backup (INV-11).
 *
 * [method] is [LockMethod.PASSWORD], [LockMethod.PATTERN_3X3] or [LockMethod.LOGIN_PASSWORD] (one local account; several
 * accounts and roles are not built, G03). Biometrics ([biometric]) are an addition to the method, never a replacement.
 */
@Serializable
data class AppLockConfig(
    val enabled: Boolean = false,
    val method: LockMethod = LockMethod.PASSWORD,
    val login: String? = null,
    val salt: String? = null,
    val hash: String? = null,
    /** The PBKDF2 rounds of [hash] (O4); configs saved before 0.9.77 have none and were made with 210 000. */
    val iterations: Int = com.notebudget.app.core.security.CredentialHasher.LEGACY_ITERATIONS,
    val biometric: Boolean = false,
    val timeoutSeconds: Int = LockTimeout.DEFAULT.seconds,
    /** The security question for recovery (G02: no e-mail, the answer is checked on the phone). */
    val question: String? = null,
    val answerSalt: String? = null,
    val answerHash: String? = null,
    val answerIterations: Int = com.notebudget.app.core.security.CredentialHasher.LEGACY_ITERATIONS,
    /** Wrong tries in a row; from [freeTries] the next try waits, longer each time. */
    val failedAttempts: Int = 0,
    val blockedUntil: Long = 0,
    /**
     * The same wait on the clock since boot (O12): when it began there ([blockedAtElapsed]) and how long it is ([blockedWait]).
     * The wait ends only when both clocks say so, so moving the phone's time forward does not end it. `0` = not kept (older).
     */
    val blockedAtElapsed: Long = 0,
    val blockedWait: Long = 0,
    /** Wrong tries in a row that cost nothing before the wait starts (user-configurable; was a fixed 5). */
    val freeTries: Int = 5,
    /** The wait after the first try past [freeTries], in seconds; it then doubles, up to 15 minutes (user-configurable; was a fixed 30 s). */
    val baseWaitSeconds: Int = 10,
    /** Total wrong tries in a row that trigger [lockoutMode]; `0` = off (the wait above still applies, but never locks the person out entirely). */
    val maxAttempts: Int = 0,
    val lockoutMode: LockoutMode = LockoutMode.RECOVERY_ONLY,
    /** Set once [maxAttempts] is reached in [LockoutMode.RECOVERY_ONLY]: direct guessing is refused until a recovery method sets a new secret. */
    val lockedOut: Boolean = false,
    /** Only meaningful for [LockMethod.PIN] (user, 2026-09-22): how many digits it has, so the on-screen number pad
     * can submit as soon as that many are typed, with no separate confirm tap — the length itself is not a secret. */
    val pinLength: Int? = null,
    /** Recovery by a code sent to the person's own mailbox (D129); the server settings are kept apart, see `SmtpStore`. */
    val emailRecovery: Boolean = false,
    val emailCodeSalt: String? = null,
    val emailCodeHash: String? = null,
    val emailCodeIterations: Int = com.notebudget.app.core.security.CredentialHasher.LEGACY_ITERATIONS,
    val emailCodeExpiresAt: Long = 0,
    val emailCodeTries: Int = 0,
    val emailLastSentAt: Long = 0,
    /** The authenticator key, ENCRYPTED (see `SecretBox`): it cannot be hashed because codes are made from it. */
    val totpBox: String? = null,
)

/** The ways to get back in after forgetting the secret. */
enum class RecoveryMethod { QUESTION, EMAIL, AUTHENTICATOR }

/**
 * What happens once [AppLockConfig.maxAttempts] wrong tries in a row are reached (user's own choice, off by default
 * via `maxAttempts = 0`). [WIPE] is opt-in and irreversible — never the default, always behind its own confirmation
 * in Settings (§1.3): the person types a confirmation phrase before it can be turned on.
 */
enum class LockoutMode { RECOVERY_ONLY, WIPE }

/** What sending the recovery code came to. */
sealed interface EmailCodeResult {
    data object Sent : EmailCodeResult
    data object NotConfigured : EmailCodeResult

    /** A code was sent a moment ago; wait [waitMillis] before asking for another. */
    data class TooSoon(val waitMillis: Long) : EmailCodeResult
    data class Failed(val error: com.notebudget.app.core.mail.MailError, val detail: String) : EmailCodeResult
}

/** A new authenticator key waiting for its first code (not saved until a right code is typed). */
data class TotpSetup(val secret: String, val uri: String)

/** Why a lock could not be set up. */
enum class LockSetupError { INVALID_METHOD, SECRET_LENGTH, LOGIN_INVALID, PATTERN_INVALID, ANSWER_BLANK, RECOVERY_NOT_SET, CODE_EXPIRED }

sealed interface UnlockResult {
    data object Success : UnlockResult

    /** Wrong secret; [triesBeforeDelay] more tries until the app makes the person wait. */
    data class Wrong(val triesBeforeDelay: Int) : UnlockResult

    /** Too many wrong tries: nothing is checked until [untilMillis]. */
    data class Blocked(val untilMillis: Long) : UnlockResult

    /** [AppLockConfig.maxAttempts] was reached with [LockoutMode.RECOVERY_ONLY]: no more guesses are checked at all, only a recovery method (question / e-mail / authenticator) can get back in. */
    data object LockedOut : UnlockResult

    /** [AppLockConfig.maxAttempts] was reached with [LockoutMode.WIPE]: the app's own data was just erased. */
    data object Wiped : UnlockResult
}

/** When the lock screen must be shown. */
object LockPolicy {
    /**
     * [backgroundedAt] is when the app was left, or `null` when it was not left since it started (a fresh start): a fresh
     * start always asks. Leaving the app for [LockTimeout.NEVER] does not ask again.
     */
    fun shouldLock(config: AppLockConfig, nowMillis: Long, backgroundedAt: Long?): Boolean {
        if (!config.enabled) return false
        if (backgroundedAt == null) return true
        if (config.timeoutSeconds < 0) return false
        return nowMillis - backgroundedAt >= config.timeoutSeconds * 1000L
    }

    /** How long a window of the phone that the app opened itself (a file picker, "share", a page of the settings) may stay open. */
    const val OWN_WINDOW_MILLIS = 5 * 60_000L

    /** The app goes out of sight this soon after it opened a window itself, or the leaving is not that window's. */
    const val OWN_WINDOW_OPENS_MILLIS = 3_000L

    /**
     * Was the app left for a window it opened itself (D346)? [openedAt] is when it opened one (`null`: it did not), [leftAt]
     * when it went out of sight. Coming back from such a window within [OWN_WINDOW_MILLIS] does not ask for the lock: making a
     * backup or choosing a file is not leaving the app.
     */
    fun leftForOwnWindow(openedAt: Long?, leftAt: Long): Boolean = openedAt != null && leftAt - openedAt in 0..OWN_WINDOW_OPENS_MILLIS

    fun backFromOwnWindow(leftAt: Long, nowMillis: Long): Boolean = nowMillis - leftAt in 0..OWN_WINDOW_MILLIS
}

/** The 3x3 pattern pad: which dot a finger is on (dots 0..8, row by row). */
object PatternGrid {
    /** The dot whose centre is within [hitRadius] of ([x], [y]) on a pad of [size] x [size] pixels, or `null`. */
    fun dotAt(x: Float, y: Float, size: Float, hitRadius: Float): Int? {
        val cell = size / 3f
        for (row in 0..2) for (column in 0..2) {
            val cx = cell * (column + 0.5f)
            val cy = cell * (row + 0.5f)
            if (kotlin.math.hypot(x - cx, y - cy) <= hitRadius) return row * 3 + column
        }
        return null
    }

    /** Centre of dot [index] on a pad of [size] pixels, as (x, y). */
    fun centre(index: Int, size: Float): Pair<Float, Float> {
        val cell = size / 3f
        return cell * (index % 3 + 0.5f) to cell * (index / 3 + 0.5f)
    }

    /** The text form of a pattern: `0,1,2,5`. */
    fun encode(dots: List<Int>): String = dots.joinToString(",")
}
