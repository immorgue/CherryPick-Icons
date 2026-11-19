package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import com.cosmod.launcherglass.xposed.GlassStyle
import com.cosmod.launcherglass.xposed.KyantOptics
import com.cosmod.launcherglass.xposed.LiquidGlassShader
import kotlin.math.min
import kotlin.math.roundToInt

/** Captures the app behind the surface, excluding the navigation and popup themselves. */
internal class UiLiquidGlass(
    context: Context,
    private val source: () -> View?,
    private val radiusDp: Float,
    private val selection: Boolean = false,
    private val backdropColor: Int? = null,
    private val tintColor: Int? = null,
    private val trackBackdrop:Boolean=true,
    private val trackSourceContent:Boolean=true,
    private val initialSurface:Surface=Surface.REGULAR,
    private val foreground:View?=null,
    refract:Boolean=true
) : View(context) {
    enum class Surface { REGULAR, LIQUID_SHEET, TAB_TRACK, TAB_THUMB }
    private val density = resources.displayMetrics.density
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val radius get() = min(radiusDp * density, min(width, height) / 2f)
    private val margin = (density * (if(initialSurface==Surface.LIQUID_SHEET)52f else 32f)).roundToInt()
    private val scene = RenderNode("CherryPick.uiGlassBackdrop")
    private val lens = if (refract && Build.VERSION.SDK_INT >= 33) LiquidGlassShader.createLifted() else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val shape = RectF()
    private var mask:BitmapShader?=null
    private var contour:Bitmap?=null
    private val ownLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private var recordedX = Int.MIN_VALUE
    private var recordedY = Int.MIN_VALUE
    private var recordedScaleX = Float.NaN
    private var recordedScaleY = Float.NaN
    private var capturedSource: View? = null
    private var capturedScroll = 0
    private var needsCapture = true
    private var glow: Shader? = null
    private var glowAlpha = 0f
    private var glowAnimator: ValueAnimator? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val behind = source()
        if (behind != null && behind !== this) {
            getLocationOnScreen(ownLocation)
            behind.getLocationOnScreen(sourceLocation)
            if (behind !== capturedSource || (trackSourceContent && (behind.isDirty || foreground?.isDirty==true)) ||
                ownLocation[0] - sourceLocation[0] != recordedX ||
                ownLocation[1] - sourceLocation[1] != recordedY || (trackSourceContent && scrollStamp(behind) != capturedScroll) ||
                totalScale(true) != recordedScaleX || totalScale(false) != recordedScaleY) {
                needsCapture = true
                invalidate()
            }
        }
        true
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize(0, heightMeasureSpec))
    }

    fun invalidateBackdrop() {
        needsCapture = true
        invalidate()
    }

    private fun scrollStamp(view: View): Int {
        if (view !is android.view.ViewGroup) return view.scrollY
        return view.scrollY + (0 until view.childCount).sumOf { scrollStamp(view.getChildAt(it)) }
    }

    private fun totalScale(horizontal: Boolean): Float {
        var node: View? = this
        var scale = 1f
        while (node != null) {
            scale *= if (horizontal) node.scaleX else node.scaleY
            node = node.parent as? View
        }
        return scale.coerceAtLeast(.1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(trackBackdrop)viewTreeObserver.addOnPreDrawListener(preDraw)
        needsCapture = true
    }

    override fun onDetachedFromWindow() {
        if (trackBackdrop && viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
        glowAnimator?.cancel()
        scene.discardDisplayList()
        capturedSource = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        shape.set(0f, 0f, w.toFloat(), h.toFloat())
        com.cosmod.launcherglass.util.ContinuousCorners.path(path, shape, radius,
            preserveSmoothing=radius<min(w,h)*.499f)
        if(lens!=null){
            val image = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            Canvas(image).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            mask=BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
            contour=if(radius<min(w,h)*.499f) com.cosmod.launcherglass.xposed.GlassGeometry.contourField(image) else null
        }
        configureOptics()
        needsCapture = true
    }

    private fun configureOptics(){
        val w=width;val h=height
        if(w==0 || h==0)return
        val wrapperWidth = w + margin * 2
        val wrapperHeight = h + margin * 2
        scene.setPosition(-margin, -margin, w + margin, h + margin)
        val optics=when(initialSurface){
            Surface.REGULAR ->KyantOptics(if(selection)30 else 24,if(selection)22 else 17,28,0,108)
            Surface.LIQUID_SHEET ->lensDp(24f,48f,26,0,if(dark)65 else 111)
            Surface.TAB_TRACK ->lensDp(12f,10f,25,0,105)
            Surface.TAB_THUMB ->lensDp(20f,14f,31,0,105)
        }
        val blurDp=when(initialSurface){
            Surface.LIQUID_SHEET ->20f
            Surface.TAB_TRACK ->8f
            Surface.TAB_THUMB ->0f
            Surface.REGULAR ->if(selection)5f else 9f
        }
        val blur=if(blurDp>0f)RenderEffect.createBlurEffect(density*blurDp,density*blurDp,Shader.TileMode.CLAMP)else null
        val alpha=mask
        val effect = if (alpha!=null && Build.VERSION.SDK_INT >= 33) lens?.let {
            val style = GlassStyle(false, 0, 0, 0, 0, null, kyant = optics, cornerField = contour)
            RenderEffect.createRuntimeShaderEffect(it.updateLifted(alpha, w, h, margin.toFloat(),
                wrapperWidth, wrapperHeight, null, radius / min(w, h), style,
                depthEffect=initialSurface==Surface.LIQUID_SHEET), "backdrop")
        } else null
        val material=if(effect!=null && blur!=null)RenderEffect.createChainEffect(effect,blur)else effect ?: blur
        scene.setRenderEffect(material)
        edgePaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0x70FFFFFF, 0x14FFFFFF, 0x05FFFFFF, 0x42FFFFFF),
            floatArrayOf(0f, .37f, .70f, 1f), Shader.TileMode.CLAMP)
    }

    private fun lensDp(band:Float,amount:Float,highlight:Int,dispersion:Int,vibrancy:Int):KyantOptics {
        val edge=min(width,height).coerceAtLeast(1)
        return KyantOptics(((band*density/edge-.025f)/.00225f).roundToInt().coerceAtLeast(0),
            (amount*density/(edge*.0018f)).roundToInt(),highlight,dispersion,vibrancy)
    }

    private fun capture() {
        val behind = source()
        val recording = scene.beginRecording(width + margin * 2, height + margin * 2)
        try {
            recording.drawColor(backdropColor ?: if (dark) Color.BLACK else 0xfff1f1f1.toInt())
            if (behind != null && behind !== this) {
                getLocationOnScreen(ownLocation)
                behind.getLocationOnScreen(sourceLocation)
                recordedX = ownLocation[0] - sourceLocation[0]
                recordedY = ownLocation[1] - sourceLocation[1]
                recordedScaleX = totalScale(true)
                recordedScaleY = totalScale(false)
                if(foreground!=null && behind.parent===parent && foreground.parent===parent){
                    // Track and labels are siblings below the lens. Their local
                    // transforms avoid screen-pixel rounding on every drag frame.
                    val left=x+(1f-scaleX)*pivotX
                    val top=y+(1f-scaleY)*pivotY
                    recording.translate(margin-left/scaleX,margin-top/scaleY)
                    recording.scale(1f/scaleX,1f/scaleY)
                    val save=recording.save()
                    recording.translate(behind.x,behind.y)
                    behind.draw(recording)
                    recording.restoreToCount(save)
                    recording.translate(foreground.x,foreground.y)
                    foreground.draw(recording)
                }else{
                    recording.translate(margin - recordedX / recordedScaleX, margin - recordedY / recordedScaleY)
                    recording.scale(1f / recordedScaleX, 1f / recordedScaleY)
                    if (behind is androidx.core.widget.NestedScrollView) behind.getChildAt(0)?.let { content ->
                        recording.translate((content.left - behind.scrollX).toFloat(), (content.top - behind.scrollY).toFloat())
                        content.draw(recording)
                    } else behind.draw(recording)
                }
                if(trackSourceContent)capturedScroll = scrollStamp(behind)
            }
            capturedSource = behind
        } finally { scene.endRecording() }
        needsCapture = false
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (needsCapture || !scene.hasDisplayList()) capture()
        val checkpoint = canvas.save()
        canvas.clipPath(path)
        if (canvas.isHardwareAccelerated) canvas.drawRenderNode(scene)
        paint.shader = null
        paint.color = tintColor ?: when(initialSurface){
            Surface.LIQUID_SHEET ->if(dark)0x90171717.toInt()else 0xb6dedede.toInt()
            Surface.TAB_TRACK ->if(dark)0x8016181d.toInt()else 0x88f5f5fa.toInt()
            Surface.TAB_THUMB ->if(dark)0x39ffffff else 0x65ffffff
            Surface.REGULAR ->if(dark)(if(selection)0x42ffffff else 0x69303030)else
                (if(selection)0x79ffffff else 0xb0f4f4f4).toInt()
        }
        canvas.drawPath(path, paint)
        glow?.takeIf { glowAlpha > 0 }?.let {
            paint.shader = it
            paint.alpha = (glowAlpha * 255).roundToInt()
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.alpha = 255
        }
        if(initialSurface==Surface.REGULAR){
            edgePaint.style = Paint.Style.STROKE
            edgePaint.strokeWidth = density * .7f
            canvas.drawPath(path, edgePaint)
        }
        canvas.restoreToCount(checkpoint)
    }

    fun touch(x: Float, y: Float, pressed: Boolean) {
        if(width==0 || height==0)return
        glowAnimator?.cancel()
        if (pressed) {
            glow = RadialGradient(x, y, maxOf(width, height) * .65f,
                0x26FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            glowAlpha = 1f
            invalidate()
        } else if (ValueAnimator.areAnimatorsEnabled() && glowAlpha > 0) {
            glowAnimator = ValueAnimator.ofFloat(glowAlpha, 0f).apply {
                duration = 180
                addUpdateListener { glowAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { glowAlpha = 0f; invalidate() }
    }
}
