package com.notio.desktop

/**
 * Where the sheet lies in the note area (D118, D241, D261): [areaWidth] x [areaHeight] is the whole area, the sheet at 1x is
 * [sheetWidth] x [sheetHeight] (the largest size of its proportions that fits the area), and the tool panel lies over the area
 * and covers [coveredBottom] pixels at its bottom, [coveredRight] at its right, [coveredTop] at its top or [coveredLeft] at its left
 * (Android D281). All in pixels.
 */
data class SheetFrame(
    val areaWidth: Float,
    val areaHeight: Float,
    val sheetWidth: Float,
    val sheetHeight: Float,
    val coveredBottom: Float = 0f,
    val coveredRight: Float = 0f,
    /** A panel at the top or on the left (D281). */
    val coveredTop: Float = 0f,
    val coveredLeft: Float = 0f,
) {
    /** The part of the area that the panel leaves free; it starts at ([coveredLeft], [coveredTop]). */
    val freeWidth: Float get() = (areaWidth - coveredRight - coveredLeft).coerceAtLeast(1f)
    val freeHeight: Float get() = (areaHeight - coveredBottom - coveredTop).coerceAtLeast(1f)

    companion object {
        /** The frame of a sheet of the proportions [ratio] (width / height) in an area, the panel covering nothing yet. */
        fun fit(areaWidth: Float, areaHeight: Float, ratio: Float): SheetFrame {
            val w: Float
            val h: Float
            if (areaWidth / areaHeight > ratio) { h = areaHeight; w = h * ratio } else { w = areaWidth; h = w / ratio }
            return SheetFrame(areaWidth, areaHeight, w, h)
        }
    }
}

/**
 * The zoom and pan of a drawing sheet: the sheet is drawn at [scale] (1 = the size of [SheetFrame.sheetWidth] x
 * [SheetFrame.sheetHeight]) with its top left corner at ([offsetX], [offsetY]) of the area. Screen point = offset + scale *
 * unzoomed point. On each side the sheet is centred in the free part of the area when it is smaller than it, and otherwise
 * can be moved but never so far that an empty strip shows between its edge and the free part's edge (D261).
 */
data class SheetView(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {

    /** The same view moved inside its limits for [frame] (after the area, the sheet or the panel changed). */
    fun within(frame: SheetFrame): SheetView {
        val x = place(offsetX - frame.coveredLeft, scale * frame.sheetWidth, frame.freeWidth) + frame.coveredLeft
        val y = place(offsetY - frame.coveredTop, scale * frame.sheetHeight, frame.freeHeight) + frame.coveredTop
        return if (x == offsetX && y == offsetY) this else copy(offsetX = x, offsetY = y)
    }

    /**
     * Zooms by [factor] keeping the point ([cx], [cy]) of the area where it is (the pinch centre), then moves by ([panX], [panY])
     * (the fingers moving together).
     */
    fun zoomAround(factor: Float, cx: Float, cy: Float, frame: SheetFrame, panX: Float = 0f, panY: Float = 0f): SheetView {
        if (!factor.isFinite() || factor <= 0f) return this
        val next = (scale * factor).coerceIn(MIN, MAX)
        val k = next / scale
        return SheetView(next, cx - (cx - offsetX) * k + panX, cy - (cy - offsetY) * k + panY).within(frame)
    }

    /** The + and - buttons: one step around the middle of the free part of the area. */
    fun step(zoomIn: Boolean, frame: SheetFrame): SheetView =
        zoomAround(if (zoomIn) STEP else 1f / STEP, frame.coveredLeft + frame.freeWidth / 2f, frame.coveredTop + frame.freeHeight / 2f, frame)

    /** A point of the area as a point of the unzoomed sheet (where a finger really is). */
    fun toSheetX(x: Float): Float = (x - offsetX) / scale
    fun toSheetY(y: Float): Float = (y - offsetY) / scale

    val percent: Int get() = Math.round(scale * 100f)

    companion object {
        const val MIN = 0.25f
        const val MAX = 8f
        const val STEP = 1.25f
        val NORMAL = SheetView()

        /** "Fit" (the percentage button): the whole sheet as large as the free part of the area allows, centred. */
        fun fit(frame: SheetFrame): SheetView {
            val s = minOf(frame.freeWidth / frame.sheetWidth, frame.freeHeight / frame.sheetHeight, 1f).coerceIn(MIN, MAX)
            return SheetView(s, 0f, 0f).within(frame)
        }

        private fun place(offset: Float, size: Float, free: Float): Float =
            if (size <= free) (free - size) / 2f else offset.coerceIn(free - size, 0f)
    }
}
