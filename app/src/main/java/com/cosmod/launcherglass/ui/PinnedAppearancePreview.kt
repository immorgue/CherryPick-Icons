package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.widget.NestedScrollView
import kotlin.math.abs

/** Keeps the preview's render size stable while its visible viewport collapses. */
internal class PinnedAppearancePreview(
    context: Context,
    private val preview: ModuleAppearancePreview,
    val expandedHeight: Int,
    private val compactHeight: Int,
    grid: Boolean,
    private val scroll: NestedScrollView,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val clip = Path()
    private val bounds = RectF()
    private var shownHeight = expandedHeight
    var cornerRadius=0f
        private set
    val collapseRange get() = expandedHeight - compactHeight
    private val cropGrid = grid
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var dragging = false
    private val location = IntArray(2)
    private val scrollLocation = IntArray(2)

    init {
        clipChildren = true
        preview.pivotY = 0f
        addView(preview, LayoutParams(-1, expandedHeight))
    }

    fun collapse(scrollY: Int): Int {
        val next = (expandedHeight - scrollY).coerceIn(compactHeight, expandedHeight)
        if (next == shownHeight) return shownHeight
        shownHeight = next
        val progress = (expandedHeight - next).toFloat() / (expandedHeight - compactHeight).coerceAtLeast(1)
        preview.translationY = if (cropGrid)
            (compactHeight * .5f - expandedHeight * .29f) * progress
        else (next - expandedHeight) * .5f
        invalidate()
        return shownHeight
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        val radius = CouiGeometry.insetRadius(CouiGeometry.cardRadius(this), 8 * density)
        cornerRadius=radius
        preview.setCornerRadius(radius)
        com.cosmod.launcherglass.util.ContinuousCorners.path(clip, bounds, radius)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downY = event.rawY
            dragging = false
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.rawY - downY) > slop) dragging = true
        val forwarded = MotionEvent.obtain(event)
        getLocationInWindow(location)
        scroll.getLocationInWindow(scrollLocation)
        forwarded.offsetLocation((location[0] - scrollLocation[0]).toFloat(),
            (location[1] - scrollLocation[1]).toFloat())
        if (event.actionMasked == MotionEvent.ACTION_UP && !dragging) forwarded.action = MotionEvent.ACTION_CANCEL
        scroll.dispatchTouchEvent(forwarded)
        forwarded.recycle()
        return true
    }
}
