package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.PathInterpolator
import java.util.WeakHashMap

/** Retains the editor's material display lists during an appearance change. */
internal class LauncherAppearanceTransition(activity: Activity, private val onApplied: () -> Unit) {
    private class Frame(val bounds: Rect, val current: RenderNode, val revision: Int) {
        var displayed = current
    }

    private class Dissolve(val frame: Frame, val previous: RenderNode, val blend: RenderNode)

    private val main = Handler(Looper.getMainLooper())
    private val root = activity.window.decorView
    private val frames = WeakHashMap<View, WeakHashMap<Drawable, Frame>>()
    private val outgoing = ArrayList<Dissolve>()
    private val add = Paint().apply { blendMode = BlendMode.PLUS }
    private val pending = LinkedHashSet<View>()
    var collecting = false
        private set
    val preparing: Boolean get() = collecting || pending.isNotEmpty()
    private var revision = 0
    private var running: ValueAnimator? = null
    private var amount = 0f
    private var closed = false

    fun change(update: () -> Unit) {
        if (closed) {
            update()
            return
        }
        finish()
        amount = if (ValueAnimator.areAnimatorsEnabled() && frames.isNotEmpty()) 1f else 0f
        val next = ++revision
        collecting = amount > 0f
        try { update() } finally { collecting = false }
        onApplied()
        if (amount == 0f) {
            pending.forEach(View::invalidate)
            pending.clear()
            return
        }

        val start = Runnable {
            if (closed || revision != next || running != null || amount == 0f) return@Runnable
            if (outgoing.isEmpty()) {
                amount = 0f
                return@Runnable
            }
            running = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 185
                interpolator = PathInterpolator(.25f, 0f, .2f, 1f)
                addUpdateListener {
                    amount = it.animatedValue as Float
                    outgoing.forEach { layer ->
                        layer.previous.alpha = amount
                        layer.frame.current.alpha = 1f - amount
                    }
                    root.postInvalidateOnAnimation()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (running === animation) {
                            running = null
                            releaseOutgoing()
                        }
                    }
                })
                start()
            }
        }
        fun startAfterDraw() {
            if (root.isHardwareAccelerated) root.viewTreeObserver.registerFrameCommitCallback { main.post(start) }
            root.postDelayed(start, 100)
        }
        if (pending.isEmpty()) startAfterDraw() else root.postOnAnimation(object : Runnable {
            override fun run() {
                if (closed || revision != next) return
                val batch = pending.iterator()
                repeat(3) {
                    if (batch.hasNext()) {
                        batch.next().invalidate()
                        batch.remove()
                    }
                }
                if (pending.isEmpty()) startAfterDraw() else root.postOnAnimation(this)
            }
        })
    }

    fun prepare(view: View) { pending.add(view) }

    fun draw(canvas: Canvas, bounds: Rect, binding: IconMaterialRenderer.Binding, record: (Canvas) -> Unit) {
        if (closed || !canvas.isHardwareAccelerated || binding.foreign || binding.view.rootView !== root ||
            bounds.isEmpty || !ValueAnimator.areAnimatorsEnabled()) {
            record(canvas)
            return
        }
        val byIcon = frames.getOrPut(binding.view) { WeakHashMap() }
        val previous = byIcon[binding.drawable]?.takeIf { it.bounds == bounds }
        val frame = if (previous?.revision == revision) previous else {
            val old = previous?.current?.takeIf { amount > 0f && it.hasDisplayList() }
            Frame(Rect(bounds), node("CherryPick.editorMaterial",bounds),revision).also {
                byIcon[binding.drawable] = it
                if (old != null) {
                    val blend = node("CherryPick.editorDissolve",bounds)
                    val dissolve = Dissolve(it,old,blend)
                    old.setUseCompositingLayer(true,add)
                    old.alpha = amount
                    it.current.alpha = 1f - amount
                    it.displayed = blend
                    recordBlend(dissolve,true)
                    outgoing.add(dissolve)
                }
            }
        }
        val target = frame.current.beginRecording(bounds.width(), bounds.height())
        try {
            target.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            record(target)
        } finally {
            frame.current.endRecording()
        }
        canvas.drawRenderNode(frame.displayed)
    }

    private fun node(name: String, bounds: Rect) = RenderNode(name).apply {
        setPosition(bounds.left,bounds.top,bounds.right,bounds.bottom)
        setClipToBounds(false)
    }

    private fun recordBlend(layer: Dissolve, animate: Boolean) {
        val bounds = layer.frame.bounds
        val canvas = layer.blend.beginRecording(bounds.width(),bounds.height())
        try {
            canvas.translate(-bounds.left.toFloat(),-bounds.top.toFloat())
            if (animate) {
                // Interpolate the two premultiplied textures. Source-over would
                // stack both translucent plates and briefly darken the icons.
                val state = canvas.saveLayer(bounds.left.toFloat(),bounds.top.toFloat(),
                    bounds.right.toFloat(),bounds.bottom.toFloat(),null)
                canvas.drawRenderNode(layer.frame.current)
                canvas.drawRenderNode(layer.previous)
                canvas.restoreToCount(state)
            } else canvas.drawRenderNode(layer.frame.current)
        } finally { layer.blend.endRecording() }
    }

    fun close() {
        closed = true
        finish()
        pending.forEach(View::invalidate)
        pending.clear()
        frames.clear()
        root.invalidate()
    }

    private fun finish() {
        val animator = running
        running = null
        animator?.cancel()
        releaseOutgoing()
    }

    private fun releaseOutgoing() {
        amount = 0f
        outgoing.forEach { layer ->
            layer.frame.current.alpha = 1f
            recordBlend(layer,false)
            layer.frame.displayed = layer.frame.current
            layer.previous.discardDisplayList()
        }
        outgoing.clear()
    }
}
