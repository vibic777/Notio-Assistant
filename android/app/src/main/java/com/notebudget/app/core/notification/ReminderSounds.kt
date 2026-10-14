package com.notebudget.app.core.notification

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * The melody of a reminder (D129): the phone's own notification, ringtone and alarm sounds (chosen with the system picker),
 * or any audio file from the phone, mp3 included. A chosen FILE is copied into the app (so it plays even if the original is
 * moved) and handed out through a [FileProvider] address that the system's notification service is allowed to read.
 */
object ReminderSounds {
    private const val DIR = "reminder_sounds"
    private const val MAX_BYTES = 25L * 1024 * 1024

    private fun authority(context: Context) = context.packageName + ".fileprovider"

    /**
     * Copies the chosen audio file into the app and returns the address to store in the reminder, or `null` when it is
     * not audio, too big, or cannot be read.
     */
    fun importFile(context: Context, source: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val type = resolver.getType(source).orEmpty()
        val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase().takeIf { it in AUDIO_EXTENSIONS }
            ?: when {
                type.contains("mpeg") -> "mp3"
                type.contains("mp4") || type.contains("aac") -> "m4a"
                type.contains("ogg") -> "ogg"
                type.contains("wav") -> "wav"
                type.startsWith("audio/") -> "mp3"
                else -> return null
            }
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // A readable name, so the reminder can show what melody it has.
        val safeName = name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").take(40).ifBlank { "melody" }
        val target = File(dir, "${UUID.randomUUID().toString().take(8)}-$safeName.$extension")
        resolver.openInputStream(source)?.use { input ->
            target.outputStream().use { out ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) { target.delete(); return null }
                    out.write(buffer, 0, n)
                }
            }
        } ?: return null
        if (target.length() == 0L) { target.delete(); return null }
        val uri = FileProvider.getUriForFile(context, authority(context), target)
        // The notification service runs in another process: it must be allowed to read the file.
        context.grantUriPermission("com.android.systemui", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uri.toString()
    }.getOrNull()

    /** What to show as the name of the melody; `null` address = the phone's default notification sound. */
    fun title(context: Context, uri: String?, defaultTitle: String): String {
        if (uri.isNullOrBlank()) return defaultTitle
        val parsed = Uri.parse(uri)
        if (parsed.authority == authority(context)) {
            // "<8 chars>-<name>.<ext>" -> the name
            return parsed.lastPathSegment.orEmpty().substringAfter('-').substringBeforeLast('.').ifBlank { defaultTitle }
        }
        return runCatching { RingtoneManager.getRingtone(context, parsed)?.getTitle(context) }.getOrNull() ?: parsed.lastPathSegment.orEmpty()
    }

    /** A short preview of the melody; the caller keeps the [Ringtone] to stop it. `null` if it cannot be played. */
    fun preview(context: Context, uri: String?): Ringtone? {
        val target = uri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        return runCatching { RingtoneManager.getRingtone(context, target)?.also { it.play() } }.getOrNull()
    }

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "wav", "flac", "opus", "amr", "3gp")
}
