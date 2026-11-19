package com.cosmod.launcherglass.ui

import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import com.cosmod.launcherglass.util.ContinuousCorners

internal object ContinuousOutline {
    fun apply(view: View, radius: () -> Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            private val path = Path()
            private val bounds = RectF()
            private var w = -1
            private var h = -1
            private var r = Float.NaN
            override fun getOutline(view: View, outline: Outline) {
                val next = radius()
                if (w != view.width || h != view.height || r != next) {
                    w = view.width; h = view.height; r = next
                    bounds.set(0f, 0f, w.toFloat(), h.toFloat())
                    ContinuousCorners.path(path, bounds, r)
                }
                outline.setPath(path)
            }
        }
        view.clipToOutline = true
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> view.invalidateOutline() }
    }
}
