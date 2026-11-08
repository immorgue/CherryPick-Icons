package com.cosmod.launcherglass.xposed

import kotlin.math.max

internal object DockGeometry {
    /** Padding expands equally around the icons, never relative to the screen bottom. */
    fun vertical(top:Float,bottom:Float,padding:Float)=
        kotlin.math.round(top-padding).toInt() to kotlin.math.round(bottom+padding).toInt()
    fun halfWidth(automatic:Boolean,center:Float,left:Float,right:Float,rowWidth:Float,cellWidth:Float,iconSize:Float,padding:Float):Float =
        if(automatic)max(center-left,right-center)+padding
        else rowWidth/2f-cellWidth/2f+iconSize/2f+padding
}
