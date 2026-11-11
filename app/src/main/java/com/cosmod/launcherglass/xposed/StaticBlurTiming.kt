package com.cosmod.launcherglass.xposed

internal object StaticBlurTiming {
    private const val RETURN_FADE_NS=120_000_000L
    fun returnProgress(elapsedNs:Long):Float {
        val t=(elapsedNs.toDouble()/RETURN_FADE_NS).coerceIn(0.0,1.0).toFloat()
        return t*t*(3f-2f*t)
    }
}
