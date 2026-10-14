package com.notebudget.app.feature.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records the microphone to a file. An interface so the ViewModel logic is unit-testable. */
interface AudioRecorder {
    /** @throws Exception if the microphone cannot be opened (busy, no permission, ...). */
    fun start(file: File)

    /** Stops and releases. `false` if nothing usable was recorded (e.g. stopped instantly). */
    fun stop(): Boolean

    fun release()
}

/** Plays one audio file. */
interface AudioPlayer {
    /** Prepares [file]; `false` if it cannot be played. [onCompleted] runs when playback ends. */
    fun load(file: File, onCompleted: () -> Unit): Boolean
    fun start()
    fun pause()
    fun seekTo(positionMs: Int)
    val positionMs: Int
    val durationMs: Int
    fun release()
}

/** AAC in an MPEG-4 container (`.m4a`): small, plays everywhere, one source (the microphone). */
class MediaRecorderEngine(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    override fun start(file: File) {
        release()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    override fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false // stop() throws when no audio data was captured
        } finally {
            r.release()
        }
    }

    override fun release() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
    }
}

class MediaPlayerEngine : AudioPlayer {
    private var player: MediaPlayer? = null

    override fun load(file: File, onCompleted: () -> Unit): Boolean {
        release()
        return try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { onCompleted() }
            }
            true
        } catch (_: Exception) {
            release()
            false
        }
    }

    override fun start() { player?.start() }
    override fun pause() { player?.takeIf { it.isPlaying }?.pause() }
    override fun seekTo(positionMs: Int) { player?.seekTo(positionMs) }
    override val positionMs: Int get() = player?.currentPosition ?: 0
    override val durationMs: Int get() = player?.duration ?: 0

    override fun release() {
        player?.release()
        player = null
    }
}
