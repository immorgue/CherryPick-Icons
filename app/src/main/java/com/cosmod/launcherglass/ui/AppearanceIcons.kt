package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import com.cosmod.launcherglass.R
import kotlin.math.roundToInt

internal object AppearanceIcons {
    data class Sample(val stock: Bitmap, val plate: Bitmap? = null, val glyph: Bitmap? = null)

    fun system(context: Context): List<Sample> {
        val pm = context.packageManager
        val systemApps = listOf(
            listOf("com.android.settings"),
            listOf("com.android.camera", "com.oplus.camera", "com.google.android.GoogleCamera"),
            listOf("com.coloros.calculator", "com.android.calculator2", "com.google.android.calculator"),
            listOf("com.coloros.alarmclock", "com.android.deskclock", "com.google.android.deskclock"),
            listOf("com.coloros.gallery3d", "com.android.gallery3d", "com.google.android.apps.photos"),
            listOf("com.coloros.calendar", "com.android.calendar", "com.google.android.calendar"),
            listOf("com.coloros.filemanager", "com.google.android.documentsui", "com.android.documentsui"),
            listOf("com.coloros.soundrecorder", "com.android.soundrecorder", "com.google.android.soundrecorder"),
            listOf("com.coloros.weather2"),
            listOf("com.android.chrome"),
            listOf("com.android.contacts")
        )
        val samples = linkedMapOf<String, Sample>()
        for (alternatives in systemApps) {
            for (name in alternatives) {
                val icon = runCatching { rawIcon(context, name) }.getOrNull() ?: continue
                samples[name] = render(icon)
                break
            }
            if (samples.size == 8) break
        }
        if (samples.size < 8) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (entry in pm.queryIntentActivities(launcher, 0)) {
                val name = entry.activityInfo.packageName
                if (name in samples || name == context.packageName) continue
                val icon = runCatching { entry.loadIcon(pm) }.getOrNull() ?: continue
                if (icon !is AdaptiveIconDrawable) continue
                samples[name] = render(icon)
                if (samples.size == 8) break
            }
        }
        return samples.values.toList()
    }

    fun app(context: Context): Sample =
        render(requireNotNull(context.getDrawable(R.mipmap.ic_launcher)),
            context.getDrawable(R.drawable.ic_launcher_artwork))

    private fun rawIcon(context: Context, name: String): Drawable {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(name, 0)
        val resources = pm.getResourcesForApplication(info)
        val value = android.util.TypedValue()
        resources.getValue(info.icon, value, true)
        val path = value.string?.toString()
        if (path != null && !path.endsWith(".xml")) {
            val bitmap = resources.openRawResource(info.icon).use { BitmapFactory.decodeStream(it) }
            if (bitmap != null) return BitmapDrawable(resources, bitmap)
        }
        return pm.getApplicationIcon(info)
    }

    private fun render(drawable: Drawable, appGlyph: Drawable? = null): Sample {
        val size = 288
        fun bitmap(draw: (Canvas) -> Unit) =
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
        drawable.setBounds(0, 0, size, size)
        val stock = bitmap(drawable::draw)
        val mask = bitmap { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            // The same continuous silhouette is used for all samples.
            val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
            canvas.drawRoundRect(bounds, size * .24f, size * .24f, paint)
        }
        val mono = appGlyph ?: if (Build.VERSION.SDK_INT >= 33 && drawable is AdaptiveIconDrawable)
            drawable.monochrome else null
        val glyph = mono?.let {
            val inset = if (appGlyph == null) size/6 else size/36
            it.setBounds(inset, inset, size - inset, size - inset)
            bitmap(it::draw).takeIf { image ->
                val pixels = IntArray(size * size)
                image.getPixels(pixels, 0, size, 0, 0, size, size)
                if (appGlyph != null) {
                    // The CherryPick launcher asset contains an opaque grayscale
                    // plate. Isolate the two colored parts of the original logo.
                    for (index in pixels.indices) {
                        val pixel = pixels[index]
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        val chroma = maxOf(r, g, b) - minOf(r, g, b)
                        val coverage = ((chroma - 10) / 90f).coerceIn(0f, 1f)
                        pixels[index] = (coverage * Color.alpha(pixel)).roundToInt() shl 24 or 0xffffff
                    }
                    image.setPixels(pixels, 0, size, 0, 0, size, size)
                    return@takeIf true
                }
                val opaque = pixels.count { pixel -> Color.alpha(pixel) >= 192 }
                opaque in (size * size / 16)..(size * size / 2)
            }
        } ?: extractFlatGlyph(stock)
        return Sample(stock, if (glyph != null) mask else null, glyph)
    }

    private fun extractFlatGlyph(stock: Bitmap): Bitmap? {
        val pixels = IntArray(stock.width * stock.height)
        stock.getPixels(pixels, 0, stock.width, 0, 0, stock.width, stock.height)
        val buckets = IntArray(4096)
        var count = 0
        for (color in pixels) if (Color.alpha(color) > 200) {
            val key = (Color.red(color) shr 4 shl 8) or (Color.green(color) shr 4 shl 4) or (Color.blue(color) shr 4)
            buckets[key]++
            count++
        }
        val dominant = buckets.indices.maxByOrNull { buckets[it] } ?: return null
        if (count == 0 || buckets[dominant] < count * .25f) return null
        val red = ((dominant shr 8) and 15) * 16 + 8
        val green = ((dominant shr 4) and 15) * 16 + 8
        val blue = (dominant and 15) * 16 + 8
        var foreground = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            val difference = maxOf(kotlin.math.abs(Color.red(color) - red),
                kotlin.math.abs(Color.green(color) - green), kotlin.math.abs(Color.blue(color) - blue))
            val coverage = ((difference - 28) / 110f).coerceIn(0f, 1f)
            val alpha = (Color.alpha(color) * coverage).roundToInt()
            if (alpha >= 192) foreground++
            pixels[index] = alpha shl 24 or 0xffffff
        }
        if (foreground < count / 50 || foreground > count / 2) return null
        return Bitmap.createBitmap(pixels, stock.width, stock.height, Bitmap.Config.ARGB_8888)
    }
}
