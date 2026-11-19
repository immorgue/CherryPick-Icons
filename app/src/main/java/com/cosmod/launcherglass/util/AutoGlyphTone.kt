package com.cosmod.launcherglass.util

/** One-time grayscale relief for bitmap-only icons; no extra render loop or readback. */
object AutoGlyphTone {
    fun apply(pixels: IntArray, width: Int, height: Int, foreground: Int, dark: Boolean, background: Int? = null,
              translucent: Boolean = false): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val gray = IntArray(pixels.size)
        val histogram = IntArray(256)
        var count = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val y = (299 * (p ushr 16 and 255) + 587 * (p ushr 8 and 255) + 114 * (p and 255)) / 1000
            gray[i] = y
            if (p ushr 24 >= 128) { histogram[y]++; count++ }
        }
        fun percentile(fraction: Float): Int {
            val target = kotlin.math.ceil(count * fraction.toDouble()).toInt().coerceAtLeast(1)
            var total = 0
            for (i in histogram.indices) { total += histogram[i]; if (total >= target) return i }
            return 255
        }
        val low = percentile(.02f)
        val high = percentile(.98f)
        val span = (high - low).coerceAtLeast(1)
        val fr = foreground ushr 16 and 255
        val fg = foreground ushr 8 and 255
        val fb = foreground and 255
        val fa = foreground ushr 24
        return IntArray(pixels.size) { i ->
            val sourceAlpha = pixels[i] ushr 24
            val alpha = if(translucent) sourceAlpha else sourceAlpha * fa / 255
            if (alpha == 0) 0 else {
                val value = if (high - low < 20) 1f else ((gray[i] - low) / span.toFloat()).coerceIn(0f, 1f)
                val coverage = if (translucent) {
                    val x = i % width
                    val detail = if (x > 0 && x < width - 1 && i >= width && i < pixels.size - width &&
                        sourceAlpha >= 128 && pixels[i - 1] ushr 24 >= 128 && pixels[i + 1] ushr 24 >= 128 &&
                        pixels[i - width] ushr 24 >= 128 && pixels[i + width] ushr 24 >= 128) {
                        val average = (gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width]) * .25f
                        ((gray[i] - average) / span.coerceAtLeast(32)).coerceIn(0f, 1f)
                    } else 0f
                    // Raised detail remains readable while low and mid tones expose the glass.
                    val smooth = value * value * (3f - 2f * value)
                    (alpha * (.06f + .61f * smooth + .15f * detail) * fa / 255f).toInt()
                } else alpha
                // Preserve internal contrast; the opaque bitmap must not become one flat silhouette.
                val relief = if (dark) .40f + .60f * value else .70f + .30f * value
                fun channel(foreground:Int,shift:Int)=if(background==null)(foreground*relief).toInt()
                    else ((background ushr shift and 255)*(1f-value)+foreground*value).toInt()
                coverage shl 24 or (channel(fr,16) shl 16) or (channel(fg,8) shl 8) or channel(fb,0)
            }
        }
    }
}
