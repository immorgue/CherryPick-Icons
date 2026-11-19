package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import com.cosmod.launcherglass.bridge.HookReflection
import com.cosmod.launcherglass.util.GlassLogger
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Splits a flat background baked into an OEM card's background image. */
internal object WidgetBitmapBackdrop {
    data class Layer(val detail: Bitmap?, val lightPlate: Boolean = false)
    private data class Probe(val width: Int, val height: Int, val time: Long, val weatherArt: Boolean, val layer: Layer?)
    private val probes = WeakHashMap<Drawable, Probe>()

    /** Quick App's standalone image layers are painted independently of CSS background alpha. */
    fun imageLayers(drawable: Drawable): List<Drawable> {
        if (drawable.javaClass.name != "org.hapjs.component.view.drawable.CSSBackgroundDrawable") return emptyList()
        val field = drawable.javaClass.declaredFields.singleOrNull { LayerDrawable::class.java.isAssignableFrom(it.type) }
            ?: return emptyList()
        val layers = runCatching { field.isAccessible = true; field.get(drawable) as? LayerDrawable }.getOrNull()
            ?: return emptyList()
        return List(layers.numberOfLayers) { layers.getDrawable(it) }
    }

    fun inspect(drawable: Drawable, replaceWeatherArt: Boolean): Layer? {
        val bounds = drawable.bounds
        if (bounds.isEmpty || drawable.alpha == 0 || drawable is Animatable) return null
        val now = SystemClock.uptimeMillis()
        probes[drawable]?.takeIf { it.width == bounds.width() && it.height == bounds.height() && it.weatherArt == replaceWeatherArt &&
            (it.layer != null || now - it.time < 3_000) }?.let { return it.layer }
        val result = runCatching { extract(drawable, replaceWeatherArt) }.onFailure { error ->
            GlassLogger.animationLimited("WidgetBackdrop", drawable.javaClass.name, 50) { "Background capture: ${error.message}" }
        }.getOrNull()
        probes[drawable] = Probe(bounds.width(), bounds.height(), now, replaceWeatherArt, result)
        return result
    }

    private fun extract(drawable: Drawable, replaceWeatherArt: Boolean): Layer? {
        val sample = render(drawable, 40)
        val small = IntArray(sample.width * sample.height)
        sample.getPixels(small, 0, sample.width, 0, 0, sample.width, sample.height)
        sample.recycle()
        // Weather's sky illustration is a full-size CSS image behind the
        // separately rendered forecast. Replace the sky, leaving the forecast intact.
        if (replaceWeatherArt && small.count { it ushr 24 >= 224 } >= small.size * .82f &&
            imageLayers(drawable).isNotEmpty()) return Layer(null)
        val background = dominantColor(small) ?: run {
            GlassLogger.animationLimited("WidgetBackdrop", drawable.javaClass.name, 50) {
                "${drawable.javaClass.name}: opaque samples ${small.count { it ushr 24 >= 248 }}/${small.size}; no flat backing"
            }
            return null
        }
        val light = android.graphics.Color.luminance(background) > .60f
        if (small.all { it ushr 24 < 8 || difference(it, background) <= noiseTolerance(background) }) return Layer(null, light)
        val image = render(drawable, 720)
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        for (i in pixels.indices) pixels[i] = foreground(pixels[i], background)
        image.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return Layer(image, light)
    }

    private fun render(drawable: Drawable, maxEdge: Int): Bitmap {
        val bounds = drawable.bounds
        val scale = maxEdge.toFloat() / maxOf(bounds.width(), bounds.height())
        val w = (bounds.width() * scale).roundToInt().coerceAtLeast(1)
        val h = (bounds.height() * scale).roundToInt().coerceAtLeast(1)
        fun draw(canvas: Canvas) {
            canvas.scale(w.toFloat() / bounds.width(), h.toFloat() / bounds.height())
            canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            drawable.draw(canvas)
        }
        val software = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(software))
            return software
        } catch (_: IllegalArgumentException) {
            software.recycle()
        }
        // Some theme engines decode the image into a hardware-only bitmap.
        val node = RenderNode("CherryPick.widgetBackdrop")
        node.setPosition(0, 0, w, h)
        try {
            val canvas = node.beginRecording(w, h)
            try { draw(canvas) } finally { node.endRecording() }
            val hardware = HookReflection.callStaticMethod(HardwareRenderer::class.java,
                "createHardwareBitmap", node, w, h) as? Bitmap ?: error("Cannot sample widget background")
            try { return hardware.copy(Bitmap.Config.ARGB_8888, true) } finally { hardware.recycle() }
        } finally { node.discardDisplayList() }
    }

    internal fun dominantColor(pixels: IntArray): Int? {
        val colors = HashMap<Int, Int>()
        for (p in pixels) if (p ushr 24 >= 248) colors[p] = (colors[p] ?: 0) + 1
        val candidate = colors.maxByOrNull { it.value }?.key ?: return null
        val tolerance = noiseTolerance(candidate)
        val coverage = pixels.count { it ushr 24 >= 248 && difference(it, candidate) <= tolerance }
        return candidate.takeIf { coverage >= pixels.size * .66f }
    }

    private fun difference(a: Int, b: Int): Int = maxOf(
        abs((a ushr 16 and 255) - (b ushr 16 and 255)),
        abs((a ushr 8 and 255) - (b ushr 8 and 255)), abs((a and 255) - (b and 255)))

    private fun noiseTolerance(color: Int): Int {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        // ColorOS clock plates use a very shallow gray gradient in their raster art.
        return if (maxOf(r, g, b) - minOf(r, g, b) <= 10) 12 else 4
    }

    internal fun foreground(pixel: Int, background: Int): Int {
        val alpha = pixel ushr 24
        val tolerance = noiseTolerance(background)
        val contrast = difference(pixel, background)
        if (alpha < 8 || contrast <= tolerance) return 0
        fun channel(color: Int, shift: Int) = color ushr shift and 255
        fun coverage(shift: Int): Float {
            val base = channel(background, shift)
            val delta = channel(pixel, shift) - base
            return if (delta > 0) delta / (255 - base).toFloat() else -delta / base.coerceAtLeast(1).toFloat()
        }
        val detail = maxOf(coverage(16), coverage(8), coverage(0))
        fun recover(shift: Int): Int {
            val base = channel(background, shift)
            return (base + (channel(pixel, shift) - base) / detail).roundToInt().coerceIn(0, 255)
        }
        val fade = ((contrast - tolerance) / 12f).coerceAtMost(1f)
        return ((alpha * detail * fade).roundToInt() shl 24) or (recover(16) shl 16) or (recover(8) shl 8) or recover(0)
    }
}
