package com.cosmod.launcherglass.xposed

internal object StaticBlurTiming {
    private const val RETURN_FADE_NS=120_000_000L
    private const val LIVE_RETURN_FADE_NS=48_000_000L
    fun returnProgress(elapsedNs:Long,live:Boolean=false):Float {
        val duration=if(live)LIVE_RETURN_FADE_NS else RETURN_FADE_NS
        val t=(elapsedNs.toDouble()/duration).coerceIn(0.0,1.0).toFloat()
        return t*t*(3f-2f*t)
    }
}
