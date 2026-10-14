package com.notio.desktop

import kotlin.math.roundToInt

/**
 * The background of a drawing or blueprint sheet (D265): a colour, and whether the sheet is see-through and by how much. It is
 * not painted into the raster layer: it lies under both layers, so the eraser shows it again, the bucket fills the empty sheet
 * as before, and a fully see-through sheet has no background at all (a PNG with transparency).
 */
data class SheetBackground(
    /** The colour, RGB (the alpha bits are ignored). */
    val color: Int = WHITE,
    val transparent: Boolean = false,
    /** 0 - 100 %: 100 = no background at all, 50 = the colour at half strength. Used only when [transparent]. */
    val transparency: Int = 100,
) {
    /** The colour to paint under the layers, ARGB. */
    val argb: Int
        get() {
            val alpha = if (!transparent) 255 else (255 * (100 - transparency.coerceIn(0, 100)) / 100f).roundToInt()
            return (alpha shl 24) or (color and 0xFFFFFF)
        }

    /** Whether there is anything to paint at all. */
    val visible: Boolean get() = argb ushr 24 != 0

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        val DEFAULT = SheetBackground()

        /** [top] laid over [bottom] (both ARGB, not premultiplied), as the sheet shows them. */
        fun over(top: Int, bottom: Int): Int {
            val ta = top ushr 24
            if (ta == 255) return top
            val ba = bottom ushr 24
            if (ta == 0) return bottom
            val outA = ta + ba * (255 - ta) / 255
            if (outA == 0) return 0
            fun ch(shift: Int): Int {
                val t = (top shr shift) and 0xFF
                val b = (bottom shr shift) and 0xFF
                return ((t * ta + b * ba * (255 - ta) / 255) / outA).coerceIn(0, 255)
            }
            return (outA shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
