package com.cosmod.launcherglass.ui

/** Defaults for absent keys only. Existing installations retain their saved values. */
object SettingsDefaults {
    fun initialize(context: android.content.Context, prefs: android.content.SharedPreferences) {
        // Capture this before migrations populate an otherwise fresh preference file.
        val hadSavedValues = prefs.all.isNotEmpty()
        val removed=listOf("refraction_before_blur","folder_refraction_before_blur","photo_widget_refraction_before_blur","artwork_icons",
            "experimental_liquid_optics","dock_event_driven","experimental_compact_dock_effects","experimental_reuse_static_wallpaper",
            "glass_haze_enabled","experimental_haze_bloom","blur_color_depth_enabled","blur_color_depth_bits","blur_color_depth_stage","blur_dither","blur_dither_strength")
        if(removed.any(prefs::contains))prefs.edit().apply {removed.forEach {remove(it)}}.apply()
        val modeEditor=prefs.edit()
        (listOf(SettingsProvider.KEY_BLUR_STYLE)+ScopedSettingsKeys.all.map {it.key(SettingsProvider.KEY_BLUR_STYLE)}).forEach {key ->
            if(prefs.contains(key) && prefs.getInt(key,0)==SettingsProvider.BLUR_STYLE_LIQUID)modeEditor.putInt(key,SettingsProvider.BLUR_STYLE_KYANT)
        }
        modeEditor.apply()
        initializePhoto(prefs)
        if (prefs.getInt("defaults_revision", 0) >= 38) { initializeColors(prefs); initializeTone(prefs); return }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val upgrade = info.firstInstallTime != info.lastUpdateTime || hadSavedValues
        val editor = prefs.edit()
        if (upgrade) {
            // Preserve legacy appearance defaults for absent keys. A missing blur
            // mode uses Live; explicitly saved choices are never overwritten.
            val oldBooleans = booleans + mapOf(SettingsProvider.KEY_ENABLED to true,
                SettingsProvider.KEY_FOLDER_BLUR_ENABLED to true,
                SettingsProvider.KEY_DRAWER_BLUR_ENABLED to true)
            val oldIntegers = mapOf(SettingsProvider.KEY_BG_OPACITY to 35,
                SettingsProvider.KEY_BLUR_RADIUS to 25, SettingsProvider.KEY_BLUR_MODE to SettingsProvider.BLUR_MODE_LIVE,
                SettingsProvider.KEY_LIVE_FPS to 30, SettingsProvider.KEY_STROKE_OPACITY to 70,
                SettingsProvider.KEY_SHADOW_OPACITY to 60, SettingsProvider.KEY_GRADIENT_OPACITY to 60)
            oldBooleans.forEach { (key, value) -> if (!prefs.contains(key)) editor.putBoolean(key, value) }
            oldIntegers.forEach { (key, value) -> if (!prefs.contains(key)) editor.putInt(key, value) }
        }
        editor.remove("force_themed").putInt("defaults_revision", 38).apply()
        initializeColors(prefs)
        initializeTone(prefs)
    }
    private fun initializePhoto(prefs:android.content.SharedPreferences){
        val editor=prefs.edit()
        for((key,base) in listOf(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH to SettingsProvider.KEY_LIQUID_WIDTH,
            SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION to SettingsProvider.KEY_LIQUID_DISTORTION)){
            if(!prefs.contains(key))editor.putInt(key,prefs.getInt(base,integer(base)))
        }
        editor.apply()
    }
    private fun initializeTone(prefs:android.content.SharedPreferences) {
        if(prefs.getBoolean("tone_group_migrated",false))return
        val editor=prefs.edit()
        // Preserve the effective appearance of legacy individual toggles.
        val opacity=prefs.getBoolean("bg_opacity_enabled",true)
        val saturation=prefs.getBoolean("blur_saturation_enabled",false)
        val brightness=prefs.getBoolean("blur_lightening_enabled",false)
        if(!prefs.contains(SettingsProvider.KEY_BLUR_TONE_ENABLED))editor.putBoolean(SettingsProvider.KEY_BLUR_TONE_ENABLED,opacity || saturation || brightness)
        if(!opacity)editor.putInt(SettingsProvider.KEY_BG_OPACITY,0)
        if(!saturation)editor.putInt(SettingsProvider.KEY_BLUR_SATURATION,100)
        if(!brightness)editor.putInt(SettingsProvider.KEY_BLUR_LIGHTENING,50)
        editor.remove("bg_opacity_enabled").remove("blur_saturation_enabled").remove("blur_lightening_enabled")
            .putBoolean("tone_group_migrated",true).apply()
    }
    private fun initializeBlurBrightness(prefs: android.content.SharedPreferences) {
        if (prefs.getInt("blur_brightness_revision", 0) >= 1) return
        val editor = prefs.edit()
        if (prefs.contains(SettingsProvider.KEY_BLUR_LIGHTENING)) {
            val old = prefs.getInt(SettingsProvider.KEY_BLUR_LIGHTENING,0).coerceIn(0,100)
            editor.putInt(SettingsProvider.KEY_BLUR_LIGHTENING,50+(old+1)/2)
        }
        editor.putInt("blur_brightness_revision",1).apply()
    }
    private fun initializeColors(prefs: android.content.SharedPreferences) {
        initializeBlurBrightness(prefs)
        if(prefs.getInt("outline_sliders_revision",0)<1) {
            val editor=prefs.edit()
            if(!prefs.getBoolean("glyph_stroke_width_enabled",false))editor.putInt(SettingsProvider.KEY_GLYPH_STROKE_WIDTH,35)
            if(!prefs.getBoolean("glyph_stroke_brightness_enabled",false))editor.putInt(SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,45)
            editor.remove("glyph_stroke_width_enabled").remove("glyph_stroke_brightness_enabled")
                .putInt("outline_sliders_revision",1).apply()
        }
        if (prefs.getInt("color_profiles_revision", 0) >= 1) {
            if (prefs.contains("color_theme")) prefs.edit().remove("color_theme").apply()
            return
        }
        val activeDark = prefs.getInt("color_theme", 1) == 1
        val legacy = listOf(SettingsProvider.KEY_COLOR_HUE, SettingsProvider.KEY_COLOR_SATURATION, SettingsProvider.KEY_COLOR_BRIGHTNESS)
        val editor = prefs.edit()
        listOf(false, true).forEach { dark ->
            SettingsProvider.colorKeys(dark).forEachIndexed { index, key ->
                if (!prefs.contains(key)) {
                    val value = if (prefs.contains(legacy[index]) && (index != 2 || dark == activeDark))
                        prefs.getInt(legacy[index], integer(key)) else integer(key)
                    editor.putInt(key, value)
                }
            }
        }
        editor.remove("color_theme").putInt("color_profiles_revision", 1).apply()
    }
    private val booleans = mapOf(
        WallpaperProfiles.ENABLED to false,
        SettingsProvider.KEY_SCENE_DRAG_BACKDROP to false,
        SettingsProvider.KEY_HIDE_HOME_GESTURE_BAR to false,
        SettingsProvider.KEY_MUSIC_ISLAND_EXPANSION to false,
        SettingsProvider.KEY_STATIC_ANIMATION_BLUR to false,
        SettingsProvider.KEY_MODULE_LOGGING to false,
        SettingsProvider.KEY_APP_WINDOW_TILT to false,
        SettingsProvider.KEY_APP_GENIE to true,
        SettingsProvider.KEY_APP_DROPLET to false,
        SettingsProvider.KEY_APP_GESTURE_BOUNCE to false,
        SettingsProvider.KEY_BOOT_RESTART to true,
        SettingsProvider.KEY_BOOT_RESTART_TOAST to true,
        SettingsProvider.KEY_DEVELOPER_MODE to false,
        SettingsProvider.KEY_GPU_EFFECTS to true,
        SettingsProvider.KEY_LIMIT_CLOSING_BLUR to true,
        SettingsProvider.KEY_SMOOTH_SWIPE_LEAD to false,
        SettingsProvider.KEY_SWIPE_LEAD to false,
        SettingsProvider.KEY_KYANT_MAGNIFIER to false,
        SettingsProvider.KEY_KYANT_CONTROL_CENTER to false,
        SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION to true,
        SettingsProvider.KEY_OPTIMIZE_MINI_ICONS to true,
        SettingsProvider.KEY_MD3_UI to true,
        SettingsProvider.KEY_DOCK_ENABLED to true,
        SettingsProvider.KEY_DOCK_AUTO_LENGTH to true,
        SettingsProvider.KEY_PHOTO_WIDGET_BLUR to false,
        SettingsProvider.KEY_PHOTO_WIDGET_TINT to false,
        SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED to true,
        SettingsProvider.KEY_BLUR_RADIUS_ENABLED to true,
        SettingsProvider.KEY_BLUR_TONE_ENABLED to true,
        SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED to true,
        SettingsProvider.KEY_GLYPH_STROKE to false,
        SettingsProvider.KEY_ENABLED to false,
        SettingsProvider.KEY_BLUR_ENABLED to true,
        SettingsProvider.KEY_FOLDER_BLUR_ENABLED to false,
        SettingsProvider.KEY_DRAWER_BLUR_ENABLED to false,
        SettingsProvider.KEY_IDLE_PAUSE to true,
        SettingsProvider.KEY_ICON_STROKE to true,
        SettingsProvider.KEY_GLYPH_SHADOW to true,
        SettingsProvider.KEY_DESIGN_ENABLED to true,
        SettingsProvider.KEY_VOLUME_ENABLED to true
    )
    private val integers = mapOf(
        SettingsProvider.KEY_BLUR_STYLE to SettingsProvider.BLUR_STYLE_KYANT,
        SettingsProvider.KEY_ARTWORK_TRANSPARENCY to 40,
        // Proportions of the reference dock: about a quarter of the icon size
        // around the icons, corners that follow the icon corners.
        SettingsProvider.KEY_DOCK_SIDE_PADDING to 26,
        SettingsProvider.KEY_DOCK_VERTICAL_PADDING to 27,
        SettingsProvider.KEY_DOCK_RADIUS to 52,
        SettingsProvider.KEY_DOCK_BLUR_HEIGHT to 100,
        SettingsProvider.KEY_DOCK_BLUR_Y to 0,
        SettingsProvider.KEY_BLUR_QUALITY to 0,
        SettingsProvider.KEY_LIQUID_WIDTH to 40,
        SettingsProvider.KEY_LIQUID_DISTORTION to 50,
        SettingsProvider.KEY_GLYPH_STROKE_WIDTH to 35,
        SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS to 45,
        SettingsProvider.KEY_GLYPH_STROKE_OPACITY to 40,
        SettingsProvider.KEY_BLUR_VIBRANCY to 0,
        SettingsProvider.KEY_PHOTO_WIDGET_OPACITY to 55,
        SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH to 40,
        SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION to 50,
        SettingsProvider.KEY_BLUR_SATURATION to 100,
        SettingsProvider.KEY_BLUR_LIGHTENING to 50,
        SettingsProvider.KEY_ICON_APPEARANCE to 1,
        SettingsProvider.KEY_CLEAR_BRIGHTNESS to 70,
        SettingsProvider.KEY_LIGHT_HUE to 30,
        SettingsProvider.KEY_LIGHT_SATURATION to 25,
        SettingsProvider.KEY_LIGHT_BRIGHTNESS to 85,
        SettingsProvider.KEY_DARK_HUE to 30,
        SettingsProvider.KEY_DARK_SATURATION to 25,
        SettingsProvider.KEY_DARK_BRIGHTNESS to 30,
        SettingsProvider.KEY_COLOR_MODE to 0,
        SettingsProvider.KEY_COLOR_HUE to 30,
        SettingsProvider.KEY_COLOR_SATURATION to 25,
        SettingsProvider.KEY_COLOR_BRIGHTNESS to 30,
        SettingsProvider.KEY_BG_OPACITY to 40,
        SettingsProvider.KEY_BLUR_RADIUS to 10,
        SettingsProvider.KEY_BLUR_MODE to SettingsProvider.BLUR_MODE_LIVE,
        SettingsProvider.KEY_LIVE_FPS to 60,
        SettingsProvider.KEY_FLIGHT_ICON_REVEAL to 200,
        SettingsProvider.KEY_APP_OPEN_RESPONSE to 300,
        SettingsProvider.KEY_APP_CLOSE_SPEED to 70,
        SettingsProvider.KEY_APP_GENIE_STRENGTH to 200,
        SettingsProvider.KEY_APP_ANIMATION_SLOWDOWN to 100,
        SettingsProvider.KEY_SWIPE_FPS to 120,
        SettingsProvider.KEY_KYANT_WIDTH to 45,
        SettingsProvider.KEY_KYANT_DISTORTION to 300,
        SettingsProvider.KEY_KYANT_HIGHLIGHT to 30,
        SettingsProvider.KEY_KYANT_DISPERSION to 50,
        SettingsProvider.KEY_KYANT_ZOOM to 28,
        SettingsProvider.KEY_KYANT_VIBRANCY to 100,
        SettingsProvider.KEY_STROKE_OPACITY to 40,
        SettingsProvider.KEY_SHADOW_OPACITY to 40,
        SettingsProvider.KEY_GRADIENT_OPACITY to 50
    ) + com.cosmod.launcherglass.util.DesktopSizing.defaults
    private val dockDefaults=mapOf(
        "dock_kyant_width" to 80,"dock_kyant_dispersion" to 148,"dock_kyant_highlight" to 70,
        "dock_blur_radius" to 8,"dock_bg_opacity" to 7,"dock_blur_saturation" to 30,
        "dock_blur_vibrancy" to 121,"dock_blur_lightening" to 52,"dock_blur_style" to SettingsProvider.BLUR_STYLE_KYANT)
    /** Only portable module preferences. Device reports, language and migration keys stay local. */
    fun portable(): Map<String, Any> = (booleans - WallpaperProfiles.ENABLED - SettingsProvider.KEY_BOOT_RESTART - SettingsProvider.KEY_BOOT_RESTART_TOAST - SettingsProvider.KEY_MD3_UI) + integers +
        ScopedSettingsKeys.all.flatMap { scope -> listOf(scope.own to false) +
            scope.booleans.map { scope.key(it) to boolean(it) } + scope.integers.map { scope.key(it) to integer(scope.key(it)) } }
    fun boolean(key: String): Boolean = if(ScopedSettingsKeys.isOwnKey(key))false else booleans[key] ?: ScopedSettingsKeys.baseOf(key)?.let(::boolean) ?: booleans.getValue(key)
    fun integer(key: String): Int = dockDefaults[key] ?: integers[key] ?: ScopedSettingsKeys.baseOf(key)?.let(::integer) ?: integers.getValue(key)
    fun value(key: String): Any = if(ScopedSettingsKeys.isOwnKey(key))false else booleans[key] ?: dockDefaults[key] ?: integers[key] ?: ScopedSettingsKeys.baseOf(key)?.let(::value) ?: integers.getValue(key)
}
