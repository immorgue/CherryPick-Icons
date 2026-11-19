package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.*
import android.widget.*
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.doOnLayout
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.IslandSpring
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.roundToInt
import com.google.android.material.R as M

internal class CouiGlassPopup(
    context: Context,
    private val source: () -> View?,
    private val options: List<Pair<Int, String>>,
    private val selected: Int,
    private val choose: (Int) -> Unit
) : PopupWindow(context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (density * value).roundToInt()
    private val ink = MaterialColors.getColor(context, M.attr.colorOnSurface, Color.WHITE)
    private val glass = UiLiquidGlass(context, source, CouiGeometry.POPUP_RADIUS_DP.toFloat())
    private val highlight = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(CouiGeometry.POPUP_RADIUS_DP - CouiGeometry.GROUP_INSET_DP).toFloat()
            setColor((ink and 0xffffff) or 0x16000000)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = dp(CouiGeometry.GROUP_INSET_DP)
        setPadding(inset, inset, inset, inset)
    }
    private val panel = object : FrameLayout(context) {
        private var downY = 0f
        private var sliding = false
        private var hovered = -1
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (closing) return true
            val pressed = event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE
            glass.touch(event.x, event.y, pressed)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                animation?.cancel()
                rows.alpha = 1f
                downY = event.y
                moveHighlight(event.y)
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.y - downY) > slop) {
                sliding = true
                moveHighlight(event.y)
                val index = if (event.x in 0f..width.toFloat()) itemAt(event.y) else -1
                if (index != hovered) {
                    hovered = index
                    if (index >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                for (i in 0 until rows.childCount) rows.getChildAt(i).isPressed = i == hovered
            }
            if (sliding && event.actionMasked == MotionEvent.ACTION_UP) {
                val item = if (event.x in 0f..width.toFloat()) itemAt(event.y) else -1
                sliding = false
                for (i in 0 until rows.childCount) rows.getChildAt(i).isPressed = false
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                if (item >= 0) rows.getChildAt(item).performClick() else dismiss()
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                sliding = false
                for (i in 0 until rows.childCount) rows.getChildAt(i).isPressed = false
                val original = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
                highlight.animate().translationY(rows.getChildAt(original).top - rows.paddingTop.toFloat())
                    .setDuration(160).setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start()
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                animate().cancel()
                animate().translationX(0f).translationY(0f).alpha(1f)
                    .scaleX(if (pressed) 1.015f else 1f).scaleY(if (pressed) 1.015f else 1f)
                    .setDuration(if (pressed) 120 else 220)
                    .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f))
                    .setUpdateListener { keepTextUnscaled() }.start()
            }
            return super.dispatchTouchEvent(event)
        }

        private fun itemAt(y: Float): Int {
            for (index in 0 until rows.childCount) {
                val child = rows.getChildAt(index)
                if (y >= rows.top + child.top && y < rows.top + child.bottom) return index
            }
            return -1
        }

        private fun moveHighlight(y: Float) {
            highlight.animate().cancel()
            val index = itemAt(y)
            val item = rows.getChildAt(index) ?: return
            val top = rows.top + item.top.toFloat()
            val middle = top + item.height / 2f
            val attraction = (1f - abs(y - middle) / (item.height / 2f)).coerceIn(0f, 1f) * .25f
            val center = y + (middle - y) * attraction
            val minimum = rows.paddingTop.toFloat()
            val maximum = (rows.height - rows.paddingBottom - highlight.height).toFloat().coerceAtLeast(minimum)
            highlight.translationY = (center - highlight.height / 2f).coerceIn(minimum, maximum) - minimum
        }
    }
    private var animation: ValueAnimator? = null
    private var closing = false
    private var pendingChoice: Int? = null
    private var sourceScaleX = 1f
    private var sourceScaleY = 1f
    private var sourceX = 0f
    private var sourceY = 0f

    init {
        contentView = panel
        isFocusable = true
        isOutsideTouchable = true
        inputMethodMode = INPUT_METHOD_NOT_NEEDED
        animationStyle = 0
        elevation = dp(14).toFloat()
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        ContinuousOutline.apply(panel) { dp(CouiGeometry.POPUP_RADIUS_DP).toFloat() }
        ContinuousOutline.apply(highlight) { dp(CouiGeometry.POPUP_RADIUS_DP - CouiGeometry.GROUP_INSET_DP).toFloat() }
        panel.addView(glass, FrameLayout.LayoutParams(-1, -1))
        panel.addView(highlight, FrameLayout.LayoutParams(-1, dp(48)).apply {
            marginStart = dp(8); marginEnd = dp(8); topMargin = dp(8)
        })
        panel.addView(rows, FrameLayout.LayoutParams(-1, -2))
        for ((id, name) in options) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(12), dp(4), dp(12), dp(4))
                minimumHeight = dp(48)
                isFocusable = true
                isSelected = id == selected
                contentDescription = name
                val normal = GradientDrawable().apply {
                    cornerRadius = dp(CouiGeometry.POPUP_RADIUS_DP - CouiGeometry.GROUP_INSET_DP).toFloat()
                    setColor(Color.TRANSPARENT)
                }
                background = RippleDrawable(ColorStateList.valueOf((ink and 0xffffff) or 0x1A000000), normal, null)
                addView(TextView(context).apply {
                    text = name
                    textSize = 15f
                    setTextColor(ink)
                    includeFontPadding = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(AppCompatImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context,R.color.ui_control_accent))
                    visibility = if (id == selected) View.VISIBLE else View.INVISIBLE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(12) })
                setOnClickListener {
                    if (!closing) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        pendingChoice = id
                        dismiss()
                    }
                }
            }
            rows.addView(item, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun show(anchor: View) {
        val visible = Rect()
        anchor.getWindowVisibleDisplayFrame(visible)
        width = minOf(dp(248), (visible.width() - dp(16)).coerceAtLeast(1))
        rows.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(visible.height() - dp(16), View.MeasureSpec.AT_MOST))
        height = rows.measuredHeight
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        highlight.layoutParams = (highlight.layoutParams as FrameLayout.LayoutParams).apply {
            height = rows.getChildAt(index)?.measuredHeight ?: dp(48)
        }
        highlight.translationY = (0 until index).sumOf { rows.getChildAt(it).measuredHeight }.toFloat()
        val selectedMiddle = rows.paddingTop +
            (0 until index).sumOf { rows.getChildAt(it).measuredHeight } +
            (rows.getChildAt(index)?.measuredHeight ?: 0) / 2
        val x = (anchorLocation[0] + anchor.width - width).coerceIn(
            visible.left + dp(8), (visible.right - width - dp(8)).coerceAtLeast(visible.left + dp(8)))
        val y = (anchorLocation[1] + anchor.height / 2 - selectedMiddle).coerceIn(
            visible.top + dp(8), (visible.bottom - height - dp(8)).coerceAtLeast(visible.top + dp(8)))
        sourceScaleX = anchor.width.toFloat() / width
        sourceScaleY = anchor.height.toFloat() / height
        sourceX = (anchorLocation[0] - x).toFloat()
        sourceY = (anchorLocation[1] - y).toFloat()
        panel.pivotX = 0f
        panel.pivotY = 0f
        rows.pivotX = 0f
        rows.pivotY = 0f
        showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
        panel.doOnLayout {
            rows.getChildAt(index)?.requestFocus()
            if (ValueAnimator.areAnimatorsEnabled()) {
                val spring = IslandSpring(0f, 0f, 1f, true, speed = 1.6)
                morph(0f)
                animation = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 420
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener {
                        val fraction = it.animatedValue as Float
                        val progress = spring.position(fraction * .42).coerceIn(0f, 1.01f)
                        morph(progress)
                        panel.alpha = (fraction * 6f).coerceAtMost(1f)
                        rows.alpha = ((progress - .22f) / .62f).coerceIn(0f, 1f)
                        glass.invalidateBackdrop()
                    }
                    start()
                }
            }
        }
    }

    override fun dismiss() {
        if (closing || !isShowing) return
        closing = true
        animation?.cancel()
        highlight.animate().cancel()
        panel.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { finishDismiss(); return }
        val startX = panel.scaleX
        val startY = panel.scaleY
        val translationX = panel.translationX
        val translationY = panel.translationY
        val alpha = panel.alpha
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener {
                val fraction = it.animatedValue as Float
                panel.scaleX = startX + (sourceScaleX - startX) * fraction
                panel.scaleY = startY + (sourceScaleY - startY) * fraction
                panel.translationX = translationX + (sourceX - translationX) * fraction
                panel.translationY = translationY + (sourceY - translationY) * fraction
                panel.alpha = alpha * (1f - fraction)
                rows.alpha = (1f - fraction * 2).coerceAtLeast(0f)
                keepTextUnscaled()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { finishDismiss() }
            })
            start()
        }
    }

    private fun finishDismiss() {
        super.dismiss()
        val choice = pendingChoice
        pendingChoice = null
        if (choice != null) choose(choice)
    }

    private fun morph(progress: Float) {
        panel.scaleX = sourceScaleX + (1f - sourceScaleX) * progress
        panel.scaleY = sourceScaleY + (1f - sourceScaleY) * progress
        panel.translationX = sourceX * (1f - progress)
        panel.translationY = sourceY * (1f - progress)
        keepTextUnscaled()
    }

    private fun keepTextUnscaled() {
        rows.scaleX = 1f / panel.scaleX.coerceAtLeast(.1f)
        rows.scaleY = 1f / panel.scaleY.coerceAtLeast(.1f)
    }

    fun dismissImmediately() {
        pendingChoice = null
        animation?.removeAllListeners()
        animation?.cancel()
        highlight.animate().cancel()
        panel.animate().cancel()
        super.dismiss()
    }

    fun invalidateBackdrop() = glass.invalidateBackdrop()
}
