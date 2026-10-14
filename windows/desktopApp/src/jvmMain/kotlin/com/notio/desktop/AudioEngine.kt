package com.notio.desktop

import com.notio.desktop.media.AacM4aWriter
import com.notio.desktop.media.PcmReader
import com.notio.shared.AppLog
import com.notio.shared.data.AttachmentStore
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** Records the microphone to a file (the phone's `AudioRecorder`). */
interface AudioRecorder {
    /** @throws Exception if the microphone cannot be opened. */
    fun start(file: File)

    /** Stops and closes the file. `false` if nothing usable was recorded. */
    fun stop(): Boolean

    fun release()
}

/** Plays one audio file (the phone's `AudioPlayer`). */
interface AudioPlayer {
    fun load(file: File, onCompleted: () -> Unit): Boolean
    fun start()
    fun pause()
    fun seekTo(positionMs: Int)
    val positionMs: Int
    val durationMs: Int
    fun release()
}

/**
 * The microphone of the computer as the phone records (AGENT.md D35): 16-bit mono PCM at 44.1 kHz from the default input, encoded on
 * the fly into AAC in an MPEG-4 file (`.m4a`) by Windows Media Foundation, so the phone plays what the computer recorded and back.
 */
class DesktopRecorder : AudioRecorder {
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var failure: Throwable? = null
    @Volatile private var recordedMs = 0L

    override fun start(file: File) {
        release()
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val line = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
        line.open(format, 44_100)
        running = true
        failure = null
        recordedMs = 0
        val ready = java.util.concurrent.CountDownLatch(1)
        thread = Thread({
            try {
                AacM4aWriter(file).use { writer ->
                    ready.countDown()
                    line.start()
                    val buffer = ByteArray(4_410 * 2)
                    while (running) {
                        val n = line.read(buffer, 0, buffer.size)
                        if (n > 0) writer.write(buffer, n)
                        recordedMs = writer.durationMs
                    }
                    line.stop()
                }
            } catch (e: Throwable) {
                failure = e
                AppLog.critical("audio", "the recording failed", e)
            } finally {
                ready.countDown()
                line.close()
            }
        }, "notio-recorder").apply { isDaemon = true; start() }
        ready.await()
        failure?.let { running = false; throw it }
    }

    override fun stop(): Boolean {
        val t = thread ?: return false
        running = false
        t.join(10_000)
        thread = null
        return failure == null && recordedMs >= 300
    }

    override fun release() {
        if (thread != null) stop()
    }
}

/**
 * Plays a recording through the default output: Media Foundation decodes (the phone's AAC `.m4a` included) block by block into a
 * `SourceDataLine`; pause keeps the place, seek moves it.
 */
class DesktopPlayer : AudioPlayer {
    private var file: File? = null
    private var completed: () -> Unit = {}
    private var thread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var basePositionMs = 0L
    @Volatile private var line: SourceDataLine? = null
    private var length = 0

    override fun load(file: File, onCompleted: () -> Unit): Boolean {
        release()
        return runCatching {
            length = PcmReader(file).use { it.durationMs.toInt() }
            this.file = file
            completed = onCompleted
            basePositionMs = 0
            true
        }.getOrElse {
            AppLog.e("audio", "a recording could not be opened", it)
            false
        }
    }

    override fun start() {
        val f = file ?: return
        if (playing) return
        playing = true
        val from = basePositionMs
        thread = Thread({
            var reachedEnd = false
            try {
                PcmReader(f).use { reader ->
                    if (from > 0) reader.seek(from)
                    val format = AudioFormat(reader.sampleRate.toFloat(), 16, reader.channels, true, false)
                    val out = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
                    out.open(format)
                    line = out
                    // The sound line of Windows is given back however the playing ends (the whole-code pass, round 2).
                    try {
                        out.start()
                        while (playing) {
                            val block = reader.read()
                            if (block == null) { reachedEnd = true; break }
                            out.write(block, 0, block.size)
                        }
                        if (reachedEnd) out.drain()
                        basePositionMs = from + out.microsecondPosition / 1000
                    } finally {
                        line = null
                        out.close()
                    }
                }
            } catch (e: Throwable) {
                AppLog.e("audio", "a recording could not be played", e)
                // A failed playing is a finished one: the button goes back to Play instead of showing a playing that never ends.
                reachedEnd = true
            }
            if (reachedEnd && playing) {
                playing = false
                basePositionMs = 0
                completed()
            }
        }, "notio-player").apply { isDaemon = true; start() }
    }

    override fun pause() {
        if (!playing) return
        playing = false
        line?.let { runCatching { it.stop(); it.flush() } }
        thread?.join(2_000)
        thread = null
    }

    override fun seekTo(positionMs: Int) {
        val wasPlaying = playing
        pause()
        basePositionMs = positionMs.toLong().coerceIn(0, length.toLong())
        if (wasPlaying) start()
    }

    override val positionMs: Int
        get() = (basePositionMs + (if (playing) (line?.microsecondPosition ?: 0) / 1000 else 0)).toInt().coerceAtMost(length)

    override val durationMs: Int get() = length

    override fun release() {
        pause()
        file = null
        length = 0
    }
}

/**
 * The recordings of the notes in the data folder (the phone's `AudioStore`): a key `<noteId>` is the single recording of a note from
 * before D112 (`audio.m4a`), `<noteId>_<clipId>` a clip (`clip-<clipId>.m4a`) — the names a backup gives them.
 */
class AudioStore(private val files: AttachmentStore) {
    private fun place(key: String): Pair<String, String> {
        val cut = key.lastIndexOf('_')
        return if (cut < 0) key to "audio.m4a" else key.substring(0, cut) to "clip-" + key.substring(cut + 1) + ".m4a"
    }

    fun fileIfExists(key: String): File? = runCatching { place(key).let { (note, name) -> files.file(note, name) } }.getOrNull()

    fun read(key: String): ByteArray? = fileIfExists(key)?.readBytes()

    fun write(key: String, bytes: ByteArray) {
        val (note, name) = place(key)
        files.write(note, name, bytes.inputStream())
    }

    fun delete(key: String) {
        fileIfExists(key)?.delete()
    }
}
