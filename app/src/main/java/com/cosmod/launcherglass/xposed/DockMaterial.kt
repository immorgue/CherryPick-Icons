package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Looper
import android.graphics.Matrix
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.HardwareRenderer
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.PathInterpolator
import android.view.ViewTreeObserver
import com.cosmod.launcherglass.util.WallpaperHelper
import com.cosmod.launcherglass.util.LiveBlurEngine
import java.lang.ref.WeakReference
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Glass dock behind the hotseat icons (opt-in).
 *
 * Drawn from OplusHotseat.onDraw, below the icons, with the same live
 * wallpaper material as the icons. Paddings and corner radius are percentages
 * of the icon size, so the proportions stay the same with any icon size or
 * display density. The dock is symmetric about the hotseat centre and
 * follows the native row while an item is dragged. While it is shown, the native dock plate (if the launcher has
 * one) is skipped for that frame.
 */
internal object DockMaterial {
    private const val NATIVE="cherrypick.nativeDock"
    private class Dock {
        val renderer=DockRenderer()
        val iconRenderer=DockRenderer()
        var iconKey:List<Any?>?=null
        var iconWallpaper:Bitmap?=null
        var iconSource:Bitmap?=null
        var target=Rect()
        var observing:ViewTreeObserver?=null
        var listener:ViewTreeObserver.OnPreDrawListener?=null
        var signature:List<Any?>?=null
        val rect=Rect()
        val pathRect=Rect()
        var pathRadius=Float.NaN
        var path:Path?=null
        var layer:DockLayer?=null
        var attaching=false
        var suppressNative=false
        var presence=DockPresence()
        var animator:ValueAnimator?=null
        var lastRowAlpha=1f
        // Last wallpaper/layout stamps seen by the frame listeners.
        var stamp=Long.MIN_VALUE
        var layout=-1L
        // False until the layer actually holds a drawn plate. An empty
        // display list must be retried, not left empty forever.
        var plateReady=false
    }
    private val docks=WeakHashMap<View,Dock>()
    private val iconMethods=HashMap<Class<*>,Method?>()
    private var smoothUnavailable=false
    private var weight=Float.NaN
    private val failures=HashSet<String>()
    private var logged=false
    // Event-driven gate: the composite backdrop and the hotseat signature are
    // rebuilt only when the wallpaper, the dock layout or the settings change.
    private var layoutRevision=0L
    private var lastStamp=Long.MIN_VALUE
    private var lastLayout=-1L
    fun noteLayout(){layoutRevision++}

    fun enabled()=PrefsHelper.enabled && PrefsHelper.blurEnabled && PrefsHelper.dockEnabled

    /**
     * Called once per pre-draw from the shared frame listener. Returns after a
     * single volatile read unless the wallpaper, the dock layout or the
     * settings changed, so a stationary launcher does no dock work per frame.
     */
    fun onFrame(){
        if(!enabled() || DockSettings.style().colorOnly || docks.isEmpty())return
        val context=WallpaperHelper.appContext ?: return
        // Live frames have their own publication cadence. Until the composite
        // has a smaller live update path, retain its existing refresh behaviour;
        // a source/layout-only stamp must never freeze video under dock icons.
        if(!PrefsHelper.dockEventDriven || WallpaperHelper.isLiveWallpaper(context)){
            prepareIconWallpaper(WallpaperHelper.getBlurredWallpaper(context))
            for(dock in docks.values){
                val layer=dock.layer ?: continue
                if(!layer.isAttachedToWindow)continue
                layer.active=enabled();layer.sync();layer.invalidate()
            }
            return
        }
        // Static wallpaper changes only on content/layout/settings events.
        val stamp=WallpaperHelper.stamp()
        if(stamp==lastStamp && layoutRevision==lastLayout)return
        // Wait for publication rather than consuming a new stamp with old pixels.
        if(!WallpaperHelper.sourceReady())return
        // A launch/return animation already owns the frame budget. The plate is
        // cheap to refresh; the full-screen icon composite waits for the first
        // settled frame instead of being rebuilt mid-transition.
        if(LiveBlurEngine.isAppLaunchInProgress || NativeSurfaceLive.active){
            for(dock in docks.values){
                val layer=dock.layer ?: continue
                if(!layer.isAttachedToWindow)continue
                layer.active=enabled();layer.sync();layer.invalidate()
            }
            return
        }
        lastStamp=stamp;lastLayout=layoutRevision
        prepareIconWallpaper(WallpaperHelper.getBlurredWallpaper(context))
        // The plate lives in its own full-screen layer. Refresh it once here
        // instead of invalidating it on every hotseat draw.
        for(dock in docks.values){
            val layer=dock.layer ?: continue
            if(!layer.isAttachedToWindow)continue
            layer.active=enabled();layer.sync();layer.invalidate()
        }
    }

    /** Independent pixels only: never reference hotseat/icon RenderNodes. */
    fun prepareIconWallpaper(wallpaper:Bitmap?) {
        if(!enabled() || wallpaper==null || wallpaper.isRecycled)return
        for((owner,dock) in docks.entries.toList()) {
            val hotseat=owner as? ViewGroup ?: continue
            if(!hotseat.isAttachedToWindow || !hotseat.isShown)continue
            try {
                val rect=geometry(hotseat) ?: continue
                val dm=hotseat.resources.displayMetrics
                val scale=maxOf(dm.widthPixels.toFloat()/wallpaper.width,dm.heightPixels.toFloat()/wallpaper.height)
                val offset=(wallpaper.width-dm.widthPixels/scale).coerceAtLeast(0f)*LiveBlurEngine.wallpaperScrollRatio
                // Resting coordinates keep unlock/press transforms out of the
                // static dock cache, exactly like its own renderer.
                var x=0f;var y=0f;var node:View?=hotseat
                while(node!=null){
                    val parent=node.parent as? View
                    x+=node.left;y+=node.top
                    node=parent
                }
                val radius=radius(rect)
                val live=WallpaperHelper.isLiveWallpaper(hotseat.context)
                val key=listOf(if(live)wallpaper else null,wallpaper.width,wallpaper.height,
                    WallpaperHelper.getDockWallpaper(hotseat.context),Rect(rect),radius,x,y,scale,if(live)offset else 0f,
                    DockSettings.style(),PrefsHelper.gradientOpacity,PrefsHelper.iconStroke,PrefsHelper.strokeOpacity,SurfaceMaterial.geometryRevision)
                if(dock.iconKey==key && dock.iconWallpaper?.isRecycled==false){dock.iconSource=wallpaper;continue}
                android.os.Trace.beginSection("CherryPick.dockIconBackdrop")
                try {
                val recording=RenderNode("CherryPick.dockIconBackdrop")
                recording.setPosition(0,0,wallpaper.width,wallpaper.height)
                val canvas=recording.beginRecording(wallpaper.width,wallpaper.height)
                try {
                    canvas.drawBitmap(wallpaper,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG))
                    canvas.translate(offset,0f);canvas.scale(1f/scale,1f/scale);canvas.translate(x,y)
                    dock.iconRenderer.draw(canvas,hotseat,rect,path(hotseat,rect,radius,dock),radius,1f)
                }finally{recording.endRecording()}
                val image=try {
                    XposedHelpers.callStaticMethod(HardwareRenderer::class.java,"createHardwareBitmap",recording,wallpaper.width,wallpaper.height) as? Bitmap
                }finally{recording.discardDisplayList()}
                if(image!=null){
                    if(!live)BackdropCoordinates.register(image,offset)
                    dock.iconWallpaper=image;dock.iconSource=wallpaper;dock.iconKey=key
                }
                }finally{android.os.Trace.endSection()}
            }catch(t:Throwable){fail(t)}
        }
    }

    fun iconWallpaper(view:View?,wallpaper:Bitmap):Bitmap {
        if(!enabled() || view==null)return wallpaper
        var current:View?=view
        repeat(16){
            val owner=current ?: return wallpaper
            docks[owner]?.let {dock ->
                if(dock.iconSource===wallpaper)return dock.iconWallpaper?.takeUnless {it.isRecycled} ?: wallpaper
            }
            current=owner.parent as? View
        }
        return wallpaper
    }

    fun install(loader:ClassLoader){
        DockLayer.install()
        val type=XposedHelpers.findClassIfExists("com.android.launcher3.OplusHotseat",loader)
        if(type==null){GlassLogger.i("Dock","OplusHotseat not found; dock unavailable");return}
        val rowBackground=type.declaredFields.none {it.name=="mDockerBackgroundDrawable"}
        if(rowBackground) {
            ModuleHooks.hookAllMethods(type,"setDockerBackground",object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam) {
                    val owner=param.thisObject as? ViewGroup ?: return
                    wrapRowBackground(owner,docks.getOrPut(owner){Dock()})
                }
            })
            XposedHelpers.findClassIfExists("com.android.launcher.custom.OplusToolUtils",loader)?.let {tools ->
                ModuleHooks.hookAllMethods(tools,"drawDockerBack",object:XC_MethodHook(){
                    override fun beforeHookedMethod(param:MethodHookParam) {
                        val owner=param.args.getOrNull(1) as? ViewGroup ?: return
                        if(enabled() && docks[owner]?.suppressNative==true)param.result=null
                    }
                })
            }
        }
        ModuleHooks.hookAllMethods(type,"onDraw",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? ViewGroup ?: return
                val dock=docks.getOrPut(view){Dock()}
                if(rowBackground)wrapRowBackground(view,dock)
                if(!enabled()){suppressNative(view,dock,false);return}
                observe(view,dock)
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                try {
                    if(!draw(view,canvas,dock)){suppressNative(view,dock,false);return}
                    suppressNative(view,dock,true)
                    if(rowBackground)return
                    // One dock: skip the native plate for this frame.
                    val native=NativeIconAccess.field(view,"mDockerBackgroundDrawable") as? Drawable ?: return
                    XposedHelpers.setObjectField(view,"mDockerBackgroundDrawable",null)
                    param.setObjectExtra(NATIVE,native)
                }catch(t:Throwable){suppressNative(view,dock,false);fail(t)}
            }
            override fun afterHookedMethod(param:MethodHookParam){
                val native=param.getObjectExtra(NATIVE) as? Drawable ?: return
                try {
                    if(NativeIconAccess.field(param.thisObject,"mDockerBackgroundDrawable")==null)
                        XposedHelpers.setObjectField(param.thisObject,"mDockerBackgroundDrawable",native)
                }catch(t:Throwable){fail(t)}
            }
        })
        // Children move without invalidating the hotseat itself: re-record the
        // dock when the row geometry changes (icon added, removed, re-centred).
        ModuleHooks.hookAllMethods(type,"onLayout",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? ViewGroup ?: return
                val dock=docks.getOrPut(view){Dock()}
                if(!enabled())return
                try {
                    noteLayout()
                    observe(view,dock)
                    val next=geometry(view)
                    updatePresence(view,dock,next!=null)
                    if(next!=dock.target)view.invalidate()
                }catch(t:Throwable){fail(t)}
            }
        })
    }

    private fun suppressNative(owner:ViewGroup,dock:Dock,value:Boolean) {
        if(dock.suppressNative==value)return
        dock.suppressNative=value
        (NativeIconAccess.field(owner,"mShortcutsAndWidgets") as? View)?.invalidate()
    }

    /** Older launchers put the plate on the child row, outside OplusHotseat.onDraw. */
    private fun wrapRowBackground(owner:ViewGroup,dock:Dock) {
        val row=NativeIconAccess.field(owner,"mShortcutsAndWidgets") as? View ?: return
        val native=row.background ?: return
        if(native is LegacyDockBackground)return
        val wrapper=LegacyDockBackground(native,owner,dock)
        row.background=wrapper
        // View.setBackground detaches the previous drawable's callback.
        wrapper.attach()
    }
    private class LegacyDockBackground(private val native:Drawable,owner:ViewGroup,private val dock:Dock):Drawable(),Drawable.Callback {
        private val owner=WeakReference(owner)
        fun attach(){native.callback=this}
        override fun draw(canvas:Canvas) {
            if(owner.get()==null || !enabled() || !dock.suppressNative)native.draw(canvas)
        }
        override fun onBoundsChange(bounds:Rect){native.bounds=bounds}
        override fun setAlpha(alpha:Int){native.alpha=alpha}
        override fun getAlpha()=native.alpha
        override fun setColorFilter(filter:android.graphics.ColorFilter?){native.colorFilter=filter}
        @Suppress("DEPRECATION") override fun getOpacity()=native.opacity
        override fun isStateful()=native.isStateful
        override fun onStateChange(state:IntArray)=native.setState(state)
        override fun onLevelChange(level:Int)=native.setLevel(level)
        override fun getPadding(padding:Rect)=native.getPadding(padding)
        override fun getOutline(outline:android.graphics.Outline){native.getOutline(outline)}
        override fun getOpticalInsets()=native.opticalInsets
        override fun getIntrinsicWidth()=native.intrinsicWidth
        override fun getIntrinsicHeight()=native.intrinsicHeight
        override fun setVisible(visible:Boolean,restart:Boolean):Boolean {
            val changed=super.setVisible(visible,restart)
            return native.setVisible(visible,restart) || changed
        }
        override fun invalidateDrawable(who:Drawable){invalidateSelf()}
        override fun scheduleDrawable(who:Drawable,what:Runnable,whenMillis:Long){scheduleSelf(what,whenMillis)}
        override fun unscheduleDrawable(who:Drawable,what:Runnable){unscheduleSelf(what)}
    }

    fun onSettingsChanged(){
        if(Looper.myLooper()!==Looper.getMainLooper()){android.os.Handler(Looper.getMainLooper()).post {onSettingsChanged()};return}
        for(view in docks.keys.toList()){
            docks[view]?.apply {
                animator?.cancel();animator=null;presence=DockPresence()
                lastStamp=Long.MIN_VALUE;layoutRevision++
                rect.setEmpty();target.setEmpty();renderer.reset();iconRenderer.reset();signature=null
                iconKey=null;iconSource=null
                layer?.apply {active=enabled();retainWhenHidden=false;sync();invalidate()}
                if(!enabled()){
                    listener?.let {if(observing?.isAlive==true)observing?.removeOnPreDrawListener(it)}
                    listener=null;observing=null
                }
            }
            (NativeIconAccess.field(view,"mShortcutsAndWidgets") as? View)?.invalidate()
            view.invalidate()
        }
    }

    private fun rowAlpha(view:ViewGroup):Float {
        val row=NativeIconAccess.field(view,"mShortcutsAndWidgets") as? ViewGroup ?: return 1f
        var alpha=0f
        for(i in 0 until row.childCount){val c=row.getChildAt(i);if(c.visibility==View.VISIBLE)alpha=maxOf(alpha,c.alpha)}
        return (alpha*row.alpha).coerceIn(0f,1f)
    }
    private fun updatePresence(owner:ViewGroup,dock:Dock,present:Boolean) {
        if(!dock.presence.observe(present))return
        dock.plateReady=false
        dock.animator?.cancel()
        dock.layer?.retainWhenHidden=!present && dock.presence.drawable
        val animator=ValueAnimator.ofFloat(dock.presence.fraction,dock.presence.target)
        dock.animator=animator
        animator.duration=180L
        animator.interpolator=PathInterpolator(.2f,0f,0f,1f)
        animator.addUpdateListener {
            if(dock.animator!==it)return@addUpdateListener
            dock.presence.update(it.animatedValue as Float)
            dock.layer?.apply {
                retainWhenHidden=!dock.presence.hasItems && dock.presence.drawable
                sync();invalidate()
            }
            owner.invalidate()
        }
        animator.addListener(object:AnimatorListenerAdapter(){
            override fun onAnimationEnd(animation:Animator){if(dock.animator===animation)dock.animator=null}
        })
        animator.start()
    }
    private fun observe(view:ViewGroup,dock:Dock){
        val observer=view.viewTreeObserver
        if(dock.observing===observer && observer.isAlive)return
        dock.listener?.let { if(dock.observing?.isAlive==true)dock.observing?.removeOnPreDrawListener(it) }
        val weak=WeakReference(view)
        val listener=ViewTreeObserver.OnPreDrawListener {
            val v=weak.get()
            if(v!=null && enabled())try {
                // Cheap exit: nothing the dock draws changed this frame.
                val stamp=WallpaperHelper.stamp()
                if(stamp==dock.stamp && layoutRevision==dock.layout && dock.animator==null &&
                    !LiveBlurEngine.hasRecentMotion)return@OnPreDrawListener true
                dock.stamp=stamp;dock.layout=layoutRevision
                val live=WallpaperHelper.isLiveWallpaper(v.context)
                val values=if(live){
                    val matrix=Matrix();XposedHelpers.callMethod(v,"transformMatrixToGlobal",matrix)
                    FloatArray(9).also {matrix.getValues(it)}.toList()
                }else emptyList()
                val wall=if(!IconMaterialRenderer.clearGlass(DockSettings.style()))WallpaperHelper.getDockWallpaper(v.context) else null
                val geometry=geometry(v)
                updatePresence(v,dock,geometry!=null)
                val signature=listOf(values,System.identityHashCode(wall),wall?.generationId,
                    if(live)LiveBlurEngine.wallpaperScrollRatio else 0f,rowAlpha(v),DockSettings.style(),geometry)
                if(signature!=dock.signature){dock.signature=signature;v.invalidate()}
            }catch(_:Throwable){}
            true
        }
        observer.addOnPreDrawListener(listener);dock.observing=observer;dock.listener=listener
    }
    private fun draw(hotseat:ViewGroup,canvas:Canvas,dock:Dock):Boolean {
        val layer=dock.layer
        if(layer!=null && layer.isAttachedToWindow){
            // sync() invalidates only when the pose or the opacity changed.
            layer.active=enabled();layer.sync()
            // The layer owns the plate. Until it has drawn one, keep asking for
            // a frame; an empty display list would otherwise stay empty until
            // an unrelated invalidation arrived.
            if(!dock.plateReady)layer.invalidate()
            if(!PrefsHelper.dockEventDriven)layer.invalidate()
            return true
        }
        if(!dock.attaching){
            dock.attaching=true
            val weak=WeakReference(hotseat)
            hotseat.post {
                try {weak.get()?.takeIf {it.isAttachedToWindow}?.let {owner ->
                    dock.layer=DockLayer.attach(owner,{dock.target}){view,target -> enabled() && drawPlate(view,target,dock)}
                    owner.invalidate()
                }}catch(t:Throwable){fail(t)}finally{dock.attaching=false}
            }
        }
        return drawPlate(hotseat,canvas,dock)
    }
    private fun drawPlate(hotseat:ViewGroup,canvas:Canvas,dock:Dock):Boolean {
        // A hidden horizontal row must not expose the native plate during unlock/launch.
        val next=geometry(hotseat)
        updatePresence(hotseat,dock,next!=null)
        if(next==null && (!dock.presence.drawable || dock.target.isEmpty)){
            // Nothing to show: the layer content is up to date with "empty".
            dock.plateReady=true
            return rowAlpha(hotseat)==0f && NativeIconAccess.call(hotseat,"hasVerticalHotseat")!=true
        }
        // Native spring translations already animate the row; a second animator lags behind it.
        if(next!=null){dock.target.set(next);dock.lastRowAlpha=rowAlpha(hotseat)}
        dock.rect.set(dock.target)
        val rect=Rect(dock.rect)
        val radius=radius(rect);val path=path(hotseat,rect,radius,dock)
        val saved=canvas.save()
        val plateAlpha=(if(next!=null)rowAlpha(hotseat)else dock.lastRowAlpha)*dock.presence.fraction
        val drawn=try {
            val scale=.94f+.06f*dock.presence.fraction
            canvas.scale(scale,scale,rect.exactCenterX(),rect.exactCenterY())
            dock.renderer.draw(canvas,hotseat,rect,path,radius,plateAlpha)
        }finally{canvas.restoreToCount(saved)}
        // A plate recorded while the row is still transparent is invisible:
        // keep asking for frames until it is actually painted.
        dock.plateReady=drawn && plateAlpha>0.01f
        if(drawn && !logged){logged=true;GlassLogger.i("Dock","Direct dock: bounds=$rect, radius=$radius, smooth=${path!=null}")}
        return drawn
    }

    private var iconSize=0f
    private fun radius(rect:Rect)=(iconSize*PrefsHelper.dockRadius/100f).coerceAtMost(minOf(rect.width(),rect.height())/2f).coerceAtLeast(0f)

    /** Dock bounds in hotseat coordinates, or null without visible icons. */
    private fun geometry(hotseat:ViewGroup):Rect? {
        if(NativeIconAccess.call(hotseat,"hasVerticalHotseat")==true)return null
        val container=NativeIconAccess.field(hotseat,"mShortcutsAndWidgets") as? ViewGroup ?: return null
        if(container.width<=0 || container.height<=0)return null
        val icons=RectF()
        val icon=Rect()
        val mapped=RectF()
        var size=0
        for(index in 0 until container.childCount){
            val child=container.getChildAt(index)
            if(child.visibility!=View.VISIBLE || !iconBounds(child,icon))continue
            size=maxOf(size,icon.width(),icon.height())
            mapped.set(icon)
            mapped.offset(container.left+child.x-container.scrollX,(container.top+child.top-container.scrollY).toFloat())
            icons.union(mapped)
        }
        if(icons.isEmpty || size<=0)return null
        iconSize=size.toFloat()
        val side=size*PrefsHelper.dockSidePadding/100f
        val vertical=size*PrefsHelper.dockVerticalPadding/100f
        val center=container.left+container.width/2f
        val cell=(NativeIconAccess.call(hotseat,"getCellWidth") as? Number)?.toFloat() ?: size.toFloat()
        val half=DockGeometry.halfWidth(PrefsHelper.dockAutoLength,center,icons.left,icons.right,
            hotseat.width.toFloat(),cell,size.toFloat(),side)
        val height=DockGeometry.vertical(icons.top,icons.bottom,vertical)
        return Rect((center-half).roundToInt(),height.first,(center+half).roundToInt(),height.second)
    }

    /** The visible icon square of a hotseat item, in its own coordinates. */
    private fun iconBounds(child:View,out:Rect):Boolean {
        val method=iconMethods.getOrPut(child.javaClass){
            generateSequence(child.javaClass as Class<*>?){it.superclass}.mapNotNull {type ->
                type.declaredMethods.firstOrNull {(it.name=="getIconBounds" || it.name=="getPreviewBounds") &&
                    it.parameterTypes.size==1 && it.parameterTypes[0]==Rect::class.java}
            }.firstOrNull()?.apply {isAccessible=true}
        }
        out.setEmpty()
        if(method!=null)try {method.invoke(child,out)}catch(_:Throwable){out.setEmpty()}
        if(out.isEmpty){
            // Unknown item type: a centred square at the top of the cell.
            val side=minOf(child.width-child.paddingLeft-child.paddingRight,child.height-child.paddingTop-child.paddingBottom)
            if(side<=0)return false
            val left=(child.width-side)/2
            out.set(left,child.paddingTop,left+side,child.paddingTop+side)
        }
        return !out.isEmpty
    }

    /** ColorOS continuous ("smooth") corners like the icons; a plain round rect otherwise. */
    private fun path(view:View,rect:Rect,radius:Float,dock:Dock):Path? {
        if(smoothUnavailable)return null
        if(dock.path!=null && dock.pathRect==rect && dock.pathRadius==radius)return dock.path
        return try {
            val manager=XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager",view.context.classLoader)
            if(weight.isNaN()){
                val map=manager.declaredMethods.first {it.name=="mapWeight" && it.parameterCount in 1..2 &&
                    it.parameterTypes.all {arg -> arg==Float::class.javaPrimitiveType}}
                weight=((if(map.parameterCount==1)map.invoke(null,3f)else map.invoke(null,3f,1.1f)) as Number).toFloat()
            }
            val path=Path()
            XposedHelpers.callStaticMethod(manager,"createSmoothPath",path,RectF(rect),radius,weight,Path.Direction.CW)
            if(path.isEmpty)null else path.also {dock.path=it;dock.pathRect.set(rect);dock.pathRadius=radius}
        }catch(t:Throwable){
            smoothUnavailable=true
            GlassLogger.e("Dock","Smooth corners unavailable; using rounded rectangle",t)
            null
        }
    }

    private fun fail(t:Throwable){
        if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("Dock","Dock material failed",t)
    }
}
