package com.notio.shared.lock

import com.notio.shared.domain.Clock

/** A clock the tests move by hand (the phone's TestClock). */
class TestClock(var current: Long = 1_000_000L) : Clock {
    override fun now(): Long = current
}
