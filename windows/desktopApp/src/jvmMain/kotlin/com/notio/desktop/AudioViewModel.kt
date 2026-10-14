package com.notio.desktop

import com.notio.shared.AppLog
import com.notio.shared.transfer.AudioClipNames
import com.notio.shared.domain.AudioClip
import com.notio.shared.domain.AudioDocument
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AudioProblem { RECORD_FAILED, NOTHING_RECORDED, PLAYBACK_FAILED }

data class AudioState(
    val loaded: Boolean = false,
    val clips: List<AudioClip> = emptyList(),
    /** The clip that is in the player (playing, paused or ready), if any. */
    val selectedId: String? = null,
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    /** Playback position of the selected clip, or the elapsed time while recording. */
    val positionMs: Int = 0,
    /** Length of the selected clip. */
    val durationMs: Int = 0,
    val problem: AudioProblem? = null,
    /** The last recording was stopped by its time limit (D251): the screen says so once. */
    val limitReached: Boolean = false,
)

/** A clip ready to be saved to a file the user picks. */
class AudioExport(val fileName: String, val bytes: ByteArray)

/**
 * One audio note (D112): a list of recordings, each with a name and a comment. Recording adds a clip;
 * any clip can be played, renamed, commented, exported or deleted on its own. The clips are kept as files
 * and described by the note's [AudioDocument]. A clip is recorded to a temporary file and stored only when
 * it is valid, so a failed or empty recording never harms the existing ones. Leaving the screen while
 * recording stops and keeps the clip.
 */
class AudioViewModel(
    private val noteId: String,
    private val notes: NoteLookup,
    private val store: AudioStore,
    private val noteUseCases: NoteUseCases,
    private val recorder: AudioRecorder,
    private val player: AudioPlayer,
    private val cacheDir: File,
    /** Outlives this ViewModel: finishing a recording after the screen is gone. */
    private val appScope: CoroutineScope,
    /** The name given to a new clip: "Recording N". */
    private val defaultName: (Int) -> String = { "Recording $it" },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** How exported files are named (D139); `null` keeps "<note title> - <recording name>". */
    private val formats: com.notio.shared.domain.FormatPreferences? = null,
    /** Where the phone is, asked when a recording ends and only if the person wants the place noted (D148). */
    private val placeProvider: suspend () -> com.notio.shared.domain.GeoPoint? = { null },
    /** Where deleted recordings go (D156); without it they are deleted for good. */
    private val trash: MediaTrash? = null,
    /** How long a recording may last (D251); none by default here, the screen passes the setting. */
    recordingLimit: kotlinx.coroutines.flow.Flow<com.notio.shared.domain.RecordingLimit> =
        kotlinx.coroutines.flow.flowOf(com.notio.shared.domain.RecordingLimit.NONE),
    /** Where files are read and written; a test keeps it on its own thread. */
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    /** Keeps the recording known outside its note (D255): the floating Stop and the tray; none in tests. */
    private val keepAlive: RecordingKeepAlive = RecordingKeepAlive.NONE,
) : ViewModel() {

    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val tempFile = File(cacheDir, "recording-$noteId.m4a")
    private var startedAt = 0L
    private var ticker: Job? = null
    private var limitJob: Job? = null
    private var limit = com.notio.shared.domain.RecordingLimit.NONE

    /** Whether the recording is known outside its note (D255). */
    private var keptAlive = false

    init {
        viewModelScope.launch { recordingLimit.collect { limit = it } }
        // Stop pressed on the floating button or in the tray menu (D255).
        viewModelScope.launch { keepAlive.stopRequests.collect { if (_state.value.isRecording) stopRecording() } }
        viewModelScope.launch {
            val note = notes.getById(noteId)
            val document = AudioDocument.forNote(note?.extras, store.fileIfExists(noteId) != null)
            _state.update { it.copy(loaded = true, clips = document.clips) }
        }
    }

    private fun keyOf(clip: AudioClip) = AudioDocument.fileKey(noteId, clip.id)

    private fun clip(id: String) = _state.value.clips.firstOrNull { it.id == id }

    // ---- recording ----

    /** [limitOnce]: a limit typed for this recording only (D263); `null` = the one of Settings. */
    fun startRecording(limitOnce: com.notio.shared.domain.RecordingLimit? = null) {
        if (_state.value.isRecording) return
        stopPlayback()
        // No clip is "current" while recording: the ticking time belongs to the NEW recording, not to the last card (D117).
        if (_state.value.selectedId != null) {
            player.release()
            _state.update { it.copy(selectedId = null, durationMs = 0) }
        }
        try {
            cacheDir.mkdirs()
            tempFile.delete()
            recorder.start(tempFile)
            AppLog.i("audio", "recording")
        } catch (e: Exception) {
            AppLog.critical("audio", "the voice recording could not start", e)
            _state.update { it.copy(problem = AudioProblem.RECORD_FAILED) }
            return
        }
        startedAt = nowMillis()
        keptAlive = keepAlive.start(noteId, microphone = true, camera = false)
        _state.update { it.copy(isRecording = true, positionMs = 0, problem = null, limitReached = false) }
        // The recording stops by itself after its limit (D251); any other stop cancels this.
        limitJob?.cancel()
        val ms = (limitOnce ?: limit).millis
        limitJob = if (ms > 0) viewModelScope.launch {
            kotlinx.coroutines.delay(ms)
            if (_state.value.isRecording) {
                AppLog.i("audio", "the time limit of the recording was reached")
                stopRecording()
                _state.update { it.copy(limitReached = true) }
            }
        } else null
        startTicker { (nowMillis() - startedAt).toInt() }
    }

    fun stopRecording() {
        if (!_state.value.isRecording) return
        // Counted before the keep-alive goes, so Exit always sees either the recording or its saving (the user, 2026-09-28).
        PendingSaves.begin()
        limitJob?.cancel()
        limitJob = null
        stopTicker()
        val valid = recorder.stop()
        releaseKeepAlive()
        val elapsed = (nowMillis() - startedAt).toInt()
        _state.update { it.copy(isRecording = false, positionMs = 0) }
        if (!valid || !tempFile.isFile || tempFile.length() == 0L) {
            tempFile.delete()
            _state.update { it.copy(problem = AudioProblem.NOTHING_RECORDED) }
            PendingSaves.end()
            return
        }
        viewModelScope.launch {
          try {
            // A full disk or a vanished file is a failed recording, not a crash (rule 18).
            runCatching { commitRecording(elapsed) }
                .onSuccess { select(it.id) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    AppLog.critical("audio", "the recording could not be kept", e)
                    tempFile.delete()
                    _state.update { it.copy(problem = AudioProblem.RECORD_FAILED) }
                }
          } finally {
            PendingSaves.end()
          }
        }
    }

    /** Stores the temp file as a new clip at the end of the list and saves the list. */
    private suspend fun commitRecording(elapsedMs: Int): AudioClip {
        val bytes = withContext(io) { tempFile.readBytes().also { tempFile.delete() } }
        val clip = AudioClip(
            id = AudioClip.newId(),
            name = freshName(),
            createdAt = nowMillis(),
            durationMs = elapsedMs,
            place = runCatching { placeProvider() }.getOrNull(),
        )
        store.write(keyOf(clip), bytes)
        persist(_state.value.clips + clip)
        return clip
    }

    /** "Recording N" with the first N that no clip of this note uses yet. */
    private fun freshName(): String {
        val taken = _state.value.clips.map { it.name }.toSet()
        var n = _state.value.clips.size + 1
        while (defaultName(n) in taken) n++
        return defaultName(n)
    }

    // ---- clips ----

    fun rename(clipId: String, name: String) = change(clipId) { it.copy(name = name.trim().take(MAX_NAME)) }

    fun setComment(clipId: String, comment: String) = change(clipId) { it.copy(comment = comment.trim().take(MAX_COMMENT)) }

    private fun change(clipId: String, edit: (AudioClip) -> AudioClip) {
        if (clip(clipId) == null) return
        viewModelScope.launch { persist(_state.value.clips.map { if (it.id == clipId) edit(it).copy(updatedAt = nowMillis()) else it }) }
    }

    /** Deletes one recording (its file too); the others stay. */
    fun delete(clipId: String) {
        val target = clip(clipId) ?: return
        if (_state.value.isRecording) return
        if (_state.value.selectedId == clipId) {
            stopTicker()
            player.release()
            _state.update { it.copy(selectedId = null, isPlaying = false, positionMs = 0, durationMs = 0) }
        }
        viewModelScope.launch { remove(listOf(target)) }
    }

    /** Takes recordings out of the note: into the trash (the file stays), or for good when there is no trash (D156). */
    private suspend fun remove(targets: List<AudioClip>) {
        val before = _state.value.clips
        val title = notes.getById(noteId)?.title.orEmpty()
        val json = kotlinx.serialization.json.Json { encodeDefaults = false }
        targets.forEach { clip ->
            if (trash != null) {
                trash.stash(noteId, TrashedMedia.AUDIO, keyOf(clip), clip.name, json.encodeToString(AudioClip.serializer(), clip), before.indexOf(clip), title)
            } else {
                store.delete(keyOf(clip))
            }
        }
        val ids = targets.map { it.id }.toSet()
        persist(before.filterNot { it.id in ids })
    }

    /** Deletes several recordings at once (D146): their files too; the rest stay. */
    fun deleteMany(clipIds: Set<String>) {
        if (_state.value.isRecording) return
        val targets = _state.value.clips.filter { it.id in clipIds }
        if (targets.isEmpty()) return
        if (_state.value.selectedId in clipIds) {
            stopTicker()
            player.release()
            _state.update { it.copy(selectedId = null, isPlaying = false, positionMs = 0, durationMs = 0) }
        }
        viewModelScope.launch { remove(targets) }
    }

    /** The clip as a file named "<note title> - <clip name>.m4a", or `null` if its file is gone. */
    suspend fun prepareExport(clipId: String): AudioExport? {
        val target = clip(clipId) ?: return null
        val bytes = store.read(keyOf(target)) ?: return null
        val note = notes.getById(noteId)
        val title = note?.title.orEmpty()
        val naming = formats?.exportNaming?.let { it.first() }
        val fileName = if (naming == null) {
            AudioClipNames.exportNames(title, listOf(target)).first()
        } else {
            val item = target.name.ifBlank { defaultName(_state.value.clips.indexOf(target) + 1) }
            naming.baseName(title, item, note?.createdAt ?: target.createdAt, maxOf(target.updatedAt, target.createdAt), nowMillis(), fallback = "audio") + ".m4a"
        }
        return AudioExport(fileName, bytes)
    }

    /** Saves the list in the note: the JSON in `extras`, and a text copy of names and comments for search. */
    private suspend fun persist(clips: List<AudioClip>) {
        _state.update { it.copy(clips = clips) }
        val note = notes.getById(noteId) ?: return
        val document = AudioDocument(clips = clips)
        noteUseCases.save(note.copy(contentText = document.searchText(), extras = document.encode()), withExtras = true)
    }

    // ---- playback ----

    /** Makes [clipId] the clip in the player (paused at the start), replacing the previous one. */
    fun select(clipId: String) {
        val target = clip(clipId) ?: return
        if (_state.value.isRecording) return
        if (_state.value.selectedId == clipId) return
        stopPlayback()
        val file = store.fileIfExists(keyOf(target))
        val ok = file != null && player.load(file) { _state.update { it.copy(isPlaying = false, positionMs = 0) }; stopTicker() }
        _state.update {
            it.copy(
                selectedId = if (ok) clipId else null,
                isPlaying = false,
                positionMs = 0,
                durationMs = if (ok) player.durationMs else 0,
                problem = if (ok) null else AudioProblem.PLAYBACK_FAILED,
            )
        }
    }

    /** Play or pause [clipId]; another clip that is playing is stopped first. */
    fun togglePlayback(clipId: String) {
        if (_state.value.isRecording) return
        select(clipId)
        val s = _state.value
        if (s.selectedId != clipId) return
        if (s.isPlaying) stopPlayback() else {
            player.start()
            _state.update { it.copy(isPlaying = true, problem = null) }
            startTicker { player.positionMs }
        }
    }

    fun seekTo(positionMs: Int) {
        if (_state.value.selectedId == null || _state.value.isRecording) return
        player.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    private fun stopPlayback() {
        if (_state.value.isPlaying) {
            player.pause()
            stopTicker()
            _state.update { it.copy(isPlaying = false, positionMs = player.positionMs) }
        }
    }

    private fun startTicker(position: () -> Int) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(positionMs = position()) }
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * The screen went to the background (the app minimised, the phone locked). A recording goes on under its foreground
     * service (D255) until Stop or its limit; only when Android refused the service is it finished and kept now. Playback
     * is paused.
     */
    fun onScreenStopped() {
        if (_state.value.isRecording && !keptAlive) stopRecording()
        stopPlayback()
    }

    private fun releaseKeepAlive() {
        if (keptAlive) keepAlive.stop()
        keptAlive = false
    }

    override fun onCleared() {
        stopTicker()
        if (_state.value.isRecording) {
            val valid = recorder.stop()
            releaseKeepAlive()
            val elapsed = (nowMillis() - startedAt).toInt()
            if (valid && tempFile.isFile && tempFile.length() > 0) {
                // viewModelScope is gone: finish the save on the app scope so the clip is not lost.
                appScope.launch { commitRecording(elapsed) }
            } else {
                tempFile.delete()
            }
        }
        recorder.release()
        player.release()
    }

    companion object {
        const val MAX_NAME = 80
        const val MAX_COMMENT = 1000
    }
}
