package com.cosmod.launcherglass.util

/** Blur strength shared by Kyant GPU and compatibility paths. */
internal object LiquidOptics {
    fun blurRadius(radius:Int,height:Int,liquid:Boolean)=radius*height/800f*(if(liquid).28f else 1f)
}
