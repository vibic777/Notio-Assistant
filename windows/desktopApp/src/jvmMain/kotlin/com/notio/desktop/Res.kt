package com.notio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** The Android texts of the current language, for screens ported from the phone (D17). */
val LocalRes = staticCompositionLocalOf { AndroidRes.forLanguage(null) }

/** `stringResource(R.string.name, args)` of the phone, with the resource's name as text. */
@Composable
fun stringResource(name: String, vararg args: Any?): String = LocalRes.current.str(name, *args)

/** `pluralStringResource(R.plurals.name, count, args)` of the phone. */
@Composable
fun pluralStringResource(name: String, count: Int, vararg args: Any?): String = LocalRes.current.plural(name, count, *args)

/** Puts [text] on the clipboard (the phone's `copyText`). */
fun copyToClipboard(text: String) {
    runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
}
