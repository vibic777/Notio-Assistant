package com.notio.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.notio.desktop.media.H264Mp4Writer
import com.notio.desktop.media.VideoFrame
import com.notio.desktop.media.VideoReader
import com.notio.desktop.media.Webcam
import com.notio.shared.AppLog
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.DataBufferInt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** What a finished recording or photo is: its file and what the note's list keeps about it. */
class CapturedMedia(val file: File, val width: Int, val height: Int, val durationMs: Int, val audio: String)

/** Pixels (0xAARRGGBB) as a Skia image: Windows' N32 is BGRA, the same bytes as little-endian ints. */
fun skiaImage(width: Int, height: Int, argb: IntArray): Image {
    val bytes = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asIntBuffer().put(argb, 0, width * height)
    return Image.makeRaster(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE), bytes.array(), width * 4)
}

fun VideoFrame.toImageBitmap(): ImageBitmap = skiaImage(width, height, argb).toComposeImageBitmap()

/** A picture of the camera as the photo of a note (JPEG, quality 90). */
fun VideoFrame.toJpeg(): ByteArray = skiaImage(width, height, argb).encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes

/** The microphone into a video file, until [stop] (16-bit mono 44.1 kHz, the same as the voice recordings). */
private class MicFeed(private val sink: (ByteArray, Int) -> Unit) {
    @Volatile private var running = true
    private val format = AudioFormat(44_100f, 16, 1, true, false)
    private val line = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
    private val thread: Thread

    init {
        line.open(format, 44_100)
        line.start()
        thread = Thread({
            val buffer = ByteArray(4_410 * 2)
            try {
                while (running) {
                    val n = line.read(buffer, 0, buffer.size)
                    if (n > 0) sink(buffer, n)
                }
            } catch (e: Throwable) {
                AppLog.e("media", "the microphone stopped", e)
            } finally {
                line.stop()
                line.close()
            }
        }, "notio-mic").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { line.stop() }
        thread.join(3_000)
    }
}

/**
 * Records the main screen into an MP4 note item (Android D147 "Record the screen"; AGENT.md D36): the screen's pictures [fps] times a
 * second through `java.awt.Robot`, H.264 by Media Foundation, and the microphone when asked.
 */
class ScreenRecorder {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var mic: MicFeed? = null
    private var result: CapturedMedia? = null
    @Volatile var startedAt = 0L
        private set

    val isRecording: Boolean get() = running

    fun start(file: File, withMic: Boolean, fps: Int = 15, overlay: VideoOverlay? = null) {
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        val rect = Rectangle(screen.x, screen.y, screen.width and 1.inv(), screen.height and 1.inv())
        val writer = H264Mp4Writer(file, rect.width, rect.height, fps = fps, bitrate = 8_000_000, withAudio = withMic)
        val lock = Any()
        running = true
        startedAt = System.currentTimeMillis()
        mic = if (withMic) runCatching { MicFeed { b, n -> synchronized(lock) { writer.addAudio(b, n) } } }.getOrNull() else null
        thread = Thread({
            val robot = Robot()
            var frames = 0L
            var last = 0L
            try {
                while (running) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    val shot = robot.createScreenCapture(rect)
                    val pixels = (shot.raster.dataBuffer as? DataBufferInt)?.data ?: shot.getRGB(0, 0, rect.width, rect.height, null, 0, rect.width)
                    overlay?.apply(pixels, shot.width, shot.height, elapsed)
                    synchronized(lock) { writer.addFrame(pixels, shot.width, elapsed) }
                    frames++
                    last = elapsed
                    val next = startedAt + frames * 1000 / fps
                    val wait = next - System.currentTimeMillis()
                    if (wait > 0) Thread.sleep(wait)
                }
            } catch (e: Throwable) {
                AppLog.critical("media", "the screen recording failed", e)
            } finally {
                mic?.stop()
                runCatching { synchronized(lock) { writer.close() } }.onFailure { AppLog.critical("media", "the screen recording could not be saved", it) }
                result = if (frames > 0) CapturedMedia(file, writer.width, writer.height, last.toInt(), if (mic != null) "MIC" else "NONE") else null
            }
        }, "notio-screen").apply { isDaemon = true; start() }
    }

    /** Stops and returns the recording, or `null` when nothing was recorded. */
    fun stop(): CapturedMedia? {
        running = false
        thread?.join(15_000)
        thread = null
        return result.also { result = null }
    }
}

/**
 * The camera of the computer for photo and video notes (Android `CameraCapture`, D147): its pictures shown live ([latest]), a photo
 * taken from the current picture, a video recorded from the pictures as they come with the microphone when asked.
 */
class CameraSession(index: Int = 0) : AutoCloseable {
    private val reader: VideoReader = Webcam.open(index)
    @Volatile var latest: VideoFrame? = null
        private set
    @Volatile private var running = true
    private var writer: H264Mp4Writer? = null
    private var mic: MicFeed? = null
    private val lock = Any()
    private var recordStart = 0L
    private var recordFile: File? = null
    private var lastTime = 0L
    @Volatile var onFrame: (VideoFrame) -> Unit = {}
    private val thread = Thread({
        try {
            while (running) {
                val frame = reader.read() ?: break
                latest = frame
                synchronized(lock) {
                    writer?.let { w ->
                        if (recordStart == 0L) recordStart = frame.timeMs
                        lastTime = frame.timeMs - recordStart
                        // The picture on screen stays clean; the overlay goes only into the recording.
                        val pixels = overlay?.let { o -> frame.argb.copyOf().also { o.apply(it, frame.width, frame.height, lastTime) } } ?: frame.argb
                        w.addFrame(pixels, frame.width, lastTime)
                    }
                }
                onFrame(frame)
            }
        } catch (e: Throwable) {
            AppLog.e("media", "the camera stopped", e)
        }
    }, "notio-camera").apply { isDaemon = true; start() }

    val isRecording: Boolean get() = writer != null

    private var overlay: VideoOverlay? = null

    fun startRecording(file: File, withMic: Boolean, overlay: VideoOverlay? = null) {
        val frame = latest ?: throw IllegalStateException("no picture yet")
        synchronized(lock) {
            val w = H264Mp4Writer(file, frame.width, frame.height, fps = 30, bitrate = 6_000_000, withAudio = withMic)
            recordStart = 0
            lastTime = 0
            recordFile = file
            this.overlay = overlay
            writer = w
            mic = if (withMic) runCatching { MicFeed { b, n -> synchronized(lock) { writer?.addAudio(b, n) } } }.getOrNull() else null
        }
    }

    fun stopRecording(): CapturedMedia? {
        val m = mic
        mic = null
        m?.stop()
        synchronized(lock) {
            val w = writer ?: return null
            writer = null
            w.close()
            return recordFile?.let { CapturedMedia(it, w.width, w.height, lastTime.toInt(), if (m != null) "MIC" else "NONE") }
        }
    }

    override fun close() {
        if (isRecording) runCatching { stopRecording() }
        running = false
        thread.join(3_000)
        reader.close()
    }
}

/**
 * The small picture of a photo or of a video's first picture, at most [longest] px; kept in [PictureCache] (bounded by memory, one
 * version per file — the whole-code pass: this cache used to keep every version of every picture for good). Blocking: off the UI thread.
 */
object MediaThumbnails {
    fun key(file: File, longest: Int = 640) = "thumb:" + file.path + "@" + longest

    fun get(file: File, video: Boolean, longest: Int = 640): ImageBitmap? {
        val key = key(file, longest)
        val stamp = file.lastModified().takeIf { it != 0L } ?: return null
        PictureCache.get(key, stamp)?.let { return it }
        val image = runCatching {
            if (video) VideoReader.open(file).use { r -> r.read()?.let { skiaImage(it.width, it.height, it.argb) } }
            else Image.makeFromEncoded(file.readBytes())
        }.getOrNull() ?: return null
        return runCatching { PictureCache.scaled(image, longest) }.getOrNull()?.also { PictureCache.put(key, stamp, it) }
    }
}
