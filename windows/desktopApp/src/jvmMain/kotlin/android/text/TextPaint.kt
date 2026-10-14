@file:Suppress("unused", "PackageDirectoryMismatch")

package android.text

import android.graphics.Paint
import android.graphics.Typeface
import org.jetbrains.skia.Font

/** The phone's TextPaint on Skia (AGENT.md D26): size, face, underline and strikethrough, measuring. */
class TextPaint(flags: Int = 0) : Paint(flags) {
    var textSize = 12f
    var typeface: Typeface? = null
    var isUnderlineText = false
    var isStrikeThruText = false

    internal fun font(): Font = Font(typeface?.sk, textSize)

    fun measureText(text: String): Float = font().measureTextWidth(text)

    /** The distance from one baseline to the next. */
    val fontSpacing: Float get() = font().metrics.let { it.descent - it.ascent + it.leading }

    class FontMetrics(@JvmField val ascent: Float, @JvmField val descent: Float)

    val fontMetrics: FontMetrics get() = font().metrics.let { FontMetrics(it.ascent, it.descent) }

}
