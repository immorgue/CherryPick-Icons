package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.cosmod.launcherglass.util.ContinuousCorners
import com.google.android.material.card.MaterialCardView

/** Paint the real smooth contour too: clipping a circular background alone loses its outward curves. */
internal class CouiCardView(context:Context):MaterialCardView(context) {
    private val contour=Path()
    private val bounds=RectF()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var contourWidth=-1
    private var contourHeight=-1
    private var contourRadius=Float.NaN
    init {setWillNotDraw(false)}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(contourWidth!=width || contourHeight!=height || contourRadius!=radius){
            contourWidth=width;contourHeight=height;contourRadius=radius
            bounds.set(0f,0f,width.toFloat(),height.toFloat())
            ContinuousCorners.path(contour,bounds,radius)
        }
        paint.color=cardBackgroundColor.getColorForState(drawableState,cardBackgroundColor.defaultColor)
        canvas.drawPath(contour,paint)
    }

}
