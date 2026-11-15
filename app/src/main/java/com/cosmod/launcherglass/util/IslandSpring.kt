package com.cosmod.launcherglass.util

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Exact damped spring solution; retargeting starts at the current position AND velocity. */
internal class IslandSpring(start:Float,velocity:Float,private val target:Float,opening:Boolean,speed:Double=1.0){
    private val frequency=(if(opening)13.5 else 14.0)*speed
    private val damping=if(opening).74 else 1.0
    private val decay=damping*frequency
    private val oscillation=frequency*sqrt(1-damping*damping)
    private val a=(start-target).toDouble()
    private val b=if(oscillation==0.0)velocity+decay*a else (velocity+decay*a)/oscillation
    fun position(seconds:Double):Float=(target+exp(-decay*seconds)*
        (if(oscillation==0.0)a+b*seconds else a*cos(oscillation*seconds)+b*sin(oscillation*seconds))).toFloat()
    fun velocity(seconds:Double):Float {
        if(oscillation==0.0)return (exp(-decay*seconds)*(b-decay*(a+b*seconds))).toFloat()
        val c=cos(oscillation*seconds);val s=sin(oscillation*seconds)
        return (exp(-decay*seconds)*((-decay*a+oscillation*b)*c+(-decay*b-oscillation*a)*s)).toFloat()
    }
}
