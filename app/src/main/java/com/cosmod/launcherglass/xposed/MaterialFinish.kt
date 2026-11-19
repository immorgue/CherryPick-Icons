package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Build
import android.util.LruCache
import kotlin.math.exp
import kotlin.math.roundToInt

/** Developer experiments affect only the backdrop, before tint, rim and glyph overlays. */
data class GlassFinish(val transparency: Int = 0, val haze: Boolean = false) {
    // Transparency belongs to the blur input, never to the refracted material.
    val active get() = haze
}

internal object MaterialFinish {
    private data class Key(val width: Int, val height: Int, val finish: GlassFinish)
    private val effects = object : LruCache<Key, RenderEffect>(32) {}

    @Synchronized fun effect(input: RenderEffect?, width: Int, height: Int, finish: GlassFinish): RenderEffect? {
        if (!finish.haze || Build.VERSION.SDK_INT < 33) return input
        val key = Key(width, height, GlassFinish(haze = true))
        val outer = effects[key] ?: run {
            val shader = RuntimeShader(SOURCE)
            uniforms(shader, width, height)
            val result = RenderEffect.createRuntimeShaderEffect(shader, "image")
            effects.put(key, result)
            result
        }
        return if (input == null) outer else RenderEffect.createChainEffect(outer, input)
    }

    /** Owned by the parent plate; never share a mutable input shader between materials. */
    fun shader(input: Shader, width: Int, height: Int, finish: GlassFinish, retained: RuntimeShader?): Shader {
        if (!finish.haze || Build.VERSION.SDK_INT < 33) return input
        if (finish.haze && Build.VERSION.SDK_INT >= 33) {
            val shader = retained ?: RuntimeShader(SOURCE)
            shader.setInputShader("image", input)
            uniforms(shader, width, height)
            return shader
        }
        return input
    }

    @androidx.annotation.RequiresApi(33)
    private fun uniforms(shader: RuntimeShader, width: Int, height: Int) {
        shader.setFloatUniform("size", width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1).toFloat())
    }

    fun applyCpu(source: Bitmap, finish: GlassFinish): Bitmap {
        if (!finish.active) return source
        val readable = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) ?: return source else source
        try {
            val pixels = IntArray(source.width * source.height)
            readable.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            for (y in 0 until source.height) for (x in 0 until source.width) {
                val i = y * source.width + x
                val c = pixels[i]
                val alpha = Color.alpha(c)
                if (!finish.haze || alpha == 0) {
                    pixels[i] = (alpha shl 24) or (c and 0xffffff)
                    continue
                }
                // Original iOS haze (0.2.33), without later bloom/color-depth experiments.
                val px = (x + .5f) / source.width
                val py = (y + .5f) / source.height
                val dx = px - .18f; val dy = (py - .08f) * .85f
                val veil = .025f + .09f * exp(-(dx * dx + dy * dy) * 2.4f)
                val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
                val luma = .2126f * r + .7152f * g + .0722f * b
                fun channel(v: Float, tint: Float): Int {
                    val softened = ((v * .88f + luma * .12f) - .5f) * .92f + .5f
                    return ((softened * (1f - veil) + tint * veil) * (1f - .06f * py)).coerceIn(0f, 1f).times(255).roundToInt()
                }
                pixels[i] = Color.argb(alpha, channel(r, .60f), channel(g, .67f), channel(b, .70f))
            }
            return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
        } finally {
            if (readable !== source) readable.recycle()
        }
    }

    private const val SOURCE = """
        uniform shader image;
        uniform float2 size;
        half4 main(float2 xy) {
            half4 c=image.eval(xy);
            float a=float(c.a);
            if(a<=.00001)return c;
            float3 rgb=clamp(float3(c.rgb)/a,0.0,1.0);
            float luma=dot(rgb,float3(.2126,.7152,.0722));
            rgb=(mix(rgb,float3(luma),.12)-.5)*.92+.5;
            float2 uv=xy/size;
            float2 delta=(uv-float2(.18,.08))*float2(1.0,.85);
            float veil=.025+.09*exp(-dot(delta,delta)*2.4);
            rgb=mix(rgb,float3(.60,.67,.70),veil)*(1.0-.06*uv.y);
            return half4(half3(clamp(rgb,0.0,1.0)*a),c.a);
        }
    """
}
