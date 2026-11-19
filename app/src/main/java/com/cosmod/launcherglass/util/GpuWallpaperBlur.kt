package com.cosmod.launcherglass.util

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import com.cosmod.launcherglass.xposed.PrefsHelper

/** Accessed only by CosmodWallpaper's single worker, never by the UI thread. */
object GpuWallpaperBlur {
    private class Target(private val name: String) {
        private var reader: ImageReader? = null
        private var renderer: HardwareRenderer? = null
        private val node = RenderNode(name)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var effectRadius = -1f
        private var effectTone = Int.MIN_VALUE
        private var effectVibrancy = -1
        private var effectTransparency = -1
        private var outputWidth = 0
        private var outputHeight = 0

        fun render(source: Bitmap, width: Int, height: Int, radius: Float,level:Int=50,vibrancy:Int=0,transparency:Int=0): Bitmap {
            // ColorOS UniRender supports this GPU entry point directly, while
            // a standalone ImageReader renderer can submit no output at all.
            if (hardwareBitmapMethod != null) {
                if (width != outputWidth || height != outputHeight) {
                    node.setPosition(0, 0, width, height)
                    outputWidth = width
                    outputHeight = height
                }
                record(source, width, height, radius,level,vibrancy,transparency)
                return hardwareBitmapMethod.invoke(null, node, width, height) as? Bitmap
                    ?: error("GPU $name produced no hardware bitmap")
            }
            var images = reader
            if (images == null || images.width != width || images.height != height) {
                close()
                images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                reader = images
                renderer = HardwareRenderer().apply {
                    setSurface(images.surface)
                    setContentRoot(node)
                    setOpaque(false)
                }
                node.setPosition(0, 0, width, height)
            }
            record(source, width, height, radius,level,vibrancy,transparency)
            val result = renderer!!.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = images.acquireLatestImage() ?: error("GPU $name produced no image (sync=$result)")
            image.use {
                val buffer = it.hardwareBuffer ?: error("GPU $name produced no hardware buffer")
                buffer.use { hardware ->
                    val wrapped = Bitmap.wrapHardwareBuffer(hardware, ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: error("Cannot wrap GPU $name buffer")
                    try {
                        // Own the GPU texture before returning the ImageReader's
                        // slot; cached folder display lists must not see it reused.
                        return wrapped.copy(Bitmap.Config.HARDWARE, false)
                            ?: error("Cannot retain GPU $name image")
                    } finally { wrapped.recycle() }
                }
            }
        }

        private fun record(source: Bitmap, width: Int, height: Int, radius: Float,level:Int,vibrancy:Int,transparency:Int) {
            val actualRadius=radius
            if (effectRadius != actualRadius || effectTone != level || effectVibrancy != vibrancy || effectTransparency != transparency) {
                val vivid=BackdropVibrancy.effect(vibrancy)
                val color=vivid
                val blur=if(actualRadius>0f){
                    if(color!=null)RenderEffect.createBlurEffect(actualRadius,actualRadius,color,Shader.TileMode.CLAMP)
                    else RenderEffect.createBlurEffect(actualRadius,actualRadius,Shader.TileMode.CLAMP)
                }else color
                val toned=ShadowTone.effect(BlurOpacity.effect(blur,color,transparency),level)
                node.setRenderEffect(toned)
                effectRadius = actualRadius;effectTone=level;effectVibrancy=vibrancy;effectTransparency=transparency
            }
            val canvas = node.beginRecording(width, height)
            try {
                canvas.drawBitmap(source, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
            } finally { node.endRecording() }
        }

        fun close() {
            renderer?.destroy()
            renderer = null
            reader?.close()
            reader = null
            node.discardDisplayList()
            effectRadius = -1f
        }
    }

    // Optional OEM GPU path; lookup failure selects ImageReader, rendering failure uses the caller's CPU fallback.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private val hardwareBitmapMethod = try {
        HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap",
            RenderNode::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
    } catch (_: Throwable) { null }
    private val blurTarget = Target("CosmodBlur")
    private val folderTarget = Target("CherryPickFolderBlur")
    private val dockTarget = Target("CherryPickDockBlur")
    private val sampleTarget = Target("CosmodMotionSample")
    private val dissolveNode by lazy { RenderNode("CherryPick.wallpaperDissolve") }
    private var count = 0
    private var elapsedMs = 0L

    fun blur(source: Bitmap, width: Int, height: Int, radius: Float): Bitmap {
        val start = SystemClock.uptimeMillis()
        val result = blurTarget.render(source, width, height, radius,PrefsHelper.blurLightening,PrefsHelper.blurVibrancy,PrefsHelper.glassFinish.transparency)
        elapsedMs += SystemClock.uptimeMillis() - start
        count++
        if (count == 1 || count % 60 == 0) {
            GlassLogger.i("GpuBlur", "GPU blur active: ${width}x$height, output=${result.config}, frames=$count, avg=${elapsedMs / count}ms")
        }
        return result
    }

    fun blurFolder(source:Bitmap,width:Int,height:Int,radius:Float,level:Int,vibrancy:Int,transparency:Int)=folderTarget.render(source,width,height,radius,level,vibrancy,transparency)
    fun blurDock(source:Bitmap,width:Int,height:Int,radius:Float,level:Int,vibrancy:Int,transparency:Int)=dockTarget.render(source,width,height,radius,level,vibrancy,transparency)

    internal fun dissolve(old: Bitmap, next: Bitmap, screenWidth: Int, screenHeight: Int, progress: Float): Bitmap {
        val w = next.width
        val h = next.height
        val viewport = RectF(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat())
        val newCrop = com.cosmod.launcherglass.xposed.WallpaperFootprint.source(next, screenWidth, screenHeight, viewport)
        val oldCrop = com.cosmod.launcherglass.xposed.WallpaperFootprint.source(old, screenWidth, screenHeight, viewport)
        fun draw(canvas: Canvas) {
            val oldImage = WallpaperHelper.forCanvas(old, canvas)
            val newImage = WallpaperHelper.forCanvas(next, canvas)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val alpha = (progress * 255f).toInt().coerceIn(0, 255)
            paint.alpha = 255 - alpha
            paint.shader = BitmapShader(oldImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setRectToRect(oldCrop, newCrop, Matrix.ScaleToFit.FILL) })
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
            paint.alpha = alpha
            paint.blendMode = BlendMode.PLUS
            canvas.drawBitmap(newImage, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
        }
        val hardware = hardwareBitmapMethod
        if (hardware != null && PrefsHelper.gpuEffects) {
            runCatching {
                val node = dissolveNode
                node.setPosition(0, 0, w, h)
                try { draw(node.beginRecording(w, h)) } finally { node.endRecording() }
                try { hardware.invoke(null, node, w, h) as? Bitmap }
                finally { node.discardDisplayList() }
            }.getOrNull()?.let { return it }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
    }

    fun fingerprint(source: Bitmap): Long {
        val thumbnail = sampleTarget.render(source, 12, 12, 0f)
        try {
            val pixels = thumbnail.copy(Bitmap.Config.ARGB_8888, false) ?: error("Cannot read motion thumbnail")
            try {
                var signature = 17L
                for (y in 0 until 12) for (x in 0 until 12) {
                    signature = signature * 31L + (pixels.getPixel(x, y) and 0x00F8F8F8)
                }
                return signature
            } finally { pixels.recycle() }
        } finally { thumbnail.recycle() }
    }

    fun close() {
        blurTarget.close()
        folderTarget.close()
        dockTarget.close()
        sampleTarget.close()
    }
}
