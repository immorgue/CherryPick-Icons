package com.cosmod.launcherglass.xposed

import kotlin.math.abs
import kotlin.math.exp

/** Filters velocity/lead only. Native position stays exact; owns no frame loop. */
internal class SmoothSwipeLead {
    private var lastNs=0L
    private var x=0f
    private var y=0f
    private var width=0f
    private var height=0f
    private var lead=0f
    fun reset(){lastNs=0L;lead=0f}
    fun update(px:Float,py:Float,w:Float,h:Float,nowNs:Long,moving:Boolean):Float {
        if(!px.isFinite() || !py.isFinite() || !w.isFinite() || !h.isFinite() || w<=0f || h<=0f){reset();return 0f}
        val elapsed=nowNs-lastNs
        val continuous=lastNs!=0L && elapsed in 1L..100_000_000L && abs(px-x)<=80f &&
            abs(py-y)<=.25f && abs(w-width)<=.25f && abs(h-height)<=.25f
        val cap=minOf(8f,w*.10f)
        if(!continuous)lead=0f else {
            // A fixed time horizon avoids a larger lead at lower material FPS.
            val target=if(moving)((px-x)*16_000_000f/elapsed).coerceIn(-cap,cap)else 0f
            val blend=(1.0-exp(-elapsed/35_000_000.0)).toFloat()
            lead+=(target-lead)*blend
            if(abs(lead)<.005f && abs(target)<.005f)lead=0f
        }
        lastNs=nowNs;x=px;y=py;width=w;height=h
        return lead
    }
}
