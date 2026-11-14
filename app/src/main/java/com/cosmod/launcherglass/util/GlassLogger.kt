package com.cosmod.launcherglass.util

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GlassLogger {
    @Volatile internal var enabled = true

    private val animationLogs by lazy { java.util.concurrent.ThreadPoolExecutor(1,1,30L,
        java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
        java.util.concurrent.ThreadFactory {work -> Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        },"CherryPickLogs").apply {isDaemon=true}},java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
    ).apply {allowCoreThreadTimeOut(true)} }

    internal inline fun animation(component:String,message:()->String) {
        if(enabled) animation(component,message())
    }

    /** Logcat stays immediate; LSPosed's synchronous file writer leaves the animation/lease lock. */
    fun animation(component:String,message:String) {
        if(!enabled)return
        val formatted="[${dateFormat.get()!!.format(Date())}] [$component] $message"
        Log.i(TAG,formatted)
        animationLogs.execute {if(enabled)try {XposedBridge.log("$TAG: $formatted")}catch(_:Throwable){}}
    }
    fun animationLimited(component:String,key:String,every:Int=500,message:()->String){
        if(!enabled)return
        val count=synchronized(counters){(counters[key] ?: 0).inc().also {counters[key]=it}}
        if(count<=3 || count%every==0)animation(component,if(count<=3)message()else "${message()} (x$count)")
    }

    const val TAG = "ColorOSGlass"
    private val dateFormat = object:ThreadLocal<SimpleDateFormat>() {
        override fun initialValue()=SimpleDateFormat("HH:mm:ss.SSS",Locale.US)
    }

    fun i(component: String, message: String) {
        if(!enabled)return
        val time = dateFormat.get()!!.format(Date())
        val formatted = "[$time] [$component] $message"

        // Use info-level messages so diagnostics remain visible with debug logging filtered.
        Log.i(TAG, formatted)

        // 2. LSPosed Log
        try {
            XposedBridge.log("$TAG: $formatted")
        } catch (_: Throwable) {}
    }

    private val counters = HashMap<String, Int>()

    /**
     * Hot-path diagnostics: the first few occurrences, then one aggregated
     * line per [every] events. XposedBridge.log is synchronous file I/O and
     * must not run on every animation frame.
     */
    fun limited(component: String, key: String, every: Int = 500, message: () -> String) {
        if(!enabled)return
        val count = synchronized(counters) { (counters[key] ?: 0).inc().also { counters[key] = it } }
        if (count <= 3 || count % every == 0) i(component, if (count <= 3) message() else "${message()} (x$count)")
    }

    fun d(component: String, message: String) {
        i(component, message)
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        if(!enabled)return
        val errorMsg = if (throwable != null) "$message: ${throwable.message}\n${Log.getStackTraceString(throwable)}" else message
        val time = dateFormat.get()!!.format(Date())
        val formatted = "[$time] [$component] ERROR: $errorMsg"
        Log.e(TAG, formatted)
        try {
            XposedBridge.log("$TAG: $formatted")
        } catch (_: Throwable) {}
    }

    fun readLogs(context: android.content.Context): String {
        return SuUtil.readLogcat(context)
    }

    fun clearLogs(): Boolean {
        return SuUtil.clearLogcat()
    }
}
