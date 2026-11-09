package com.cosmod.launcherglass.xposed

/** Restore the one-sample lead only for horizontal workspace movement. */
internal object SwipePrediction {
    fun offset(previousX:Float,previousY:Float,previousW:Float,previousH:Float,
               x:Float,y:Float,w:Float,h:Float):Float {
        val dx=x-previousX
        if(!dx.isFinite() || !y.isFinite() || !previousY.isFinite() ||
            !w.isFinite() || !h.isFinite() || !previousW.isFinite() || !previousH.isFinite() ||
            kotlin.math.abs(dx)>80f ||
            kotlin.math.abs(y-previousY)>.25f ||
            kotlin.math.abs(w-previousW)>.25f || kotlin.math.abs(h-previousH)>.25f ||
            previousW<=0f || previousH<=0f)return 0f
        return dx
    }
}
