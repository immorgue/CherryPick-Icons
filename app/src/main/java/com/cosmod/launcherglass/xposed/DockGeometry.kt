package com.cosmod.launcherglass.xposed

import kotlin.math.max

internal object DockGeometry {
    /** Padding expands equally around the icons, never relative to the screen bottom. */
    fun vertical(top:Float,bottom:Float,padding:Float,heightPercent:Int=100):Pair<Int,Int> {
        val center=(top+bottom)*.5f
        val half=((bottom-top)*.5f+padding)*heightPercent.coerceIn(50,200)/100f
        return kotlin.math.round(center-half).toInt() to kotlin.math.round(center+half).toInt()
    }
    fun halfWidth(automatic:Boolean,center:Float,left:Float,right:Float,rowWidth:Float,cellWidth:Float,iconSize:Float,padding:Float):Float =
        if(automatic)max(center-left,right-center)+padding
        else rowWidth/2f-cellWidth/2f+iconSize/2f+padding
}
