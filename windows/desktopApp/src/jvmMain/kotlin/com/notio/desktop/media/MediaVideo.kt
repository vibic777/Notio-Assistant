package com.notio.desktop.media

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** Video GUIDs of `mfapi.h` (AGENT.md D36). */
internal object MFV {
    val MEDIATYPE_VIDEO = MF.guid("73646976-0000-0010-8000-00AA00389B71")
    val VIDEO_H264 = MF.guid("34363248-0000-0010-8000-00AA00389B71")
    val VIDEO_RGB32 = MF.guid("00000016-0000-0010-8000-00AA00389B71")
    val MT_AVG_BITRATE = MF.guid("20332624-fb0d-4d9e-bd0d-cbf6786c102e")
    val MT_INTERLACE_MODE = MF.guid("e2724bb8-e676-4806-b4b2-a8d6efb44ccd")
    val MT_FRAME_SIZE = MF.guid("1652c33d-d6b2-4012-b834-72030849a37d")
    val MT_FRAME_RATE = MF.guid("c459a2e8-3d2c-4e44-b132-fee5156c7bb0")
    val MT_PIXEL_ASPECT_RATIO = MF.guid("c6376a1e-8d0a-4027-be45-6d9a0ad39bb6")
    val MT_DEFAULT_STRIDE = MF.guid("644b4e48-1e02-4516-b0eb-c01ca9d49ac6")
    val READWRITE_ENABLE_HARDWARE_TRANSFORMS = MF.guid("a634a91c-822b-41b9-a494-4de4643612b0")
    val SOURCE_READER_ENABLE_VIDEO_PROCESSING = MF.guid("fb394f3d-ccf1-42ee-bbb3-f9b845d5681d")
    val DEVSOURCE_ATTRIBUTE_SOURCE_TYPE = MF.guid("c60ac5fe-252a-478f-a0ef-bc8fa5f7cad3")
    val DEVSOURCE_VIDCAP = MF.guid("8ac3587a-4ae7-42d8-99e0-0a6013eef90f")
    val DEVSOURCE_FRIENDLY_NAME = MF.guid("60d0e559-52f8-4fa2-bbce-acdb34a8ec01")
    val IID_IMFMediaSource = MF.guid("279a808d-aec7-40c8-9c6b-a6b492c78a66")

    const val INTERLACE_PROGRESSIVE = 2
    const val FIRST_VIDEO = 0xFFFFFFFC.toInt()
    const val FIRST_AUDIO = 0xFFFFFFFD.toInt()
    const val ALL_STREAMS = 0xFFFFFFFE.toInt()
    const val MEDIASOURCE = 0xFFFFFFFF.toInt()
    const val END_OF_STREAM = 0x2

    fun pack(high: Int, low: Int): Long = (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)
}

internal fun Com.getUInt64(key: GUID): Long? = LongByReference().let { if (call(8, key, it) >= 0) it.value else null }

/** One picture of a video: [argb] top-down, [width] x [height], and its moment in the video. */
class VideoFrame(val width: Int, val height: Int, val argb: IntArray, val timeMs: Long)

/**
 * Writes an MPEG-4 video (H.264, and AAC sound when [withAudio]) from RGB pictures (AGENT.md D36): the phone's `.mp4` of the camera
 * and of the screen recorder. [addFrame] takes the pixels (`0xAARRGGBB`, top-down) with their moment; [addAudio] 16-bit mono PCM.
 */
class H264Mp4Writer(
    file: File,
    width: Int,
    height: Int,
    private val fps: Int = 30,
    bitrate: Int = 6_000_000,
    private val withAudio: Boolean = false,
    private val sampleRate: Int = 44_100,
) : AutoCloseable {
    /** H.264 wants even sides; one column / row is dropped when needed. */
    val width = width and 1.inv()
    val height = height and 1.inv()
    private val writer: Com
    private val videoStream: Int
    private var audioStream = -1
    private var audioSamples = 0L
    private var closed = false

    init {
        MF.startup()
        try {
            val attrs = newAttributes(2)
            val w = PointerByReference()
            try {
                attrs.setGuid(MF.TRANSCODE_CONTAINERTYPE, MF.CONTAINER_MPEG4)
                attrs.setUInt32(MFV.READWRITE_ENABLE_HARDWARE_TRANSFORMS, 1)
                MF.check(MF.fn("mfreadwrite", "MFCreateSinkWriterFromURL").invokeInt(arrayOf(WString(file.absolutePath), Pointer.NULL, attrs.p, w)), "MFCreateSinkWriterFromURL")
            } finally {
                attrs.release()
            }
            writer = Com(w.value)
            val index = IntByReference()
            newMediaType().use { out ->
                out.setGuid(MF.MT_MAJOR_TYPE, MFV.MEDIATYPE_VIDEO)
                out.setGuid(MF.MT_SUBTYPE, MFV.VIDEO_H264)
                out.setUInt32(MFV.MT_AVG_BITRATE, bitrate)
                out.setUInt32(MFV.MT_INTERLACE_MODE, MFV.INTERLACE_PROGRESSIVE)
                out.setUInt64(MFV.MT_FRAME_SIZE, MFV.pack(this.width, this.height))
                out.setUInt64(MFV.MT_FRAME_RATE, MFV.pack(fps, 1))
                out.setUInt64(MFV.MT_PIXEL_ASPECT_RATIO, MFV.pack(1, 1))
                writer.check(3, "AddStream(video)", out.p, index)
            }
            videoStream = index.value
            newMediaType().use { input ->
                input.setGuid(MF.MT_MAJOR_TYPE, MFV.MEDIATYPE_VIDEO)
                input.setGuid(MF.MT_SUBTYPE, MFV.VIDEO_RGB32)
                input.setUInt32(MFV.MT_INTERLACE_MODE, MFV.INTERLACE_PROGRESSIVE)
                input.setUInt64(MFV.MT_FRAME_SIZE, MFV.pack(this.width, this.height))
                input.setUInt64(MFV.MT_FRAME_RATE, MFV.pack(fps, 1))
                input.setUInt64(MFV.MT_PIXEL_ASPECT_RATIO, MFV.pack(1, 1))
                // Top-down rows (a positive stride), as Java keeps pixels.
                input.setUInt32(MFV.MT_DEFAULT_STRIDE, this.width * 4)
                writer.check(4, "SetInputMediaType(video)", videoStream, input.p, Pointer.NULL)
            }
            if (withAudio) {
                newMediaType().use { out ->
                    out.setGuid(MF.MT_MAJOR_TYPE, MF.MEDIATYPE_AUDIO)
                    out.setGuid(MF.MT_SUBTYPE, MF.AUDIO_AAC)
                    out.setUInt32(MF.MT_AUDIO_BITS_PER_SAMPLE, 16)
                    out.setUInt32(MF.MT_AUDIO_SAMPLES_PER_SECOND, sampleRate)
                    out.setUInt32(MF.MT_AUDIO_NUM_CHANNELS, 1)
                    out.setUInt32(MF.MT_AUDIO_AVG_BYTES_PER_SECOND, 16_000)
                    writer.check(3, "AddStream(audio)", out.p, index)
                }
                audioStream = index.value
                newMediaType().use { input ->
                    input.setGuid(MF.MT_MAJOR_TYPE, MF.MEDIATYPE_AUDIO)
                    input.setGuid(MF.MT_SUBTYPE, MF.AUDIO_PCM)
                    input.setUInt32(MF.MT_AUDIO_BITS_PER_SAMPLE, 16)
                    input.setUInt32(MF.MT_AUDIO_SAMPLES_PER_SECOND, sampleRate)
                    input.setUInt32(MF.MT_AUDIO_NUM_CHANNELS, 1)
                    input.setUInt32(MF.MT_AUDIO_BLOCK_ALIGNMENT, 2)
                    input.setUInt32(MF.MT_AUDIO_AVG_BYTES_PER_SECOND, 2 * sampleRate)
                    writer.check(4, "SetInputMediaType(audio)", audioStream, input.p, Pointer.NULL)
                }
            }
            writer.check(5, "BeginWriting")
        } catch (e: Throwable) {
            MF.shutdown()
            throw e
        }
    }

    private fun sample(bytes: Int, fill: (Pointer) -> Unit, time100ns: Long, duration100ns: Long, stream: Int) {
        val buffer = PointerByReference()
        MF.check(MF.fn("mfplat", "MFCreateMemoryBuffer").invokeInt(arrayOf(bytes, buffer)), "MFCreateMemoryBuffer")
        Com(buffer.value).use { buf ->
            val data = PointerByReference()
            buf.check(3, "Lock", data, Pointer.NULL, Pointer.NULL)
            fill(data.value)
            buf.check(4, "Unlock")
            buf.check(6, "SetCurrentLength", bytes)
            val s = PointerByReference()
            MF.check(MF.fn("mfplat", "MFCreateSample").invokeInt(arrayOf(s)), "MFCreateSample")
            Com(s.value).use { smp ->
                smp.check(42, "AddBuffer", buf.p)
                smp.check(36, "SetSampleTime", time100ns)
                smp.check(38, "SetSampleDuration", duration100ns)
                writer.check(6, "WriteSample", stream, smp.p)
            }
        }
    }

    /** One picture at [timeMs] from the start; [argb] has at least [sourceWidth] x height pixels, top-down. */
    fun addFrame(argb: IntArray, sourceWidth: Int, timeMs: Long) {
        sample(width * height * 4, { p ->
            for (y in 0 until height) p.write(y.toLong() * width * 4, argb, y * sourceWidth, width)
        }, timeMs * 10_000, 10_000_000L / fps, videoStream)
    }

    fun addAudio(pcm: ByteArray, length: Int = pcm.size) {
        if (audioStream < 0 || length <= 0) return
        val frames = length / 2
        sample(length, { it.write(0, pcm, 0, length) }, audioSamples * 10_000_000L / sampleRate, frames * 10_000_000L / sampleRate, audioStream)
        audioSamples += frames
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            writer.check(11, "Finalize")
        } finally {
            writer.release()
            MF.shutdown()
        }
    }
}

/**
 * Reads the pictures of a video (or of a camera, see [Webcam]) as RGB, top-down (AGENT.md D36): the first picture of a note's video
 * for its card, and its length.
 */
class VideoReader private constructor(private val reader: Com) : AutoCloseable {
    var width = 0
        private set
    var height = 0
        private set
    private var stride = 0
    var durationMs = 0L
        internal set
    private var closed = false

    internal fun configure() {
        reader.check(4, "SetStreamSelection", MFV.ALL_STREAMS, 0)
        reader.check(4, "SetStreamSelection", MFV.FIRST_VIDEO, 1)
        newMediaType().use { rgb ->
            rgb.setGuid(MF.MT_MAJOR_TYPE, MFV.MEDIATYPE_VIDEO)
            rgb.setGuid(MF.MT_SUBTYPE, MFV.VIDEO_RGB32)
            reader.check(7, "SetCurrentMediaType", MFV.FIRST_VIDEO, Pointer.NULL, rgb.p)
        }
        val current = PointerByReference()
        reader.check(6, "GetCurrentMediaType", MFV.FIRST_VIDEO, current)
        val t = Com(current.value)
        try {
            val size = t.getUInt64(MFV.MT_FRAME_SIZE) ?: 0
            width = (size ushr 32).toInt()
            height = (size and 0xFFFFFFFFL).toInt()
            // RGB is bottom-up unless the type says otherwise (a negative stride).
            stride = t.getUInt32(MFV.MT_DEFAULT_STRIDE) ?: -(width * 4)
        } finally {
            t.release()
        }
    }

    /** The next picture, or `null` at the end. */
    fun read(): VideoFrame? {
        while (true) {
            val index = IntByReference()
            val flags = IntByReference()
            val time = LongByReference()
            val sample = PointerByReference()
            reader.check(9, "ReadSample", MFV.FIRST_VIDEO, 0, index, flags, time, sample)
            if (flags.value and MFV.END_OF_STREAM != 0) {
                sample.value?.let { Com(it).release() }
                return null
            }
            val s = sample.value ?: continue
            return Com(s).use { smp ->
                val contiguous = PointerByReference()
                smp.check(41, "ConvertToContiguousBuffer", contiguous)
                Com(contiguous.value).use { buf ->
                    val data = PointerByReference()
                    val length = IntByReference()
                    buf.check(3, "Lock", data, Pointer.NULL, length)
                    val pixels = IntArray(width * height)
                    val rowBytes = width * 4
                    val topDown = stride > 0
                    for (y in 0 until height) {
                        val srcRow = if (topDown) y else height - 1 - y
                        data.value.read(srcRow.toLong() * rowBytes, pixels, y * width, width)
                    }
                    buf.check(4, "Unlock")
                    for (i in pixels.indices) pixels[i] = pixels[i] or (0xFF shl 24)
                    VideoFrame(width, height, pixels, time.value / 10_000)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        reader.release()
        MF.shutdown()
    }

    companion object {
        fun open(file: File): VideoReader {
            MF.startup()
            val attrs = newAttributes(1)
            val r = PointerByReference()
            try {
                attrs.setUInt32(MFV.SOURCE_READER_ENABLE_VIDEO_PROCESSING, 1)
                MF.check(MF.fn("mfreadwrite", "MFCreateSourceReaderFromURL").invokeInt(arrayOf(WString(file.absolutePath), attrs.p, r)), "MFCreateSourceReaderFromURL")
            } catch (e: Throwable) {
                attrs.release()
                MF.shutdown()
                throw e
            }
            attrs.release()
            val reader = VideoReader(Com(r.value))
            try {
                reader.configure()
                val pv = Memory(24).apply { clear() }
                reader.durationMs = if (reader.reader.call(12, MFV.MEDIASOURCE, MF.PD_DURATION, pv) >= 0) pv.getLong(8) / 10_000 else 0
            } catch (e: Throwable) {
                reader.close()
                throw e
            }
            return reader
        }

        /** A reader of a camera's source (see [Webcam]). */
        internal fun ofSource(source: Pointer): VideoReader {
            val attrs = newAttributes(1)
            val r = PointerByReference()
            try {
                attrs.setUInt32(MFV.SOURCE_READER_ENABLE_VIDEO_PROCESSING, 1)
                MF.check(MF.fn("mfreadwrite", "MFCreateSourceReaderFromMediaSource").invokeInt(arrayOf(source, attrs.p, r)), "MFCreateSourceReaderFromMediaSource")
            } finally {
                attrs.release()
            }
            return VideoReader(Com(r.value)).also { it.configure() }
        }
    }
}

/** The cameras of this computer (Media Foundation video capture devices), by name. */
object Webcam {
    /** The names of the cameras, in the system's order; empty when there is none. */
    fun names(): List<String> = withDevices { devices -> devices.map { it.second } }

    /** Opens camera [index] for reading pictures; closing the reader turns it off. */
    fun open(index: Int = 0): VideoReader {
        MF.startup()
        try {
            return withDevices { devices ->
                val activate = devices.getOrNull(index)?.first ?: throw MediaException("no camera")
                val source = PointerByReference()
                activate.check(33, "ActivateObject", MFV.IID_IMFMediaSource, source)
                // The reader holds the source now (and shuts it down, turning the camera off, when it is released).
                try { VideoReader.ofSource(source.value) } finally { Com(source.value).release() }
            }
        } catch (e: Throwable) {
            MF.shutdown()
            throw e
        }
    }

    private fun <T> withDevices(block: (List<Pair<Com, String>>) -> T): T {
        MF.startup()
        val attrs = newAttributes(1)
        val list = PointerByReference()
        val count = IntByReference()
        val devices = ArrayList<Pair<Com, String>>()
        try {
            attrs.setGuid(MFV.DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MFV.DEVSOURCE_VIDCAP)
            MF.check(com.sun.jna.NativeLibrary.getInstance("mf").getFunction("MFEnumDeviceSources").invokeInt(arrayOf(attrs.p, list, count)), "MFEnumDeviceSources")
            for (i in 0 until count.value) {
                val activate = Com(list.value.getPointer(i.toLong() * com.sun.jna.Native.POINTER_SIZE))
                val name = PointerByReference()
                val length = IntByReference()
                val text = if (activate.call(13, MFV.DEVSOURCE_FRIENDLY_NAME, name, length) >= 0) name.value.getWideString(0).also {
                    com.sun.jna.NativeLibrary.getInstance("ole32").getFunction("CoTaskMemFree").invokeVoid(arrayOf(name.value))
                } else "Camera ${i + 1}"
                devices += activate to text
            }
            return block(devices)
        } finally {
            devices.forEach { it.first.release() }
            if (count.value > 0) com.sun.jna.NativeLibrary.getInstance("ole32").getFunction("CoTaskMemFree").invokeVoid(arrayOf(list.value))
            attrs.release()
            MF.shutdown()
        }
    }
}
