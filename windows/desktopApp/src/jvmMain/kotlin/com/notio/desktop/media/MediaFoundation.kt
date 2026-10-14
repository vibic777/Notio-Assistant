package com.notio.desktop.media

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * Windows Media Foundation through JNA (AGENT.md D35): the system's own AAC encoder and MPEG-4 writer for recordings the phone can
 * play (`.m4a`, AAC-LC, as Android's MediaRecorder makes them), and its decoder for playing recordings made on the phone. Only the
 * few COM calls these need, by their vtable slots from `mfidl.h` / `mfreadwrite.h`; every call's HRESULT is checked.
 */
internal object MF {
    private val mfplat by lazy { NativeLibrary.getInstance("mfplat") }
    private val mfreadwrite by lazy { NativeLibrary.getInstance("mfreadwrite") }
    private val ole32 by lazy { NativeLibrary.getInstance("ole32") }

    private const val MF_VERSION = 0x00020070
    private var started = 0

    val available: Boolean by lazy { System.getProperty("os.name").orEmpty().startsWith("Windows") && runCatching { mfplat; mfreadwrite; true }.getOrDefault(false) }

    @Synchronized
    fun startup() {
        // COM on the calling thread (multi-threaded; S_FALSE and RPC_E_CHANGED_MODE are fine), then Media Foundation once.
        ole32.getFunction("CoInitializeEx").invokeInt(arrayOf(Pointer.NULL, 0))
        if (started++ == 0) check(mfplat.getFunction("MFStartup").invokeInt(arrayOf(MF_VERSION, 0)), "MFStartup")
    }

    @Synchronized
    fun shutdown() {
        if (started > 0 && --started == 0) mfplat.getFunction("MFShutdown").invokeInt(arrayOf())
    }

    fun check(hr: Int, what: String) {
        if (hr < 0) throw MediaException("$what failed: 0x" + Integer.toHexString(hr))
    }

    fun guid(text: String): GUID = GUID.fromString("{$text}")

    // ---- GUIDs (mfapi.h) ----
    val MT_MAJOR_TYPE = guid("48eba18e-f8c9-4687-bf11-0a74c9f96a8f")
    val MT_SUBTYPE = guid("f7e34c9a-42e8-4714-b74b-cb29d72c35e5")
    val MEDIATYPE_AUDIO = guid("73647561-0000-0010-8000-00AA00389B71")
    val AUDIO_AAC = guid("00001610-0000-0010-8000-00AA00389B71")
    val AUDIO_PCM = guid("00000001-0000-0010-8000-00AA00389B71")
    val MT_AUDIO_BITS_PER_SAMPLE = guid("f2deb57f-40fa-4764-aa33-ed4f2d1ff669")
    val MT_AUDIO_SAMPLES_PER_SECOND = guid("5faeeae7-0290-4c31-9e8a-c534f68d9dba")
    val MT_AUDIO_NUM_CHANNELS = guid("37e48bf5-645e-4c5b-89de-ada9e29b696a")
    val MT_AUDIO_AVG_BYTES_PER_SECOND = guid("1aab75c8-cfef-451c-ab95-ac034b8e1731")
    val MT_AUDIO_BLOCK_ALIGNMENT = guid("322de230-9eeb-43bd-ab7a-ff412251541d")
    val TRANSCODE_CONTAINERTYPE = guid("150ff23f-4abc-478b-ac4f-e1916fba1cca")
    val CONTAINER_MPEG4 = guid("dc6cd05d-b9d0-40ef-bd35-fa622c1ab28a")
    val PD_DURATION = guid("6c990d33-bb8e-477a-8598-0d5d96fcd88a")
    val GUID_NULL = GUID()

    fun fn(lib: String, name: String): Function = (if (lib == "mfreadwrite") mfreadwrite else mfplat).getFunction(name)
}

class MediaException(message: String) : Exception(message)

/** A COM interface pointer: calls go through its vtable. */
internal class Com(val p: Pointer) {
    private fun slot(index: Int): Function = Function.getFunction(p.getPointer(0).getPointer(index.toLong() * com.sun.jna.Native.POINTER_SIZE))

    fun call(index: Int, vararg args: Any?): Int = slot(index).invokeInt(arrayOf<Any?>(p, *args))

    fun check(index: Int, what: String, vararg args: Any?) = MF.check(call(index, *args), what)

    fun release() {
        slot(2).invokeInt(arrayOf<Any?>(p))
    }

    // IMFAttributes
    fun setUInt32(key: GUID, value: Int) = check(21, "SetUINT32", key, value)
    fun setUInt64(key: GUID, value: Long) = check(22, "SetUINT64", key, value)
    fun setGuid(key: GUID, value: GUID) = check(24, "SetGUID", key, value)
    fun getUInt32(key: GUID): Int? = IntByReference().let { if (call(7, key, it) >= 0) it.value else null }
}

internal fun <T> Com.use(block: (Com) -> T): T = try { block(this) } finally { release() }

internal fun newMediaType(): Com = PointerByReference().let {
    MF.check(MF.fn("mfplat", "MFCreateMediaType").invokeInt(arrayOf(it)), "MFCreateMediaType")
    Com(it.value)
}

internal fun newAttributes(count: Int): Com = PointerByReference().let {
    MF.check(MF.fn("mfplat", "MFCreateAttributes").invokeInt(arrayOf(it, count)), "MFCreateAttributes")
    Com(it.value)
}

/**
 * Writes 16-bit PCM as AAC-LC into an MPEG-4 audio file (the phone's `.m4a`: mono, 44.1 kHz). [write] takes PCM bytes as they come
 * from the microphone; [finish] closes the file. The file is only valid after [finish].
 */
class AacM4aWriter(private val file: File, private val sampleRate: Int = 44_100, private val channels: Int = 1) : AutoCloseable {
    private val writer: Com
    private val stream: Int
    private var samplesWritten = 0L
    private var closed = false

    init {
        MF.startup()
        try {
            val attrs = newAttributes(1)
            val w = PointerByReference()
            try {
                attrs.setGuid(MF.TRANSCODE_CONTAINERTYPE, MF.CONTAINER_MPEG4)
                MF.check(MF.fn("mfreadwrite", "MFCreateSinkWriterFromURL").invokeInt(arrayOf(WString(file.absolutePath), Pointer.NULL, attrs.p, w)), "MFCreateSinkWriterFromURL")
            } finally {
                attrs.release()
            }
            writer = Com(w.value)
            val index = IntByReference()
            newMediaType().use { out ->
                out.setGuid(MF.MT_MAJOR_TYPE, MF.MEDIATYPE_AUDIO)
                out.setGuid(MF.MT_SUBTYPE, MF.AUDIO_AAC)
                out.setUInt32(MF.MT_AUDIO_BITS_PER_SAMPLE, 16)
                out.setUInt32(MF.MT_AUDIO_SAMPLES_PER_SECOND, sampleRate)
                out.setUInt32(MF.MT_AUDIO_NUM_CHANNELS, channels)
                // 96 kbit/s: the lowest rate the Windows AAC encoder takes (the phone records at 64).
                out.setUInt32(MF.MT_AUDIO_AVG_BYTES_PER_SECOND, 12_000)
                writer.check(3, "AddStream", out.p, index)
            }
            stream = index.value
            newMediaType().use { input ->
                input.setGuid(MF.MT_MAJOR_TYPE, MF.MEDIATYPE_AUDIO)
                input.setGuid(MF.MT_SUBTYPE, MF.AUDIO_PCM)
                input.setUInt32(MF.MT_AUDIO_BITS_PER_SAMPLE, 16)
                input.setUInt32(MF.MT_AUDIO_SAMPLES_PER_SECOND, sampleRate)
                input.setUInt32(MF.MT_AUDIO_NUM_CHANNELS, channels)
                input.setUInt32(MF.MT_AUDIO_BLOCK_ALIGNMENT, 2 * channels)
                input.setUInt32(MF.MT_AUDIO_AVG_BYTES_PER_SECOND, 2 * channels * sampleRate)
                writer.check(4, "SetInputMediaType", stream, input.p, Pointer.NULL)
            }
            writer.check(5, "BeginWriting")
        } catch (e: Throwable) {
            MF.shutdown()
            throw e
        }
    }

    /** Milliseconds of sound written so far. */
    val durationMs: Long get() = samplesWritten * 1000 / sampleRate

    fun write(pcm: ByteArray, length: Int = pcm.size) {
        if (length <= 0) return
        val buffer = PointerByReference()
        MF.check(MF.fn("mfplat", "MFCreateMemoryBuffer").invokeInt(arrayOf(length, buffer)), "MFCreateMemoryBuffer")
        Com(buffer.value).use { buf ->
            val data = PointerByReference()
            buf.check(3, "Lock", data, Pointer.NULL, Pointer.NULL)
            data.value.write(0, pcm, 0, length)
            buf.check(4, "Unlock")
            buf.check(6, "SetCurrentLength", length)
            val sample = PointerByReference()
            MF.check(MF.fn("mfplat", "MFCreateSample").invokeInt(arrayOf(sample)), "MFCreateSample")
            Com(sample.value).use { s ->
                s.check(42, "AddBuffer", buf.p)
                val frames = length / (2 * channels)
                s.check(36, "SetSampleTime", samplesWritten * 10_000_000L / sampleRate)
                s.check(38, "SetSampleDuration", frames * 10_000_000L / sampleRate)
                writer.check(6, "WriteSample", stream, s.p)
                samplesWritten += frames
            }
        }
    }

    /** Closes the file (MPEG-4 needs its index written at the end). */
    fun finish() {
        if (closed) return
        closed = true
        try {
            writer.check(11, "Finalize")
        } finally {
            writer.release()
            MF.shutdown()
        }
    }

    override fun close() = finish()
}

/**
 * Reads any audio file Windows can decode (the phone's `.m4a`, MP3, WAV ...) as 16-bit PCM, block by block; [seek] moves to a moment.
 */
class PcmReader(file: File) : AutoCloseable {
    private val reader: Com
    val sampleRate: Int
    val channels: Int
    val durationMs: Long
    private var closed = false

    init {
        MF.startup()
        try {
            val r = PointerByReference()
            MF.check(MF.fn("mfreadwrite", "MFCreateSourceReaderFromURL").invokeInt(arrayOf(WString(file.absolutePath), Pointer.NULL, r)), "MFCreateSourceReaderFromURL")
            reader = Com(r.value)
            reader.check(4, "SetStreamSelection", ALL_STREAMS, 0)
            reader.check(4, "SetStreamSelection", FIRST_AUDIO, 1)
            newMediaType().use { pcm ->
                pcm.setGuid(MF.MT_MAJOR_TYPE, MF.MEDIATYPE_AUDIO)
                pcm.setGuid(MF.MT_SUBTYPE, MF.AUDIO_PCM)
                pcm.setUInt32(MF.MT_AUDIO_BITS_PER_SAMPLE, 16)
                reader.check(7, "SetCurrentMediaType", FIRST_AUDIO, Pointer.NULL, pcm.p)
            }
            val current = PointerByReference()
            reader.check(6, "GetCurrentMediaType", FIRST_AUDIO, current)
            val t = Com(current.value)
            try {
                sampleRate = t.getUInt32(MF.MT_AUDIO_SAMPLES_PER_SECOND) ?: 44_100
                channels = t.getUInt32(MF.MT_AUDIO_NUM_CHANNELS) ?: 1
            } finally {
                t.release()
            }
            // PROPVARIANT: vt at 0, the 64-bit value at 8.
            val pv = Memory(24).apply { clear() }
            durationMs = if (reader.call(12, MEDIASOURCE, MF.PD_DURATION, pv) >= 0) pv.getLong(8) / 10_000 else 0
        } catch (e: Throwable) {
            MF.shutdown()
            throw e
        }
    }

    /** The next block of PCM, or `null` at the end. */
    fun read(): ByteArray? {
        while (true) {
            val index = IntByReference()
            val flags = IntByReference()
            val time = LongByReference()
            val sample = PointerByReference()
            reader.check(9, "ReadSample", FIRST_AUDIO, 0, index, flags, time, sample)
            if (flags.value and END_OF_STREAM != 0) {
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
                    val bytes = data.value.getByteArray(0, length.value)
                    buf.check(4, "Unlock")
                    bytes
                }
            }
        }
    }

    /** Moves to [ms] from the start (the next [read] gives the sound from there, to the nearest block). */
    fun seek(ms: Long) {
        val pv = Memory(24).apply { clear() }
        pv.setShort(0, VT_I8)
        pv.setLong(8, ms * 10_000)
        reader.check(8, "SetCurrentPosition", MF.GUID_NULL, pv)
    }

    override fun close() {
        if (closed) return
        closed = true
        reader.release()
        MF.shutdown()
    }

    private companion object {
        const val ALL_STREAMS = 0xFFFFFFFE.toInt()
        const val FIRST_AUDIO = 0xFFFFFFFD.toInt()
        const val MEDIASOURCE = 0xFFFFFFFF.toInt()
        const val END_OF_STREAM = 0x2
        const val VT_I8: Short = 20
    }
}
