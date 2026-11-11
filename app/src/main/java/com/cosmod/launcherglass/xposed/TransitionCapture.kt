package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt

/** A cluster is a scene, not an icon bitmap. Only native adornments are baked. */
internal object TransitionCapture {
    private class Element(val matrix:Matrix,val clip:Rect,val render:(Canvas)->Unit)
    private class Scene(val width:Int,val height:Int){
        var canvas:Canvas?=null
        var adornments:Bitmap?=null
        val elements=ArrayList<Element>()
        var interior:RenderNode?=null
        var guardCorners=true
        var nativeMask:Bitmap?=null
        var materialClip:Path?=null
    }
    private val captures=Collections.synchronizedMap(WeakHashMap<Bitmap,Boolean>())
    private val scenes=Collections.synchronizedMap(WeakHashMap<Bitmap,Scene>())
    private val drawableScenes=Collections.synchronizedMap(WeakHashMap<Drawable,Scene>())
    private class Cached(val scene:Scene,val bitmap:Bitmap,val tag:Any?,val builtAt:Long=android.os.SystemClock.uptimeMillis())
    private data class Source(val id:Long,val user:Any?,val width:Int,val height:Int,
        val contour:MultiIconMaterialHook.Contour?,val geometry:Long)
    private val cached=HashMap<Any,Cached>()
    private fun source(view:View):Any {
        val owner=NativeIconAccess.field(view,"attachedShortcutContainer") as? View ?: view
        val info=owner.tag ?: view.tag ?: return owner
        val id=(NativeIconAccess.field(info,"id") as? Number)?.toLong() ?: return owner
        return if(id>=0)Source(id,NativeIconAccess.field(info,"user"),view.width,view.height,
            MultiIconMaterialHook.contour(view),SurfaceMaterial.geometryRevision)else owner
    }
    private val target=ThreadLocal<View?>()
    private val recording=ThreadLocal<Boolean>()
    private val pending=ThreadLocal<Scene?>()
    private var logs=0
    private val failures=HashSet<String>()
    val renderingSnapshot:Boolean get()=recording.get()==true
    fun onSettingsChanged(){
        if(Looper.myLooper()!=Looper.getMainLooper()){
            android.os.Handler(Looper.getMainLooper()).post {onSettingsChanged()};return
        }
        cached.clear()
    }
    fun onWorkspaceDraw(view:View){
        val key=source(view)
        val entry=cached[key] ?: return
        if(!renderingSnapshot && android.os.SystemClock.uptimeMillis()-entry.builtAt>5000L &&
            com.cosmod.launcherglass.util.LiveBlurEngine.isResumed &&
            !com.cosmod.launcherglass.util.LiveBlurEngine.isAppLaunchInProgress &&
            !com.cosmod.launcherglass.util.LiveBlurEngine.hasRecentMotion)cached.remove(key)
    }
    fun rememberDrawable(drawable:Drawable){
        val bitmap=(drawable as? BitmapDrawable)?.bitmap ?: return
        scenes[bitmap]?.let {drawableScenes[drawable]=it}
    }
    fun drawLive(drawable:BitmapDrawable,canvas:Canvas):Boolean {
        if(!PrefsHelper.backdropEnabled)return false
        val scene=drawableScenes[drawable] ?: scenes[drawable.bitmap] ?: return false
        val overlay=scene.adornments?.takeUnless {it.isRecycled} ?: return false
        val bounds=Rect(drawable.bounds);val alpha=drawable.alpha
        if(bounds.isEmpty)return false
        NativeSurfaceLive.capture(canvas,scene.width,scene.height){renderScene(scene,overlay,it,bounds,alpha)}
        // The BitmapDrawable already contains the complete material snapshot.
        // The OEM scales its single buffer for the entire static flight.
        if(PrefsHelper.staticAnimationBlur)return false
        renderScene(scene,overlay,canvas,bounds,alpha)
        return true
    }
    fun captures(canvas:Canvas):Boolean {
        val scene=pending.get() ?: return false
        return scene.canvas!=null && (scene.canvas===canvas || IconGlassHook.previewCaptureMatrix(canvas)!=null)
    }
    @Suppress("DEPRECATION")
    fun captureElement(canvas:Canvas,render:(Canvas)->Unit):Boolean {
        val scene=pending.get() ?: return false
        if(scene.canvas==null)return false
        val matrix=if(scene.canvas===canvas)Matrix().also {canvas.getMatrix(it)}
            else IconGlassHook.previewCaptureMatrix(canvas) ?: return false
        scene.elements.add(Element(matrix,Rect(canvas.clipBounds),render))
        return true
    }
    fun install(loader:ClassLoader){
        val utils=XposedHelpers.findClassIfExists("com.oplus.basecommon.util.BitmapUtils",loader) ?: return
        val floating=XposedHelpers.findClassIfExists("com.android.launcher3.anim.floatingdrawable.IconDrawableView",loader)
        if(floating!=null)ModuleHooks.hookAllMethods(floating,"onDraw",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!PrefsHelper.enabled || !PrefsHelper.backdropEnabled)return
                val original=NativeIconAccess.field(param.thisObject,"originalIconDrawable") as? BitmapDrawable ?: return
                val scene=drawableScenes[original] ?: scenes[original.bitmap] ?: return
                if(!scene.guardCorners)return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                // This is already the complete composed cluster. Stock
                // IconDrawableView additionally extrudes bitmap edge strips
                // behind it, producing a second square/flat plate. Keep the
                // stock drawable bounds and animation, draw the scene once.
                if(drawLive(original,canvas))param.result=null
            }
        })
        // A large closed folder is also a composed snapshot, but is not a
        // ShortcutContainer/GridRecyclerView. Capture its page materials via
        // the inherited View.draw entry without changing its native corners.
        ModuleHooks.findAndHookMethod(View::class.java,"draw",Canvas::class.java,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                if(target.get()!==view || !view.javaClass.name.contains("FolderIcon"))return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                if(drawHardware(view,canvas))param.result=null
            }
        })
        ModuleHooks.hookAllMethods(BitmapDrawable::class.java,"draw",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val drawable=param.thisObject as? BitmapDrawable ?: return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                try {
                    if(drawLive(drawable,canvas))param.result=null
                }catch(t:Throwable){
                    if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("Transition","Live scene unavailable; complete native bitmap retained",t)
                }
            }
        })
        ModuleHooks.hookAllMethods(utils,"convertViewToBitmapInMainExecutor",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){(param.result as? Bitmap)?.let {captures[it]=true}}
        })
        ModuleHooks.hookAllMethods(utils,"convertBitmapToMutable",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                val source=param.args.firstOrNull() as? Bitmap ?: return
                val result=param.result as? Bitmap ?: return
                if(captures[source]==true)captures[result]=true
                scenes[source]?.let {scenes[result]=it}
            }
        })
        val recorder=com.cosmod.launcherglass.util.LauncherMethodResolver.snapshotRecorder(utils,View::class.java) ?: return
        ModuleHooks.hookMethod(recorder,object:XC_MethodHook(){
            @Suppress("UNCHECKED_CAST")
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.args.lastOrNull() as? View ?: return
                val future=param.args.getOrNull(3) as? CompletableFuture<Any?> ?: return
                param.setObjectExtra("priorTarget",target.get());param.setObjectExtra("priorScene",pending.get())
                param.setObjectExtra("sceneCapture",true)
                target.set(view);pending.remove()
                // Publish the recipe before waking the caller waiting for the
                // Bitmap, including when that caller is on another thread.
                param.args[3]=object:CompletableFuture<Any?>(){
                    override fun complete(value:Any?):Boolean {
                        if(value is Bitmap)pending.get()?.takeIf {it.elements.isNotEmpty()}?.let {
                            captures[value]=true;scenes[value]=it
                            if(logs++<12)GlassLogger.i("Transition","Live cluster scene: ${it.width}x${it.height}, elements=${it.elements.size}")
                        }
                        return future.complete(value)
                    }
                    override fun completeExceptionally(error:Throwable)=future.completeExceptionally(error)
                }
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("sceneCapture")!=true)return
                target.set(param.getObjectExtra("priorTarget") as? View)
                pending.set(param.getObjectExtra("priorScene") as? Scene)
            }
        })
    }
    fun isViewCapture(drawable:Drawable)=((drawable as? BitmapDrawable)?.bitmap)?.let {captures[it]==true} ?: false
    /** Record native badges/labels once and retain exact material geometry as GPU recipes. */
    fun drawHardware(view:View,canvas:Canvas):Boolean {
        if(!PrefsHelper.backdropEnabled || (PrefsHelper.colorOnly && FolderSettings.style().colorOnly))return false
        if(target.get()!==view || recording.get()==true || canvas.isHardwareAccelerated || !PrefsHelper.enabled)return false
        if(view.width<=0 || view.height<=0)return false
        val key=source(view)
        cached[key]?.takeIf {it.scene.width==view.width && it.scene.height==view.height &&
            !it.bitmap.isRecycled && it.scene.adornments?.isRecycled==false}?.let {
            pending.set(it.scene)
            android.os.Trace.beginSection("CherryPick.transitionScene.cached")
            try {canvas.drawBitmap(it.bitmap,0f,0f,null)}finally{android.os.Trace.endSection()}
            return true
        }
        val scene=Scene(view.width,view.height)
        scene.guardCorners=view.javaClass.name.contains("GridRecyclerView")
        scene.materialClip=MultiIconMaterialHook.contour(view)?.takeIf {it.template==null}?.path(view)
        recording.set(true);pending.set(scene)
        android.os.Trace.beginSection("CherryPick.transitionScene")
        try {
            // Keep the original complete BitmapDrawable and all OEM size/span
            // metadata. Live rendering replaces only its draw, never its type
            // or the inputs used by native 1x2/2x1 morphing and edge extension.
            // Record the hierarchy once. The material commands are retained
            // separately. The complete OEM bitmap can be composed from these
            // commands without traversing the native hierarchy a second time.
            val node=RenderNode("CherryPick.clusterArtwork").apply {setPosition(0,0,scene.width,scene.height)}
            val artwork=node.beginRecording(scene.width,scene.height)
            scene.canvas=artwork
            try {view.draw(artwork)}finally{scene.canvas=null;node.endRecording()}
            if(scene.elements.isEmpty()) {
                pending.remove()
                // A launcher version without captured material commands must
                // keep its complete native drawing, including GPU-only icons.
                val image=try {rasterize(node,scene.width,scene.height)}finally{node.discardDisplayList()} ?: return false
                canvas.drawBitmap(image,0f,0f,null)
                return true
            }
            // Freeze native artwork before retaining it: child RenderNodes
            // belong to launcher Views and can be cleared/rebound mid-flight.
            // Keeping that live hierarchy here would bring back missing icons.
            scene.adornments=try {hardwareBitmap(node,scene.width,scene.height)}finally{node.discardDisplayList()}
                ?: run {pending.remove();return false}
            val full=RenderNode("CherryPick.clusterSnapshot").apply {setPosition(0,0,scene.width,scene.height)}
            val complete=full.beginRecording(scene.width,scene.height)
            try {renderContents(scene,scene.adornments,complete,Rect(0,0,scene.width,scene.height),255)}finally{full.endRecording()}
            val image=try {rasterize(full,scene.width,scene.height)}finally{full.discardDisplayList()}
                ?: run {pending.remove();return false}
            scene.nativeMask=image
            if(cached.values.sumOf {it.bitmap.allocationByteCount.toLong()+(it.scene.adornments?.allocationByteCount ?: 0)}>12L*1024*1024)cached.clear()
            cached[key]=Cached(scene,image,view.tag)
            flattenEdges(image)
            canvas.drawBitmap(image,0f,0f,null)
            return true
        }finally{recording.remove();android.os.Trace.endSection()}
    }
    private fun renderScene(scene:Scene,bitmap:Bitmap,canvas:Canvas,bounds:Rect,alpha:Int){
        // The original complete native buffer stays below the live child.
        // Do not composite another antialiased edge over its corner coverage:
        // different buffer resolutions otherwise shimmer as the surface scales.
        if(scene.guardCorners && NativeSurfaceLive.isSampling && canvas.isHardwareAccelerated && android.os.Build.VERSION.SDK_INT>=33){
            val node=scene.interior ?: RenderNode("CherryPick.clusterInterior").apply {
                setPosition(0,0,scene.width,scene.height)
                val shader=RuntimeShader(INTERIOR)
                shader.setInputShader("nativeMask",BitmapShader(scene.nativeMask ?: bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP))
                shader.setFloatUniform("hasNativeMask",if(scene.nativeMask!=null)1f else 0f)
                setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader,"content"))
                scene.interior=this
            }
            val recording=node.beginRecording(scene.width,scene.height)
            val base=Matrix().apply {setRectToRect(RectF(0f,0f,scene.width.toFloat(),scene.height.toFloat()),RectF(bounds),Matrix.ScaleToFit.FILL)}
            try {NativeSurfaceLive.withSampleTransform(base){renderContents(scene,bitmap,recording,Rect(0,0,scene.width,scene.height),255)}}
            finally{node.endRecording()}
            val saved=canvas.save()
            try {canvas.concat(base);node.alpha=alpha/255f;canvas.drawRenderNode(node)}finally{canvas.restoreToCount(saved)}
            return
        }
        renderContents(scene,bitmap,canvas,bounds,alpha)
    }
    private const val INTERIOR="""
        uniform shader content;
        uniform shader nativeMask;
        uniform float hasNativeMask;
        half4 main(float2 p) {
            half4 c=content.eval(p);
            // The native layer supplies the original fractional edge alpha.
            // Use the native snapshot's exact silhouette/radius as the upper bound.
            // A downsampled material mask must never expose glass outside it.
            return c.a < .995 || (hasNativeMask > .5 && nativeMask.eval(p).a < .995) ? half4(0.0) : c;
        }
    """
    private fun renderContents(scene:Scene,bitmap:Bitmap?,canvas:Canvas,bounds:Rect,alpha:Int){
        val outer=if(alpha<255)canvas.saveLayerAlpha(RectF(bounds),alpha)else canvas.save()
        try {
            val base=Matrix().apply {setRectToRect(RectF(0f,0f,scene.width.toFloat(),scene.height.toFloat()),RectF(bounds),Matrix.ScaleToFit.FILL)}
            canvas.concat(base)
            canvas.clipRect(0,0,scene.width,scene.height)
            val materialSave=canvas.save()
            scene.materialClip?.let {canvas.clipPath(it)}
            try {
                for(element in scene.elements){
                    val saved=canvas.save()
                    try {
                        canvas.concat(element.matrix);canvas.clipRect(element.clip)
                        val world=Matrix().apply {setConcat(base,element.matrix)}
                        NativeSurfaceLive.withSampleTransform(world){element.render(canvas)}
                    }finally{canvas.restoreToCount(saved)}
                }
            }finally{canvas.restoreToCount(materialSave)}
            val image=checkNotNull(bitmap){"Native cluster artwork is unavailable"}
            canvas.drawBitmap(com.cosmod.launcherglass.util.WallpaperHelper.forCanvas(image,canvas),null,
                Rect(0,0,scene.width,scene.height),Paint(Paint.FILTER_BITMAP_FLAG))
        }finally{canvas.restoreToCount(outer)}
    }
    /** One full rasterization required by the OEM bitmap snapshot API. */
    private fun rasterize(node:RenderNode,width:Int,height:Int):Bitmap? {
        val hardware=hardwareBitmap(node,width,height) ?: return null
        return try {hardware.copy(Bitmap.Config.ARGB_8888,true)}finally{hardware.recycle()}
    }
    private fun hardwareBitmap(node:RenderNode,width:Int,height:Int):Bitmap? =
        try {XposedHelpers.callStaticMethod(HardwareRenderer::class.java,"createHardwareBitmap",node,width,height)}
            catch(_:Throwable){XposedHelpers.callStaticMethod(Class.forName("android.view.ThreadedRenderer"),"createHardwareBitmap",node,width,height)} as? Bitmap

    private fun flattenEdges(bitmap:Bitmap){
        val width=bitmap.width
        val height=bitmap.height
        if(width<24 || height<24 || !bitmap.isMutable)return
        try {
            val pixels=IntArray(width*height)
            bitmap.getPixels(pixels,0,width,0,0,width,height)
            val band=(0.035f*minOf(width,height)).roundToInt().coerceIn(3,14)
            var changed=false
            for(side in 0 until 4)changed=flattenSide(pixels,width,height,side,band) or changed
            if(changed)bitmap.setPixels(pixels,0,width,0,0,width,height)
        }catch(t:Throwable){
            if(failures.add(t.javaClass.name+":"+t.message))GlassLogger.e("Transition","Snapshot edge flattening skipped",t)
        }
    }

    /** side: 0 top, 1 bottom, 2 left, 3 right; a runs along the side, d inwards. */
    private fun flattenSide(pixels:IntArray,width:Int,height:Int,side:Int,band:Int):Boolean {
        val along=if(side<2)width else height
        val depth=if(side<2)height else width
        fun index(a:Int,d:Int)=when(side){
            0 -> d*width+a
            1 -> (height-1-d)*width+a
            2 -> a*width+d
            else -> a*width+(width-1-d)
        }
        val edges=IntArray(3)
        var found=0
        for(line in intArrayOf(along/3,along/2,along*2/3)){
            for(d in 0 until depth/4){
                if(pixels[index(line,d)].ushr(24)>=128){edges[found++]=d;break}
            }
        }
        if(found<2)return false
        edges.sort(0,found)
        val edge=edges[(found-1)/2]
        if(edge+band>=depth/2)return false
        val red=IntArray(256)
        val green=IntArray(256)
        val blue=IntArray(256)
        var count=0
        for(d in edge+1..edge+band)for(a in along/4 until along*3/4){
            val c=pixels[index(a,d)]
            if(c.ushr(24)<200)continue
            red[(c shr 16) and 255]++;green[(c shr 8) and 255]++;blue[c and 255]++
            count++
        }
        if(count<8)return false
        val r=median(red,count)
        val g=median(green,count)
        val b=median(blue,count)
        for(d in maxOf(0,edge-2)..edge+band){
            val weight=if(d<=edge)1f else {
                val t=(d-edge).toFloat()/band
                1f-t*t*(3f-2f*t)
            }
            if(weight<=0f)continue
            for(a in 0 until along){
                val i=index(a,d)
                val c=pixels[i]
                val alpha=c.ushr(24)
                if(alpha==0)continue
                pixels[i]=Color.argb(alpha,mix((c shr 16) and 255,r,weight),mix((c shr 8) and 255,g,weight),mix(c and 255,b,weight))
            }
        }
        return true
    }

    private fun median(histogram:IntArray,count:Int):Int {
        var seen=0
        for(value in 0 until 256){
            seen+=histogram[value]
            if(seen*2>=count)return value
        }
        return 255
    }

    private fun mix(from:Int,to:Int,weight:Float)=(from+(to-from)*weight).roundToInt().coerceIn(0,255)
}
