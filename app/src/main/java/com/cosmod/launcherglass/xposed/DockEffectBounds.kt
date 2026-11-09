package com.cosmod.launcherglass.xposed

/** Full width is retained; the transparent area above the dock need not be filtered. */
internal object DockEffectBounds {
    fun top(rootHeight:Int,restingTop:Float,posedTop:Float,margin:Int):Int {
        if(rootHeight<=0 || !restingTop.isFinite() || !posedTop.isFinite())return 0
        val required=(minOf(restingTop,posedTop)-margin.coerceAtLeast(0)).toInt().coerceAtLeast(0)
        return (required/128*128).coerceAtMost(rootHeight-1)
    }
}
