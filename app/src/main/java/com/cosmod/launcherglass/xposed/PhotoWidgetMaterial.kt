package com.cosmod.launcherglass.xposed

import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.bridge.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Glass behind widget content, with a separate compositor for photo widgets. */
internal object PhotoWidgetMaterial {
    private val identities=WeakHashMap<View,ColorDrawable>()
    private val plateNodes = WeakHashMap<View, RenderNode>()
    private val flightPlateNodes = WeakHashMap<View, RenderNode>()
    private val hosts=WeakHashMap<View,Boolean>()
    private fun enabled() = PrefsHelper.enabled && PrefsHelper.blurEnabled && PrefsHelper.widgetGlassEnabled
    private fun enabled(kind: WidgetKind) = when (kind) {
        WidgetKind.CLOCK -> PrefsHelper.clockWidget.enabled
        WidgetKind.WEATHER -> PrefsHelper.weatherWidget.enabled
        WidgetKind.PHOTO, WidgetKind.OTHER -> PrefsHelper.photoWidgetBlur
    }
    internal fun flightHost(source: View?): View? {
        if (!enabled()) return null
        var view = source
        repeat(12) {
            val current = view ?: return null
            if (hosts[current] == true && detections[current]?.let { enabled(it.kind) } == true) return current
            view = current.parent as? View
        }
        return null
    }
    fun onSettingsChanged(){
        for (host in suppressed.keys.weakSnapshot()) {
            if (!enabled() || detections[host]?.let { !enabled(it.kind) } == true) restoreBackground(host)
        }
        hosts.keys.weakSnapshot().forEach {it.invalidate()}
        if(!enabled()){
            WidgetFlight.clear()
            for((root,listener) in roots.strongSnapshot())if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnPreDrawListener(listener)
            roots.clear();stamps.clear()
        }
    }
    private val logged=HashSet<String>()
    private val drawMethods=HashSet<java.lang.reflect.Method>()
    private val drawing=ThreadLocal<MutableSet<View>>()
    private val roots=WeakHashMap<View,android.view.ViewTreeObserver.OnPreDrawListener>()
    private val stamps=WeakHashMap<View,Int>()
    private data class Suppressed(val target: WeakReference<View>, val drawable: WeakReference<Drawable>,
        val alpha: Int, val layer: WidgetBitmapBackdrop.Layer, val reportsAlpha: Boolean,
        val images: List<Pair<WeakReference<Drawable>, Int>>)
    private val suppressed = WeakHashMap<View, List<Suppressed>>()

    private fun restoreBackground(host: View) {
        suppressed.remove(host)?.forEach(::restoreLayer)
    }

    private fun restoreLayer(background: Suppressed) {
        val drawable = background.drawable.get() ?: return
        val owner = drawable.callback
        if (owner == null || owner === background.target.get()) {
            if (!background.reportsAlpha || drawable.alpha == 0) drawable.alpha = background.alpha
            background.images.forEach { (ref, alpha) -> ref.get()?.takeIf { it.alpha == 0 }?.alpha = alpha }
            drawable.invalidateSelf()
        }
    }

    private fun replaceBackgrounds(host: View, backgrounds: List<Background>): List<Suppressed> {
        val old = suppressed[host].orEmpty()
        if (old.size == backgrounds.size && old.indices.all { index ->
                val layer = old[index]
                val target = backgrounds[index].target.get()
                layer.target.get() === target && layer.drawable.get() === target?.background &&
                    (!layer.reportsAlpha || target?.background?.alpha == 0) &&
                    layer.images.all { (image, _) -> image.get()?.alpha == 0 }
            }) return old
        restoreBackground(host)
        return backgrounds.mapNotNull { suppressLayer(it.target.get(), it.layer) }.also {
            if (it.isNotEmpty()) suppressed[host] = it
        }
    }

    private fun suppressLayer(target: View?, layer: WidgetBitmapBackdrop.Layer): Suppressed? {
        val drawable = target?.background
        if (target == null || drawable == null || drawable.alpha == 0 || drawable.callback !== target) return null
        if (drawable.mutate() !== drawable) return null
        // Keep the drawable attached so RemoteViews can still update its color,
        // padding and outline. Changing its alpha invalidates the native draw node.
        val alpha = drawable.alpha
        val images = WidgetBitmapBackdrop.imageLayers(drawable).map { image ->
            (WeakReference(image) to image.alpha).also { image.alpha = 0 }
        }
        drawable.alpha = 0
        drawable.invalidateSelf()
        return Suppressed(WeakReference(target), WeakReference(drawable), alpha, layer, drawable.alpha == 0, images)
    }

    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            restoreBackground(view)
            detections.remove(view)
            plateNodes.remove(view)
            flightPlateNodes.remove(view)
            WidgetFlight.remove(view)
            stamps.remove(view)
            hosts.remove(view)
            view.removeOnAttachStateChangeListener(this)
        }
    }
    private fun content(view:View):View {
        val child=if(view is AppWidgetHostView)(view as? ViewGroup)?.getChildAt(0)
            else try {HookReflection.callMethod(view,"getSmartView") as? View}catch(_:Throwable){null}
        // Global Gallery can draw mSnapshot before its smart subtree is laid out.
        return child?.takeIf {it.width>0 && it.height>0} ?: view
    }
    private fun transform(host:View,child:View)=Matrix().apply {
        if(child!==host){setTranslate((child.left-host.scrollX).toFloat(),(child.top-host.scrollY).toFloat());preConcat(child.matrix)}
        if(host !is AppWidgetHostView)try {
            // Match clipSmoothRoundCorner's mask transform, including the OEM
            // press/hover spring, which is not necessarily a View scale property.
            val bounds=HookReflection.callMethod(host,"getCardBounds") as Rect
            reset()
            if(child!==host)setTranslate(bounds.left.toFloat(),bounds.top.toFloat())
            if(HookReflection.callMethod(host,"hasHoverFlag")==true && child!==host){
                val manager=HookReflection.callMethod(host,"getFocusFrameAnimManager")
                if(manager!=null)postConcat(HookReflection.callMethod(manager,"getHoverBitmapMatrix",RectF(bounds)) as Matrix)
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
            if(current!=null && enabled())for(host in hosts.keys.weakSnapshot()){
                if(hosts[host]!=true)continue
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
    private data class WidgetSource(val info:Any?,val provider:ComponentName?,val cardType:Int?,
        val knownPhoto:Boolean,val kind:WidgetKind)
    private data class Background(val target: WeakReference<View>, val drawable: WeakReference<Drawable>, val layer: WidgetBitmapBackdrop.Layer)
    private data class Detection(val child:WeakReference<View>,val width:Int,val height:Int,
        val source:WidgetSource,val material:WidgetMaterial,val kind:WidgetKind,val backgrounds:List<Background>,
        val checkAgainAt:Long,val settlingUntil:Long)
    private val detections = WeakHashMap<View, Detection>()

    private fun source(view:View):WidgetSource? {
        fun kind(title: CharSequence?, provider: ComponentName?): WidgetKind {
            return sequenceOf(title?.toString(), provider?.className, provider?.packageName)
                .map(WidgetKind::fromName).firstOrNull { it != WidgetKind.OTHER } ?: WidgetKind.OTHER
        }
        if(view !is AppWidgetHostView){
            val info=(try {HookReflection.callMethod(view,"getItemInfo")}catch(_:Throwable){null}) ?: view.tag
            val provider=try {info?.let {HookReflection.getObjectField(it,"mProviderName")} as? ComponentName}catch(_:Throwable){null}
                ?: try {info?.let {HookReflection.callMethod(it,"getTargetComponent")} as? ComponentName}catch(_:Throwable){null}
                ?: try {info?.let {HookReflection.getObjectField(it,"providerName")} as? ComponentName}catch(_:Throwable){null}
            val cardType=try {HookReflection.callMethod(view,"getCardType") as? Int}catch(_:Throwable){null}
            val result=PhotoProviders.supports(provider?.packageName,provider?.className,cardType,true)
            val title = runCatching { (HookReflection.callMethod(view, "getTitle") as? TextView)?.text }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: runCatching { info?.let { HookReflection.getObjectField(it, "title") } as? CharSequence }.getOrNull()
            return if(info!=null || result)WidgetSource(info,provider,cardType,result,kind(title,provider))else null
        }
        val appWidget = view.appWidgetInfo ?: return null
        val provider = appWidget.provider ?: return null
        val title = runCatching { appWidget.loadLabel(view.context.packageManager) }.getOrNull()
        return WidgetSource(null,provider,null,PhotoProviders.supports(provider.packageName,provider.className,null,false),kind(title,provider))
    }

    private fun detect(view: View, child: View): Detection? {
        val now = SystemClock.uptimeMillis()
        val previous = detections[view]?.takeIf { it.child.get() === child && it.width == child.width && it.height == child.height }
        previous?.takeIf { now < it.checkAgainAt && it.backgrounds.all { layer ->
            layer.target.get()?.background === layer.drawable.get()
        } }?.let { return it }
        val source = source(view) ?: return null
        if (child === view && !source.knownPhoto) return null
        val active = suppressed[view].orEmpty().mapNotNull { state -> state.drawable.get()?.let { it to state.layer } }.toMap()
        val result = WidgetContentDetector.inspect(child, source.knownPhoto, source.kind, source.provider?.className, active)
        val backgrounds=result.backgrounds.mapNotNull { backdrop ->
            backdrop.view.background?.let { Background(WeakReference(backdrop.view),WeakReference(it),backdrop.layer) }
        }
        val settlingUntil=previous?.settlingUntil ?: now+750L
        val isClockOrWeather=source.kind==WidgetKind.CLOCK || source.kind==WidgetKind.WEATHER ||
            result.kind==WidgetKind.CLOCK || result.kind==WidgetKind.WEATHER
        // Quick App clocks and weather cards install their drawable after the
        // first layout. An empty result from that first draw is provisional.
        val waitingForBackdrop=isClockOrWeather && backgrounds.isEmpty() && now<settlingUntil
        val delay=if(waitingForBackdrop)80L else 1000L
        if(waitingForBackdrop)view.postInvalidateDelayed(delay)
        return Detection(WeakReference(child),child.width,child.height,source,result.material,result.kind,
            backgrounds,now+delay,settlingUntil).also { detections[view]=it }
    }

    fun install(loader:ClassLoader){
        WidgetFlight.install(loader)
        val cardClass=HookReflection.findClassIfExists("com.android.launcher3.card.LauncherCardView",loader)
        val contentHook=object:HookCallback(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                if(view !is AppWidgetHostView && cardClass?.isInstance(view)!=true)return
                if(!view.isAttachedToWindow || !view.isShown)return
                if(!hosts.containsKey(view)){
                    // A picker can use the same host classes as the workspace.
                    if(generateSequence(view.parent){(it as? View)?.parent}.none {
                            it.javaClass.name.startsWith("com.android.launcher3.") &&
                                it.javaClass.name.substringAfterLast('.') in workspaceAncestors
                        })return
                    hosts[view]=false
                    view.addOnAttachStateChangeListener(attachment)
                }
                if(!enabled())return
                val child=content(view)
                if(child.width<=0 || child.height<=0)return
                val detected=detect(view,child) ?: run { restoreBackground(view); hosts[view]=false; return }
                val source=detected.source
                val provider=source.provider?.flattenToShortString() ?: "Card/${source.cardType}"
                if(GlassLogger.enabled && logged.add("$provider/${detected.kind}/${detected.material}"))
                    GlassLogger.i("PhotoWidget","$provider: ${detected.kind}/${detected.material}, backgrounds=${detected.backgrounds.size}")
                if(detected.material==WidgetMaterial.NATIVE || !enabled(detected.kind)){
                    restoreBackground(view)
                    hosts[view]=false
                    return
                }
                watch(view)
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                val active=drawing.get() ?: HashSet<View>().also {drawing.set(it)}
                if(!active.add(view))return
                param.setObjectExtra("photoActive",true)
                android.os.Trace.beginSection("CherryPick.photoMaterial")
                try {
                val bounds=if(child!==view)Rect(0,0,child.width,child.height)else try {
                    Rect(HookReflection.callMethod(view,"getCardContentBounds") as Rect)
                }catch(_:Throwable){Rect(view.paddingLeft,view.paddingTop,view.width-view.paddingRight,view.height-view.paddingBottom)}
                var radius=minOf(bounds.width(),bounds.height())*.08f
                val outline=Outline()
                try {
                    val hasRootBackground = detected.backgrounds.any { it.target.get() === child }
                    child.outlineProvider?.getOutline(child,outline)
                    if(child is ViewGroup && child.clipToOutline && outline.getRect(outlineBounds) && !outlineBounds.isEmpty && child!==view)
                        bounds.intersect(outlineBounds)
                    if(outline.radius>=0f && (outline.radius>0f || hasRootBackground))radius=outline.radius
                }catch(_:Throwable){}
                val rightAngle=try {source.info?.let {HookReflection.getBooleanField(it,"mIsUseRightAngleCard")}==true}catch(_:Throwable){false}
                val pair=if(view is AppWidgetHostView || rightAngle)null else try {HookReflection.callMethod(view,"getCardRadiusAndWeight",view)}catch(_:Throwable){null}
                if(pair!=null)try {radius=(HookReflection.callMethod(pair,"a") as Number).toFloat()}catch(_:Throwable){}
                val nativeMask=if(view is AppWidgetHostView)null else try {
                    if(rightAngle)null else {
                        val stored=HookReflection.getObjectField(view,"mBitmap") as? Bitmap
                        val cardBounds=HookReflection.callMethod(view,"getCardBounds") as Rect
                        (stored?.takeIf {!it.isRecycled && it.width==cardBounds.width()+2 && it.height==cardBounds.height()+2}
                            ?: (HookReflection.callMethod(view,"createClipSmoothRoundBitmap") as? Bitmap)?.also {HookReflection.setObjectField(view,"mBitmap",it)})
                            ?.takeIf {!it.isRecycled && it.width>2 && it.height>2}
                    }
                }catch(_:Throwable){null}
                // The native alpha mask already defines both material and rim.
                // Construct its vector fallback only when that mask is missing.
                val nativePath=if(view is AppWidgetHostView || nativeMask!=null)null else try {
                    if(rightAngle)Path().apply {addRect(RectF(bounds),Path.Direction.CCW)} else if(pair!=null){
                        val weight=(HookReflection.callMethod(pair,"b") as Number).toFloat()
                        Path().also {path ->
                            val manager=HookReflection.findClass("com.android.launcher3.util.SmoothRoundedManager",loader)
                            HookReflection.callStaticMethod(manager,"createSmoothPath",path,RectF(bounds),radius,weight,Path.Direction.CCW)
                        }
                    }else null
                }catch(_:Throwable){null}
                val identity=identities.getOrPut(child){ColorDrawable(Color.TRANSPARENT)}
                try {
                    val base = GlassStyle.icons()
                    val opacity = when (detected.kind) {
                        WidgetKind.CLOCK -> PrefsHelper.clockWidget.opacity
                        WidgetKind.WEATHER -> PrefsHelper.weatherWidget.opacity
                        else -> base.opacity
                    }
                    val style = WidgetGlassSettings.style(base, detected.kind, PrefsHelper.iconPalette.autoLook, opacity,
                        PrefsHelper.photoWidgetLiquidWidth, PrefsHelper.photoWidgetLiquidDistortion)
                    val matrix=transform(view,child)
                    val snapshot = TransitionCapture.isSnapshotTarget(view) || TransitionCapture.captures(canvas) ||
                        NativeSurfaceLive.materialSource != null
                    val glassAlpha = WidgetFlight.opacity(view, snapshot, style)
                    val save=canvas.save()
                    val drawn = try {
                        canvas.concat(matrix)
                        if (glassAlpha < 1f) {
                            val tint = style.plateColor ?: PrefsHelper.plateColor()
                            if (canvas.isHardwareAccelerated && !snapshot && !bounds.isEmpty) {
                                val flat = flightPlateNodes.getOrPut(view) {
                                    RenderNode("CherryPick.widgetFlightPlate").apply { setForceDarkAllowed(false) }
                                }
                                flat.setPosition(0, 0, maxOf(bounds.right, 1), maxOf(bounds.bottom, 1))
                                flat.alpha = 1f - glassAlpha
                                try {
                                    SurfaceMaterial.drawWidgetFlight(flat.beginRecording(), bounds, radius, nativePath,
                                        nativeMask, tint, style.opacity, 1f)
                                } finally { flat.endRecording() }
                                canvas.drawRenderNode(flat)
                            } else {
                                SurfaceMaterial.drawWidgetFlight(canvas, bounds, radius, nativePath, nativeMask,
                                    tint, style.opacity, 1f - glassAlpha)
                            }
                        }
                        fun draw(target: Canvas) = SurfaceMaterial.draw(target, bounds, child, identity, radius, nativePath,
                            decorate = false, style = style, nativeMask = nativeMask)
                        if (glassAlpha == 0f) true
                        else if (canvas.isHardwareAccelerated && !bounds.isEmpty && !DragPreviewMaterial.captures(canvas)) {
                            // OEM force-dark can remap these paint colors inside a Quick App host.
                            // Scope the exemption to our plate and leave the widget content native.
                            val node = plateNodes.getOrPut(view) {
                                RenderNode("CherryPick.widgetPlate").apply { setForceDarkAllowed(false) }
                            }
                            node.setPosition(0, 0, maxOf(bounds.right, 1), maxOf(bounds.bottom, 1))
                            node.alpha = glassAlpha
                            val result = try { draw(node.beginRecording()) } finally { node.endRecording() }
                            if (result) canvas.drawRenderNode(node)
                            result
                        } else if (glassAlpha < 1f) {
                            val layer = canvas.saveLayerAlpha(RectF(bounds), (glassAlpha * 255f).toInt())
                            try { draw(canvas) } finally { canvas.restoreToCount(layer) }
                        } else draw(canvas)
                    } finally { canvas.restoreToCount(save) }
                    if (!drawn && glassAlpha == 1f) { restoreBackground(view); return }
                    val backgrounds = replaceBackgrounds(view,detected.backgrounds)
                    // The light native dial can have black marks, hands and digits.
                    // Lift their contrast on dark glass without changing its shared tint.
                    if (detected.kind == WidgetKind.CLOCK && backgrounds.any { it.layer.lightPlate } &&
                        Color.luminance(style.plateColor ?: Color.BLACK) < .16f &&
                        (style.opacity >= 35 || com.cosmod.launcherglass.util.AutoIconPalette.wallpaper(view.context).luminance < .42f)) {
                        val layerBounds = RectF(bounds).also { matrix.mapRect(it) }
                        param.setObjectExtra("widgetContentSave", canvas.saveLayer(layerBounds, clockContrast))
                    }
                    if (backgrounds.any { it.layer.detail != null }) {
                        val detailSave = canvas.save()
                        try {
                            canvas.concat(matrix)
                            for (layer in backgrounds) {
                                val detail = layer.layer.detail ?: continue
                                val rect = layer.drawable.get()?.bounds?.takeUnless { it.isEmpty }
                                canvas.drawBitmap(detail, null, rect ?: bounds, detailPaint)
                            }
                        } finally { canvas.restoreToCount(detailSave) }
                    }
                    if(detected.material==WidgetMaterial.PHOTO){
                        val paint=Paint().apply {
                            alpha=if(PrefsHelper.colorOnly)255 else PrefsHelper.photoWidgetOpacity*255/100
                            if(PrefsHelper.colorOnly || PrefsHelper.photoWidgetTint)colorFilter=photoFilter()
                        }
                        // Wrap the entire OEM photo compositor, including its cached snapshot.
                        param.setObjectExtra("widgetContentSave",canvas.saveLayer(null as RectF?,paint))
                    }
                    param.setObjectExtra("photoBounds",bounds);param.setObjectExtra("photoRadius",radius);param.setObjectExtra("photoPath",nativePath)
                    param.setObjectExtra("photoMask",nativeMask)
                    param.setObjectExtra("photoTransform",matrix)
                    param.setObjectExtra("widgetGlassAlpha",glassAlpha)
                    if(logged.add(provider))GlassLogger.i("PhotoWidget","Complete compositor attached: $provider, bounds=$bounds, nativeMask=${nativeMask!=null}, nativeShape=${nativePath!=null}, radius=$radius")
                }catch(t:Throwable){GlassLogger.e("PhotoWidget","Photo material failed",t)}
                }finally{android.os.Trace.endSection()}
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("photoActive")!=true)return
                drawing.get()?.remove(param.thisObject as? View)
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                (param.getObjectExtra("widgetContentSave") as? Int)?.let(canvas::restoreToCount)
                val bounds=param.getObjectExtra("photoBounds") as? Rect ?: return
                android.os.Trace.beginSection("CherryPick.photoContour")
                try {
                    val radius=param.getObjectExtra("photoRadius") as Float
                    val alpha = param.getObjectExtra("widgetGlassAlpha") as? Float ?: 1f
                    if (alpha <= 0f) return
                    val outer=canvas.save()
                    try {
                        canvas.concat(param.getObjectExtra("photoTransform") as Matrix)
                        SurfaceMaterial.contour(canvas,bounds,radius,param.getObjectExtra("photoPath") as? Path,
                            param.getObjectExtra("photoMask") as? Bitmap,alpha)
                    }finally{canvas.restoreToCount(outer)}
                }catch(t:Throwable){GlassLogger.e("PhotoWidget","Photo contour failed",t)}
                finally{android.os.Trace.endSection()}
            }
        }
        for(name in listOf("com.android.launcher3.card.TitleCardView","com.android.launcher3.card.CommonCardView","com.android.launcher3.card.EngineCardView","com.android.launcher3.card.LauncherCardView","com.android.launcher3.widget.LauncherAppWidgetHostView","com.android.launcher3.widget.CustomLauncherAppWidgetHostView")){
                var cls:Class<*>?=HookReflection.findClassIfExists(name,loader)
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
    private val workspaceAncestors = setOf("ShortcutAndWidgetContainer", "CellLayout", "Workspace", "OplusWorkspace")
    private val outlineBounds = Rect()
    private val detailPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clockContrast = Paint().apply {
        colorFilter = LightingColorFilter(0x00808080, 0x00777777)
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
