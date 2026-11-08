package com.cosmod.launcherglass.xposed

/** A transaction may change only one channel; merge must preserve the other. */
internal data class SurfaceTransformUpdate(val position:Pair<Float,Float>?=null,val matrix:List<Float>?=null) {
    init {require(matrix==null || matrix.size==4)}
    fun then(other:SurfaceTransformUpdate)=SurfaceTransformUpdate(other.position ?: position,other.matrix ?: matrix)
}
