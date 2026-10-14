package com.notebudget.app.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.notebudget.app.domain.model.GeoPoint
import com.notebudget.app.domain.model.OverlaySettings
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Size (upright), length and rotation of a video file. */
class VideoInfo(val width: Int, val height: Int, val durationMs: Int)

/**
 * The last step of a video (D147, D148): the file the camera or the screen recorder wrote gets the overlays (a time stamp
 * that runs with the video, the place, a text, a picture) and the sound (the microphone, the phone's own sound, or both
 * mixed, recorded apart as a WAV file) in one pass, with Media3 Transformer. If the video has nothing to add it is used as it is.
 */
@UnstableApi
object VideoProcessor {

    class Request(
        val video: File,
        val audio: File?,
        val overlay: OverlaySettings,
        /** The moment the recording started; the time stamp of the video runs from here. */
        val startedAtMillis: Long,
        val place: GeoPoint?,
        val logo: File?,
        val output: File,
    )

    /** `true` when [Request.output] was written with everything applied. On `false` the caller keeps the plain video. */
    suspend fun process(context: Context, request: Request): Boolean {
        val info = probe(request.video) ?: return false
        val needsOverlay = request.overlay.any
        if (!needsOverlay && request.audio == null) return false
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                runCatching { start(context, request, info, needsOverlay, continuation) }
                    .onFailure { if (continuation.isActive) continuation.resume(false) }
            }
        }
    }

    private fun start(context: Context, request: Request, info: VideoInfo, needsOverlay: Boolean, continuation: CancellableContinuation<Boolean>) {
        val videoEffects: List<androidx.media3.common.Effect> = if (needsOverlay) {
            val logo = OverlayRenderer.loadLogo(request.logo, request.overlay, info.width)
            val overlay = StampedOverlay(info.width, info.height, request.overlay, request.place, logo, request.startedAtMillis)
            listOf(OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(overlay)))
        } else {
            emptyList()
        }
        val video = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(request.video)))
            .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
            .build()
        // The vararg constructor is deprecated in Media3 1.9+ in favour of declaring the track types; kept on purpose until the
        // new one is checked on a device with a camera video (its own sound) and a screen video (none) - D231.
        @Suppress("DEPRECATION")
        val sequences = mutableListOf(EditedMediaItemSequence.Builder(video).build())
        request.audio?.let { wav ->
            @Suppress("DEPRECATION")
            sequences += EditedMediaItemSequence.Builder(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).build()).build()
        }
        val composition = Composition.Builder(sequences).build()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
            .build()
        request.output.delete()
        transformer.start(composition, request.output.absolutePath)
        continuation.invokeOnCancellation { transformer.cancel() }
    }

    /** Size, rotation and length of a video, or `null` when it cannot be read. */
    fun probe(file: File): VideoInfo? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun meta(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            var w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            if (w <= 0 || h <= 0) null else VideoInfo(w, h, meta(MediaMetadataRetriever.METADATA_KEY_DURATION))
        } finally {
            retriever.release()
        }
    }.getOrNull()

    /** A small JPEG of a frame of the video (about a second in), for lists. */
    fun thumbnail(file: File): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0) ?: return@runCatching null
            PhotoComposer.thumbnail(frame)
        } finally {
            retriever.release()
        }
    }.getOrNull()

    /** A frame-sized transparent picture that shows the overlays; it is drawn again each second because of the time stamp. */
    private class StampedOverlay(
        private val width: Int,
        private val height: Int,
        private val settings: OverlaySettings,
        private val place: GeoPoint?,
        private val logo: Bitmap?,
        private val startedAt: Long,
    ) : BitmapOverlay() {
        private var second = -1L
        private var current: Bitmap? = null

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val s = if (settings.changesWithTime) presentationTimeUs / 1_000_000 else 0L
            val cached = current
            if (cached != null && s == second) return cached
            second = s
            return OverlayRenderer.bitmap(width, height, settings, OverlayInputs(startedAt + s * 1000, place, logo)).also { current = it }
        }
    }
}
