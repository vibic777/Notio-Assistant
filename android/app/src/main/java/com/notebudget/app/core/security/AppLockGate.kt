package com.notebudget.app.core.security

import com.notebudget.app.core.common.Clock
import com.notebudget.app.domain.model.AppLockConfig
import com.notebudget.app.domain.model.LockPolicy
import com.notebudget.app.domain.usecase.AppLockUseCases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the lock screen covers the app right now (D124). It decides from the lock settings and from how long the app was
 * out of sight; the activity tells it when the app is left and returned to. Turning the lock off unlocks at once.
 */
class AppLockGate(
    private val useCases: AppLockUseCases,
    private val clock: Clock = Clock.System,
) {
    private var config = AppLockConfig()
    private var leftAt: Long? = null
    /** When the app opened a window of the phone itself, until the leaving that follows it (D346). */
    private var ownWindowAt: Long? = null
    private var leftForOwnWindow = false

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _enabled = MutableStateFlow(false)

    /** The lock is on: the window is then hidden from screenshots and from the recent-apps picture. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** At process start: the lock is asked for, whatever the timeout says. */
    suspend fun start() {
        config = useCases.config()
        _enabled.value = config.enabled
        _locked.value = LockPolicy.shouldLock(config, clock.now(), backgroundedAt = null)
    }

    /** After the lock settings changed. */
    suspend fun refresh() {
        config = useCases.config()
        _enabled.value = config.enabled
        if (!config.enabled) _locked.value = false
    }

    /** The app went out of sight (not a rotation). While the lock screen is up the time does not count. */
    fun onLeft() {
        if (_locked.value) return
        val now = clock.now()
        leftAt = now
        leftForOwnWindow = LockPolicy.leftForOwnWindow(ownWindowAt, now)
        ownWindowAt = null
    }

    /**
     * The app itself opens a window of the phone (D346): where a backup is saved, a file chosen, something shared, a page of the
     * phone's settings. Coming back from it is not a return to an app that was left.
     */
    fun onOwnWindow() {
        if (!_locked.value) ownWindowAt = clock.now()
    }

    /** The app is back: lock again if it was away for longer than the timeout. */
    fun onReturned() {
        val left = leftAt ?: return
        val own = leftForOwnWindow
        leftForOwnWindow = false
        if (own && LockPolicy.backFromOwnWindow(left, clock.now())) return
        if (!_locked.value && LockPolicy.shouldLock(config, clock.now(), left)) _locked.value = true
    }

    fun unlocked() {
        _locked.value = false
        leftAt = null
        leftForOwnWindow = false
        ownWindowAt = null
    }
}
