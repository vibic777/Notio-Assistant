package com.notebudget.app.feature.media

import com.notebudget.app.core.common.AppLog
import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.notebudget.app.data.media.MediaNoteWriter
import com.notebudget.app.data.media.MediaStore
import com.notebudget.app.data.media.OverlayImages
import com.notebudget.app.domain.model.AudioSource
import com.notebudget.app.domain.model.GeoPoint
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.OverlayPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What happens when a video or a screen recording has been filmed (D147, D148): overlays and sound are put on it, a small
 * picture is made, and the result becomes a new item of the note. Whatever fails on the way, the plain video is kept, so a
 * recording is never lost because of a decoration.
 */
@UnstableApi
class RecordingFinisher(
    private val context: Context,
    private val store: MediaStore,
    private val writer: MediaNoteWriter,
    private val overlays: OverlayPreferences,
    private val overlayImages: OverlayImages,
) {
    class Outcome(val saved: Boolean, val decorated: Boolean)

    suspend fun finish(
        noteId: String,
        kind: MediaKind,
        rawVideo: File,
        audioWav: File?,
        audio: AudioSource,
        startedAt: Long,
        place: GeoPoint?,
    ): Outcome {
        val settings = overlays.overlay(kind).first()
        val processed = store.newTemp(".mp4")
        val decorated = runCatching {
            VideoProcessor.process(
                context,
                VideoProcessor.Request(
                    video = rawVideo, audio = audioWav, overlay = settings, startedAtMillis = startedAt, place = place,
                    logo = settings.image.file.takeIf { it.isNotBlank() }?.let { overlayImages.file(it) }, output = processed,
                ),
            )
        }.onFailure { AppLog.e("video", "overlays and sound could not be put on the video; the plain video is kept", it) }
            .getOrDefault(false) && processed.length() > 0
        if (!decorated) AppLog.w("video", "the video is saved without overlays / mixed sound")
        val finalFile = if (decorated) processed else rawVideo
        val info = withContext(Dispatchers.IO) { VideoProcessor.probe(finalFile) }
        val thumbnail = withContext(Dispatchers.IO) { VideoProcessor.thumbnail(finalFile) }
        val item = MediaItem(
            id = MediaItem.newId(),
            createdAt = startedAt,
            durationMs = info?.durationMs ?: 0,
            width = info?.width ?: 0,
            height = info?.height ?: 0,
            place = place.takeIf { settings.geo.enabled || place != null },
            audio = if (audioWav != null || audio == AudioSource.MIC) audio.name else AudioSource.NONE.name,
        )
        val saved = writer.add(noteId, kind, item, source = finalFile, bytes = null, thumbnail = thumbnail)
        withContext(Dispatchers.IO) {
            rawVideo.delete()
            processed.delete()
            audioWav?.delete()
        }
        return Outcome(saved, decorated)
    }
}
