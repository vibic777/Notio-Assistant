package com.notebudget.app.feature.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import com.notebudget.app.domain.model.AudioSource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * The sound of a recording (D147): the microphone, the sound the phone itself plays (Android 10+, needs the screen
 * capture permission), or both mixed. It is written to a temporary file and [stop] turns it into one WAV file that the video
 * processor puts under the picture. Both sources are 16-bit mono at 44.1 kHz, so mixing is a sum of samples.
 */
class AudioCapture(private val source: AudioSource, private val projection: MediaProjection?, private val dir: File) {
    private var mic: Recorder? = null
    private var system: Recorder? = null

    /** `true` when every source that was asked for started; the ones that could not start are reported by [failed]. */
    @Volatile
    var failed: Boolean = false
        private set

    // NewApi: the system-sound branch checks SDK_INT >= Q first, which lint does not follow
    @SuppressLint("MissingPermission", "NewApi")
    fun start(): Boolean {
        if (source == AudioSource.NONE) return false
        dir.mkdirs()
        if (source.usesMic) {
            mic = runCatching {
                val size = bufferSize()
                val record = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                Recorder(record, File.createTempFile("mic-", ".pcm", dir), size).also { it.start() }
            }.getOrNull()
            if (mic == null) failed = true
        }
        if (source.usesSystem) {
            system = runCatching {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null)
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val size = bufferSize()
                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
                    )
                    .setBufferSizeInBytes(size)
                    .build()
                Recorder(record, File.createTempFile("sys-", ".pcm", dir), size).also { it.start() }
            }.getOrNull()
            if (system == null) failed = true
        }
        return mic != null || system != null
    }

    private fun bufferSize(): Int = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192) * 2

    /** Stops the sources and returns the mixed sound as a WAV file, or `null` when nothing was recorded. */
    fun stop(): File? {
        val a = mic?.finish()
        val b = system?.finish()
        mic = null
        system = null
        val inputs = listOfNotNull(a, b).filter { it.length() > 0 }
        if (inputs.isEmpty()) {
            inputs.forEach { it.delete() }
            listOfNotNull(a, b).forEach { it.delete() }
            return null
        }
        val out = File.createTempFile("audio-", ".wav", dir)
        WavMixer.mix(inputs, out)
        listOfNotNull(a, b).forEach { it.delete() }
        return out
    }

    /** Reads the microphone or the playback capture into a raw file on a thread of its own. */
    private class Recorder(private val record: AudioRecord, val file: File, private val bufferBytes: Int) {
        @Volatile private var running = false
        private var worker: Thread? = null

        fun start() {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not ready" }
            record.startRecording()
            running = true
            worker = thread(name = "audio-capture", isDaemon = true) {
                val buffer = ByteArray(bufferBytes / 2)
                BufferedOutputStream(FileOutputStream(file)).use { out ->
                    while (running) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n > 0) out.write(buffer, 0, n) else if (n < 0) break
                    }
                }
            }
        }

        fun finish(): File {
            running = false
            runCatching { record.stop() }
            worker?.join(2000)
            record.release()
            return file
        }
    }

    companion object {
        const val RATE = 44_100
    }
}

/** Puts raw 16-bit mono PCM files together (summing the samples) and writes them as one WAV file. */
object WavMixer {
    fun mix(inputs: List<File>, out: File) {
        val dataBytes = inputs.maxOf { it.length() } and 1L.inv()
        FileOutputStream(out).use { raw ->
            val stream = BufferedOutputStream(raw)
            writeHeader(stream, dataBytes, AudioCapture.RATE)
            if (inputs.size == 1) {
                BufferedInputStream(FileInputStream(inputs[0])).use { it.copyTo(stream) }
            } else {
                val readers = inputs.map { BufferedInputStream(FileInputStream(it)) }
                try {
                    val chunk = 4096
                    val buffers = inputs.map { ByteArray(chunk) }
                    var left = dataBytes
                    while (left > 0) {
                        val want = minOf(left, chunk.toLong()).toInt()
                        val samples = IntArray(want / 2)
                        readers.forEachIndexed { i, r ->
                            val n = readFully(r, buffers[i], want)
                            for (s in 0 until n / 2) {
                                samples[s] += ((buffers[i][2 * s + 1].toInt() shl 8) or (buffers[i][2 * s].toInt() and 0xFF))
                            }
                        }
                        for (s in samples) {
                            val v = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            stream.write(v and 0xFF)
                            stream.write((v shr 8) and 0xFF)
                        }
                        left -= want
                    }
                } finally {
                    readers.forEach { it.close() }
                }
            }
            stream.flush()
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, want: Int): Int {
        var read = 0
        while (read < want) {
            val n = input.read(buffer, read, want - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    fun writeHeader(out: OutputStream, dataBytes: Long, rate: Int) {
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.write("RIFF".toByteArray())
        out.write(le32(36 + dataBytes))
        out.write("WAVEfmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1)) // PCM
        out.write(le16(1)) // mono
        out.write(le32(rate.toLong()))
        out.write(le32(rate * 2L))
        out.write(le16(2))
        out.write(le16(16))
        out.write("data".toByteArray())
        out.write(le32(dataBytes))
    }
}
