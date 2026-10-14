package com.notebudget.app.feature.audio

import androidx.lifecycle.viewModelScope
import com.notebudget.app.data.drawing.AudioStore
import com.notebudget.app.domain.model.AudioClip
import com.notebudget.app.domain.model.AudioDocument
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.TestEnv
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeRecorder(var fail: Boolean = false, var valid: Boolean = true, val content: ByteArray = byteArrayOf(9, 9, 9)) : AudioRecorder {
    var file: File? = null
    var released = false
    override fun start(file: File) {
        if (fail) throw IllegalStateException("mic busy")
        this.file = file
        file.writeBytes(content)
    }

    override fun stop(): Boolean = valid.also { if (!valid) file?.writeBytes(ByteArray(0)) }
    override fun release() { released = true }
}

private class FakePlayer(val duration: Int = 60_000) : AudioPlayer {
    var loaded: File? = null
    var playing = false
    var position = 0
    var completed: (() -> Unit)? = null
    override fun load(file: File, onCompleted: () -> Unit): Boolean { loaded = file; completed = onCompleted; return true }
    override fun start() { playing = true }
    override fun pause() { playing = false }
    override fun seekTo(positionMs: Int) { position = positionMs }
    override val positionMs get() = position
    override val durationMs get() = duration
    override fun release() { playing = false }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AudioViewModelTest {
    private val env = TestEnv()
    private val dir: File = Files.createTempDirectory("audio-test").toFile().also { it.deleteOnExit() }
    private val store = AudioStore(File(dir, "audio"))
    private val recorder = FakeRecorder()
    private val player = FakePlayer()
    private var now = 0L

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun noteId(): String {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (env.notes.create(folder.id, "Memo", NoteType.AUDIO) as DomainResult.Success).value.id
    }

    private val created = mutableListOf<AudioViewModel>()

    private fun vm(id: String, rec: FakeRecorder = recorder) =
        AudioViewModel(
            id, env.noteRepo, store, env.notes, rec, player, File(dir, "cache"),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { n -> "Recording $n" }, { now },
        ).also { created += it }

    /**
     * The position ticker reschedules itself forever, and runTest would chase it through virtual
     * time at the end of the test. Cancelling the ViewModel's scope, as Android does on clear, ends it.
     */
    private fun test(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            created.forEach { it.viewModelScope.cancel() }
            // A clip still being written on the real IO dispatcher comes back to Main when done: wait for it here, before
            // tearDown resets Main, or it fails the next test.
            created.forEach { it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join() }
        }
    }

    /** The clip is written on the real IO dispatcher, so give it a moment (real time, not virtual). */
    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(10)
        }
    }

    /** Records one clip and waits until it is stored and selected. */
    private fun record(vm: AudioViewModel, clipsAfter: Int) {
        vm.startRecording()
        now += 2_000
        vm.stopRecording()
        eventually("clip $clipsAfter stored") { vm.state.value.clips.size == clipsAfter && vm.state.value.selectedId != null }
    }

    private suspend fun document(id: String) = AudioDocument.parse(env.noteRepo.getById(id)?.extras)

    @Test
    fun `a recording stops by itself at its time limit and is kept (D251)`() = test {
        val id = noteId()
        val vm = AudioViewModel(
            id, env.noteRepo, store, env.notes, recorder, player, File(dir, "cache"),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { n -> "Recording $n" }, { now },
            recordingLimit = kotlinx.coroutines.flow.flowOf(com.notebudget.app.domain.model.RecordingLimit(1, com.notebudget.app.domain.model.LimitUnit.SECONDS)),
        ).also { created += it }
        vm.startRecording()
        assertTrue(vm.state.value.isRecording)
        testScheduler.advanceTimeBy(900)
        assertTrue("not yet", vm.state.value.isRecording)
        now += 1_000
        testScheduler.advanceTimeBy(200)
        assertFalse(vm.state.value.isRecording)
        assertTrue(vm.state.value.limitReached)
        eventually("the clip is kept") { vm.state.value.clips.size == 1 }
        // Stopped by hand before the limit: the limit does not fire later.
        vm.startRecording()
        assertFalse(vm.state.value.limitReached)
        now += 500
        vm.stopRecording()
        testScheduler.advanceTimeBy(5_000)
        assertFalse(vm.state.value.limitReached)
    }

    @Test
    fun `a new audio note has nothing to play`() = test {
        val vm = vm(noteId())
        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.clips.isEmpty())
        assertNull(vm.state.value.selectedId)
        vm.seekTo(5)
        assertFalse(vm.state.value.isPlaying)
    }

    @Test
    fun `recording adds a named clip with its own file, saves the list in the note and touches it`() = test {
        val id = noteId()
        val vm = vm(id)
        env.clock.current = 99_000
        vm.startRecording()
        assertTrue(vm.state.value.isRecording)
        now = 3_500
        vm.stopRecording()

        assertFalse(vm.state.value.isRecording)
        eventually("clip stored and selected") { vm.state.value.selectedId != null }
        val clip = vm.state.value.clips.single()
        assertEquals("Recording 1", clip.name)
        assertEquals(3_500, clip.durationMs)
        assertArrayEquals(byteArrayOf(9, 9, 9), store.read(AudioDocument.fileKey(id, clip.id)))
        val note = env.noteRepo.getById(id)!!
        assertEquals(99_000L, note.updatedAt)
        assertEquals(listOf(clip), AudioDocument.parse(note.extras)?.clips)
        assertEquals("Recording 1", note.contentText)
        assertEquals(store.fileIfExists(AudioDocument.fileKey(id, clip.id)), player.loaded)
        assertEquals(60_000, vm.state.value.durationMs)
    }

    @Test
    fun `every recording is added to the list and none replaces another`() = test {
        val id = noteId()
        val vm = vm(id)
        record(vm, 1)
        record(vm, 2)

        val clips = vm.state.value.clips
        assertEquals(listOf("Recording 1", "Recording 2"), clips.map { it.name })
        assertEquals(2, clips.map { it.id }.toSet().size)
        clips.forEach { assertNotNull(store.fileIfExists(AudioDocument.fileKey(id, it.id))) }
        assertEquals(clips, document(id)?.clips)
    }

    @Test
    fun `a new clip never gets a name that another clip already has`() = test {
        val id = noteId()
        val vm = vm(id)
        record(vm, 1)
        record(vm, 2)
        vm.delete(vm.state.value.clips.first().id) // leaves "Recording 2"
        eventually("deleted") { vm.state.value.clips.size == 1 }
        record(vm, 2)
        assertEquals(listOf("Recording 2", "Recording 3"), vm.state.value.clips.map { it.name })
    }

    @Test
    fun `an empty recording is rejected and the clips that exist survive`() = test {
        val id = noteId()
        val good = vm(id)
        record(good, 1)
        val kept = good.state.value.clips.single()

        val bad = vm(id, FakeRecorder(valid = false))
        bad.startRecording()
        bad.stopRecording()

        assertEquals(AudioProblem.NOTHING_RECORDED, bad.state.value.problem)
        assertEquals(listOf(kept), bad.state.value.clips)
        assertNotNull(store.fileIfExists(AudioDocument.fileKey(id, kept.id)))
    }

    @Test
    fun `a busy microphone is reported and nothing is recording`() = test {
        val vm = vm(noteId(), FakeRecorder(fail = true))
        vm.startRecording()
        assertEquals(AudioProblem.RECORD_FAILED, vm.state.value.problem)
        assertFalse(vm.state.value.isRecording)
    }

    @Test
    fun `playing another clip stops the first one, seeking works, and recording pauses playback`() = test {
        val id = noteId()
        val vm = vm(id)
        record(vm, 1)
        record(vm, 2)
        val (first, second) = vm.state.value.clips

        vm.togglePlayback(first.id)
        assertTrue(player.playing && vm.state.value.isPlaying)
        assertEquals(store.fileIfExists(AudioDocument.fileKey(id, first.id)), player.loaded)
        vm.seekTo(12_000)
        assertEquals(12_000, player.position)

        vm.togglePlayback(second.id)
        assertEquals(second.id, vm.state.value.selectedId)
        assertEquals(store.fileIfExists(AudioDocument.fileKey(id, second.id)), player.loaded)
        assertTrue(vm.state.value.isPlaying)

        vm.startRecording()
        assertFalse("starting to record stops playback", player.playing)
        vm.togglePlayback(first.id)
        assertFalse("cannot play while recording", player.playing)
    }

    @Test
    fun `while a new recording is made no old clip is current, so its card cannot show the running time`() = test {
        val vm = vm(noteId())
        record(vm, 1)
        record(vm, 2)
        assertNotNull("the last recording is selected after it is made", vm.state.value.selectedId)

        vm.startRecording()

        assertTrue(vm.state.value.isRecording)
        assertNull(vm.state.value.selectedId)
        assertEquals(0, vm.state.value.durationMs)
        now += 5_000
        // the ticker measures the NEW recording
        vm.stopRecording()
        eventually("third clip") { vm.state.value.clips.size == 3 && vm.state.value.selectedId != null }
        assertEquals(listOf("Recording 1", "Recording 2", "Recording 3"), vm.state.value.clips.map { it.name })
    }

    @Test
    fun `the end of a clip resets playback`() = test {
        val vm = vm(noteId())
        record(vm, 1)
        vm.togglePlayback(vm.state.value.clips.single().id)
        player.completed?.invoke()
        assertFalse(vm.state.value.isPlaying)
        assertEquals(0, vm.state.value.positionMs)
    }

    @Test
    fun `kept alive, a recording goes on in the background until Stop in the notification (D255)`() = test {
        val stops = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var alive = false
        val keep = object : com.notebudget.app.feature.media.RecordingKeepAlive {
            override fun start(noteId: String, microphone: Boolean, camera: Boolean): Boolean { alive = microphone && !camera; return true }
            override fun stop() { alive = false }
            override val stopRequests = stops
        }
        val id = noteId()
        val vm = AudioViewModel(
            id, env.noteRepo, store, env.notes, recorder, player, File(dir, "cache"),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { n -> "Recording $n" }, { now }, keepAlive = keep,
        ).also { created += it }
        vm.startRecording()
        assertTrue(alive)
        vm.onScreenStopped()
        assertTrue("the phone locked: still recording", vm.state.value.isRecording)
        stops.tryEmit(Unit)
        eventually("stopped from outside") { !vm.state.value.isRecording }
        assertFalse(alive)
        eventually("clip stored") { vm.state.value.clips.size == 1 }
    }

    @Test
    fun `the app going to the background finishes and keeps a recording in progress`() = test {
        val id = noteId()
        val vm = vm(id)
        vm.startRecording()
        vm.onScreenStopped()
        assertFalse(vm.state.value.isRecording)
        eventually("clip stored") { vm.state.value.clips.size == 1 }
        assertArrayEquals(byteArrayOf(9, 9, 9), store.read(AudioDocument.fileKey(id, vm.state.value.clips.single().id)))
    }

    @Test
    fun `a clip gets a name and a comment that are saved and found by search`() = test {
        val id = noteId()
        val vm = vm(id)
        record(vm, 1)
        val clip = vm.state.value.clips.single()

        vm.rename(clip.id, "  Lecture 1  ")
        vm.setComment(clip.id, "about\nrockets")

        val saved = document(id)!!.clips.single()
        assertEquals("Lecture 1", saved.name)
        assertEquals("about\nrockets", saved.comment)
        assertEquals("Lecture 1: about rockets", env.noteRepo.getById(id)?.contentText)
        assertEquals(saved, vm.state.value.clips.single())
    }

    @Test
    fun `deleting one clip removes its file only`() = test {
        val id = noteId()
        val vm = vm(id)
        record(vm, 1)
        record(vm, 2)
        val (first, second) = vm.state.value.clips
        vm.togglePlayback(first.id)

        vm.delete(first.id)

        eventually("first removed") { vm.state.value.clips.size == 1 }
        assertNull(store.fileIfExists(AudioDocument.fileKey(id, first.id)))
        assertNotNull(store.fileIfExists(AudioDocument.fileKey(id, second.id)))
        assertEquals(listOf(second), document(id)?.clips)
        assertNull("the deleted clip was in the player", vm.state.value.selectedId)
        assertFalse(vm.state.value.isPlaying)
    }

    @Test
    fun `a note made before multiple recordings shows its one file as the first clip and keeps it`() = test {
        val id = noteId()
        store.write(id, byteArrayOf(1, 2, 3)) // the old layout: <noteId>.m4a and no document
        val vm = vm(id)
        assertEquals(listOf(AudioClip.LEGACY_ID), vm.state.value.clips.map { it.id })

        record(vm, 2)

        val clips = vm.state.value.clips
        assertEquals(AudioClip.LEGACY_ID, clips[0].id)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read(id))
        assertEquals(clips, document(id)?.clips)

        vm.delete(AudioClip.LEGACY_ID)
        eventually("legacy removed") { vm.state.value.clips.size == 1 }
        assertNull(store.fileIfExists(id))
    }

    @Test
    fun `a clip is exported under the note title and its name`() = test {
        val id = noteId()
        val vm = vm(id)
        record(vm, 1)
        val clip = vm.state.value.clips.single()
        vm.rename(clip.id, "Part: one")

        val export = vm.prepareExport(clip.id)!!

        assertEquals("Memo - Part_ one.m4a", export.fileName)
        assertArrayEquals(byteArrayOf(9, 9, 9), export.bytes)
        assertNull(vm.prepareExport("nope"))
    }

    @Test
    fun `durations are formatted`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("1:05", formatDuration(65_400))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }
}
