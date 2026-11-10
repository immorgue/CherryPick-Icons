package com.cosmod.launcherglass.xposed

import android.appwidget.AppWidgetHostView
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.*
import java.util.WeakHashMap

/** Wallpaper glass below remote photo content; contour above; no volume. */
internal object PhotoWidgetMaterial {
    private val identities=WeakHashMap<View,ColorDrawable>()
    private val hosts=WeakHashMap<View,Boolean>()
    fun onSettingsChanged(){
        hosts.keys.weakSnapshot().forEach {it.invalidate()}
        if(!PrefsHelper.enabled || !PrefsHelper.blurEnabled || !PrefsHelper.photoWidgetBlur){
            for((root,listener) in roots.strongSnapshot())if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnPreDrawListener(listener)
            roots.clear();stamps.clear()
        }
    }
    private val logged=HashSet<String>()
    private val drawMethods=HashSet<java.lang.reflect.Method>()
    private val drawing=ThreadLocal<MutableSet<View>>()
    private val roots=WeakHashMap<View,android.view.ViewTreeObserver.OnPreDrawListener>()
    private val stamps=WeakHashMap<View,Int>()
    private fun content(view:View):View {
        val child=if(view is AppWidgetHostView)(view as? ViewGroup)?.getChildAt(0)
            else try {XposedHelpers.callMethod(view,"getSmartView") as? View}catch(_:Throwable){null}
        // Global Gallery can draw mSnapshot before its smart subtree is laid out.
        return child?.takeIf {it.width>0 && it.height>0} ?: view
    }
    private fun transform(host:View,child:View)=Matrix().apply {
        if(child!==host){setTranslate((child.left-host.scrollX).toFloat(),(child.top-host.scrollY).toFloat());preConcat(child.matrix)}
        if(host !is AppWidgetHostView)try {
            // Match clipSmoothRoundCorner's mask transform, including the OEM
            // press/hover spring, which is not necessarily a View scale property.
            val bounds=XposedHelpers.callMethod(host,"getCardBounds") as Rect
            reset()
            if(child!==host)setTranslate(bounds.left.toFloat(),bounds.top.toFloat())
            if(XposedHelpers.callMethod(host,"hasHoverFlag")==true && child!==host){
                val manager=XposedHelpers.callMethod(host,"getFocusFrameAnimManager")
                if(manager!=null)postConcat(XposedHelpers.callMethod(manager,"getHoverBitmapMatrix",RectF(bounds)) as Matrix)
            }
        }catch(_:Throwable){}
    }
    private fun watch(view:View){
        hosts[view]=true;IconGlassHook.registerMaterialView(view)
        val root=view.rootView
        if(roots.containsKey(root))return
        val weak=java.lang.ref.WeakReference(root)
        val listener=android.view.ViewTreeObserver.OnPreDrawListener {
            if(PrefsHelper.colorOnly)return@OnPreDrawListener true
            val current=weak.get()
            if(current!=null && PrefsHelper.enabled && PrefsHelper.photoWidgetBlur)for(host in hosts.keys.weakSnapshot()){
                if(host.rootView!==current || !host.isShown)continue
                val child=content(host)
                val values=FloatArray(9);transform(host,child).getValues(values)
                val stamp=31*(31*values.contentHashCode()+child.width)+child.height
                if(stamps.put(host,stamp)!=stamp)host.invalidate()
            }
            true
        }
        roots[root]=listener;root.viewTreeObserver.addOnPreDrawListener(listener)
    }
    private data class PhotoSource(val info:Any?,val provider:android.content.ComponentName?,val cardType:Int?)
    private fun source(view:View):PhotoSource? {
        if(view !is AppWidgetHostView){
            if(generateSequence(view.javaClass as Class<*>?){it.superclass}.none {it.name=="com.android.launcher3.card.LauncherCardView"})return null
            val info=(try {XposedHelpers.callMethod(view,"getItemInfo")}catch(_:Throwable){null}) ?: view.tag
            val provider=try {info?.let {XposedHelpers.getObjectField(it,"mProviderName")} as? android.content.ComponentName}catch(_:Throwable){null}
                ?: try {info?.let {XposedHelpers.callMethod(it,"getTargetComponent")} as? android.content.ComponentName}catch(_:Throwable){null}
                ?: try {info?.let {XposedHelpers.getObjectField(it,"providerName")} as? android.content.ComponentName}catch(_:Throwable){null}
            val cardType=try {XposedHelpers.callMethod(view,"getCardType") as? Int}catch(_:Throwable){null}
            val result=PhotoProviders.supports(provider?.packageName,provider?.className,cardType,true)
            if(logged.add("candidate:$provider:$cardType"))GlassLogger.i("PhotoWidget","Card candidate: host=${view.javaClass.name}, provider=$provider, type=$cardType, supported=$result")
            return if(result)PhotoSource(info,provider,cardType)else null
        }
        val provider=view.appWidgetInfo?.provider ?: return null
        return if(PhotoProviders.supports(provider.packageName,provider.className,null,false))PhotoSource(null,provider,null)else null
    }
    fun install(loader:ClassLoader){
        val contentHook=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                val source=source(view) ?: return
                watch(view)
                val child=content(view)
                if(child.width<=0 || child.height<=0)return
                val provider=source.provider?.flattenToShortString() ?: "Gallery card/${source.cardType}"
                if(logged.add("host:$provider"))GlassLogger.i("PhotoWidget","Registered host: ${view.javaClass.name}, provider=$provider")
                if(!PrefsHelper.enabled || !PrefsHelper.blurEnabled || !PrefsHelper.photoWidgetBlur)return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                val active=drawing.get() ?: HashSet<View>().also {drawing.set(it)}
                if(!active.add(view))return
                param.setObjectExtra("photoActive",true)
                android.os.Trace.beginSection("CherryPick.photoMaterial")
                try {
                val bounds=if(child!==view)Rect(0,0,child.width,child.height)else try {
                    Rect(XposedHelpers.callMethod(view,"getCardContentBounds") as Rect)
                }catch(_:Throwable){Rect(view.paddingLeft,view.paddingTop,view.width-view.paddingRight,view.height-view.paddingBottom)}
                var radius=minOf(bounds.width(),bounds.height())*.08f
                val outline=Outline()
                try {child.outlineProvider?.getOutline(child,outline);if(outline.radius>0f)radius=outline.radius}catch(_:Throwable){}
                val rightAngle=try {source.info?.let {XposedHelpers.getBooleanField(it,"mIsUseRightAngleCard")}==true}catch(_:Throwable){false}
                val pair=if(view is AppWidgetHostView || rightAngle)null else try {XposedHelpers.callMethod(view,"getCardRadiusAndWeight",view)}catch(_:Throwable){null}
                if(pair!=null)try {radius=(XposedHelpers.callMethod(pair,"a") as Number).toFloat()}catch(_:Throwable){}
                val nativeMask=if(view is AppWidgetHostView)null else try {
                    if(rightAngle)null else {
                        val stored=XposedHelpers.getObjectField(view,"mBitmap") as? Bitmap
                        val cardBounds=XposedHelpers.callMethod(view,"getCardBounds") as Rect
                        (stored?.takeIf {!it.isRecycled && it.width==cardBounds.width()+2 && it.height==cardBounds.height()+2}
                            ?: (XposedHelpers.callMethod(view,"createClipSmoothRoundBitmap") as? Bitmap)?.also {XposedHelpers.setObjectField(view,"mBitmap",it)})
                            ?.takeIf {!it.isRecycled && it.width>2 && it.height>2}
                    }
                }catch(_:Throwable){null}
                // The native alpha mask already defines both material and rim.
                // Construct its vector fallback only when that mask is missing.
                val nativePath=if(view is AppWidgetHostView || nativeMask!=null)null else try {
                    if(rightAngle)Path().apply {addRect(RectF(bounds),Path.Direction.CCW)} else if(pair!=null){
                        val weight=(XposedHelpers.callMethod(pair,"b") as Number).toFloat()
                        Path().also {path ->
                            val manager=XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager",loader)
                            XposedHelpers.callStaticMethod(manager,"createSmoothPath",path,RectF(bounds),radius,weight,Path.Direction.CCW)
                        }
                    }else null
                }catch(_:Throwable){null}
                val identity=identities.getOrPut(child){ColorDrawable(Color.TRANSPARENT)}
                try {
                    val style=GlassStyle.icons().copy(width=PrefsHelper.photoWidgetLiquidWidth,distortion=PrefsHelper.photoWidgetLiquidDistortion,extendedLens=true)
                    val matrix=transform(view,child)
                    val save=canvas.save()
                    val drawn=try {canvas.concat(matrix);SurfaceMaterial.draw(canvas,bounds,child,identity,radius,nativePath,decorate=false,style=style,nativeMask=nativeMask)}finally{canvas.restoreToCount(save)}
                    if(!drawn)return
                    val paint=Paint().apply {
                        alpha=if(PrefsHelper.colorOnly)255 else PrefsHelper.photoWidgetOpacity*255/100
                        if(PrefsHelper.colorOnly || PrefsHelper.photoWidgetTint)colorFilter=photoFilter()
                    }
                    // Enclose the COMPLETE OEM compositor: live children, cached
                    // Picture, preload image and mSnapshot. Treating only the
                    // smart subtree leaves an opaque snapshot above the glass.
                    param.setObjectExtra("photoSave",canvas.saveLayer(null as RectF?,paint))
                    param.setObjectExtra("photoBounds",bounds);param.setObjectExtra("photoRadius",radius);param.setObjectExtra("photoPath",nativePath)
                    param.setObjectExtra("photoMask",nativeMask)
                    param.setObjectExtra("photoTransform",matrix)
                    if(logged.add(provider))GlassLogger.i("PhotoWidget","Complete compositor attached: $provider, bounds=$bounds, nativeMask=${nativeMask!=null}, nativeShape=${nativePath!=null}, radius=$radius")
                }catch(t:Throwable){GlassLogger.e("PhotoWidget","Photo material failed",t)}
                }finally{android.os.Trace.endSection()}
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("photoActive")!=true)return
                drawing.get()?.remove(param.thisObject as? View)
                val save=param.getObjectExtra("photoSave") as? Int ?: return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                android.os.Trace.beginSection("CherryPick.photoContour")
                try {
                    canvas.restoreToCount(save)
                    val bounds=param.getObjectExtra("photoBounds") as Rect
                    val radius=param.getObjectExtra("photoRadius") as Float
                    val outer=canvas.save()
                    try {
                        canvas.concat(param.getObjectExtra("photoTransform") as Matrix)
                        SurfaceMaterial.contour(canvas,bounds,radius,param.getObjectExtra("photoPath") as? Path,param.getObjectExtra("photoMask") as? Bitmap)
                    }finally{canvas.restoreToCount(outer)}
                }catch(t:Throwable){GlassLogger.e("PhotoWidget","Photo contour failed",t)}
                finally{android.os.Trace.endSection()}
            }
        }
        for(name in listOf("com.android.launcher3.card.TitleCardView","com.android.launcher3.card.CommonCardView","com.android.launcher3.card.EngineCardView","com.android.launcher3.card.LauncherCardView","com.android.launcher3.widget.LauncherAppWidgetHostView","com.android.launcher3.widget.CustomLauncherAppWidgetHostView")){
                var cls:Class<*>?=XposedHelpers.findClassIfExists(name,loader)
                while(cls!=null){
                    val method=cls.declaredMethods.firstOrNull {it.name=="dispatchDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))}
                    if(method!=null){
                        if(drawMethods.add(method))ModuleHooks.hookMethod(method,contentHook)
                        break
                    }
                    cls=cls.superclass
                }
        }
    }
    private var filterColor=0
    private var tintFilter:ColorMatrixColorFilter?=null
    private fun photoFilter():ColorMatrixColorFilter {
        val color=PrefsHelper.iconPalette.foreground
        if(tintFilter==null || filterColor!=color){
            filterColor=color
            val peak=maxOf(Color.red(color),Color.green(color),Color.blue(color),1).toFloat()
            val rgb=floatArrayOf(Color.red(color)/peak,Color.green(color)/peak,Color.blue(color)/peak)
            val matrix=ColorMatrix().apply {setSaturation(0f)}
            matrix.postConcat(ColorMatrix(floatArrayOf(rgb[0],0f,0f,0f,0f, 0f,rgb[1],0f,0f,0f, 0f,0f,rgb[2],0f,0f, 0f,0f,0f,1f,0f)))
            tintFilter=ColorMatrixColorFilter(matrix)
        }
        return tintFilter!!
    }
}
