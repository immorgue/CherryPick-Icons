package com.cosmod.launcherglass.util

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.*

/** Continuous 60% corners. Geometry adapted from figma-squircle (MIT, Tien Pham). */
object ContinuousCorners {
    const val SMOOTHING = .6f
    fun innerRadius(outer: Float, padding: Float) = max(0f, outer - padding)

    @JvmOverloads fun path(out: Path, bounds: RectF, radius: Float, smoothing: Float = SMOOTHING, preserveSmoothing:Boolean=true): Path {
        out.rewind()
        val budget = min(bounds.width(), bounds.height()).coerceAtLeast(0f) / 2
        val r = radius.coerceIn(0f, budget)
        if (r <= .001f) { out.addRect(bounds, Path.Direction.CW); return out }
        // A full-height floating control remains a capsule, with no corner bulge.
        val s = if(preserveSmoothing)smoothing.coerceIn(0f,1f) else min(smoothing.coerceIn(0f, 1f), max(0f, budget / r - 1))
        if (s <= .001f) { out.addRoundRect(bounds, r, r, Path.Direction.CW); return out }
        var p = (1 + s) * r
        val sweep = 90 * (1 - s)
        val angle = 45 * s
        val rad = Math.PI / 180
        val arc = (sin(sweep / 2 * rad) * r * sqrt(2.0)).toFloat()
        val chord = r * tan(angle / 2 * rad)
        val c = (chord * cos(angle * rad)).toFloat()
        val d = (c * tan(angle * rad)).toFloat()
        var b = (p - arc - c - d) / 3
        var a = 2 * b
        if(p>budget){
            val available=budget-d-arc-c
            b=min(b,available*5/6)
            a=available-b
            p=budget
        }
        val l = bounds.left; val t = bounds.top; val right = bounds.right; val bottom = bounds.bottom
        out.moveTo(right - p, t)
        out.rCubicTo(a, 0f, a+b, 0f, a+b+c, d)
        out.arcTo(right-2*r, t, right, t+2*r, -90+angle, sweep, false)
        out.rCubicTo(d, c, d, b+c, d, a+b+c)
        out.lineTo(right, bottom-p)
        out.rCubicTo(0f, a, 0f, a+b, -d, a+b+c)
        out.arcTo(right-2*r, bottom-2*r, right, bottom, angle, sweep, false)
        out.rCubicTo(-c, d, -(b+c), d, -(a+b+c), d)
        out.lineTo(l+p, bottom)
        out.rCubicTo(-a, 0f, -(a+b), 0f, -(a+b+c), -d)
        out.arcTo(l, bottom-2*r, l+2*r, bottom, 90+angle, sweep, false)
        out.rCubicTo(-d, -c, -d, -(b+c), -d, -(a+b+c))
        out.lineTo(l, t+p)
        out.rCubicTo(0f, -a, 0f, -(a+b), d, -(a+b+c))
        out.arcTo(l, t, l+2*r, t+2*r, 180+angle, sweep, false)
        out.rCubicTo(c, -d, b+c, -d, a+b+c, -d)
        out.close()
        return out
    }
}
