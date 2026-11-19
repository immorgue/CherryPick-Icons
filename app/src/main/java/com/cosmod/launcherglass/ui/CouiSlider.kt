package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider

/** Keeps Slider's touch, keyboard and accessibility handling and draws a ColorOS track. */
internal class CouiSlider(context: Context, private val accent: Int, private val rail: Int) : Slider(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val knob=com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnPrimary)

    init {
        trackHeight = (20 * density).toInt()
        thumbRadius = (6 * density).toInt()
        labelBehavior = LabelFormatter.LABEL_GONE
        isTickVisible = false
    }

    override fun onDraw(canvas: Canvas) {
        val cap = trackHeight / 2f
        val left = trackSidePadding.toFloat()
        val right = left + trackWidth
        val middle = height / 2f
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val fraction = ((value - valueFrom) / (valueTo - valueFrom)).coerceIn(0f, 1f)
        val x = left + trackWidth * if (rtl) 1f - fraction else fraction

        paint.color = rail
        canvas.drawRoundRect(left - cap, middle - cap, right + cap, middle + cap, cap, cap, paint)
        paint.color = accent
        val start = if (rtl) x - cap else left - cap
        val end = if (rtl) right + cap else x + cap
        canvas.drawRoundRect(start, middle - cap, end, middle + cap, cap, cap, paint)
        paint.color = Color.BLACK
        paint.alpha = 36
        canvas.drawCircle(x, middle + density, thumbRadius + density * .55f, paint)
        paint.color = knob
        canvas.drawCircle(x, middle, thumbRadius.toFloat(), paint)
        if (isFocused && !isInTouchMode) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            canvas.drawRoundRect(left - cap, middle - cap, right + cap, middle + cap, cap, cap, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
