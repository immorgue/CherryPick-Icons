package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.CompoundButton
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

// Android Views adaptation of COUI SwitchDefaults from ONextBox's Apache-2.0 COUI sources.
internal class CouiSwitch(context: Context, private val accent: Int) : CompoundButton(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val offColor = if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES) 0xFF505050.toInt() else 0xFFE5E5E5.toInt()
    private var thumbPosition = 0f
    private var motion: ValueAnimator? = null
    private val activeThumb=Color.WHITE

    init {
        buttonDrawable = null
        background = null
        minimumWidth = (48 * density).roundToInt()
        minimumHeight = minimumWidth
        isClickable = true
        isFocusable = true
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun setChecked(checked: Boolean) {
        val changed = isChecked != checked
        super.setChecked(checked)
        if (!changed || checked != isChecked) return
        val target = if (checked) 1f else 0f
        motion?.cancel()
        if (!isAttachedToWindow || !isLaidOut || !ValueAnimator.areAnimatorsEnabled()) {
            thumbPosition = target
            invalidate()
            return
        }
        motion = ValueAnimator.ofFloat(thumbPosition, target).apply {
            duration = 200L
            interpolator = CherryMotion
            addUpdateListener {
                thumbPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val middleX = width / 2f
        val middleY = height / 2f
        val radius = 12 * density
        paint.color = ColorUtils.blendARGB(offColor, accent, thumbPosition)
        paint.alpha = if (!isEnabled) 76 else if (isPressed) 200 else 255
        canvas.drawRoundRect(middleX - 22 * density, middleY - radius,
            middleX + 22 * density, middleY + radius, radius, radius, paint)
        val directionalPosition = if (layoutDirection == LAYOUT_DIRECTION_RTL) 1f - thumbPosition else thumbPosition
        paint.color = ColorUtils.blendARGB(Color.WHITE,activeThumb,thumbPosition)
        paint.alpha = if (isEnabled) 255 else 140
        canvas.drawCircle(middleX + (directionalPosition - .5f) * 20 * density,
            middleY, 9 * density, paint)
    }

    override fun onDetachedFromWindow() {
        motion?.cancel()
        motion = null
        thumbPosition = if (isChecked) 1f else 0f
        super.onDetachedFromWindow()
    }
}
