package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Build
import android.view.View
import com.cosmod.launcherglass.util.GlassVolume
import com.cosmod.launcherglass.util.WallpaperHelper
import com.cosmod.launcherglass.bridge.HookReflection

/** Dock-specific, direct-resolution path rendering; no nested sampler RenderNode. */
internal class DockRenderer {
    private val plate=RenderNode("CherryPick.dockPlate").apply {setUseCompositingLayer(true,null)}
    // Primitive cache key: comparing it must not allocate on every frame.
    private var keyWallpaper:Bitmap?=null
    private var keyGeneration=0
    private val keyRect=Rect()
    private var keyRadius=Float.NaN
    private var keySource:RectF?=null
    private var keyStyle:GlassStyle?=null
    private var keyPrefs=0
    private var keyRevision=Long.MIN_VALUE
    private var lens:LiquidGlassShader?=null
    private var lastWallpaper:Bitmap?=null
    private val sampleClock=SampleFrameClock()
    private var sampledWallpaper:Bitmap?=null
    private var sampledSource:RectF?=null
    private var sampledStyle:GlassStyle?=null
    private var sampleWidth=0
    private var sampleHeight=0
    private var staticWallpaper:Bitmap?=null
    private var staticSource:RectF?=null
    private var staticBounds=Rect()
    private var staticScreenWidth=0
    private var staticScreenHeight=0
    private val solid:Shader=LinearGradient(0f,0f,1f,0f,Color.WHITE,Color.WHITE,Shader.TileMode.CLAMP)
    // A live wallpaper changes every frame; the plate may follow it at 30 Hz
    // without the recorder rebuilding a full plate on each display frame.
    private val plateClock=SampleFrameClock()
    fun reset(){lastWallpaper=null;staticWallpaper=null;staticSource=null;sampledWallpaper=null;sampledSource=null;sampledStyle=null;keyWallpaper=null;keyGeneration=0;keyRect.setEmpty();keyRadius=Float.NaN;keySource=null;keyStyle=null;keyPrefs=0;keyRevision=Long.MIN_VALUE;plate.discardDisplayList()}
    fun draw(canvas:Canvas, view:View, rect:Rect, path:Path?, radius:Float, alpha:Float):Boolean {
        val style=DockSettings.style().copy(extendedLens=true)
        val w=rect.width();val h=rect.height()
        if(w<=0 || h<=0)return false
        val clear=IconMaterialRenderer.clearGlass(style)
        val current=if(!clear)WallpaperHelper.getDockWallpaper(view.context)?.takeUnless {it.isRecycled} else null
        var wallpaper=if(clear)null else (current ?: lastWallpaper?.takeUnless {it.isRecycled}) ?: return false
        if(current!=null && !current.isRecycled)lastWallpaper=current
        var source=wallpaper?.let {
            val dm=view.resources.displayMetrics
            if(!WallpaperHelper.isLiveWallpaper(view.context)){
                if(staticWallpaper!==it || staticBounds!=rect || staticScreenWidth!=dm.widthPixels || staticScreenHeight!=dm.heightPixels){
                    // Resolve resting layout coordinates, ignoring OEM unlock,
                    // launch and press transforms. Those move the retained dock
                    // as a whole; they do not change a static wallpaper sample.
                    val screen=RectF(rect)
                    var current:View?=view
                    while(current!=null){
                        val parent=current.parent as? View
                        screen.offset((current.left-(parent?.scrollX ?: 0)).toFloat(),(current.top-(parent?.scrollY ?: 0)).toFloat())
                        current=parent
                    }
                    staticWallpaper=it;staticBounds.set(rect);staticScreenWidth=dm.widthPixels;staticScreenHeight=dm.heightPixels
                    staticSource=WallpaperFootprint.source(it,dm.widthPixels,dm.heightPixels,screen)
                }
                staticSource!!
            }else{
                val world=Matrix();HookReflection.callMethod(view,"transformMatrixToGlobal",world)
                val screen=RectF(rect);world.mapRect(screen)
                WallpaperFootprint.source(it,dm.widthPixels,dm.heightPixels,screen)
            }
        }
        // Retain the resting crop as well as its pixels. The hotseat's OEM
        // scale/translation animates this layer; resampling that transform
        // during a static flight magnifies the blur and registers new GPU
        // resources on the same RenderThread as the opening app snapshot.
        if(com.cosmod.launcherglass.util.LiveBlurEngine.freezeAnimationWallpaper &&
            sampledStyle==style && sampleWidth==w && sampleHeight==h && sampledWallpaper?.isRecycled==false){
            wallpaper=sampledWallpaper
            source=sampledSource
        }
        if(canvas.isHardwareAccelerated){
            val now=System.nanoTime()
            val fps=PrefsHelper.liveFps.coerceIn(1,60)
            val changed=sampledWallpaper!==wallpaper || sampledSource!=source
            if(changed && WallpaperHelper.isLiveWallpaper(view.context) && sampledStyle==style && sampleWidth==w && sampleHeight==h &&
                sampledWallpaper?.isRecycled==false && sampleClock.remainingNs(now,fps)>0L){
                wallpaper=sampledWallpaper;source=sampledSource
            }else if(changed || sampledStyle!=style || sampleWidth!=w || sampleHeight!=h){
                sampledWallpaper=wallpaper;sampledSource=source?.let(::RectF);sampledStyle=style
                sampleWidth=w;sampleHeight=h;sampleClock.record(now,fps)
            }
        }
        val shape=path ?: com.cosmod.launcherglass.util.ContinuousCorners.path(Path(),RectF(rect),radius)
        if(canvas.isHardwareAccelerated){
            val prefsKey=PrefsHelper.gpuEffects.hashCode()*31+PrefsHelper.gradientOpacity*7+
                PrefsHelper.iconStroke.hashCode()*13+PrefsHelper.strokeOpacity*17
            val generation=wallpaper?.generationId ?: 0
            val structureChanged=keyRect!=rect || keyRadius!=radius || keyStyle!=style || keyPrefs!=prefsKey ||
                keyRevision!=SurfaceMaterial.geometryRevision
            val changed=keyWallpaper!==wallpaper || keyGeneration!=generation || keySource!=source || structureChanged
            val liveWallpaper=WallpaperHelper.isLiveWallpaper(view.context)
            val nowNs=System.nanoTime()
            // Geometry/settings must never wait for a wallpaper FPS slot.
            // Static content changes only on invalidation; apply that event now.
            val due=structureChanged || !liveWallpaper || plateClock.remainingNs(nowNs,30)==0L
            if((changed && due) || !plate.hasDisplayList()){
                if(structureChanged && com.cosmod.launcherglass.util.GlassLogger.enabled)
                    com.cosmod.launcherglass.util.GlassLogger.animation("Dock", "Recording plate: rect=$rect radius=$radius optics=${style.kyant} source=$source image=${wallpaper?.width}x${wallpaper?.height} hardware=${canvas.isHardwareAccelerated}")
                android.os.Trace.beginSection("CherryPick.dockRecord")
                plate.setPosition(0,0,w,h)
                val recording=plate.beginRecording(w,h)
                try {
                    recording.translate(-rect.left.toFloat(),-rect.top.toFloat())
                    drawPlate(recording,rect,shape,radius,1f,wallpaper,source,style)
                }finally{plate.endRecording();android.os.Trace.endSection()}
                keyWallpaper=wallpaper;keyGeneration=generation;keyRect.set(rect);keyRadius=radius
                keySource=source?.let(::RectF);keyStyle=style;keyPrefs=prefsKey
                keyRevision=SurfaceMaterial.geometryRevision
                plateClock.record(nowNs,if(liveWallpaper)30 else 1)
            }
            plate.alpha=alpha.coerceIn(0f,1f)
            val saved=canvas.save()
            try {canvas.translate(rect.left.toFloat(),rect.top.toFloat());canvas.drawRenderNode(plate)}
            finally{canvas.restoreToCount(saved)}
        }else drawPlate(canvas,rect,shape,radius,alpha,wallpaper,source,style)
        return true
    }
    internal fun drawPlate(canvas:Canvas,rect:Rect,shape:Path,radius:Float,alpha:Float,wallpaper:Bitmap?,source:RectF?,style:GlassStyle){
        val w=rect.width();val h=rect.height()
        val style=if(style.kyant!=null && radius>0f)
            style.copy(cornerField=GlassGeometry.contourField(contourMask(w,h,radius,shape,rect)))else style
        val saved=if(alpha<.999f)canvas.saveLayerAlpha(RectF(rect),(alpha.coerceIn(0f,1f)*255).toInt()) else canvas.save()
        try {
            canvas.clipPath(shape)
            canvas.translate(rect.left.toFloat(),rect.top.toFloat())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            if(wallpaper!=null && source!=null){
                val image=WallpaperHelper.forCanvas(wallpaper,canvas)
                val localRect=RectF(0f,0f,w.toFloat(),h.toFloat())
                val gpu=if(style.kyant!=null && PrefsHelper.gpuEffects && canvas.isHardwareAccelerated && Build.VERSION.SDK_INT>=33){
                    val l=lens ?: LiquidGlassShader.create().also {lens=it}
                    l?.update(image,solid,source,w,h,style.filter,(radius/minOf(w,h)).coerceIn(0f,.5f),style)
                }else if(style.kyant!=null && (!PrefsHelper.gpuEffects || !canvas.isHardwareAccelerated))
                    CpuLiquidGlass.shader(image,contourMask(w,h,radius,shape,rect),w,h,source,(radius/minOf(w,h)).coerceIn(0f,.5f),style)
                else null
                if(com.cosmod.launcherglass.util.GlassLogger.enabled)
                    com.cosmod.launcherglass.util.GlassLogger.animationLimited("Dock","lensDraw",500) {"Lens=${gpu!=null} field=${style.cornerField?.width}x${style.cornerField?.height} finish=${style.finish}"}
                paint.shader=gpu ?: GlassGeometry.wallpaperShader(image).apply {
                    setLocalMatrix(Matrix().apply {setRectToRect(source,localRect,Matrix.ScaleToFit.FILL)})
                }
                if(gpu==null)paint.colorFilter=style.filter
                if(style.finish.active && canvas.isHardwareAccelerated){
                    val node=finishNode ?: RenderNode("CherryPick.dockFinish").also {finishNode=it}
                    node.setPosition(0,0,w,h)
                    node.setRenderEffect(MaterialFinish.effect(null,w,h,style.finish))
                    val recording=node.beginRecording(w,h)
                    try {recording.drawRect(localRect,paint)}finally{node.endRecording()}
                    canvas.drawRenderNode(node)
                }else if(style.finish.active){
                    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawRect(localRect,paint)
                    val finished=MaterialFinish.applyCpu(bitmap,style.finish)
                    canvas.drawBitmap(finished,0f,0f,null)
                    if(finished!==bitmap)finished.recycle()
                    bitmap.recycle()
                }else canvas.drawRect(localRect,paint)
                paint.shader=null;paint.colorFilter=null
            }
            paint.color=(style.opacity.coerceIn(0,100)*255/100 shl 24) or ((style.plateColor ?: PrefsHelper.plateColor(SurfaceTone.base())) and 0xffffff)
            canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
            if(!DockSettings.auto && PrefsHelper.gradientOpacity>0){
                paint.color=Color.WHITE;paint.shader=GlassVolume.shader(h.toFloat(),PrefsHelper.gradientOpacity)
                canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint);paint.shader=null
            }
        } finally {canvas.restoreToCount(saved)}
        // Draw a directional antialiased contour directly at device resolution.
        if(!DockSettings.auto && PrefsHelper.iconStroke && PrefsHelper.strokeOpacity>0 && alpha>0f){
            val edge=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                this.style=Paint.Style.STROKE;strokeWidth=2f
                val a=(PrefsHelper.strokeOpacity.coerceIn(0,100)*.8f*alpha).toInt().coerceIn(0,255)
                shader=LinearGradient(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat(),
                    intArrayOf(Color.argb(a,255,255,255),Color.argb(a/5,255,255,255),Color.argb(a/2,0,0,0)),null,Shader.TileMode.CLAMP)
            }
            val save=canvas.save();canvas.clipPath(shape);canvas.drawPath(shape,edge);canvas.restoreToCount(save)
        }
    }
    private var finishNode:RenderNode?=null
    private var maskImage:Bitmap?=null
    private var maskWidth=0
    private var maskHeight=0
    private var maskRadius=Float.NaN
    private fun contourMask(w:Int,h:Int,radius:Float,shape:Path,rect:Rect):Bitmap {
        maskImage?.takeIf {maskWidth==w && maskHeight==h && maskRadius==radius}?.let {return it}
        val scale=minOf(1f,384f/maxOf(w,h))
        val mask=Bitmap.createBitmap((w*scale).toInt().coerceAtLeast(1),(h*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        val canvas=Canvas(mask)
        canvas.scale(mask.width.toFloat()/w,mask.height.toFloat()/h)
        canvas.translate(-rect.left.toFloat(),-rect.top.toFloat())
        canvas.drawPath(shape,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE})
        maskWidth=w;maskHeight=h;maskRadius=radius;maskImage=mask
        return mask
    }
}
