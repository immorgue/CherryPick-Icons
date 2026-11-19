package com.cosmod.launcherglass.xposed

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import com.cosmod.launcherglass.util.AppWindowDeformation
import kotlin.math.abs

/** Fits a uniformly scaled icon inside the app's projected crop. */
internal class FlyingIconBounds {
    private val inverseApp = Matrix()
    private val iconToApp = Matrix()
    private val projective = Matrix()
    private val native = RectF()
    private val fitted = RectF()
    private val target = FloatArray(8)
    private val values = FloatArray(9)
    private val appPerspective = FloatArray(16)
    private val nativePose = FloatArray(4)
    // [scaleX, scaleY, x, y] for SurfaceControl.
    val surfaceAffine = FloatArray(4)

    fun normalize(iconCrop: Rect, iconAffine: Matrix): Boolean {
        if (iconCrop.isEmpty || !iconAffine.rectStaysRect()) return false
        iconAffine.getValues(values)
        if (values[Matrix.MSCALE_X] <= 0f || values[Matrix.MSCALE_Y] <= 0f) return false
        native.set(iconCrop)
        iconAffine.mapRect(native)
        val scale = minOf(native.width() / iconCrop.width(), native.height() / iconCrop.height())
        if (!scale.isFinite() || scale <= 0f) return false
        setPose(surfaceAffine, iconCrop, scale, native.centerX(), native.centerY())
        return true
    }

    fun fit(
        iconCrop: Rect, iconAffine: Matrix, appCrop: Rect, appAffine: Matrix,
        radius: Float, deformation: AppWindowDeformation?, contentScale: Float, followWindow: Float, weight: Float
    ): Boolean {
        if (!normalize(iconCrop, iconAffine) || appCrop.isEmpty || !appAffine.invert(inverseApp)) return false
        surfaceAffine.copyInto(nativePose)
        iconToApp.setConcat(inverseApp, iconAffine)
        fitted.set(iconCrop)
        iconToApp.mapRect(fitted)
        val inset = (radius.coerceAtLeast(0f) * (.5f - .24f * followWindow) + 1f)
            .coerceAtMost(minOf(appCrop.width(), appCrop.height()) * .28f)
        val left = appCrop.left + inset
        val right = appCrop.right - inset
        val top = appCrop.top + inset
        val bottom = appCrop.bottom - inset
        if (fitted.isEmpty || !fitted.width().isFinite() || !fitted.height().isFinite()) return false
        val fitScale = minOf((right - left) / fitted.width(), (bottom - top) / fitted.height())
        val scale = minOf(1f, fitScale)
        val halfWidth = fitted.width() * scale * contentScale * .5f
        val halfHeight = fitted.height() * scale * contentScale * .5f
        val nativeX = fitted.centerX().coerceIn(left + halfWidth, maxOf(left + halfWidth, right - halfWidth))
        val nativeY = fitted.centerY().coerceIn(top + halfHeight, maxOf(top + halfHeight, bottom - halfHeight))
        val centerX = nativeX + (appCrop.exactCenterX() - nativeX) * followWindow
        val centerY = nativeY + (appCrop.exactCenterY() - nativeY) * followWindow
        corners(target, centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
        if (deformation != null) {
            // One plane, one projection. A second icon-specific tilt can expose
            // its upper edge even when both neutral rectangles are contained.
            deformation.writeMatrix(appPerspective, appCrop.left.toFloat(), appCrop.top.toFloat(),
                appCrop.width().toFloat(), appCrop.height().toFloat())
            values[0]=appPerspective[0]
            values[1]=appPerspective[1]
            values[2]=appPerspective[3]
            values[3]=appPerspective[4]
            values[4]=appPerspective[5]
            values[5]=appPerspective[7]
            values[6]=appPerspective[12]
            values[7]=appPerspective[13]
            values[8]=appPerspective[15]
            projective.setValues(values)
            projective.mapPoints(target)
        }
        appAffine.mapPoints(target)
        if (!target.all { it.isFinite() }) return false
        val x = (target[0] + target[2] + target[4] + target[6]) * .25f
        val y = (target[1] + target[3] + target[5] + target[7]) * .25f
        var uniformScale = nativePose[0]
        // Inscribe the original icon aspect ratio in the projected quad
        // without passing the trapezoid's shear or stretch to its pixels.
        for (i in target.indices step 2) {
            val next = (i + 2) % target.size
            val dx = target[next] - target[i]
            val dy = target[next + 1] - target[i + 1]
            val span = (abs(dx) * iconCrop.height() + abs(dy) * iconCrop.width()) * .5f
            if (span <= 0f) return false
            val distance = abs(dx * (y - target[i + 1]) - dy * (x - target[i]))
            uniformScale = minOf(uniformScale, distance / span)
        }
        if (uniformScale <= 0f || !uniformScale.isFinite()) return false
        setPose(surfaceAffine, iconCrop, uniformScale, x, y)
        for (i in surfaceAffine.indices) {
            surfaceAffine[i] = nativePose[i] + (surfaceAffine[i] - nativePose[i]) * weight
        }
        return true
    }

    private fun setPose(out: FloatArray, crop: Rect, scale: Float, x: Float, y: Float) {
        out[0] = scale
        out[1] = scale
        out[2] = x - crop.exactCenterX() * scale
        out[3] = y - crop.exactCenterY() * scale
    }

    private fun corners(out: FloatArray, left: Float, top: Float, right: Float, bottom: Float) {
        out[0] = left
        out[1] = top
        out[2] = right
        out[3] = top
        out[4] = right
        out[5] = bottom
        out[6] = left
        out[7] = bottom
    }
}
