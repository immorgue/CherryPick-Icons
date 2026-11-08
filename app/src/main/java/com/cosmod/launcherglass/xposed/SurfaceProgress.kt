package com.cosmod.launcherglass.xposed

/** An abandoned producer expires; an ongoing slow native gesture does not. */
internal class SurfaceProgress(startedMs:Long) {
    private var version=Long.MIN_VALUE
    private var changedAt=startedMs
    fun expired(nowMs:Long,poseVersion:Long):Boolean {
        if(version!=poseVersion){version=poseVersion;changedAt=nowMs}
        return nowMs-changedAt>4000L
    }
}
