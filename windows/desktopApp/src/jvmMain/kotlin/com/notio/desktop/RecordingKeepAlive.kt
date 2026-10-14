package com.notio.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A voice or camera recording known outside its note (Android D255's `RecordingKeepAlive`). On the phone it is a foreground service
 * that keeps the recording going in the background; on the computer a recording goes on anyway while the window is minimised or in
 * the tray, so this only lets the floating Stop window and the tray menu stop it: a Stop pressed there reaches it through
 * [stopRequests].
 */
interface RecordingKeepAlive {
    /** The recording of [noteId] runs; true while it is known. */
    fun start(noteId: String, microphone: Boolean, camera: Boolean): Boolean
    fun stop()
    /** Stop pressed on the floating button or in the tray menu. */
    val stopRequests: Flow<Unit>

    companion object {
        /** Nothing kept (tests). */
        val NONE = object : RecordingKeepAlive {
            override fun start(noteId: String, microphone: Boolean, camera: Boolean) = false
            override fun stop() = Unit
            override val stopRequests: Flow<Unit> = emptyFlow()
        }
    }
}

/**
 * Recordings being written into their notes right now (the user, 2026-09-28: Exit during a recording must not lose it): Exit waits
 * for this to reach zero. Taken before a recording lets go of the keep-alive, so there is never a moment with neither.
 */
object PendingSaves {
    private val count = java.util.concurrent.atomic.AtomicInteger()

    fun begin() { count.incrementAndGet() }

    fun end() { count.decrementAndGet() }

    val any: Boolean get() = count.get() > 0
}

/** The desktop's one: what runs (for the window and the tray), and the Stop requests to the recording. */
class DesktopRecordingKeepAlive : RecordingKeepAlive {
    /** The note whose voice / camera recording runs, and when it began; `null` while none runs. */
    var active by mutableStateOf<Active?>(null)
        private set

    data class Active(val noteId: String, val camera: Boolean, val startedAt: Long)

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val stopRequests: Flow<Unit> = requests

    override fun start(noteId: String, microphone: Boolean, camera: Boolean): Boolean {
        active = Active(noteId, camera, System.currentTimeMillis())
        return true
    }

    override fun stop() {
        active = null
    }

    /** Stop pressed outside the note. */
    fun requestStop() {
        requests.tryEmit(Unit)
    }
}
