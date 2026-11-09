package com.cosmod.launcherglass.xposed

import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook

/**
 * CPU callback cadence only. These intervals do not measure GPU completion or
 * presentation, and gaps excluded below must not be treated as proof of no jank.
 * Use SurfaceFlinger FrameTimeline for displayed-frame/deadline measurements.
 */
internal object FrameJank {
    private var last=0L
    private var windowStart=0L
    private val gaps=ArrayList<Long>(512)
    private var installed=false

    fun install(loader:ClassLoader){
        if(installed)return
        installed=true
        try {
            ModuleHooks.hookAllMethods(android.view.Choreographer::class.java,"doFrame",object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){note()}
            })
            GlassLogger.i("FrameJank","Frame interval diagnostics installed")
        }catch(t:Throwable){GlassLogger.e("FrameJank","install failed",t)}
    }

    private fun note(){
        if(!PrefsHelper.enabled || !PrefsHelper.dockEventDriven)return
        val now=System.nanoTime()
        if(last!=0L){
            val gap=now-last
            // This diagnostic filters gaps above 120 ms to omit idle periods.
            // Real long stalls can also be filtered; this is not a jank count.
            if(gap<120_000_000L)gaps.add(gap)
        }
        last=now
        if(windowStart==0L)windowStart=now
        if(now-windowStart>=2_000_000_000L){report();windowStart=now;gaps.clear()}
    }

    private fun report(){
        if(gaps.size<8)return
        val sorted=gaps.sorted()
        val n=sorted.size
        val p50=sorted[n/2]/1_000_000.0
        val p90=sorted[(n*0.9).toInt().coerceAtMost(n-1)]/1_000_000.0
        val max=sorted[n-1]/1_000_000.0
        val over833=gaps.count{it>8_333_333L}
        val over1667=gaps.count{it>16_666_666L}
        val p99=sorted[(n*0.99).toInt().coerceAtMost(n-1)]/1_000_000.0
        GlassLogger.i("FrameJank","frames=$n p50=${String.format("%.2f",p50)}ms p90=${String.format("%.2f",p90)}ms p99=${String.format("%.2f",p99)}ms max=${String.format("%.2f",max)}ms over8.33=$over833 over16.67=$over1667")
    }
}
