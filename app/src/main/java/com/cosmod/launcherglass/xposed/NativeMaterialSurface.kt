package com.cosmod.launcherglass.xposed

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import com.cosmod.launcherglass.bridge.HookReflection

/** Small material buffer, inheriting ALL motion/clipping from the OEM parent. */
internal class NativeMaterialSurface private constructor(
    val control:SurfaceControl,val surface:Surface,
    val width:Int,val height:Int,val canvasWidth:Int,val canvasHeight:Int
){
    private var shown=false
    fun detach(){
        if(!control.isValid)return
        val tx=SurfaceControl.Transaction()
        try {
            HookReflection.callMethod(tx,"hide",control)
            HookReflection.callMethod(tx,"reparent",control,null)
            tx.apply();shown=false
        }finally{tx.close()}
    }
    /** Merge removal with the workspace buffer that replaces this material. */
    fun detachOnDraw(root:View) {
        val impl=HookReflection.callMethod(root,"getViewRootImpl") ?: error("No workspace ViewRoot")
        val tx=SurfaceControl.Transaction()
        try {
            HookReflection.callMethod(tx,"hide",control)
            HookReflection.callMethod(tx,"reparent",control,null)
            val result=HookReflection.callMethod(impl,"applyTransactionOnDraw",tx)
            check(result!=false){"Workspace rejected draw transaction"}
            shown=false
        }finally{tx.close()}
    }
    fun attach(parent:SurfaceControl){
        val tx=SurfaceControl.Transaction()
        try {HookReflection.callMethod(tx,"reparent",control,parent);tx.apply()}finally{tx.close()}
    }
    fun show(){
        if(shown)return
        val tx=SurfaceControl.Transaction()
        try {
            HookReflection.callMethod(tx,"setMatrix",control,canvasWidth.toFloat()/width,0f,0f,canvasHeight.toFloat()/height)
            HookReflection.callMethod(tx,"setPosition",control,0f,0f)
            HookReflection.callMethod(tx,"setLayer",control,1)
            HookReflection.callMethod(tx,"show",control)
            tx.apply();shown=true
        }finally{tx.close()}
    }
    fun close(){
        try {
            val tx=SurfaceControl.Transaction()
            try {if(control.isValid){HookReflection.callMethod(tx,"remove",control);tx.apply()}}finally{tx.close()}
        }catch(_:Throwable){}
        try{surface.release()}catch(_:Throwable){}
        try{control.release()}catch(_:Throwable){}
    }
    companion object {
        private val session by lazy {HookReflection.newInstance(Class.forName("android.view.SurfaceSession"))}
        fun prewarmHandoff(rootType:Class<*>?,control:SurfaceControl){
            try {
                HookReflection.findMethodBestMatch(SurfaceControl.Transaction::class.java,"hide",SurfaceControl::class.java)
                HookReflection.findMethodBestMatch(SurfaceControl.Transaction::class.java,"reparent",SurfaceControl::class.java,SurfaceControl::class.java)
                HookReflection.findMethodBestMatch(rootType ?: Class.forName("com.android.internal.policy.DecorView"),"getViewRootImpl",*emptyArray<Class<*>>())
                HookReflection.findMethodBestMatch(Class.forName("android.view.ViewRootImpl"),"applyTransactionOnDraw",SurfaceControl.Transaction::class.java)
            }catch(t:Throwable){
                com.cosmod.launcherglass.util.GlassLogger.animationLimited("LiveSurface","handoff-warmup",100){"Optional handoff lookup warmup unavailable: ${t.javaClass.simpleName}"}
            }
        }
        fun create(parent:SurfaceControl?,width:Int,height:Int,canvasWidth:Int,canvasHeight:Int):NativeMaterialSurface {
            val builder=HookReflection.newInstance(Class.forName("android.view.SurfaceControl"+'$'+"Builder"),session)
            HookReflection.callMethod(builder,"setName","CherryPick.material")
            if(parent!=null)HookReflection.callMethod(builder,"setParent",parent)
            HookReflection.callMethod(builder,"setFormat",PixelFormat.TRANSLUCENT)
            HookReflection.callMethod(builder,"setBufferSize",width,height)
            val control=HookReflection.callMethod(builder,"build") as SurfaceControl
            val surface=HookReflection.newInstance(Surface::class.java) as Surface
            try {
                HookReflection.callMethod(surface,"copyFrom",control)
                check(surface.isValid){"Material producer is invalid"}
                return NativeMaterialSurface(control,surface,width,height,canvasWidth,canvasHeight)
            }catch(t:Throwable){surface.release();control.release();throw t}
        }
    }
}
