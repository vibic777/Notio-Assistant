package com.notebudget.app.core.common

import java.util.UUID

/** Time source (UTC epoch millis). An interface so that tests can control time. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** Entity id source (UUID strings, AGENT_EN.md §1.2). */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random = IdGenerator { UUID.randomUUID().toString() }
    }
}
