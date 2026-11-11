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
    private fun enabled()=PrefsHelper.enabled && PrefsHelper.blurEnabled && PrefsHelper.folderBlurEnabled
    internal data class Contour(val bounds:Rect,val radius:Float,val spanX:Int,val spanY:Int,
        val template:FolderMaterialHook.LongContour?,val rotated:Boolean) {
        val materialRadius get()=template?.radiusAt(bounds) ?: radius
        fun sharedPath()=template?.fit(bounds,rotated)
        fun path(view:View)=sharedPath() ?: Path().takeIf {NativeFolderPath.create(view,it,RectF(bounds),radius,spanX,spanY)}
    }
    internal fun sharedContour(bounds:Rect,radius:Float,sx:Int,sy:Int):Contour {
        val exact=FolderMaterialHook.longContour(sx,sy)
        return Contour(Rect(bounds),radius,sx,sy,exact ?: FolderMaterialHook.longContour(sy,sx),exact==null)
    }
    internal fun contour(view:View):Contour? {
        if(!view.javaClass.name.endsWith(".GridRecyclerView"))return null
        val bounds=(NativeIconAccess.field(view,"mRect") as? Rect)?.takeUnless {it.isEmpty} ?: return null
        val radius=(NativeIconAccess.field(view,"mRadius") as? Number)?.toFloat() ?: return null
        val owner=NativeIconAccess.field(view,"attachedShortcutContainer") as? View
        val info=owner?.tag ?: view.tag
        val x=(info?.let {NativeIconAccess.call(it,"getSpanX")} as? Number)?.toInt() ?: 1
        val y=(info?.let {NativeIconAccess.call(it,"getSpanY")} as? Number)?.toInt() ?: 1
        return sharedContour(bounds,radius,x,y)
    }
    private class GridPlate(view:View,val native:Drawable):Drawable(){
        private val owner=WeakReference(view)
        private val clip=Path()
        private var previous:Contour?=null
        private var geometry=-1L
        private var fingerprint=0L
        // The OEM draw already clips the destination canvas. A second raster
        // silhouette changes the corners (and applies antialiasing twice).
        private val coverage=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
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
            val shared=contour.template!=null
            if(previous!=contour || geometry!=SurfaceMaterial.geometryRevision){
                clip.set(contour.path(view) ?: return native.draw(canvas))
                previous=contour;geometry=SurfaceMaterial.geometryRevision
                fingerprint=SurfaceMaterial.descriptor("grid",contour.spanX,contour.spanY,contour.template)
            }
            if(shared && SurfaceMaterial.draw(canvas,rect,view,this,contour.materialRadius,clip,alpha=native.alpha,
                    style=FolderSettings.style(),pathFingerprint=fingerprint,fallback=native))return
            val saved=canvas.save()
            try {
                canvas.clipPath(clip)
                if(shared || !SurfaceMaterial.draw(canvas,rect,view,this,contour.radius,clip,alpha=native.alpha,nativeMask=coverage,
                    style=FolderSettings.style().copy(extendedLens=true),pathFingerprint=fingerprint,fallback=native,drawContour=false))native.draw(canvas)

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
    fun invalidateContours(){
        for(view in grids.keys.weakSnapshot())view.postInvalidateOnAnimation()
        for(view in hosts.keys.weakSnapshot())view.postInvalidateOnAnimation()
    }
    fun onSettingsChanged(){
        for(view in grids.keys.weakSnapshot())view.invalidate()
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
        if(grid!=null)ModuleHooks.hookAllMethods(grid,"clipRoundBackground",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!enabled() || param.args.singleOrNull() !is Canvas)return
                val view=param.thisObject as? View ?: return
                if(NativeIconAccess.field(view,"mBackDrawable") !is GridPlate)return
                // Long clusters restore this canvas before drawing the children.
                // Their plate owns the same antialiased mask as a long folder.
                if(contour(view)?.template!=null)param.result=null
            }
        })
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
