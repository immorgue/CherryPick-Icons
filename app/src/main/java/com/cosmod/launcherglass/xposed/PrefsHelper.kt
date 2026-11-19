package com.cosmod.launcherglass.xposed

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.util.AutoIconStyle
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.LiveBlurEngine
import com.cosmod.launcherglass.util.WallpaperHelper

object PrefsHelper {
    @Volatile private var settingsSnapshot:android.os.Bundle?=null
    internal fun settingsSnapshot()=settingsSnapshot?.let {android.os.Bundle(it)}
    @Volatile var limitClosingBlur=true
        private set

    @Volatile var enabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_ENABLED)
    @Volatile var gpuEffects: Boolean = true
        internal set
    @Volatile var glassFinish = GlassFinish()
        internal set
    @Volatile var bgOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_BG_OPACITY)
    @Volatile var blurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_BLUR_ENABLED)
    @Volatile var smoothSwipeLead=false
    @Volatile var appWindowTilt=false
    @Volatile var appGenie=true
    @Volatile var appDroplet=true
    @Volatile var appGestureBounce=false
    @Volatile var appOpenResponse=240
    @Volatile var appCloseSpeed=100
    @Volatile var appGenieStrength=100
    @Volatile var appAnimationSlowdown=100
    @Volatile var swipeLead=SettingsDefaults.boolean(SettingsProvider.KEY_SWIPE_LEAD)
        private set
    @Volatile var disableEdgeOptimization=SettingsDefaults.boolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION)
        private set
    @Volatile var optimizeMiniIcons=SettingsDefaults.boolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS)
        private set
    @Volatile var dockEnabled=false
        private set
    @Volatile var dockSidePadding=SettingsDefaults.integer(SettingsProvider.KEY_DOCK_SIDE_PADDING)
        private set
    @Volatile var dockBlurHeight=100
    @Volatile var dockBlurY=0
    @Volatile var dockVerticalPadding=SettingsDefaults.integer(SettingsProvider.KEY_DOCK_VERTICAL_PADDING)
        private set
    @Volatile var dockRadius=SettingsDefaults.integer(SettingsProvider.KEY_DOCK_RADIUS)
        private set
    @Volatile var dockAutoLength=SettingsDefaults.boolean(SettingsProvider.KEY_DOCK_AUTO_LENGTH)
        private set
    /** Transparency (0..100 %) of the tinted picture of plate-less icons; adjustable in developer mode. */
    @Volatile var artworkTransparency=SettingsDefaults.integer(SettingsProvider.KEY_ARTWORK_TRANSPARENCY)
    @Volatile var artworkDetection=false
        private set
    @Volatile var folderBlurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_FOLDER_BLUR_ENABLED)
    @Volatile var folderIconsBlurEnabled=true
    @Volatile var photoWidgetBlur=false
    @Volatile internal var clockWidget = WidgetGlassOptions(false, SettingsDefaults.integer(SettingsProvider.KEY_CLOCK_WIDGET_OPACITY))
    @Volatile internal var weatherWidget = WidgetGlassOptions(false, SettingsDefaults.integer(SettingsProvider.KEY_WEATHER_WIDGET_OPACITY))
    internal val widgetGlassEnabled get() = photoWidgetBlur || clockWidget.enabled || weatherWidget.enabled
    @Volatile var photoWidgetTint=false
    @Volatile var photoWidgetLiquidWidth=40
    @Volatile var photoWidgetLiquidDistortion=50
    @Volatile var photoWidgetOpacity=55
        private set
    @Volatile var drawerBlurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED)
    @Volatile var blurRadius: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_RADIUS)
    @Volatile var blurMode: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_MODE)
    @Volatile var blurStyle: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_STYLE)
    @Volatile var blurQuality=SettingsDefaults.integer(SettingsProvider.KEY_BLUR_QUALITY)
    @Volatile var liquidWidth=SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_WIDTH)
    @Volatile var liquidDistortion=SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_DISTORTION)
    val colorOnly:Boolean get()=blurStyle==SettingsProvider.BLUR_STYLE_COLOR
    val iconBackdropEnabled:Boolean get()=com.cosmod.launcherglass.util.MaterialMode.needsBackdrop(blurStyle,blurEnabled)
    val backdropEnabled:Boolean get()=(dockEnabled && DockSettings.needsOwnBackdrop) ||
        com.cosmod.launcherglass.util.MaterialMode.needsBackdrop(blurStyle,blurEnabled && (FeaturePolicy.icons || folderBlurEnabled || dockEnabled || widgetGlassEnabled),
            iconPalette.autoLook<0 && FolderSettings.own && folderBlurEnabled)
    val kyantGlass:Boolean get()=blurStyle!=SettingsProvider.BLUR_STYLE_COLOR && android.os.Build.VERSION.SDK_INT>=33
    val liquidGlass:Boolean get()=blurStyle!=SettingsProvider.BLUR_STYLE_FROSTED && kyantGlass
    @Volatile var kyant=KyantOptics.read()
    @Volatile var swipeFps=SettingsDefaults.integer(SettingsProvider.KEY_SWIPE_FPS)
    @Volatile var liveFps: Int = SettingsDefaults.integer(SettingsProvider.KEY_LIVE_FPS)
    /** Event-driven dock refresh and frame-aligned material sampling (developer comparison switch). */
    @Volatile var staticAnimationBlur=false
    @Volatile var sceneDragBackdrop=false
    @Volatile var idlePause: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_IDLE_PAUSE)
    @Volatile var iconStroke: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_ICON_STROKE)
    @Volatile var strokeOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_STROKE_OPACITY)
    @Volatile var glyphStroke = false
    @Volatile var glyphStrokeOpacity = 40
    @Volatile var glyphStrokeWidth = 35
    @Volatile var glyphStrokeBrightness = 45
    @Volatile var glyphBottomShade = SettingsDefaults.integer(SettingsProvider.KEY_GLYPH_BOTTOM_SHADE)
    @Volatile var glyphShadow: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_GLYPH_SHADOW)
    @Volatile var shadowOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_SHADOW_OPACITY)
    @Volatile var gradientOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_GRADIENT_OPACITY)

    @Volatile var iconPalette = com.cosmod.launcherglass.util.IconPalette.from(0, 0, 0, 0)

    @Volatile var blurVibrancy = 0
        private set
    private var blurSaturation = 100
    internal fun plateColor(color:Int=iconPalette.background,saturation:Int=blurSaturation)=com.cosmod.launcherglass.util.PlateTone.saturation(color,saturation)
    @Volatile var blurLightening = 50
        private set
    @Volatile var blurColorFilter: android.graphics.ColorMatrixColorFilter? = null
    private var initialized = false
    private var nativeDarkIcons = false
    @Volatile private var loaded=false

    private val clearDarkBackdrop by lazy {
        android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
            setSaturation(.5f)
            postConcat(android.graphics.ColorMatrix().apply {setScale(.58f,.58f,.58f,1f)})
        })
    }

    internal fun backdropFilter(vibrancy:Int,palette:com.cosmod.launcherglass.util.IconPalette,useAutoPreset:Boolean=true):android.graphics.ColorMatrixColorFilter? {
        if(useAutoPreset && palette.autoDark)when(palette.autoLook){
            com.cosmod.launcherglass.util.AutoIconStyle.CLEAR -> return clearDarkBackdrop
            com.cosmod.launcherglass.util.AutoIconStyle.TINTED -> return null
        }
        val colorize=palette.custom && !palette.clear
        return if (!colorize || vibrancy>=100) null else {
                    val matrix = android.graphics.ColorMatrix()
                    if (colorize) {
                        // Replace source chroma instead of tinting a colored
                        // wallpaper. Plate saturation changes the overlay color
                        // separately, independently of this chroma filter.
                        matrix.setSaturation(0f)
                        val hue = android.graphics.Color.HSVToColor(floatArrayOf(palette.tintHue.toFloat(),1f,1f))
                        val strength = palette.tintSaturation / 100f
                        val tint = android.graphics.ColorMatrix().apply { setScale(
                            1f-strength+strength*android.graphics.Color.red(hue)/255f,
                            1f-strength+strength*android.graphics.Color.green(hue)/255f,
                            1f-strength+strength*android.graphics.Color.blue(hue)/255f,1f) }
                        matrix.postConcat(tint)
                    }
                    // Vibrancy restores source chroma that a custom tint may
                    // otherwise replace. Blend the complete legacy transform
                    // toward identity, preserving its old behavior at zero.
                    val keep=(vibrancy/100f).coerceIn(0f,1f)
                    if(keep>0f){
                        val identity=android.graphics.ColorMatrix().array
                        val values=matrix.array
                        for(i in values.indices)values[i]=values[i]*(1f-keep)+identity[i]*keep
                        matrix.set(values)
                    }
                    android.graphics.ColorMatrixColorFilter(matrix)
                }
    }
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        AutoMenuBridge.initialize(context)
        loadPrefs(context)
        android.app.WallpaperManager.getInstance(context).addOnColorsChangedListener({ _, which ->
            if (which and android.app.WallpaperManager.FLAG_SYSTEM != 0) {
                com.cosmod.launcherglass.util.AutoIconPalette.invalidateWallpaper()
                loadPrefs(context)
            }
        }, Handler(Looper.getMainLooper()))
        for(delay in listOf(250L,750L,1500L,3000L))Handler(Looper.getMainLooper()).postDelayed({if(!loaded)loadPrefs(context)},delay)
        context.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
                val dark = configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                loadPrefs(context, dark)
            }
            override fun onLowMemory() {}
        })

        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    // The menu's worker already reads the persisted snapshot after its write.
                    if (uri == SettingsProvider.CONTENT_URI && AutoMenuBridge.deferProviderNotification()) return
                    loadPrefs(context)
                }
            }
            context.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI, true, observer)
            com.cosmod.launcherglass.util.SystemIconColors.changedUris().forEach { uri ->
                runCatching { context.contentResolver.registerContentObserver(uri, false, observer) }
            }
            GlassLogger.i(
                "PrefsHelper",
                "Initialized & ContentObserver registered: bgOpacity=$bgOpacity%, blur=$blurEnabled(mode=$blurMode, fps=$liveFps, idlePause=$idlePause, r=$blurRadius)"
            )
        } catch (t: Throwable) {
            GlassLogger.e("PrefsHelper", "Failed to register ContentObserver", t)
        }
    }

    fun loadPrefs(context: Context, systemDark: Boolean? = null) {
        val remote = runCatching {
            context.contentResolver.call(SettingsProvider.CONTENT_URI, SettingsProvider.METHOD_GET_ALL, null, null)
        }.getOrNull()
        if (remote != null) AutoMenuBridge.remember(context, remote)
        val base = remote ?: settingsSnapshot ?: AutoMenuBridge.cachedSettings(context) ?: return
        applyPrefs(context, systemDark, AutoMenuBridge.effective(base), reportNativeAppearance = remote != null)
        if (remote != null) AutoMenuBridge.reconnect()
    }

    internal fun applyAutoMenuSettings(context: Context, bundle: android.os.Bundle) {
        val current = settingsSnapshot
        val changed = current?.takeIf { it.keySet() == bundle.keySet() }?.let { previous ->
            bundle.keySet().filter { previous.get(it) != bundle.get(it) }
        }
        if (changed?.isEmpty() == true) return
        val appearanceOnly = bundle.getInt(com.cosmod.launcherglass.util.AutoIconStyle.ENGINE)==1 &&
            changed?.all(AutoMenuBridge::isEditableSetting)==true
        applyPrefs(context, null, bundle, reportNativeAppearance = false, appearanceOnly = appearanceOnly)
    }

    private fun applyPrefs(context: Context, systemDark: Boolean?, bundle: android.os.Bundle, reportNativeAppearance: Boolean,
        appearanceOnly: Boolean = false) {
        try {
            settingsSnapshot=android.os.Bundle(bundle)

            val featureChanged=FeaturePolicy.load(bundle)
            DesktopGeometry.load(bundle)

            val newEnabled = bundle.getBoolean(SettingsProvider.KEY_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_ENABLED))
            val autoAppearance=bundle.getInt(AutoIconStyle.ENGINE,AutoIconStyle.DEFAULT_ENGINE)==1
            val toneEnabled=autoAppearance || bundle.getBoolean(SettingsProvider.KEY_BLUR_TONE_ENABLED,true)
            var newBgOpacity = if (toneEnabled)
                bundle.getInt(SettingsProvider.KEY_BG_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_BG_OPACITY)).coerceIn(0,100) else 0
            val newBlurEnabled = autoAppearance || bundle.getInt(SettingsProvider.KEY_BLUR_STYLE,0)==SettingsProvider.BLUR_STYLE_COLOR || bundle.getBoolean(SettingsProvider.KEY_BLUR_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_BLUR_ENABLED))
            val locationBlurEnabled = bundle.getBoolean(SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED,true)
            val newFolderBlurEnabled = FeaturePolicy.desktop && bundle.getBoolean(SettingsProvider.KEY_FOLDER_BLUR_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_FOLDER_BLUR_ENABLED))
            val newFolderIconsBlurEnabled=locationBlurEnabled && bundle.getBoolean(SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED,true)
            val newDrawerBlurEnabled = locationBlurEnabled && bundle.getBoolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED))
            val newPhotoWidgetBlur=FeaturePolicy.desktop && bundle.getBoolean(SettingsProvider.KEY_PHOTO_WIDGET_BLUR,false)
            val newClockWidget = WidgetGlassOptions.read(WidgetKind.CLOCK, bundle, FeaturePolicy.desktop)
            val newWeatherWidget = WidgetGlassOptions.read(WidgetKind.WEATHER, bundle, FeaturePolicy.desktop)
            val newPhotoWidgetOpacity=bundle.getInt(SettingsProvider.KEY_PHOTO_WIDGET_OPACITY,55).coerceIn(0,100)
            val newPhotoWidgetTint=bundle.getBoolean(SettingsProvider.KEY_PHOTO_WIDGET_TINT,false)
            val developer=bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false)
            val newBlurStyle = if(autoAppearance) {
                val key=com.cosmod.launcherglass.util.AutoIconStyle.MATERIAL
                com.cosmod.launcherglass.util.AutoIconStyle.materialMode(bundle.getInt(key,1),android.os.Build.VERSION.SDK_INT>=33)
            }else com.cosmod.launcherglass.util.MaterialMode.normalize(bundle.getInt(SettingsProvider.KEY_BLUR_STYLE, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_STYLE)))
            fun transparency(mode:Int)=com.cosmod.launcherglass.util.MaterialMode.blurTransparency(mode,autoAppearance,developer,
                bundle.getInt(SettingsProvider.KEY_BLUR_TRANSPARENCY,SettingsDefaults.integer(SettingsProvider.KEY_BLUR_TRANSPARENCY)))
            val newGpuEffects=!developer || bundle.getBoolean(SettingsProvider.KEY_GPU_EFFECTS,true)
            val newLimitClosingBlur=!developer || bundle.getBoolean(SettingsProvider.KEY_LIMIT_CLOSING_BLUR,true)
            val backendChanged=newGpuEffects!=gpuEffects
            val newSmoothSwipeLead=developer && bundle.getBoolean(SettingsProvider.KEY_SMOOTH_SWIPE_LEAD,false)
            val newGlassFinish = GlassFinish(
                transparency(newBlurStyle),
                (autoAppearance || developer) && android.os.Build.VERSION.SDK_INT >= 33 && bundle.getBoolean(SettingsProvider.KEY_HAZE_ENABLED, false))
            val oldBlurTransparency=glassFinish.transparency
            val finishChanged = newGlassFinish != glassFinish
            val newSwipeLead=bundle.getBoolean(SettingsProvider.KEY_SWIPE_LEAD,SettingsDefaults.boolean(SettingsProvider.KEY_SWIPE_LEAD))
            val newDisableEdgeOptimization=bundle.getBoolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION,SettingsDefaults.boolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION))
            val newOptimizeMiniIcons=bundle.getBoolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS,SettingsDefaults.boolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS))
            val defaultArtwork=SettingsDefaults.integer(SettingsProvider.KEY_ARTWORK_TRANSPARENCY)
            val newArtworkTransparency=if(developer)bundle.getInt(SettingsProvider.KEY_ARTWORK_TRANSPARENCY,defaultArtwork).coerceIn(0,100) else defaultArtwork
            val artworkChanged=newArtworkTransparency!=artworkTransparency || developer!=artworkDetection
            if(developer!=artworkDetection)IconBitmapProcessor.clearCache()
            val newDockEnabled=FeaturePolicy.desktop && bundle.getBoolean(SettingsProvider.KEY_DOCK_ENABLED,false)
            val wasDockEnabled=dockEnabled
            fun dockValue(key:String,max:Int)=bundle.getInt(key,SettingsDefaults.integer(key)).coerceIn(0,max)
            val newDockSide=dockValue(SettingsProvider.KEY_DOCK_SIDE_PADDING,SettingsProvider.DOCK_PADDING_MAX)
            val newDockVertical=dockValue(SettingsProvider.KEY_DOCK_VERTICAL_PADDING,SettingsProvider.DOCK_PADDING_MAX)
            val newDockHeight=if(developer)bundle.getInt(SettingsProvider.KEY_DOCK_BLUR_HEIGHT,100).coerceIn(50,200)else 100
            val newDockY=if(developer)bundle.getInt(SettingsProvider.KEY_DOCK_BLUR_Y,0).coerceIn(-120,120)else 0
            val newDockRadius=dockValue(SettingsProvider.KEY_DOCK_RADIUS,100)
            val newDockAutoLength=bundle.getBoolean(SettingsProvider.KEY_DOCK_AUTO_LENGTH,SettingsDefaults.boolean(SettingsProvider.KEY_DOCK_AUTO_LENGTH))
            val dockChanged=newDockY!=dockBlurY || newDockHeight!=dockBlurHeight || newDockEnabled!=dockEnabled || newDockSide!=dockSidePadding || newDockVertical!=dockVerticalPadding || newDockRadius!=dockRadius ||
                newDockAutoLength!=dockAutoLength
            val newPhotoWidgetWidth=bundle.getInt(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH,SettingsDefaults.integer(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH)).coerceIn(0,100)
            val newPhotoWidgetDistortion=bundle.getInt(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION,50).coerceIn(0,300)
            val widgetChanged=newPhotoWidgetBlur!=photoWidgetBlur || newPhotoWidgetOpacity!=photoWidgetOpacity || newPhotoWidgetTint!=photoWidgetTint ||
                newPhotoWidgetWidth!=photoWidgetLiquidWidth || newPhotoWidgetDistortion!=photoWidgetLiquidDistortion ||
                newClockWidget != clockWidget || newWeatherWidget != weatherWidget
            val newBlurRadius = if(autoAppearance)10 else if (bundle.getBoolean(SettingsProvider.KEY_BLUR_RADIUS_ENABLED,true))
                bundle.getInt(SettingsProvider.KEY_BLUR_RADIUS, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_RADIUS)).coerceIn(0,50) else 0
            val newBlurMode = bundle.getInt(SettingsProvider.KEY_BLUR_MODE, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_MODE))
            val blurStyleChanged=newBlurStyle!=blurStyle
            val colorOnlyChanged=(newBlurStyle==SettingsProvider.BLUR_STYLE_COLOR)!=colorOnly
            val newBlurQuality=if(developer)bundle.getInt(SettingsProvider.KEY_BLUR_QUALITY,SettingsDefaults.integer(SettingsProvider.KEY_BLUR_QUALITY)).coerceIn(0,100)else 0
            val newLiquidWidth=bundle.getInt(SettingsProvider.KEY_LIQUID_WIDTH,SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_WIDTH)).coerceIn(0,100)
            val newLiquidDistortion=bundle.getInt(SettingsProvider.KEY_LIQUID_DISTORTION,SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_DISTORTION)).coerceIn(0,300)
            val newKyant=if(autoAppearance)KyantOptics.automatic else KyantOptics.read(bundle)
            val geometryChanged=newLiquidWidth!=liquidWidth || newLiquidDistortion!=liquidDistortion || newKyant!=kyant
            val qualityChanged=newBlurQuality!=blurQuality
            val newLiveFps = bundle.getInt(SettingsProvider.KEY_LIVE_FPS, SettingsDefaults.integer(SettingsProvider.KEY_LIVE_FPS))
            val newIdlePause = bundle.getBoolean(SettingsProvider.KEY_IDLE_PAUSE, SettingsDefaults.boolean(SettingsProvider.KEY_IDLE_PAUSE))
            val designEnabled = bundle.getBoolean(SettingsProvider.KEY_DESIGN_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_DESIGN_ENABLED))
            val newIconStroke = designEnabled && bundle.getBoolean(SettingsProvider.KEY_ICON_STROKE, SettingsDefaults.boolean(SettingsProvider.KEY_ICON_STROKE))
            val newStrokeOpacity = bundle.getInt(SettingsProvider.KEY_STROKE_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_STROKE_OPACITY))
            val newGlyphStroke = designEnabled && bundle.getBoolean(SettingsProvider.KEY_GLYPH_STROKE,
                SettingsDefaults.boolean(SettingsProvider.KEY_GLYPH_STROKE))
            fun outlineValue(key:String)=bundle.getInt(key,SettingsDefaults.integer(key)).coerceIn(0,100)
            val newGlyphStrokeOpacity = outlineValue(SettingsProvider.KEY_GLYPH_STROKE_OPACITY)
            val newGlyphStrokeWidth = outlineValue(SettingsProvider.KEY_GLYPH_STROKE_WIDTH)
            val newGlyphStrokeBrightness = outlineValue(SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS)
            val shadeKey=SettingsProvider.KEY_GLYPH_BOTTOM_SHADE
            val shadeSwitch=SettingsProvider.KEY_GLYPH_BOTTOM_SHADE_ENABLED
            val newGlyphBottomShade=if(designEnabled && bundle.getBoolean(shadeSwitch,SettingsDefaults.boolean(shadeSwitch)))
                bundle.getInt(shadeKey,SettingsDefaults.integer(shadeKey)).coerceIn(0,100)else 0
            val glyphOutlineChanged = newGlyphStroke != glyphStroke || newGlyphStrokeOpacity != glyphStrokeOpacity ||
                newGlyphStrokeWidth != glyphStrokeWidth || newGlyphStrokeBrightness != glyphStrokeBrightness
            val newGlyphShadow = designEnabled && bundle.getBoolean(SettingsProvider.KEY_GLYPH_SHADOW, SettingsDefaults.boolean(SettingsProvider.KEY_GLYPH_SHADOW))
            val newShadowOpacity = bundle.getInt(SettingsProvider.KEY_SHADOW_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_SHADOW_OPACITY))
            val newGradientOpacity = if (designEnabled && bundle.getBoolean(SettingsProvider.KEY_VOLUME_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_VOLUME_ENABLED)))
                bundle.getInt(SettingsProvider.KEY_GRADIENT_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_GRADIENT_OPACITY)) else 0
            val configuration = android.content.res.Configuration(context.resources.configuration)
            if (systemDark != null) {
                configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (systemDark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
            }
            if (!appearanceOnly) nativeDarkIcons = NativeIconAppearance.read(context, configuration)
                ?: (systemDark ?: com.cosmod.launcherglass.util.IconPalette.isSystemDark(context))
            val fluxSupport=if(reportNativeAppearance)FluxIconSupport.read(context)else FluxIconSupport.UNKNOWN
            if(reportNativeAppearance && fluxSupport!=FluxIconSupport.UNKNOWN && bundle.getInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,0)!=fluxSupport) {
                context.contentResolver.call(SettingsProvider.CONTENT_URI,SettingsProvider.METHOD_REPORT_ICON_APPEARANCE,
                    null,android.os.Bundle().apply {putInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,fluxSupport)})
            }
            if (reportNativeAppearance && (!bundle.containsKey(SettingsProvider.KEY_NATIVE_ICON_DARK) ||
                bundle.getBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK) != nativeDarkIcons)) {
                context.contentResolver.call(SettingsProvider.CONTENT_URI, SettingsProvider.METHOD_REPORT_ICON_APPEARANCE,
                    null, android.os.Bundle().apply { putBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK, nativeDarkIcons) })
            }
            val darkIcons=com.cosmod.launcherglass.util.IconColorTheme.isDark(
                bundle.getInt(SettingsProvider.KEY_ICON_COLOR_THEME,0),nativeDarkIcons)
            val clearIcons = bundle.getInt(SettingsProvider.KEY_ICON_APPEARANCE, 1) == 0
            val colorKeys = SettingsProvider.colorKeys(darkIcons)
            val newPalette = com.cosmod.launcherglass.util.AutoIconPalette.resolve(context, bundle, nativeDarkIcons)
                ?: com.cosmod.launcherglass.util.IconPalette.resolve(
                context, bundle.getInt(SettingsProvider.KEY_COLOR_MODE, 0),
                bundle.getInt(colorKeys[0], SettingsDefaults.integer(colorKeys[0])),
                bundle.getInt(colorKeys[1], SettingsDefaults.integer(colorKeys[1])),
                if (clearIcons) bundle.getInt(SettingsProvider.KEY_CLEAR_BRIGHTNESS, SettingsDefaults.integer(SettingsProvider.KEY_CLEAR_BRIGHTNESS))
                else bundle.getInt(colorKeys[2], SettingsDefaults.integer(colorKeys[2])), darkIcons, clearIcons)
            if (newPalette.autoLook >= 0) newBgOpacity = com.cosmod.launcherglass.util.AutoIconStyle.opacity(newPalette.autoLook, newPalette.autoDark)
            val newBlurVibrancy=if(autoAppearance)100 else if(toneEnabled)bundle.getInt(SettingsProvider.KEY_BLUR_VIBRANCY,0).coerceIn(0,200)else 0
            val vibrancyChanged=newBlurVibrancy!=blurVibrancy
            val newBlurSaturation = if(autoAppearance)100 else if (toneEnabled)
                bundle.getInt(SettingsProvider.KEY_BLUR_SATURATION,100).coerceIn(0,200) else 100
            val newBlurLightening = if(autoAppearance)50 else if (toneEnabled)
                bundle.getInt(SettingsProvider.KEY_BLUR_LIGHTENING,50).coerceIn(0,100) else 50
            val shadowToneChanged = newBlurLightening != blurLightening
            val blurColorsChanged = newBlurSaturation != blurSaturation || newBlurLightening != blurLightening
            val paletteChanged = newPalette != iconPalette
            val masterChanged = newEnabled != enabled

            if (masterChanged) {
                IconBitmapProcessor.clearCache()
            }
            val radiusChanged = newBlurRadius != blurRadius
            if (newBlurMode != blurMode) {
                LiveBlurEngine.invalidate()
            }
            val folderEnabledChanged=newFolderBlurEnabled!=folderBlurEnabled
            val materialChanged=finishChanged || backendChanged || newBgOpacity!=bgOpacity || newBlurEnabled!=blurEnabled || newBlurRadius!=blurRadius ||
                newFolderBlurEnabled!=folderBlurEnabled || newFolderIconsBlurEnabled!=folderIconsBlurEnabled || newDrawerBlurEnabled!=drawerBlurEnabled ||
                newIconStroke != iconStroke || newStrokeOpacity != strokeOpacity ||
                newGlyphShadow != glyphShadow || newShadowOpacity != shadowOpacity ||
                newGradientOpacity != gradientOpacity || newGlyphBottomShade != glyphBottomShade || glyphOutlineChanged || paletteChanged ||
                blurColorsChanged || blurStyleChanged || geometryChanged || vibrancyChanged || widgetChanged ||
                newSwipeLead!=swipeLead || newSmoothSwipeLead!=smoothSwipeLead || newDisableEdgeOptimization!=disableEdgeOptimization ||
                newOptimizeMiniIcons!=optimizeMiniIcons || artworkChanged

            if (blurColorsChanged || paletteChanged || vibrancyChanged) {
                blurSaturation = newBlurSaturation
                blurLightening = newBlurLightening
                blurColorFilter=backdropFilter(newBlurVibrancy,newPalette)
            }
            iconPalette = newPalette
            enabled = newEnabled
            gpuEffects=newGpuEffects
            glassFinish=newGlassFinish
            limitClosingBlur=newLimitClosingBlur
            sceneDragBackdrop=developer && bundle.getBoolean(SettingsProvider.KEY_SCENE_DRAG_BACKDROP,false)
            staticAnimationBlur=bundle.getBoolean(SettingsProvider.KEY_STATIC_ANIMATION_BLUR,false)
            GlassLogger.enabled=bundle.getBoolean(SettingsProvider.KEY_MODULE_LOGGING,SettingsDefaults.boolean(SettingsProvider.KEY_MODULE_LOGGING))
            appWindowTilt=FeaturePolicy.desktop && bundle.getBoolean(SettingsProvider.KEY_APP_WINDOW_TILT,false)
            appGenie=bundle.getBoolean(SettingsProvider.KEY_APP_GENIE,true)
            appDroplet=bundle.getBoolean(SettingsProvider.KEY_APP_DROPLET,true)
            appGestureBounce=bundle.getBoolean(SettingsProvider.KEY_APP_GESTURE_BOUNCE,false)
            fun animationValue(key:String)=bundle.getInt(key,SettingsDefaults.integer(key))
                .coerceIn(SettingsProvider.APP_ANIMATION_RANGES.getValue(key))
            appOpenResponse=animationValue(SettingsProvider.KEY_APP_OPEN_RESPONSE)
            appCloseSpeed=animationValue(SettingsProvider.KEY_APP_CLOSE_SPEED)
            appGenieStrength=animationValue(SettingsProvider.KEY_APP_GENIE_STRENGTH)
            appAnimationSlowdown=animationValue(SettingsProvider.KEY_APP_ANIMATION_SLOWDOWN)
            bgOpacity = newBgOpacity
            blurEnabled = newBlurEnabled
            smoothSwipeLead=newSmoothSwipeLead
            swipeLead=newSwipeLead
            disableEdgeOptimization=newDisableEdgeOptimization
            optimizeMiniIcons=newOptimizeMiniIcons
            artworkTransparency=newArtworkTransparency;artworkDetection=developer
            dockBlurY=newDockY;dockBlurHeight=newDockHeight;dockEnabled=newDockEnabled;dockSidePadding=newDockSide;dockVerticalPadding=newDockVertical;dockRadius=newDockRadius
            dockAutoLength=newDockAutoLength
            folderBlurEnabled = newFolderBlurEnabled
            folderIconsBlurEnabled=newFolderIconsBlurEnabled
            drawerBlurEnabled = newDrawerBlurEnabled
            photoWidgetBlur=newPhotoWidgetBlur;photoWidgetOpacity=newPhotoWidgetOpacity;photoWidgetTint=newPhotoWidgetTint
            clockWidget = newClockWidget; weatherWidget = newWeatherWidget
            photoWidgetLiquidWidth=newPhotoWidgetWidth;photoWidgetLiquidDistortion=newPhotoWidgetDistortion
            blurRadius = newBlurRadius
            blurMode = newBlurMode
            blurStyle = newBlurStyle
            blurVibrancy=newBlurVibrancy
            blurQuality=newBlurQuality;liquidWidth=newLiquidWidth;liquidDistortion=newLiquidDistortion
            if(radiusChanged || blurStyleChanged || qualityChanged || vibrancyChanged || backendChanged ||
                newGlassFinish.transparency!=oldBlurTransparency)WallpaperHelper.invalidate()
            liveFps = newLiveFps.coerceIn(1,60)
            swipeFps=bundle.getInt(SettingsProvider.KEY_SWIPE_FPS,SettingsDefaults.integer(SettingsProvider.KEY_SWIPE_FPS)).coerceIn(1,120)
            kyant=newKyant
            idlePause = newIdlePause
            iconStroke = newIconStroke
            strokeOpacity = newStrokeOpacity
            glyphStroke = newGlyphStroke
            glyphStrokeOpacity = newGlyphStrokeOpacity
            glyphStrokeWidth = newGlyphStrokeWidth
            glyphStrokeBrightness = newGlyphStrokeBrightness
            glyphBottomShade = newGlyphBottomShade
            glyphShadow = newGlyphShadow
            shadowOpacity = newShadowOpacity
            gradientOpacity = newGradientOpacity
            val folderChanged=FolderSettings.update(bundle)
            if(folderChanged || (FolderSettings.own && folderEnabledChanged))WallpaperHelper.invalidate()
            val dockSettingsChanged=DockSettings.update(bundle)
            if(dockSettingsChanged || (DockSettings.hasOwnAppearance && newDockEnabled!=wasDockEnabled))WallpaperHelper.invalidate()
            WallpaperHelper.onProcessingSettingsChanged()
            if(shadowToneChanged) WallpaperHelper.invalidate()

            // Publish all new values before invalidating recorded materials.
            // In particular, a contour toggle must rebuild the overlay itself,
            // not only update the wallpaper sampling RenderNode.
            if (paletteChanged || artworkChanged) {
                DynamicIconForeground.clear()
            }
            if (materialChanged || folderChanged || featureChanged) {
                IconGlassHook.invalidateShaders()
                IconGlassHook.invalidateActiveIcons()
            }
            // Liquid and Frosted use the same native icon sources. Reloading the
            // workspace here rebuilds Quick App hosts and briefly restores their stock plates.
            if (loaded && (masterChanged || backendChanged || (colorOnlyChanged && !autoAppearance) || featureChanged)) {
                IconGlassHook.onIconThemeChanged()
                IconGlassHook.reloadNativeIcons()
            }

            if(enabled){
                ModuleHooks.setEnabled(true)
                com.cosmod.launcherglass.util.LauncherWallpaperCapture.onModuleEnabled()
            }
            if (!appearanceOnly || colorOnlyChanged) LiveBlurEngine.onPrefsChanged()
            NativeSurfaceLive.onSettingsChanged()
            TransitionCapture.onSettingsChanged()
            FolderMaterialHook.onSettingsChanged()
            LiftedBackdrop.onSettingsChanged()
            MultiIconMaterialHook.onSettingsChanged()
            PhotoWidgetMaterial.onSettingsChanged()
            if(dockChanged || dockSettingsChanged || masterChanged || backendChanged ||
                (materialChanged && !DockSettings.auto))DockMaterial.onSettingsChanged()
            if(!enabled){
                IconGlassHook.onModuleDisabled()
                com.cosmod.launcherglass.util.LauncherWallpaperCapture.onModuleDisabled()
                ModuleHooks.setEnabled(false)
            }
            loaded=true
            WallpaperHelper.onPrefsReady()

            GlassLogger.i(
                "PrefsHelper",
                "Preferences loaded: enabled=$enabled, bgOpacity=$bgOpacity%, blur=$blurEnabled(mode=$blurMode, fps=$liveFps, swipeFps=$swipeFps, swipeLead=$swipeLead, idlePause=$idlePause, r=$blurRadius), stroke=$iconStroke($strokeOpacity%), glyphShadow=$glyphShadow($shadowOpacity%), gradient=$gradientOpacity%, palette=$iconPalette"
            )
        } catch (t: Throwable) {
            GlassLogger.e("PrefsHelper", "Failed to load preferences", t)
        }
    }
}
