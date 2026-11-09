package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.*
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Native shortcut clusters keep their individual items, hit targets and transitions. */
internal object MultiIconMaterialHook {
    private fun enabled()=PrefsHelper.enabled && PrefsHelper.blurEnabled
    internal data class Contour(val bounds:Rect,val radius:Float,val spanX:Int,val spanY:Int) {
        fun path(view:View)=Path().takeIf {NativeFolderPath.create(view,it,RectF(bounds),radius,spanX,spanY)}?.also {
            NativeFolderPath.intersectFill(it,grids[view]?.native,bounds)
        }
    }
    internal fun contour(view:View):Contour? {
        if(!view.javaClass.name.endsWith(".GridRecyclerView"))return null
        val bounds=(NativeIconAccess.field(view,"mRect") as? Rect)?.takeUnless {it.isEmpty} ?: return null
        val radius=(NativeIconAccess.field(view,"mRadius") as? Number)?.toFloat() ?: return null
        val owner=NativeIconAccess.field(view,"attachedShortcutContainer") as? View
        val info=owner?.tag ?: view.tag
        val x=(info?.let {NativeIconAccess.call(it,"getSpanX")} as? Number)?.toInt() ?: 1
        val y=(info?.let {NativeIconAccess.call(it,"getSpanY")} as? Number)?.toInt() ?: 1
        return Contour(Rect(bounds),radius,x,y)
    }
    private class GridPlate(view:View,val native:Drawable):Drawable(){
        private val owner=WeakReference(view)
        private val clip=Path()
        private val previous=Rect()
        private var radius=Float.NaN
        private var spans=0
        private var geometry=-1L
        private var fingerprint=0L
        override fun draw(canvas:Canvas){
            val view=owner.get() ?: return native.draw(canvas)
            if(!enabled()){native.draw(canvas);return}
            try {drawMaterial(canvas,view)}catch(t:Throwable){
                if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("MultiIcons","Normal shortcut material unavailable",t)
                native.draw(canvas)
            }
        }
        private fun drawMaterial(canvas:Canvas,view:View){
            val contour=contour(view) ?: return native.draw(canvas)
            val rect=contour.bounds
            val nativeRadius=contour.radius
            val sx=contour.spanX;val sy=contour.spanY
            if(previous!=rect || radius!=nativeRadius || spans!=sx*100+sy || geometry!=SurfaceMaterial.geometryRevision){
                clip.reset()
                if(!NativeFolderPath.create(view,clip,RectF(rect),nativeRadius,sx,sy)){native.draw(canvas);return}
                NativeFolderPath.intersectFill(clip,native,rect)
                previous.set(rect);radius=nativeRadius;spans=sx*100+sy;geometry=SurfaceMaterial.geometryRevision
                // The OEM contour is a function of size, radius and spans only.
                fingerprint=SurfaceMaterial.descriptor("grid",sx,sy)
            }
            val saved=canvas.save()
            try {
                canvas.clipPath(clip)
                if(!SurfaceMaterial.draw(canvas,rect,view,this,nativeRadius,clip,alpha=native.alpha,
                    style=GlassStyle.icons().copy(extendedLens=true),pathFingerprint=fingerprint,fallback=native))native.draw(canvas)
            }finally{canvas.restoreToCount(saved)}
        }
        override fun onBoundsChange(bounds:Rect){native.bounds=bounds}
        override fun setAlpha(alpha:Int){if(native.alpha!=alpha){native.alpha=alpha;invalidateSelf()}}
        override fun getAlpha()=native.alpha
        override fun setColorFilter(filter:ColorFilter?){if(native.colorFilter!=filter){native.colorFilter=filter;invalidateSelf()}}
        @Deprecated("Deprecated in Java")override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    private val failures=HashSet<String>()
    private val hosts=WeakHashMap<View,Boolean>()
    private val grids=WeakHashMap<View,GridPlate>()
    fun onSettingsChanged(){
        if(!enabled()){
            for((view,plate) in grids.strongSnapshot()){
                if(NativeIconAccess.field(view,"mBackDrawable")===plate)XposedHelpers.setObjectField(view,"mBackDrawable",plate.native)
                view.invalidate()
            }
            grids.clear()
        }
        for(view in hosts.keys.weakSnapshot()){
            if(enabled())try {FolderMaterialHook.attachShortcut(view)}catch(t:Throwable){
                if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("MultiIcons","Native shortcut material unavailable",t)
            }
            view.invalidate()
        }
    }
    private fun prepare(view:View){
        if(!enabled())return
        // View.background is the selection highlight. GridRecyclerView owns
        // the normal plate; OplusPreviewBackground handles hover/resize only.
        hosts[view]=true
        FolderMaterialHook.attachShortcut(view)
    }
    private fun bindItems(view:View,depth:Int=0){
        if(depth>5)return
        IconGlassHook.bindContextView(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)bindItems(view.getChildAt(i),depth+1)
    }
    fun install(loader:ClassLoader){
        val type=XposedHelpers.findClassIfExists("com.android.launcher3.viewcontainer.ShortcutContainer",loader) ?: return
        val grid=XposedHelpers.findClassIfExists("com.android.launcher3.viewcontainer.GridRecyclerView",loader)
        if(grid!=null)ModuleHooks.hookAllMethods(grid,"draw",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!enabled())return
                val view=param.thisObject as? View ?: return
                val native=NativeIconAccess.field(view,"mBackDrawable") as? Drawable ?: return
                if(native !is GridPlate){
                    val plate=GridPlate(view,native).apply {bounds=Rect(native.bounds);callback=view}
                    grids[view]=plate
                    XposedHelpers.setObjectField(view,"mBackDrawable",plate)
                    GlassLogger.i("MultiIcons","Normal shortcut background installed: ${native.javaClass.name}")
                }
                // App launch/return snapshot: the GPU image of this same view.
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                if(TransitionCapture.drawHardware(view,canvas))param.result=null
                else TransitionCapture.onWorkspaceDraw(view)
            }
        })
        for(method in listOf("onLayout","dispatchDraw"))ModuleHooks.hookAllMethods(type,method,object:XC_MethodHook(){
            private fun update(param:MethodHookParam){
                if(!enabled())return
                val view=param.thisObject as? View ?: return
                if(method=="dispatchDraw" && hosts.containsKey(view))return
                android.os.Trace.beginSection("CherryPick.bindCluster")
                try {prepare(view);bindItems(view)}catch(t:Throwable){
                    if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("MultiIcons","Native shortcut material unavailable",t)
                }finally{android.os.Trace.endSection()}
            }
            override fun beforeHookedMethod(param:MethodHookParam){if(method=="dispatchDraw")update(param)}
            override fun afterHookedMethod(param:MethodHookParam){if(method=="onLayout")update(param)}
        })
    }
}
