package com.cosmod.launcherglass.xposed

internal object FlightBlurPolicy {
    /** Native area trend survives interrupted transitions with missing resume callbacks. */
    fun direction(previousArea:Float,currentArea:Float):Int {
        if(!previousArea.isFinite() || !currentArea.isFinite() || previousArea<=0f || currentArea<=0f)return 0
        val ratio=currentArea/previousArea
        return when {ratio<.995f -> -1;ratio>1.005f -> 1;else -> 0}
    }
    fun fps(global:Int)=global.coerceIn(1,60).coerceAtMost(30)
    fun live(returning:Boolean,underTouch:Boolean)=returning && !underTouch
}
