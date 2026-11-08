package com.cosmod.launcherglass.xposed

/** Native spring settling must not be mistaken for a new app opening. */
internal class FlightDirection(initial:Int=0) {
    var direction=initial
        private set
    private var previous=Float.NaN
    private var extreme=Float.NaN
    fun returning(){direction=-1;previous=Float.NaN;extreme=Float.NaN}
    fun update(area:Float):Int {
        if(!area.isFinite() || area<=0f)return direction
        if(!previous.isFinite()){previous=area;extreme=area;return direction}
        if(direction==0) {
            direction=FlightBlurPolicy.direction(previous,area)
            extreme=area
        }else if(direction<0) {
            extreme=minOf(extreme,area)
            if(area>extreme*1.25f){direction=1;extreme=area}
        }else {
            extreme=maxOf(extreme,area)
            if(area<extreme*.85f){direction=-1;extreme=area}
        }
        previous=area
        return direction
    }
}
