package com.cosmod.launcherglass.xposed

internal object StaticBlurTiming {
    const val HOLD_NS=120_000_000L
    const val FADE_NS=100_000_000L
    const val TOTAL_NS=HOLD_NS+FADE_NS
    fun progress(elapsedNs:Long)=((elapsedNs-HOLD_NS).toDouble()/FADE_NS).coerceIn(0.0,1.0).toFloat()
    fun holdRemaining(elapsedNs:Long)=(HOLD_NS-elapsedNs).coerceIn(0L,HOLD_NS)
    fun restart(previousKind:Int,newKind:Int,elapsedNs:Long)=previousKind!=newKind || elapsedNs>=600_000_000L
}

internal object AnimationBlurWindow {
    @Volatile var generation=0L
        private set
    @Volatile private var startedNs=0L
    val startNs get()=startedNs
    private var kind=-1
    @Synchronized fun begin(newKind:Int){
        if(!PrefsHelper.staticAnimationBlur)return
        val now=System.nanoTime()
        if(generation!=0L && !StaticBlurTiming.restart(kind,newKind,now-startedNs))return
        kind=newKind;startedNs=now;generation++
    }
    fun progress(nowNs:Long)=if(generation==0L)1f else StaticBlurTiming.progress(nowNs-startedNs)
    fun holdRemaining(nowNs:Long)=if(generation==0L)0L else StaticBlurTiming.holdRemaining(nowNs-startedNs)
    fun active(nowNs:Long)=generation!=0L && nowNs-startedNs<StaticBlurTiming.TOTAL_NS
}
