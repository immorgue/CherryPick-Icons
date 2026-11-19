package com.cosmod.launcherglass.util

import android.graphics.*
import com.cosmod.launcherglass.xposed.PrefsHelper
import kotlin.math.roundToInt

/** Blend sharp and blurred inputs BEFORE the lens. Never fade glass/refraction coverage. */
object BlurOpacity {
    fun effect(blur: RenderEffect?, sharp: RenderEffect?, transparency: Int): RenderEffect? {
        val amount = transparency.coerceIn(0, 100)
        if (amount == 0 || blur === sharp) return blur
        if (amount == 100) return sharp
        fun weighted(input: RenderEffect?, alpha: Float): RenderEffect {
            val matrix = ColorMatrix().apply { array[18] = alpha }
            val filter = ColorMatrixColorFilter(matrix)
            return if (input == null) RenderEffect.createColorFilterEffect(filter)
                else RenderEffect.createColorFilterEffect(filter, input)
        }
        // PLUS is a premultiplied weighted sum, so coverage remains unchanged.
        return RenderEffect.createBlendModeEffect(weighted(sharp, amount / 100f),
            weighted(blur, 1f - amount / 100f), BlendMode.PLUS)
    }

    fun blurCpu(sharp: Bitmap, radius: Float, transparency: Int = PrefsHelper.glassFinish.transparency): Bitmap {
        val amount = transparency.coerceIn(0, 100)
        if (radius <= 0f || amount == 100) return sharp.copy(Bitmap.Config.ARGB_8888, false)
        val blurred = FastBlur.blur(sharp, radius.roundToInt().coerceAtLeast(1))
        if (amount == 0) return blurred
        val output = Bitmap.createBitmap(sharp.width, sharp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.alpha = (amount * 255 / 100f).roundToInt()
        canvas.drawBitmap(sharp, 0f, 0f, paint)
        paint.alpha = 255 - paint.alpha
        paint.blendMode = BlendMode.PLUS
        canvas.drawBitmap(blurred, 0f, 0f, paint)
        if (blurred !== sharp) blurred.recycle()
        return output
    }
}
