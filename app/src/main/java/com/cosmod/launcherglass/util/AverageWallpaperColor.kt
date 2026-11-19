package com.cosmod.launcherglass.util

/** Area-weighted mean, including dark/neutral pixels instead of selecting an accent. */
internal object AverageWallpaperColor {
    /** Use the best-supported hue family; several nearby hues count as one color. */
    fun accent(pixels: IntArray, fallback: Int): Int {
        val sectors = 24
        val weights = DoubleArray(sectors)
        val red = DoubleArray(sectors)
        val green = DoubleArray(sectors)
        val blue = DoubleArray(sectors)
        var coverage = 0.0
        var colorfulCoverage = 0.0
        for (pixel in pixels) {
            val alpha = (pixel ushr 24) / 255.0
            coverage += alpha
            val r = (pixel ushr 16) and 255
            val g = (pixel ushr 8) and 255
            val b = pixel and 255
            val high = maxOf(r, g, b)
            val chroma = high - minOf(r, g, b)
            if (alpha == 0.0 || high < 32 || chroma < maxOf(12, high / 8)) continue
            val hue = when (high) {
                r -> (g - b).toDouble() / chroma
                g -> 2.0 + (b - r).toDouble() / chroma
                else -> 4.0 + (r - g).toDouble() / chroma
            }
            val sector = (((hue + 6.0) % 6.0) * sectors / 6.0).toInt() % sectors
            colorfulCoverage += alpha
            // Favor well-supported colors over isolated saturated pixels or deep shadows.
            val weight = alpha * (.48 + .52 * (chroma / 88.0).coerceAtMost(1.0)) *
                (.5 + .5 * ((high - 32) / 96.0).coerceIn(0.0, 1.0))
            weights[sector] += weight
            red[sector] += r * weight
            green[sector] += g * weight
            blue[sector] += b * weight
        }
        if (colorfulCoverage < coverage * .085) return fallback
        fun neighbor(index: Int) = (index + sectors) % sectors
        fun support(index: Int) = weights[index] + .7 * (weights[neighbor(index - 1)] + weights[neighbor(index + 1)])
        val winner = weights.indices.maxByOrNull(::support) ?: return fallback
        val nearby = listOf(neighbor(winner - 1), winner, neighbor(winner + 1))
        val weight = nearby.sumOf { weights[it] }
        if (weight <= 0.0) return fallback
        fun channel(values: DoubleArray) = kotlin.math.round(nearby.sumOf { values[it] } / weight).toInt().coerceIn(0, 255)
        return 0xff000000.toInt() or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    fun of(pixels:IntArray):Int? {
        var red=0L;var green=0L;var blue=0L;var weight=0L
        for(pixel in pixels){
            val alpha=pixel ushr 24
            if(alpha==0)continue
            red+=(pixel ushr 16 and 255)*alpha.toLong()
            green+=(pixel ushr 8 and 255)*alpha.toLong()
            blue+=(pixel and 255)*alpha.toLong();weight+=alpha
        }
        if(weight==0L)return null
        return 0xff000000.toInt() or ((red/weight).toInt() shl 16) or ((green/weight).toInt() shl 8) or (blue/weight).toInt()
    }
}
