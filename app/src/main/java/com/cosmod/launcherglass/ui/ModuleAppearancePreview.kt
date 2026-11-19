package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.os.Bundle
import android.view.View
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.SettingsProvider as Keys
import com.cosmod.launcherglass.util.*
import com.cosmod.launcherglass.xposed.*
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** Uses the launcher material renderers with a local sample layout. */
internal class ModuleAppearancePreview(
    context: Context,
    private val prefs: SharedPreferences,
    private val worker: Executor,
    private val scope: String,
    private val iconGrid: Boolean = false,
    private val materialMode: Int? = null,
    private val materialSample: Boolean = false,
    private val automaticLook: Int? = null
) : View(context) {
    private data class Request(val source:Bitmap,val processing:ScopedGlassSettings.Processing,val gpu:Boolean)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconOutline = Path()
    private val node=RenderNode("CherryPick.settingsPreview")
    private val dockRenderer=DockRenderer()
    private val emptyGlyph=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
    private val widgetMasks = android.util.LruCache<Pair<Int, Int>, Bitmap>(4)
    private val widgetInk = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoPlaceholder by lazy { requireNotNull(context.getDrawable(R.drawable.ic_module_image)).mutate() }
    private val defaultFolderMask by lazy {
        Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawPath(ContinuousCorners.path(Path(), RectF(0f, 0f, 160f, 160f), 28f), paint)
        }
    }
    private var folderMask: Bitmap? = null
    private var folderShapeSource:String?=null
    private var wallpaper:Bitmap?=null
    private var processed:Map<Request,Bitmap> = emptyMap()
    private var pending:Set<Request> = emptySet()
    private var paletteOverride:IconPalette?=null
    private var dirty=true
    private var surfaceCorner: Float? = null
    private var icons:List<AppearanceIcons.Sample> = emptyList()
    private var iconGlass=GlassStyle.icons()
    private var iconProcessing=ScopedGlassSettings.Processing(0,0,0,50,false)
    private var saturation=100
    private var tintedPalette: IconPalette? = null
    private val tintedGlyphs = java.util.IdentityHashMap<Bitmap, Bitmap>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    var onPreviewChanged: (() -> Unit)? = null

    init {contentDescription=context.getString(R.string.ui_live_preview)}
    fun setSource(bitmap: Bitmap?, samples: List<AppearanceIcons.Sample>) {
        if (wallpaper !== bitmap) processed = emptyMap()
        wallpaper = bitmap
        icons = samples
        refresh()
    }
    fun setLoadedSource(bitmap: Bitmap?, samples: List<AppearanceIcons.Sample>, app: AppearanceIcons.Sample) =
        setSource(bitmap, if (materialSample) listOf(app) else samples)
    fun refresh(){paletteOverride=null;redraw()}
    fun setCornerRadius(radius: Float) {
        if (surfaceCorner == radius) return
        surfaceCorner = radius
        redraw()
    }
    fun setPaletteOverride(value:IconPalette){paletteOverride=value;redraw()}
    private fun redraw(){dirty=true;invalidate();onPreviewChanged?.invoke()}
    private fun n(key:String)=prefs.getInt(key,SettingsDefaults.integer(key))
    private fun b(key:String)=prefs.getBoolean(key,SettingsDefaults.boolean(key))
    private fun desktopFeature(key:String)=b(ModuleFeatures.DESKTOP) && b(key)

    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){dirty=true}

    fun tileBitmap(w: Int, h: Int): Bitmap {
        // These views are dedicated to Module tiles and are drawn offscreen.
        layout(0, 0, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            configureMaterials()
            scene(Canvas(it))
        }
    }

    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(width<=0 || height<=0)return
        if (!canvas.isHardwareAccelerated) {
            configureMaterials()
            scene(canvas)
            return
        }
        if(dirty || !node.hasDisplayList()) {
            configureMaterials()
            node.setPosition(0,0,width,height)
            val recording=node.beginRecording(width,height)
            try {scene(recording)} finally {node.endRecording()}
            dirty=false
        }
        canvas.drawRenderNode(node)
    }

    // The Activity and the injected launcher have separate process state.
    // Initialise only the material values here, without installing hooks.
    private fun configureMaterials(){
        val nativeShape=prefs.getString(FolderPreviewShape.KEY,null)
        if(nativeShape!=folderShapeSource){
            folderShapeSource=nativeShape
            folderMask = nativeShape?.let { encoded -> runCatching {
                val bytes=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            }.getOrNull() }
        }
        val dark=com.cosmod.launcherglass.util.IconColorTheme.isDark(n(Keys.KEY_ICON_COLOR_THEME),
            prefs.getBoolean(Keys.KEY_NATIVE_ICON_DARK,IconPalette.isSystemDark(context)))
        val keys=Keys.colorKeys(dark)
        val clear=n(Keys.KEY_ICON_APPEARANCE)==0
        val appearance = Bundle().apply {
            AutoIconStyle.defaults.forEach { (key, fallback) -> putInt(key, prefs.getInt(key, fallback)) }
            automaticLook?.let { putInt(AutoIconStyle.ENGINE,1);putInt(AutoIconStyle.LOOK,it) }
        }
        PrefsHelper.iconPalette=paletteOverride ?: AutoIconPalette.resolve(context, appearance,
            prefs.getBoolean(Keys.KEY_NATIVE_ICON_DARK,IconPalette.isSystemDark(context))) ?: IconPalette.resolve(context,n(Keys.KEY_COLOR_MODE),n(keys[0]),n(keys[1]),
            if(clear)n(Keys.KEY_CLEAR_BRIGHTNESS)else n(keys[2]),dark,clear)
        val design=b(Keys.KEY_DESIGN_ENABLED)
        PrefsHelper.iconStroke=design && b(Keys.KEY_ICON_STROKE)
        PrefsHelper.strokeOpacity=n(Keys.KEY_STROKE_OPACITY)
        PrefsHelper.glyphStroke=design && b(Keys.KEY_GLYPH_STROKE)
        PrefsHelper.glyphStrokeWidth=n(Keys.KEY_GLYPH_STROKE_WIDTH)
        PrefsHelper.glyphStrokeOpacity=n(Keys.KEY_GLYPH_STROKE_OPACITY)
        PrefsHelper.glyphStrokeBrightness=n(Keys.KEY_GLYPH_STROKE_BRIGHTNESS)
        PrefsHelper.glyphBottomShade=if(design && b(Keys.KEY_GLYPH_BOTTOM_SHADE_ENABLED))
            n(Keys.KEY_GLYPH_BOTTOM_SHADE).coerceIn(0,100)else 0
        PrefsHelper.glyphShadow=design && b(Keys.KEY_GLYPH_SHADOW)
        PrefsHelper.shadowOpacity=n(Keys.KEY_SHADOW_OPACITY)
        PrefsHelper.gradientOpacity=if(design && b(Keys.KEY_VOLUME_ENABLED))n(Keys.KEY_GRADIENT_OPACITY)else 0
        val tone=b(Keys.KEY_BLUR_TONE_ENABLED)
        val vibrancy=if(tone)n(Keys.KEY_BLUR_VIBRANCY)else 0
        saturation=if(tone)n(Keys.KEY_BLUR_SATURATION)else 100
        val lightening=if(tone)n(Keys.KEY_BLUR_LIGHTENING)else 50
        PrefsHelper.bgOpacity=if(tone)n(Keys.KEY_BG_OPACITY)else 0
        PrefsHelper.iconPalette.takeIf { it.autoLook >= 0 }?.let {
            PrefsHelper.bgOpacity=AutoIconStyle.opacity(it.autoLook,it.autoDark)
        }
        PrefsHelper.blurStyle=materialMode ?: MaterialMode.normalize(n(Keys.KEY_BLUR_STYLE))
        PrefsHelper.blurRadius=if(b(Keys.KEY_BLUR_RADIUS_ENABLED))n(Keys.KEY_BLUR_RADIUS)else if(materialSample)20 else 0
        PrefsHelper.blurQuality=if(b(Keys.KEY_DEVELOPER_MODE))n(Keys.KEY_BLUR_QUALITY)else 0
        PrefsHelper.gpuEffects=!b(Keys.KEY_DEVELOPER_MODE) || b(Keys.KEY_GPU_EFFECTS)
        PrefsHelper.blurColorFilter=PrefsHelper.backdropFilter(vibrancy,PrefsHelper.iconPalette)
        val bundle=Bundle().apply {
            for((key,value) in prefs.all)when(value){is Int->putInt(key,value);is Boolean->putBoolean(key,value)}
        }
        PrefsHelper.kyant=KyantOptics.read(bundle)
        if(PrefsHelper.iconPalette.autoLook>=0){
            // Automatic presets do not overwrite or inherit the custom color/blur controls.
            PrefsHelper.blurStyle=AutoIconStyle.materialMode(appearance.getInt(AutoIconStyle.MATERIAL,1),android.os.Build.VERSION.SDK_INT>=33)
            PrefsHelper.blurRadius=10
            PrefsHelper.kyant=KyantOptics.automatic
            saturation=100
            PrefsHelper.blurColorFilter=PrefsHelper.backdropFilter(100,PrefsHelper.iconPalette)
        }
        val auto=PrefsHelper.iconPalette.autoLook>=0
        PrefsHelper.glassFinish=GlassFinish(
            MaterialMode.blurTransparency(PrefsHelper.blurStyle,auto,b(Keys.KEY_DEVELOPER_MODE),n(Keys.KEY_BLUR_TRANSPARENCY)),
            (auto || b(Keys.KEY_DEVELOPER_MODE)) && android.os.Build.VERSION.SDK_INT>=33 && b(Keys.KEY_HAZE_ENABLED))
        FolderSettings.update(bundle)
        DockSettings.update(bundle)
        iconGlass=GlassStyle.icons().copy(plateColor=PrefsHelper.plateColor(PrefsHelper.iconPalette.background,saturation))
        iconProcessing=ScopedGlassSettings.Processing(iconGlass.radius,PrefsHelper.blurQuality,if(auto)100 else vibrancy,
            if(auto)50 else lightening,iconGlass.liquid,iconGlass.kyant!=null,iconGlass.finish.transparency)
    }

    private fun backdrop(canvas:Canvas,processing:ScopedGlassSettings.Processing):Bitmap? {
        val raw=wallpaper ?: return null
        val request=Request(raw,processing,PrefsHelper.gpuEffects)
        processed[request]?.let {return WallpaperHelper.forCanvas(it,canvas)}
        if(request !in pending){
            pending=pending+request
            worker.execute {
                val image=runCatching {prepareWallpaper(request)}.getOrNull()
                main.post {
                    pending=pending-request
                    if(image!=null && wallpaper===request.source){
                        processed=(processed+ (request to image)).entries.takeLastEntries(4)
                        redraw()
                    }
                }
            }
        }
        val available = processed.entries.lastOrNull {it.key.source===raw}?.value ?: raw
        return WallpaperHelper.forCanvas(available, canvas)
    }

    private fun Set<Map.Entry<Request,Bitmap>>.takeLastEntries(count:Int):Map<Request,Bitmap> =
        toList().takeLast(count).associate {it.key to it.value}

    private fun prepareWallpaper(job:Request):Bitmap {
        val raw=job.source
        val options=job.processing
        val height=minOf(600+options.quality*12,raw.height)
        val width=(height*raw.width.toFloat()/raw.height).roundToInt().coerceAtLeast(1)
        val radius=LiquidOptics.blurRadius(options.radius,height,options.liquid)
        if(job.gpu){
            runCatching {GpuWallpaperBlur.blurFolder(raw,width,height,radius,options.lightening,options.vibrancy,options.transparency)}.getOrNull()?.let {return it}
        }
        val scaled=Bitmap.createScaledBitmap(raw,width,height,true)
        val vivid=BackdropVibrancy.applyCpu(scaled,options.vibrancy)
        val blur=BlurOpacity.blurCpu(vivid,radius,options.transparency)
        return ShadowTone.applyCpu(blur,options.lightening)
    }

    private fun scene(canvas:Canvas) {
        val w=width.toFloat();val h=height.toFloat()
        val frame=RectF(0f,0f,w,h)
        val radius=surfaceCorner ?: resources.displayMetrics.density*when {
            materialSample -> width / resources.displayMetrics.density * .24f
            iconGrid -> 14f
            else -> 24f
        }
        val save=canvas.save()
        try {
            canvas.clipPath(ContinuousCorners.path(Path(),frame,radius))
            val raw=wallpaper
            paint.color=Color.WHITE
            if(raw!=null)canvas.drawBitmap(WallpaperHelper.forCanvas(raw,canvas),footprint(raw,frame).toRect(),frame,paint)
            else {
                paint.shader=LinearGradient(0f,0f,w,h,0xff163e4c.toInt(),0xff0c211d.toInt(),Shader.TileMode.CLAMP)
                canvas.drawRect(frame,paint)
                paint.shader=null
            }
            val edge=minOf(h*.58f,w*.19f)
            val top=(h-edge)/2f
            when(scope){
                "dock" -> drawDock(canvas,w,h,top,edge)
                "widget_photo" -> drawPhotoWidgets(canvas, w, h)
                "widget_clock" -> drawStatusWidget(canvas, w, h, WidgetKind.CLOCK)
                "widget_weather" -> drawStatusWidget(canvas, w, h, WidgetKind.WEATHER)
                "folders" -> drawFolders(canvas,w,h)
                "tile_icons" -> {
                    val size = minOf(h * .65f, w * .33f)
                    val y = (h - size) / 2
                    for (index in 0 until minOf(2, icons.size)) {
                        val x = w / 2 + (index - 1) * size + (index - .5f) * w * .10f
                        drawIcon(canvas, RectF(x, y, x + size, y + size), index)
                    }
                }
                else -> when {
                    materialSample -> drawIcon(canvas,frame,0)
                    iconGrid -> drawIconGrid(canvas,w,h)
                    else -> drawIconRow(canvas,w,top,edge)
                }
            }
            if(raw==null && !materialSample && onPreviewChanged==null){
                paint.color=Color.WHITE
                paint.textAlign=Paint.Align.CENTER
                paint.textSize=10 * resources.displayMetrics.density
                canvas.drawText(context.getString(R.string.ui_preview_fallback),w/2,h-10*resources.displayMetrics.density,paint)
            }
        }finally{canvas.restoreToCount(save)}
    }

    /** A preview is a crop of the wallpaper, with unchanged proportions and sample coordinates. */
    private fun footprint(image:Bitmap,bounds:RectF):RectF {
        val density = resources.displayMetrics.density
        val sceneWidth = if (materialSample) resources.displayMetrics.widthPixels - 32 * density else width.toFloat()
        val sceneHeight = if (materialSample) sceneWidth * .67f else height.toFloat()
        val sceneScale=maxOf(sceneWidth/image.width,sceneHeight/image.height)
        val left=(image.width-sceneWidth/sceneScale)/2
        val top=(image.height-sceneHeight/sceneScale)*.58f
        val offsetX = if (materialSample) (sceneWidth - width) / 2 else 0f
        val offsetY = if (materialSample) (sceneHeight - height) / 2 else 0f
        return RectF(left+(bounds.left+offsetX)/sceneScale,top+(bounds.top+offsetY)/sceneScale,
            left+(bounds.right+offsetX)/sceneScale,top+(bounds.bottom+offsetY)/sceneScale)
    }

    private fun RectF.toRect()=Rect(left.roundToInt(),top.roundToInt(),right.roundToInt(),bottom.roundToInt())

    private fun drawIconRow(canvas:Canvas,w:Float,top:Float,edge:Float){
        val count=icons.size.coerceAtMost(4)
        val cell=w*.215f
        val left=(w-(count-1)*cell-edge)/2
        repeat(count){index->
            val x=left+index*cell
            drawIcon(canvas,RectF(x,top,x+edge,top+edge),index)
        }
    }

    private fun drawIconGrid(canvas: Canvas, w: Float, h: Float) {
        val cell = w / 4.16f
        val scale = if (desktopFeature(ModuleFeatures.LAYOUT)) n(DesktopSizing.ICON_SCALE) / 100f else 1f
        val edge = minOf(cell * .76f * scale, cell * .93f, h * .41f)
        repeat(minOf(icons.size, 8)) { index ->
            val x = w / 2 + (index % 4 - 1.5f) * cell - edge / 2
            val y = h * (if (index < 4) .29f else .71f)
            drawIcon(canvas, RectF(x, y - edge / 2, x + edge, y + edge / 2), index)
        }
    }

    private fun drawIcon(canvas:Canvas,bounds:RectF,index:Int){
        val sample=icons.getOrNull(index) ?: return
        val corner = bounds.width() * .24f
        iconOutline.rewind()
        iconOutline.addRoundRect(bounds, corner, corner, Path.Direction.CW)
        val clipped = canvas.save()
        canvas.clipPath(iconOutline)
        try {
            drawIconLayers(canvas, bounds, sample)
        } finally {
            canvas.restoreToCount(clipped)
        }
    }

    private fun drawIconLayers(canvas: Canvas, bounds: RectF, sample: AppearanceIcons.Sample) {
        val enabled = if (materialSample) materialMode != null else b(Keys.KEY_ENABLED) && b(ModuleFeatures.ICONS)
        var processedGlyph = false
        val icon = if (!enabled) null else {
            val glyph = sample.glyph
            val mask = sample.plate
            if (glyph == null || mask == null) {
                processedGlyph = true
                IconBitmapProcessor.getOrProcess(sample.stock)
            }
            else ProcessedIcon(glyph, mask, PrefsHelper.iconPalette.background)
        }
        if(icon==null){paint.color=Color.WHITE;canvas.drawBitmap(sample.stock,null,bounds,paint);return}
        val image=if((materialSample || PrefsHelper.iconPalette.autoLook>=0 || b(Keys.KEY_BLUR_ENABLED)) && !IconMaterialRenderer.clearGlass(iconGlass))backdrop(canvas,iconProcessing)else null
        val source=image?.let {footprint(it,bounds)}
        val plateColor=if(image!=null)PrefsHelper.plateColor(icon.bgColor,saturation)else icon.bgColor
        // Glyph masks supplied by the OS are alpha-only. Apply the active palette
        // once by baking their tint when this preview's display list is rebuilt.
        val parts = if (processedGlyph) icon else icon.copy(glyphBitmap = tintGlyph(icon.glyphBitmap))
        IconMaterialRenderer.drawIcon(canvas,bounds.toRect(),parts,plateColor,iconGlass.opacity,image,source,style=iconGlass)
    }

    private fun tintGlyph(glyph: Bitmap): Bitmap {
        val palette = PrefsHelper.iconPalette
        if (palette != tintedPalette) {
            tintedPalette = palette
            tintedGlyphs.clear()
        }
        return tintedGlyphs.getOrPut(glyph) {
            Bitmap.createBitmap(glyph.width, glyph.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val tint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = palette.filter }
                Canvas(bitmap).drawBitmap(glyph, 0f, 0f, tint)
            }
        }
    }

    private fun drawDock(canvas:Canvas,w:Float,h:Float,top:Float,edge:Float){
        if(desktopFeature(Keys.KEY_DOCK_ENABLED)){
            val side=edge*n(Keys.KEY_DOCK_SIDE_PADDING)/100f
            val padding=edge*n(Keys.KEY_DOCK_VERTICAL_PADDING)/100f
            val height=if(b(Keys.KEY_DEVELOPER_MODE))n(Keys.KEY_DOCK_BLUR_HEIGHT)else 100
            val (y0,y1)=DockGeometry.vertical(top,top+edge,padding,height)
            val extent=if(b(Keys.KEY_DOCK_AUTO_LENGTH))w*.42f else w*.45f
            val half=(extent+side).coerceAtMost(w*.48f)
            val bounds=RectF(w/2-half,y0.toFloat().coerceAtLeast(0f),w/2+half,y1.toFloat().coerceAtMost(h))
            val radius=DockGeometry.radius(edge,bounds.width(),bounds.height(),n(Keys.KEY_DOCK_RADIUS))
            val style=DockSettings.style().let {if(DockSettings.hasOwnAppearance)it else it.copy(plateColor=iconGlass.plateColor)}
            val image=if(!IconMaterialRenderer.clearGlass(style))backdrop(canvas,if(DockSettings.hasOwnAppearance)DockSettings.processing else iconProcessing)else null
            val path=ContinuousCorners.path(Path(),bounds,radius)
            dockRenderer.drawPlate(canvas,bounds.toRect(),path,radius,1f,image,image?.let {footprint(it,bounds)},style)
        }
        drawIconRow(canvas,w,top,edge)
    }

    private fun drawFolders(canvas:Canvas,w:Float,h:Float){
        val size=minOf(h*.79f,w*.42f)
        val left=(w-size)/2;val top=(h-size)/2
        val bounds=RectF(left,top,left+size,top+size)
        if(desktopFeature(Keys.KEY_FOLDER_BLUR_ENABLED)){
            val style=FolderSettings.style().let {if(FolderSettings.own)it else it.copy(plateColor=iconGlass.plateColor)}
            drawPlate(canvas,bounds,style,if(FolderSettings.own)FolderSettings.processing else iconProcessing)
        }
        val edge=size*.34f
        repeat(minOf(icons.size,4)){index ->
            val x=left+size*.11f+index%2*size*.44f
            val y=top+size*.11f+index/2*size*.44f
            drawIcon(canvas,RectF(x,y,x+edge,y+edge),index)
        }
    }

    private fun drawPhotoWidgets(canvas: Canvas, width: Float, height: Float) {
        val gap = width * .035f
        val size = minOf(height * .76f, (width * .90f - gap) / 3)
        val left = (width - size * 3 - gap) / 2
        val top = (height - size) / 2
        drawPhoto(canvas, RectF(left, top, left + size * 2, top + size))
        drawPhoto(canvas, RectF(left + size * 2 + gap, top, left + size * 3 + gap, top + size))
    }

    private fun widgetEnabled(kind: WidgetKind) = b(Keys.KEY_ENABLED) && desktopFeature(requireNotNull(kind.switchKey)) &&
        (b(Keys.KEY_BLUR_ENABLED) || iconGlass.colorOnly || PrefsHelper.iconPalette.autoLook >= 0)

    private fun drawWidgetSurface(canvas: Canvas, bounds: RectF, kind: WidgetKind, enabled: Boolean) {
        if (enabled) {
            val style = WidgetGlassSettings.style(iconGlass, kind, PrefsHelper.iconPalette.autoLook,
                n(requireNotNull(kind.opacityKey)),
                n(Keys.KEY_PHOTO_WIDGET_LIQUID_WIDTH), n(Keys.KEY_PHOTO_WIDGET_LIQUID_DISTORTION))
            val h = 120
            val w = (h * bounds.width() / bounds.height()).roundToInt().coerceAtLeast(1)
            val key = w to h
            val mask = widgetMasks.get(key) ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                Canvas(it).drawPath(ContinuousCorners.path(Path(), RectF(0f, 0f, w.toFloat(), h.toFloat()), h * .20f), maskPaint)
                widgetMasks.put(key, it)
            }
            drawPlate(canvas, bounds, style, iconProcessing, mask)
        } else {
            widgetInk.color = 0xff26292f.toInt()
            canvas.drawPath(ContinuousCorners.path(Path(), bounds, bounds.height() * .20f), widgetInk)
        }
    }

    private fun drawPhoto(canvas: Canvas, bounds: RectF) {
        val enabled = widgetEnabled(WidgetKind.PHOTO)
        drawWidgetSurface(canvas, bounds, WidgetKind.PHOTO, enabled)
        val size = bounds.height()
        val save=canvas.save()
        try {
            canvas.clipPath(ContinuousCorners.path(Path(), bounds, size * .20f))
            val alpha = if (enabled && !iconGlass.colorOnly) n(Keys.KEY_PHOTO_WIDGET_OPACITY) / 100f else 1f
            val tint = if (enabled && (iconGlass.colorOnly || b(Keys.KEY_PHOTO_WIDGET_TINT)))
                PrefsHelper.iconPalette.foreground else Color.WHITE
            widgetInk.color = tint
            widgetInk.alpha = (28 * alpha).roundToInt()
            canvas.drawRect(bounds, widgetInk)
            photoPlaceholder.setTint(tint)
            photoPlaceholder.alpha = (195 * alpha).roundToInt()
            val half = size * .19f
            photoPlaceholder.bounds = RectF(bounds.centerX() - half, bounds.centerY() - half,
                bounds.centerX() + half, bounds.centerY() + half).toRect()
            photoPlaceholder.draw(canvas)
        }finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawStatusWidget(canvas: Canvas, width: Float, height: Float, kind: WidgetKind) {
        val size = minOf(height * .76f, width * .40f)
        val bounds = RectF(width / 2 - size, (height - size) / 2, width / 2 + size, (height + size) / 2)
        drawWidgetSurface(canvas, bounds, kind, widgetEnabled(kind))
        val x = bounds.left + size * .44f
        val y = bounds.centerY()
        widgetInk.color = Color.WHITE
        widgetInk.strokeCap = Paint.Cap.ROUND
        if (kind == WidgetKind.CLOCK) {
            widgetInk.style = Paint.Style.STROKE
            widgetInk.strokeWidth = size * .015f
            canvas.drawCircle(x, y, size * .25f, widgetInk)
            widgetInk.style = Paint.Style.FILL
            widgetInk.strokeWidth = size * .025f
            canvas.drawLine(x, y, x - size * .09f, y - size * .06f, widgetInk)
            canvas.drawLine(x, y, x + size * .13f, y - size * .11f, widgetInk)
        } else {
            widgetInk.color = 0xffffdc81.toInt()
            canvas.drawCircle(x + size * .07f, y - size * .093f, size * .14f, widgetInk)
            widgetInk.color = Color.WHITE
            canvas.drawCircle(x - size * .057f, y, size * .12f, widgetInk)
            canvas.drawRoundRect(x - size * .20f, y - size * .014f, x + size * .18f, y + size * .116f,
                size * .065f, size * .065f, widgetInk)
        }
        widgetInk.textAlign = Paint.Align.LEFT
        widgetInk.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        widgetInk.textSize = size * .39f
        val clock = kind == WidgetKind.CLOCK
        canvas.drawText(if (clock) "10:08" else "18°", bounds.left + size * .78f,
            y - (widgetInk.ascent() + widgetInk.descent()) / 2f, widgetInk)
    }

    private fun drawPlate(canvas:Canvas,bounds:RectF,style:GlassStyle,processing:ScopedGlassSettings.Processing,mask:Bitmap=folderMask ?: defaultFolderMask){
        val image=if(!IconMaterialRenderer.clearGlass(style))backdrop(canvas,processing)else null
        val color=style.plateColor ?: PrefsHelper.plateColor()
        val alpha=style.opacity*255/100
        IconMaterialRenderer.draw(canvas,bounds.toRect(),mask,emptyGlyph,color,alpha,false,image,image?.let {footprint(it,bounds)},style=style){target,rect,blurred ->
            if(!blurred)SurfaceMaterial.clearPlate(target,rect,mask,color,alpha,true)
            if(PrefsHelper.iconStroke && PrefsHelper.strokeOpacity>0)
                target.drawBitmap(GlassGeometry.rim(mask),null,rect,paint)
        }
    }
}
