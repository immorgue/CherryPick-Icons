package com.cosmod.launcherglass.xposed

/** Live refresh is separate from recording an OEM display list for an adjacent page. */
internal object LiveSamplePolicy {
    fun refresh(left:Float,top:Float,right:Float,bottom:Float,screenWidth:Int,screenHeight:Int,
                icon:Boolean,disableEdgeOptimization:Boolean):Boolean {
        if(right<=0f || bottom<=0f || left>=screenWidth || top>=screenHeight)return false
        if(!icon || disableEdgeOptimization)return true
        return left>=-.5f && top>=-.5f && right<=screenWidth+.5f && bottom<=screenHeight+.5f
    }
}
