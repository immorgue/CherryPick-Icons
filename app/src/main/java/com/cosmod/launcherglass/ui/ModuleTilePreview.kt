package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.BlurOpacity

/** Decorative artwork under a Module category's label and touch target. */
internal class ModuleTilePreview(context: Context, photos: List<Photo>, private val images: Images,
    private val surface: Int) : View(context) {
    data class Photo(val bitmap: Bitmap? = null, val animation: AnimatedImageDrawable? = null, val contain: Boolean = false)

    class Images(private val resources: Resources) {
        private data class SnapshotKey(val name: String, val height: Int)
        private val snapshots = android.util.LruCache<SnapshotKey, Bitmap>(8)

        fun clearSnapshots() = snapshots.evictAll()
        fun invalidate(name: String) {
            snapshots.snapshot().keys.filter { it.name == name }.forEach { snapshots.remove(it) }
        }
        fun snapshot(name: String, height: Int, preview: ModuleAppearancePreview): Bitmap {
            val key = SnapshotKey(name, height)
            return snapshots.get(key) ?: bake(preview.tileBitmap(360, height)).also { snapshots.put(key, it) }
        }

        val placeholder: Bitmap by lazy {
            val paper = BitmapFactory.decodeResource(resources, R.drawable.module_preview_placeholder)
            val ink = if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES) 255f else 0f
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(floatArrayOf(
                    0f, 0f, 0f, 0f, ink,
                    0f, 0f, 0f, 0f, ink,
                    0f, 0f, 0f, 0f, ink,
                    -.299f, -.587f, -.114f, 0f, 255f
                ))
                alpha = 124
            }
            Bitmap.createBitmap(paper.width, paper.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(paper, 0f, 0f, paint)
                paper.recycle()
            }
        }

        private val otherPhotos by lazy {
            listOf(R.drawable.module_other_bird,R.drawable.module_other_car,R.drawable.module_other_hype).map { id ->
                Photo(BitmapFactory.decodeResource(resources,id),contain=true)
            }
        }

        fun otherMedia(): List<Photo> {
            val sticker = runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources,R.drawable.module_other_sticker)) as? AnimatedImageDrawable
            }.getOrNull()
            return if (sticker == null) otherPhotos else otherPhotos + Photo(animation=sticker,contain=true)
        }

        /** Bake the small right-edge blur into the screenshot once. */
        fun bake(screenshot: Bitmap): Bitmap {
            val w = screenshot.width.toFloat()
            val h = screenshot.height.toFloat()
            val soft = BlurOpacity.blurCpu(screenshot, w * .012f, transparency = 0)
            val result = screenshot.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            val start = w * .79f
            val layer = canvas.saveLayer(start, 0f, w, h, null)
            canvas.clipRect(start, 0f, w, h)
            canvas.drawBitmap(soft, 0f, 0f, null)
            val fade = LinearGradient(start, 0f, w, 0f, Color.TRANSPARENT, 0xccffffff.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(start, 0f, w, h, Paint().apply { shader = fade; blendMode = BlendMode.DST_IN })
            canvas.restoreToCount(layer)
            soft.recycle()
            return result
        }
    }

    private val matrix = Matrix()
    private val bounds = RectF()
    private val imageBounds = RectF()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val artwork = RenderNode("CherryPick.moduleTileArtwork")
    private val labelBackdrop = RenderNode("CherryPick.moduleTileBlur").apply {
        val radius=22f*resources.displayMetrics.density
        setRenderEffect(RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP))
    }
    private val previewMask = Paint().apply { blendMode=BlendMode.DST_IN }
    private val labelShade = Paint()
    private var previewTop=0f
    private val visibleBounds = Rect()
    private val wipeMask = LinearGradient(-1f, 0f, 1f, 0f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val keepOutgoing = Paint().apply { shader = wipeMask; blendMode = BlendMode.DST_IN }
    private val keepIncoming = Paint().apply { shader = wipeMask; blendMode = BlendMode.DST_OUT }
    private val sumWipe = Paint().apply { blendMode = BlendMode.PLUS }
    private var start = 0L
    private var playing: AnimatedImageDrawable? = null
    private val photos = photos.toMutableList()
    private data class Preview(val name: String, val view: ModuleAppearancePreview)
    private val previews = mutableMapOf<Int, Preview>()
    private val pending = linkedSetOf<Int>()
    private val capture = Runnable {
        if (width <= 0 || height <= 0 || !isAttachedToWindow) return@Runnable
        val updates = pending.toList()
        pending.clear()
        for (index in updates) {
            val preview = previews.getValue(index)
            val captureHeight = (360f * bounds.height() / width).toInt().coerceAtLeast(1)
            replacePhoto(index, images.snapshot(preview.name, captureHeight, preview.view))
        }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun bindPreview(index: Int, name: String, preview: ModuleAppearancePreview) {
        previews[index] = Preview(name, preview)
        preview.setCornerRadius(0f)
        preview.onPreviewChanged = {
            images.invalidate(name)
            requestCapture(index)
        }
        requestCapture(index)
    }

    private fun replacePhoto(index: Int, bitmap: Bitmap) {
        photos[index] = Photo(bitmap = bitmap)
        invalidate()
    }

    fun showMedia(media: List<Photo>) {
        if (media.isEmpty()) return
        playback(null)
        photos.clear()
        photos.addAll(media)
        start = 0L
        invalidate()
    }

    private fun playback(animation: AnimatedImageDrawable?) {
        if (playing === animation) return
        playing?.let { it.stop();it.callback=null }
        playing=animation
        animation?.let { it.callback=this;it.start() }
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === playing || super.verifyDrawable(who)

    private fun requestCapture(index: Int) {
        if (pending.add(index) && isAttachedToWindow) postOnAnimation(capture)
    }

    override fun onDetachedFromWindow() {
        playback(null)
        artwork.discardDisplayList()
        labelBackdrop.discardDisplayList()
        removeCallbacks(capture)
        previews.values.forEach { it.view.onPreviewChanged = null }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val density=resources.displayMetrics.density
        val previewHeight=(85f*density).toInt().coerceAtMost(h)
        previewTop=(h-previewHeight).toFloat()
        bounds.set(0f,0f,w.toFloat(),previewHeight.toFloat())
        if (w > 0 && h > 0) {
            val fadeEnd = maxOf(h * .78f, (previewTop + h) / 2f)
            artwork.setPosition(0, 0, w, previewHeight)
            labelBackdrop.setPosition(0, 0, w, h)
            labelBackdrop.discardDisplayList()
            val opacity = FloatArray(17) { index ->
                val t = index / 16f
                t * t * t * (t * (6f * t - 15f) + 10f)
            }
            previewMask.shader = LinearGradient(0f, previewTop, 0f, fadeEnd,
                IntArray(opacity.size) { Color.argb((opacity[it] * 255f).toInt(), 255, 255, 255) },
                null, Shader.TileMode.CLAMP)
            labelShade.shader = LinearGradient(0f, h * .06f, 0f, fadeEnd,
                IntArray(opacity.size) { (surface and 0xffffff) or (((1f - opacity[it]) * 248f).toInt() shl 24) },
                null, Shader.TileMode.CLAMP)
        }
        pending.addAll(previews.keys)
        if (pending.isNotEmpty()) postOnAnimation(capture)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate() else {
            playback(null)
            start = 0L
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (bounds.isEmpty || photos.isEmpty()) return
        if (canvas.isHardwareAccelerated) {
            val recording=artwork.beginRecording()
            recording.drawColor(surface)
            try { drawMedia(recording) } finally { artwork.endRecording() }
            if (!labelBackdrop.hasDisplayList()) {
                val blur = labelBackdrop.beginRecording()
                try {
                    blur.scale(1f,height/bounds.height())
                    blur.drawRenderNode(artwork)
                } finally { labelBackdrop.endRecording() }
            }
            canvas.drawRenderNode(labelBackdrop)
            // Fade in the sharp preview over the blurred image. Let both
            // layers extend beyond the fade so no clipped edges meet there.
            val layer=canvas.saveLayer(0f,previewTop,bounds.right,height.toFloat(),null)
            val sharp=canvas.save()
            canvas.translate(0f,previewTop)
            canvas.drawRenderNode(artwork)
            canvas.restoreToCount(sharp)
            canvas.drawRect(0f,previewTop,bounds.right,height.toFloat(),previewMask)
            canvas.restoreToCount(layer)
        } else {
            val sharp=canvas.save()
            canvas.translate(0f,previewTop)
            drawMedia(canvas)
            canvas.restoreToCount(sharp)
        }
        canvas.drawRect(0f,0f,bounds.right,height.toFloat(),labelShade)
    }

    private fun drawMedia(canvas: Canvas) {
        if (photos.size == 1 || !ValueAnimator.areAnimatorsEnabled()) {
            playback(null)
            drawPhoto(canvas, photos.first(), 0f)
            return
        }

        val now = SystemClock.uptimeMillis()
        if (start == 0L) start = now
        val elapsed = now - start
        val index = ((elapsed / PHOTO_MS) % photos.size).toInt()
        val age = elapsed % PHOTO_MS
        val zoom = age.toFloat() / PHOTO_MS
        val wipe = ((age - (PHOTO_MS - WIPE_MS)).toFloat() / WIPE_MS).coerceAtLeast(0f)
        val current = photos[index]
        val visible=isShown && windowVisibility==VISIBLE && getLocalVisibleRect(visibleBounds)
        playback(current.animation.takeIf {visible})
        if (wipe == 0f) drawPhoto(canvas, current, zoom)
        else drawWipe(canvas, current, photos[(index + 1) % photos.size], zoom, wipe)
        if (visible) postInvalidateDelayed(33L)
    }

    private fun drawPhoto(canvas: Canvas, frame: Photo, zoom: Float) {
        val animation=frame.animation
        val photo=if(animation==null) frame.bitmap ?: images.placeholder else null
        val width=animation?.intrinsicWidth ?: photo!!.width
        val height=animation?.intrinsicHeight ?: photo!!.height
        val fitX = bounds.width() / width
        val fitY = bounds.height() / height
        val fit = when {
            frame.contain -> minOf(fitX,fitY) * .94f
            frame.bitmap==null && animation==null -> minOf(fitX,fitY) * .63f
            else -> maxOf(fitX,fitY)
        }
        val scale = fit * (1f + .065f * zoom)
        val left = bounds.centerX() - width * scale / 2
        val top = bounds.centerY() - height * scale / 2
        if (animation!=null) {
            val save=canvas.save()
            canvas.translate(left,top)
            canvas.scale(scale,scale)
            animation.setBounds(0,0,width,height)
            animation.draw(canvas)
            canvas.restoreToCount(save)
        } else if (photo!=null) {
            imageBounds.set(left,top,left+width*scale,top+height*scale)
            canvas.drawBitmap(photo,null,imageBounds,photoPaint)
        }
    }

    private fun drawWipe(canvas: Canvas, current: Photo, next: Photo, zoom: Float, progress: Float) {
        val feather = 12f * resources.displayMetrics.density
        val smooth = progress * progress * (3f - 2f * progress)
        val seam = bounds.right + feather - (bounds.width() + 2 * feather) * smooth
        val left = seam - feather
        val right = seam + feather

        val outgoing = canvas.save()
        if (canvas.clipRect(bounds.left, bounds.top, left, bounds.bottom)) drawPhoto(canvas, current, zoom)
        canvas.restoreToCount(outgoing)
        val incoming = canvas.save()
        if (canvas.clipRect(right, bounds.top, bounds.right, bounds.bottom)) drawPhoto(canvas, next, 0f)
        canvas.restoreToCount(incoming)

        val blendLeft = maxOf(bounds.left, left)
        val blendRight = minOf(bounds.right, right)
        if (blendLeft >= blendRight) return
        matrix.setScale(feather, 1f)
        matrix.postTranslate(seam, 0f)
        wipeMask.setLocalMatrix(matrix)
        val checkpoint = canvas.save()
        canvas.clipRect(blendLeft, bounds.top, blendRight, bounds.bottom)
        val blend = canvas.saveLayer(blendLeft, bounds.top, blendRight, bounds.bottom, null)
        drawPhoto(canvas, next, 0f)
        canvas.drawRect(blendLeft, bounds.top, blendRight, bounds.bottom, keepIncoming)
        val outgoingEdge = canvas.saveLayer(blendLeft, bounds.top, blendRight, bounds.bottom, sumWipe)
        drawPhoto(canvas, current, zoom)
        canvas.drawRect(blendLeft, bounds.top, blendRight, bounds.bottom, keepOutgoing)
        canvas.restoreToCount(outgoingEdge)
        canvas.restoreToCount(blend)
        canvas.restoreToCount(checkpoint)
    }

    private companion object {
        const val PHOTO_MS = 6_000L
        const val WIPE_MS = 760L
    }
}
