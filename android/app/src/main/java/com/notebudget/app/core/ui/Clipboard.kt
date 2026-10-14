package com.notebudget.app.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Puts [text] on the clipboard. A [sensitive] text (a password) is marked so: Android 13+ then hides it in the clipboard preview
 * and keyboards do not suggest it (D231); the same extra is understood by some older keyboards too.
 */
fun copyText(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) }
}
