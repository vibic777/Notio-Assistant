package com.notio.desktop

/**
 * Bucket fill on raster pixels (ARGB ints, row by row). Pure Kotlin, so it is unit-tested; the
 * Android side only moves pixels in and out of a bitmap.
 *
 * The region is every pixel connected (4 neighbours) to the start pixel whose colour is within
 * [tolerance] per channel of the start pixel's colour; the tolerance absorbs the anti-aliased
 * edges of strokes, which would otherwise leave a bright halo.
 */
object FloodFill {
    const val DEFAULT_TOLERANCE = 48

    /** Fills in place and returns the number of pixels changed (0 if nothing was filled). */
    fun fill(pixels: IntArray, width: Int, height: Int, x: Int, y: Int, color: Int, tolerance: Int = DEFAULT_TOLERANCE): Int {
        require(pixels.size >= width * height) { "pixel array smaller than the image" }
        if (x !in 0 until width || y !in 0 until height) return 0
        val target = pixels[y * width + x]
        // Filling a region with the colour it already has changes nothing.
        if (target == color) return 0

        val visited = BooleanArray(width * height)
        var stack = IntArray(1024)
        var top = 0
        fun push(index: Int) {
            if (top == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[top++] = index
        }
        fun matches(index: Int) = !visited[index] && close(pixels[index], target, tolerance)

        var changed = 0
        push(y * width + x)
        while (top > 0) {
            val seed = stack[--top]
            if (!matches(seed)) continue
            val row = seed / width
            val base = row * width
            var left = seed - base
            var right = left
            while (left > 0 && matches(base + left - 1)) left--
            while (right < width - 1 && matches(base + right + 1)) right++
            for (i in left..right) {
                visited[base + i] = true
                pixels[base + i] = color
            }
            changed += right - left + 1
            // Seed the rows above and below: one seed per run of matching pixels.
            for (next in intArrayOf(row - 1, row + 1)) {
                if (next < 0 || next >= height) continue
                var inRun = false
                for (i in left..right) {
                    val ok = matches(next * width + i)
                    if (ok && !inRun) push(next * width + i)
                    inRun = ok
                }
            }
        }
        return changed
    }

    private fun close(a: Int, b: Int, tolerance: Int): Boolean =
        kotlin.math.abs((a ushr 24) - (b ushr 24)) <= tolerance &&
            kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= tolerance &&
            kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= tolerance &&
            kotlin.math.abs((a and 0xFF) - (b and 0xFF)) <= tolerance
}
