package com.cosmod.launcherglass.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle

/** Read published wallpaper colors on events, never capture the display or sample each frame. */
object AutoIconPalette {
    data class Wallpaper(val hue: Int, val saturation: Int, val luminance: Float, val brightness:Float)
    private var cached: Wallpaper? = null
    private var needsSample=true
    @Synchronized fun invalidateWallpaper() { cached = null;needsSample=true }
    /** Called on existing wallpaper workers, at most once after a wallpaper event. */
    @Synchronized fun useWallpaper(bitmap:android.graphics.Bitmap):Boolean {
        if(!needsSample || bitmap.isRecycled)return false
        needsSample=false
        return runCatching {
            val small=android.graphics.Bitmap.createScaledBitmap(bitmap,64,128,true)
            val readable=if(small.config==android.graphics.Bitmap.Config.HARDWARE)
                small.copy(android.graphics.Bitmap.Config.ARGB_8888,false) ?: return false else small
            val pixels=IntArray(readable.width*readable.height)
            readable.getPixels(pixels,0,readable.width,0,0,readable.width,readable.height)
            if(readable!==small)readable.recycle()
            if(small!==bitmap)small.recycle()
            val average=AverageWallpaperColor.of(pixels) ?: return false
            val accent = analyze(listOf(AverageWallpaperColor.accent(pixels, average)))
            // The overall backdrop still decides the automatic light/dark setting.
            val next = accent.copy(luminance = analyze(listOf(average)).luminance)
            (next!=cached).also {cached=next}
        }.getOrDefault(false)
    }
    @Synchronized fun wallpaper(context: Context): Wallpaper {
        cached?.let { return it }
        val colors = runCatching { WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
        val candidates = listOfNotNull(colors?.primaryColor, colors?.secondaryColor, colors?.tertiaryColor)
            .map { it.toArgb() }
        return analyze(candidates.take(1)).also { cached = it }
    }
    internal fun analyze(colors: List<Int>): Wallpaper {
        if (colors.isEmpty()) return Wallpaper(220, 35, .35f,.4f)
        val hsv = FloatArray(3)
        // Providers without population data publish a representative primary color.
        val accent = colors.first()
        Color.colorToHSV(accent, hsv)
        var luminance = 0f
        var weight = 0f
        colors.forEachIndexed { index, color ->
            val w = if (index == 0) 3f else 1f
            luminance += (.2126f * Color.red(color) + .7152f * Color.green(color) + .0722f * Color.blue(color)) / 255f * w
            weight += w
        }
        return Wallpaper(hsv[0].toInt(), (hsv[1] * 100).toInt().coerceIn(0, 72), luminance / weight,hsv[2])
    }
    fun resolve(context: Context, settings: Bundle, nativeDark: Boolean): IconPalette? {
        if (settings.getInt(AutoIconStyle.ENGINE, AutoIconStyle.DEFAULT_ENGINE) != 1) return null
        val look = settings.getInt(AutoIconStyle.LOOK, AutoIconStyle.DEFAULT_LOOK).coerceIn(0, 3)
        val wallpaper = wallpaper(context)
        val dark = look == AutoIconStyle.DARK || (look == AutoIconStyle.DEFAULT && nativeDark) ||
            (look != AutoIconStyle.DEFAULT && AutoIconStyle.dark(settings.getInt(AutoIconStyle.THEME, 0), wallpaper.luminance))
        fun result(background: Int, foreground: Int, hue: Int = 0, saturation: Int = 0) =
            IconPalette(true, background, foreground, look == AutoIconStyle.CLEAR, hue, saturation,
                whiteGlyph = !dark, autoLook = look, autoDark = dark)
        return when (look) {
            AutoIconStyle.DEFAULT -> SystemIconColors.resolve(context, dark).let { flux ->
                IconPalette(false, flux.background, flux.foreground, systemThemed=true, autoLook=look, autoDark=dark)
            }
            AutoIconStyle.DARK -> result(0xff131518.toInt(), SystemIconColors.resolve(context, dark).foreground)
            AutoIconStyle.CLEAR -> result(if (dark) 0xff161616.toInt() else 0xffe5e9ef.toInt(),
                if (dark) 0xffededed.toInt() else 0xeaffffff.toInt())
            else -> {
                val manual = settings.getInt(AutoIconStyle.SOURCE, 0) == 1
                val hue = if (manual) settings.getInt(AutoIconStyle.HUE, 220).coerceIn(0, 360) else wallpaper.hue
                val saturation = if (manual) settings.getInt(AutoIconStyle.SATURATION, 55).coerceIn(0, 100) else wallpaper.saturation
                val s = saturation / 100f
                val plateValue=if(manual).86f else (.45f+wallpaper.brightness*.45f).coerceIn(.58f,.86f)
                val glyphValue=if(manual).94f else (.52f+wallpaper.brightness*.4f).coerceIn(.62f,.90f)
                result(Color.HSVToColor(floatArrayOf(hue.toFloat(), if (dark) s * .70f else s * .75f,
                    if (dark) plateValue * .39f else plateValue)),
                    if (dark) Color.HSVToColor(floatArrayOf(hue.toFloat(), s * .65f, glyphValue)) else 0xeeffffff.toInt(), hue, saturation)
            }
        }
    }
}
