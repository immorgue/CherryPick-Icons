package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.DesktopSizing
import com.cosmod.launcherglass.util.AutoIconStyle
import com.cosmod.launcherglass.xposed.WidgetKind
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.SuUtil
import com.cosmod.launcherglass.util.LsposedScopeStatus
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import com.google.android.material.R as M

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var page: FrameLayout
    private val executor = Executors.newSingleThreadExecutor()
    private val importSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::importPreset)
    }
    private data class Gate(val view: View, val allowed: () -> Boolean)
    private val gates = mutableListOf<Gate>()
    private val resets = mutableListOf<Pair<View, List<String>>>()
    private var currentScroll: androidx.core.widget.NestedScrollView? = null
    private var navigation=SettingsNavigation()
    private lateinit var screenHost:FrameLayout
    private var activeShell:View?=null
    private var visibleScreen=SettingsScreen.HOME
    private val scrollOffsets=mutableMapOf<SettingsScreen,Int>()
    private var bottomPill:FloatingNavigationPill?=null
    private var moduleRestart:View?=null
    private var folderExpanded=true
    private var dockExpanded=false
    private var appearancePreview:ModuleAppearancePreview?=null
    private var iconPanel=0
    private var colorExpanded=true
    private var previewWallpaper:android.graphics.Bitmap?=null
    private var previewWallpaperLoaded=false
    private var previewWallpaperLoading=false
    private var previewWallpaperRevision=0
    private var previewGlyphs: List<AppearanceIcons.Sample> = emptyList()
    private val visiblePreviews = mutableListOf<ModuleAppearancePreview>()
    private val appPreviewIcon by lazy { AppearanceIcons.app(this) }
    private val modulePreviewImages by lazy { ModuleTilePreview.Images(resources) }
    private var systemInsets = androidx.core.graphics.Insets.NONE
    private var glassHeaderInset: ((Int) -> Unit)? = null
    private var headerGlass: CouiScrollGlass? = null
    private var activePopup: CouiGlassPopup? = null
    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { reloadPreviewWallpaper() }
    }
    private val wallpaperColorsListener = android.app.WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and android.app.WallpaperManager.FLAG_SYSTEM != 0) reloadPreviewWallpaper()
    }
    private var blurExpanded = true
    private var designExpanded = false
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var lsposedText:TextView?=null
    private var statusHint:TextView?=null
    private var fluxWarning:View?=null
    private val systemColorObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            modulePreviewImages.clearSnapshots()
            visiblePreviews.forEach { it.refresh() }
            headerGlass?.invalidate()
            bottomPill?.invalidateBackdrop()
            activePopup?.invalidateBackdrop()
        }
    }
    private val supportListener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        runOnUiThread {
            modulePreviewImages.clearSnapshots()
            visiblePreviews.forEach { it.refresh() }
            headerGlass?.invalidate()
            bottomPill?.invalidateBackdrop()
            activePopup?.invalidateBackdrop()
        }
        if(key==SettingsProvider.KEY_MODULE_LOGGING)com.cosmod.launcherglass.util.GlassLogger.enabled=bool(SettingsProvider.KEY_MODULE_LOGGING)
        if(key==SettingsProvider.KEY_NATIVE_FLUX_SUPPORT)runOnUiThread {updateFluxWarning()}
    }
    private var scopeState:LsposedScopeStatus.State?=null
    private var moduleToggle:View?=null
    private var moduleBlink:ValueAnimator?=null
    private var moduleBlinkTarget:View?=null
    private var overviewResumed=false
    private var creditsTicker:CreditsTicker?=null
    private var statusEpoch=0
    private var restartBusy = false
    private var updatingUi = false
    private val refreshers = mutableListOf<() -> Unit>()
    private val toggleViews = mutableListOf<Pair<SquareAction,String>>()
    private val switches = mutableListOf<Pair<CompoundButton,String>>()
    private var useCoui = true
    private val cancelHolds = mutableListOf<() -> Unit>()
    private val primary get() = color(M.attr.colorPrimary)
    private val controlAccent get() = ContextCompat.getColor(this,R.color.ui_control_accent)
    private val onSurface get() = color(M.attr.colorOnSurface)
    private val secondaryText get() = color(M.attr.colorOnSurfaceVariant)
    private fun color(attr: Int) = MaterialColors.getColor(this, attr, Color.GRAY)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    // A scoped (folder/dock) key that was never saved inherits the current icon value.
    private fun bool(key:String):Boolean = ScopedSettingsKeys.baseOf(key)?.let {base -> prefs.getBoolean(key,prefs.getBoolean(base,SettingsDefaults.boolean(base)))}
        ?: prefs.getBoolean(key,SettingsDefaults.boolean(key))
    private fun number(key:String):Int {
        val base=ScopedSettingsKeys.baseOf(key)
        val value=base?.let {prefs.getInt(key,prefs.getInt(it,SettingsDefaults.integer(it)))}
            ?: prefs.getInt(key,SettingsDefaults.integer(key))
        return if((base ?: key)==SettingsProvider.KEY_BLUR_STYLE)com.cosmod.launcherglass.util.MaterialMode.normalize(value)else value
    }
    private fun darkIcons() = com.cosmod.launcherglass.util.IconColorTheme.isDark(number(SettingsProvider.KEY_ICON_COLOR_THEME),
        prefs.getBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK, com.cosmod.launcherglass.util.IconPalette.isSystemDark(this)))
    private fun master() = bool(SettingsProvider.KEY_ENABLED)
    private fun colorOnly() = number(SettingsProvider.KEY_BLUR_STYLE)==SettingsProvider.BLUR_STYLE_COLOR
    private fun blur() = master() && bool(SettingsProvider.KEY_BLUR_ENABLED)
    private fun folderOwn()=blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED) && bool(FolderSettingsKeys.OWN)
    private fun dockOwn()=master() && bool(SettingsProvider.KEY_DOCK_ENABLED) && bool(DockSettingsKeys.OWN) &&
        !bool(SettingsProvider.KEY_DOCK_AUTO_APPEARANCE)
    private fun design() = master() && bool(SettingsProvider.KEY_DESIGN_ENABLED)

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(UiLanguage.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(SettingsProvider.PREFS_NAME, MODE_PRIVATE)
        useCoui = !bool(SettingsProvider.KEY_LEGACY_INTERFACE)
        if (useCoui) setTheme(R.style.Theme_LauncherGlass_Coui)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (useCoui) {
            window.navigationBarColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
        }
        SettingsDefaults.initialize(this, prefs)
        com.cosmod.launcherglass.util.GlassLogger.enabled=bool(SettingsProvider.KEY_MODULE_LOGGING)
        prefs.registerOnSharedPreferenceChangeListener(supportListener)
        registerReceiver(wallpaperReceiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), Context.RECEIVER_EXPORTED)
        android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(
            wallpaperColorsListener, android.os.Handler(android.os.Looper.getMainLooper()))
        com.cosmod.launcherglass.util.SystemIconColors.changedUris().forEach { uri ->
            runCatching { contentResolver.registerContentObserver(uri, false, systemColorObserver) }
        }
        navigation=SettingsNavigation(savedInstanceState?.getIntArray("navigation"))
        savedInstanceState?.let { scrollOffsets[navigation.current] = it.getInt("scrollOffset") }
        iconPanel=savedInstanceState?.getInt("iconPanel") ?: 0
        blurExpanded = savedInstanceState?.getBoolean("blurExpanded") ?: true
        designExpanded = savedInstanceState?.getBoolean("designExpanded") ?: false
        root = column().apply { setBackgroundColor(color(M.attr.colorSurface)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            updateScreenInsets()
            insets
        }
        page = FrameLayout(this)
        root.addView(page, LinearLayout.LayoutParams(-1, -1))
        screenHost=FrameLayout(this)
        page.addView(screenHost,FrameLayout.LayoutParams(-1,-1))
        showPage(false)
        if(savedInstanceState==null && (intent?.action==Intent.ACTION_SEND || intent?.action==Intent.ACTION_VIEW)){
            @Suppress("DEPRECATION")
            val incoming=if(intent.action==Intent.ACTION_SEND)intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)else intent.data
            if(incoming?.scheme=="content")importPreset(incoming)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigation.back()) { showPage(backwards=true) }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        overviewResumed=true
        creditsTicker?.setRunning(true)
        if(::prefs.isInitialized) {
            reloadPreviewWallpaper()
            refreshControls()
            updateStatus()
            val epoch=++statusEpoch
            executor.execute {
                val state=LsposedScopeStatus.read(this)
                runOnUiThread { if(epoch==statusEpoch && !isDestroyed) { scopeState=state;updateStatus() } }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("iconPanel",iconPanel)
        outState.putIntArray("navigation",navigation.save())
        outState.putInt("scrollOffset", currentScroll?.scrollY ?: 0)
        outState.putBoolean("blurExpanded", blurExpanded)
        outState.putBoolean("designExpanded", designExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        activePopup?.dismissImmediately();activePopup=null
        overviewResumed=false;stopModuleBlink();creditsTicker?.setRunning(false)
        cancelHolds.toList().forEach { it() };super.onPause()
    }
    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(supportListener)
        contentResolver.unregisterContentObserver(systemColorObserver)
        unregisterReceiver(wallpaperReceiver)
        android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperColorsListener)
        executor.shutdown()
        super.onDestroy()
    }
    private fun refreshControls() {
        updatingUi = true
        try {
            toggleViews.forEach { (button,key) ->
                button.isChecked=bool(key)
                button.glyph.imageTintList=ColorStateList.valueOf(if(bool(key))Color.WHITE else secondaryText)
                button.backgroundTintList=ColorStateList.valueOf(if(bool(key))controlAccent else color(M.attr.colorSurfaceContainerHigh))
            }
            switches.forEach { (view,key) -> if(view.isChecked!=bool(key))view.isChecked=bool(key) }
            refreshers.forEach { it() }
        } finally { updatingUi=false }
        updateGates()
    }
    private val reveals=java.util.WeakHashMap<View,ValueAnimator>()
    private fun reveal(view:View,open:Boolean) {
        reveals.remove(view)?.cancel()
        val from=if(view.visibility==View.GONE)0 else view.height
        val width=(view.parent as View).width.coerceAtLeast(1)
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val to=if(open)view.measuredHeight else 0
        view.visibility=View.VISIBLE
        view.layoutParams=view.layoutParams.apply { height=from }
        val animator=ValueAnimator.ofInt(from,to).apply {
            duration=260;interpolator=android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { view.layoutParams=view.layoutParams.apply { height=(it.animatedValue as Int).coerceAtLeast(0) } }
            addListener(object:android.animation.AnimatorListenerAdapter(){
                private var canceled=false
                override fun onAnimationCancel(a:android.animation.Animator){canceled=true}
                override fun onAnimationEnd(a:android.animation.Animator){if(!canceled){view.visibility=if(open)View.VISIBLE else View.GONE;view.layoutParams=view.layoutParams.apply {height=ViewGroup.LayoutParams.WRAP_CONTENT};reveals.remove(view)}}
            })
        }
        reveals[view]=animator;animator.start()
    }
    private fun layoutTransition(parent: ViewGroup, durationMs:Long=264) {
        TransitionManager.beginDelayedTransition(parent,AutoTransition().apply {
            duration=durationMs;interpolator=CherryMotion
            // The action strip owns its widths and glyphs. ChangeBounds must
            // never capture and animate the same cells a second time.
            excludeTarget(ConnectedActions::class.java,true)
            excludeChildren(ConnectedActions::class.java,true)
        })
    }
    private fun pulse(view:View,time:Long=1000) { (view.parent as? ConnectedActions)?.pulse(view,time) }


    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = (if (useCoui) CouiPressRow(this) else LinearLayout(this)).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 16f, muted: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(if (muted) secondaryText else onSurface)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun add(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) })
    }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun haptic(view: View, tick: Boolean = false) {
        view.performHapticFeedback(if (tick) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.CONFIRM)
    }
    private fun card(content: View, inner: Boolean = false, contentInset: Int = 0): MaterialCardView = (if(useCoui) CouiCardView(this) else MaterialCardView(this)).apply {
        cardElevation = 0f
        if (useCoui && contentInset > 0) {
            val inset = dp(contentInset)
            setContentPadding(0, inset, 0, inset)
        }
        if (useCoui) {
            radius = dp(if (inner) 14 else 22).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(color(M.attr.colorSurfaceContainer))
            doOnLayout {
                val outer = CouiGeometry.cardRadius(this)
                radius = if (inner) CouiGeometry.insetRadius(outer, dp(8).toFloat()) else outer
            }
            ContinuousOutline.apply(this) { radius }
        } else {
            radius = dp(if (inner) 20 else 28).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(color(if (inner) M.attr.colorSurfaceContainerHigh else M.attr.colorSurfaceContainerLow))
        }
        addView(content)
    }
    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(color(M.attr.colorOutlineVariant)) },
            LinearLayout.LayoutParams(-1, if (useCoui) 1 else dp(1)).apply {
                val inset = dp(if (useCoui) 16 else 20)
                marginStart = inset
                marginEnd = inset
            })
    }
    private fun gate(view: View, allowed: () -> Boolean) { gates.add(Gate(view, allowed)) }
    private fun enableTree(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) enableTree(view.getChildAt(i), enabled)
    }
    private fun updateGates() {
        val ordered = gates.sortedBy { gate ->
            generateSequence(gate.view.parent as? View) { it.parent as? View }.count()
        }
        ordered.forEach {
            var ancestor = it.view.parent as? View
            var dimmedParent = false
            while (ancestor != null && ancestor !== page) {
                if (!ancestor.isEnabled) { dimmedParent = true; break }
                ancestor = ancestor.parent as? View
            }
            val allowed = it.allowed()
            enableTree(it.view, allowed && !dimmedParent)
            it.view.alpha = if (!allowed && !dimmedParent) .38f else 1f
        }
        resets.forEach { (view, keys) ->
            var ancestor = view.parent as? View
            var allowed = true
            while (ancestor != null && ancestor !== page) {
                val node = ancestor ?: break
                if (!node.isEnabled) { allowed = false; break }
                ancestor = node.parent as? View
            }
            val changed = keys.any { key ->
                val default = SettingsDefaults.value(key)
                if (default is Boolean) bool(key) != default else number(key) != default
            }
            view.isEnabled = allowed && changed
            view.alpha = if (allowed && !changed) .65f else 1f
        }
    }
    private fun save(key: String, value: Any, related: SharedPreferences.Editor.() -> Unit = {}) {
        if(updatingUi)return
        if (key == SettingsProvider.KEY_LEGACY_INTERFACE) {
            prefs.edit().putBoolean(key, value as Boolean).apply()
            recreate()
            return
        }
        prefs.edit().apply {
            // Own settings start as a copy of the icon values instead of jumping to defaults.
            ScopedSettingsKeys.all.firstOrNull {it.own==key}?.takeIf {value==true}?.let {scope ->
                scope.integers.forEach {base -> val own=scope.key(base);if(!prefs.contains(own))putInt(own,if(base==SettingsProvider.KEY_BLUR_STYLE && colorOnly())SettingsProvider.BLUR_STYLE_FROSTED else number(base))}
                scope.booleans.forEach {base -> val own=scope.key(base);if(!prefs.contains(own))putBoolean(own,bool(base))}
            }
            related()
            when(value) { is Boolean -> putBoolean(key, value); is Int -> putInt(key, value) } }.apply()
        contentResolver.notifyChange(SettingsProvider.CONTENT_URI, null)
        if(prefs.getBoolean(WallpaperProfiles.ENABLED,true))WallpaperProfiles.saveCurrent(this,prefs)
        appearancePreview?.refresh()
        if (navigation.current == SettingsScreen.WIDGETS && (key in widgetPreviewSwitches || key == SettingsProvider.KEY_PHOTO_WIDGET_TINT)) {
            visiblePreviews.forEach { it.refresh() }
        }
        updateGates();updateStatus()
        if(key in ModuleFeatures.keys || ScopedSettingsKeys.isOwnKey(key) || key==SettingsProvider.KEY_DEVELOPER_MODE || key==WallpaperProfiles.ENABLED || key==SettingsProvider.KEY_BLUR_STYLE)refreshControls()
    }
    private fun resetToDefaults(keys: List<String>) {
        val editor=prefs.edit()
        keys.forEach { item -> when(val value=SettingsDefaults.value(item)) {
            is Boolean -> editor.putBoolean(item,value)
            is Int -> editor.putInt(item,value)
        } }
        editor.apply()
        contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null)
        refreshControls()
    }
    private fun showLanguageDialog(view: View) {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_language))
            .setSingleChoiceItems(arrayOf("Русский","English"),if(UiLanguage.tag(this)=="ru")0 else 1) { dialog,index ->
                haptic(view);UiLanguage.choose(this,if(index==0)"ru" else "en");dialog.dismiss();recreate()
            }.setNegativeButton(getString(R.string.ui_close),null).show()
    }
    private fun toggle(label: String, key: String): View =
        if (useCoui) sectionSwitch(label, key) else makeSwitch(label, key)

    private fun sectionSwitch(label: String, key: String): CompoundButton =
        (if (useCoui) CouiSwitch(this, controlAccent) else MaterialSwitch(this).apply {
            trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
                intArrayOf(controlAccent,color(M.attr.colorSurfaceContainerHigh)))
            thumbTintList=ColorStateList.valueOf(Color.WHITE)
        }).apply {
            isChecked = bool(key)
            contentDescription = label
            setOnCheckedChangeListener { view, checked ->
                if (!updatingUi) { haptic(view); save(key, checked) }
            }
            switches.add(this to key)
        }

    private fun reset(key: String): View = if (useCoui) {
        iconButton(R.drawable.ic_restart, getString(R.string.ui_reset_defaults)) { confirmReset(key) }
            .also { resets.add(it to resetKeys(key)) }
    } else resetButton(key)

    private fun actions(views: List<View>): View = if (useCoui) row().apply {
        views.forEach { addView(it) }
    } else ConnectedActions(this, views)

    private fun rowRipple(view: View) {
        if (useCoui) {
            view.background = android.graphics.drawable.StateListDrawable().apply {
                val fill = (onSurface and 0xffffff) or 0x16000000
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(fill))
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(fill))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setEnterFadeDuration(50); setExitFadeDuration(160)
            }
            return
        }
        val value = android.util.TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) view.setBackgroundResource(value.resourceId)
    }
    private fun screenTitle()=when(navigation.current) {
        SettingsScreen.HOME -> "CherryPick Icons"
        SettingsScreen.MODULE -> getString(R.string.ui_nav_module)
        SettingsScreen.APP -> getString(R.string.ui_settings)
        SettingsScreen.COLORS -> getString(R.string.ui_nav_colors)
        SettingsScreen.BLUR -> getString(R.string.ui_material_mode)
        SettingsScreen.ICONS -> getString(R.string.ui_icon_design)
        SettingsScreen.FOLDERS -> getString(R.string.ui_nav_folders)
        SettingsScreen.DOCK -> getString(R.string.ui_nav_dock)
        SettingsScreen.WIDGETS -> getString(R.string.ui_widgets)
        SettingsScreen.DESKTOP -> getString(R.string.ui_desktop_title)
        SettingsScreen.POSITIONS -> getString(R.string.ui_desktop_title)
        SettingsScreen.WALLPAPERS -> getString(R.string.ui_nav_wallpapers)
        SettingsScreen.BEHAVIOR -> getString(R.string.ui_nav_behavior)
        SettingsScreen.DEVELOPER -> getString(R.string.ui_developer_mode)
        SettingsScreen.ANIMATIONS -> getString(R.string.ui_app_animation_title)
        SettingsScreen.ICON_STUDIO -> getString(R.string.ui_group_icons)
        SettingsScreen.WORKSPACE -> getString(R.string.ui_group_workspace)
        SettingsScreen.PERSONALIZATION -> getString(R.string.ui_group_personalization)
    }
    private fun navigate(screen:SettingsScreen){
        if(!navigation.open(screen))return
        when(screen){
            SettingsScreen.ICON_STUDIO -> iconPanel=0
            SettingsScreen.BLUR -> blurExpanded=false
            SettingsScreen.ICONS -> designExpanded=true
            SettingsScreen.FOLDERS -> folderExpanded=true
            SettingsScreen.DOCK -> dockExpanded=true
            else -> Unit
        }
        showPage()
    }
    private fun goBack(){if(navigation.back())showPage(backwards=true)}
    private fun pageHeader(): View {
        return MaterialToolbar(this).apply {
            title = screenTitle()
            minimumHeight = dp(64)
            setTitleTextAppearance(context, M.style.TextAppearance_Material3_TitleLarge)
            setTitleTextColor(onSurface)
            if (navigation.canGoBack) {
                setNavigationIcon(R.drawable.ic_arrow_back)
                navigationIcon?.mutate()?.setTint(onSurface)
                navigationContentDescription = getString(R.string.ui_back)
                setNavigationOnClickListener { haptic(it); goBack() }
            }
        }
    }

    private fun rootTab() = navigation.current in setOf(SettingsScreen.HOME, SettingsScreen.MODULE, SettingsScreen.APP)
    private fun couiIconStudio() = useCoui && navigation.current == SettingsScreen.ICON_STUDIO
    private fun couiModule() = useCoui && navigation.current == SettingsScreen.MODULE

    private fun updateScreenInsets() {
        val bars = systemInsets
        root.setPadding(bars.left, if (useCoui) 0 else bars.top, bars.right, if (useCoui) 0 else bars.bottom)
        glassHeaderInset?.invoke(bars.top)
        bottomPill?.let { it.layoutParams = (it.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = dp(12) + bottomInset()
        } }
        moduleRestart?.let { it.layoutParams = (it.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = dp(92) + bottomInset()
        } }
    }

    private fun bottomInset() = if (useCoui) systemInsets.bottom else 0

    private fun couiChromeButton(icon: Int, label: Int, primaryAction: Boolean = false,
        action: (View) -> Unit): View = FrameLayout(this).apply {
        val inset = dp(4)
        if (primaryAction) background = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(controlAccent) }, inset)
        else addView(UiLiquidGlass(this@MainActivity, { currentScroll }, 20f),
            FrameLayout.LayoutParams(-1, -1).apply { setMargins(inset, inset, inset, inset) })
        addView(AppCompatImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(if (primaryAction) Color.WHITE else onSurface)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        contentDescription = getString(label)
        isFocusable = true
        foreground = RippleDrawable(ColorStateList.valueOf((onSurface and 0xffffff) or 0x14000000),
            null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        setOnClickListener { haptic(it); action(it) }
    }

    private fun couiSwitchRow(label: String, key: String, subtitle: String?, expanded: Boolean,
        onExpand: ((Boolean) -> Unit)?): LinearLayout {
        val line = row().apply {
            setPaddingRelative(dp(16), dp(4), dp(12), dp(4))
            minimumHeight = dp(if (subtitle != null) 64 else 52)
        }
        val labels = column()
        add(labels, text(label, 16f))
        if (subtitle != null) add(labels, text(subtitle, 13f, true), 4)
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = sectionSwitch(label, key)
        val chevron = onExpand?.let { expandButton(expanded, label, it) }
        line.addView(actions(listOfNotNull(toggle, chevron)))
        rowRipple(line)
        line.setOnClickListener { (chevron ?: toggle).performClick() }
        if (key != SettingsProvider.KEY_LEGACY_INTERFACE) {
            line.setOnLongClickListener { confirmReset(key); true }
        }
        return line
    }

    private fun iconButton(icon: Int, label: String, filled: Boolean = false, action: (View) -> Unit): View {
        return AppCompatImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageResource(icon); contentDescription = label
            setPadding(dp(12), dp(12), dp(12), dp(12))
            imageTintList = ColorStateList.valueOf(color(if (filled) M.attr.colorOnPrimaryContainer else M.attr.colorOnSurfaceVariant))
            val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(if (filled) M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh)) }
            background = RippleDrawable(ColorStateList.valueOf(((primary and 0x00ffffff) or 0x20000000)), shape, null)
            setOnTouchListener { view, event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(1.10f).scaleY(1.10f).setDuration(143).setInterpolator(CherryMotion).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setInterpolator(CherryMotion).setDuration(242).start()
                }
                false
            }
            setOnClickListener { haptic(it); action(it) }
        }
    }

    private fun showPage(animate:Boolean=true,backwards:Boolean=false) {
        currentScroll?.let {scrollOffsets[visibleScreen]=it.scrollY}
        cancelHolds.toList().forEach {it()};cancelHolds.clear()
        gates.clear();resets.clear();refreshers.clear();toggleViews.clear();switches.clear()
        stopModuleBlink();moduleToggle=null;creditsTicker?.setRunning(false);creditsTicker=null
        statusText=null;statusDot=null;lsposedText=null;statusHint=null;fluxWarning=null
        val old=activeShell
        old?.animate()?.cancel()
        for(i in screenHost.childCount-1 downTo 0){val child=screenHost.getChildAt(i);if(child!==old)screenHost.removeView(child)}
        val shell=column()
        val glassHeader = useCoui
        val largeTitle = useCoui && rootTab()
        glassHeaderInset = null
        headerGlass = null
        if (!largeTitle && !glassHeader) add(shell, pageHeader())
        appearancePreview=null
        visiblePreviews.clear()
        val previewScope=when(navigation.current){
            SettingsScreen.ICON_STUDIO -> "icons"
            SettingsScreen.COLORS, SettingsScreen.BLUR, SettingsScreen.ICONS -> if (useCoui) "icons" else null
            SettingsScreen.FOLDERS -> "folders"
            SettingsScreen.DOCK -> "dock"
            else -> null
        }
        if(previewScope!=null && !couiIconStudio() && !useCoui)addAppearancePreview(shell,previewScope)
        if(navigation.current==SettingsScreen.ICON_STUDIO && !useCoui)addIconConfigurator(shell)
        val scroll=androidx.core.widget.NestedScrollView(this).apply {isFillViewport=true;clipToPadding=false}
        currentScroll=scroll
        val inset = dp(if (useCoui) CouiGeometry.PAGE_INSET_DP else 20)
        val bottom = when {
            couiIconStudio() -> 24
            useCoui && !rootTab() -> 24
            navigation.current.tab == 1 -> if (glassHeader) 92 else 184
            else -> 112
        }
        val body = column().apply {
            setPadding(inset, dp(if (largeTitle) 30 else 8), inset, dp(bottom) + bottomInset())
        }
        scroll.addView(body,ViewGroup.LayoutParams(-1,-2))
        if (glassHeader) {
            val viewport = FrameLayout(this)
            viewport.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            val chrome = FrameLayout(this)
            val backdrop = CouiScrollGlass(this, scroll, color(M.attr.colorSurface))
            headerGlass = backdrop
            // Keep the fading backdrop independent of the interactive header's clip.
            // Extending a child outside chrome also let the large title escape its viewport.
            viewport.addView(backdrop, FrameLayout.LayoutParams(-1, dp(124)))
            val grid = couiIconStudio()
            val previewGrid = previewScope == "icons"
            val previewMaxDp = minOf(if (previewGrid) 216 else 152, maxOf(104, resources.configuration.screenHeightDp / 4))
            val pinned = previewScope?.let { scope ->
                val preview = appearanceSample(scope, grid = previewGrid)
                appearancePreview = preview
                PinnedAppearancePreview(this, preview, dp(previewMaxDp),
                    dp(if (previewGrid) minOf(104, previewMaxDp - 24).coerceAtLeast(64) else previewMaxDp), previewGrid, scroll).also {
                    chrome.addView(it, FrameLayout.LayoutParams(-1, dp(previewMaxDp)).apply {
                        marginStart = inset
                        marginEnd = inset
                    })
                }
            }
            val expandedTitle = text(screenTitle(), 32f, bold = true).apply {
                isAccessibilityHeading = true
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, dp(12))
            }
            expandedTitle.measure(View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels - 2 * inset,
                View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val expandedTitleHeight = expandedTitle.measuredHeight.coerceAtLeast(dp(52))
            backdrop.scrollingTitle=expandedTitle
            scroll.doOnLayout { body.minimumHeight = scroll.height + expandedTitleHeight + (pinned?.collapseRange ?: 0) }
            val titleHost = FrameLayout(this).apply {
                clipChildren = true
                addView(expandedTitle, FrameLayout.LayoutParams(-1, expandedTitleHeight))
            }
            val titleParams = FrameLayout.LayoutParams(-1, expandedTitleHeight).apply {
                marginStart = inset; marginEnd = inset
            }
            // The moving title belongs below the backdrop, just like scrolling content.
            // Never crop it at the toolbar boundary: the backdrop performs the transition.
            viewport.addView(titleHost, 1, titleParams)
            val compactTitle = text(screenTitle(), 18f, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val controls = row().apply {
                setPaddingRelative(dp(12), 0, dp(12), 0)
                if (navigation.canGoBack) addView(
                    couiChromeButton(R.drawable.ic_arrow_back, R.string.ui_back) { goBack() },
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) })
                addView(compactTitle, LinearLayout.LayoutParams(0, -2, 1f))
                if (navigation.current.tab == 1) addView(
                    couiChromeButton(R.drawable.ic_restart, R.string.ui_restart_launcher, action = ::restart),
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) })
                if (grid) addView(
                    couiChromeButton(R.drawable.ic_check, R.string.ui_coui_done, primaryAction = true) { goBack() },
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
            }
            val controlsParams = FrameLayout.LayoutParams(-1, dp(60))
            chrome.addView(controls, controlsParams)
            viewport.addView(chrome, FrameLayout.LayoutParams(-1, dp(68)))
            fun updateHeader(top: Int) {
                val toolbarBottom = top + dp(60)
                val titleScroll = scroll.scrollY.coerceIn(0, expandedTitleHeight)
                val titleHeight = expandedTitleHeight - titleScroll
                val titleProgress = titleScroll.toFloat() / expandedTitleHeight
                compactTitle.alpha = ((titleProgress - .55f) / .45f).coerceIn(0f, 1f)
                backdrop.titleCoverage=1f-titleProgress
                titleHost.translationY = -titleScroll.toFloat()
                expandedTitle.alpha=1f-titleProgress
                titleHost.importantForAccessibility = if (titleHeight == 0) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                compactTitle.importantForAccessibility = if (titleHeight == 0) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (titleParams.topMargin != toolbarBottom)
                    titleHost.layoutParams = titleParams.apply { topMargin = toolbarBottom }
                val previewHeight = pinned?.collapse((scroll.scrollY - expandedTitleHeight).coerceAtLeast(0)) ?: 0
                val total = toolbarBottom + titleHeight + if (pinned == null) dp(12) else previewHeight + dp(24)
                val previewBottom=if(pinned==null)-1 else toolbarBottom+titleHeight+dp(8)+previewHeight
                backdrop.regions(top.toFloat(),toolbarBottom.toFloat(),previewBottom.toFloat())
                val glassHeight=maxOf(toolbarBottom+dp(64),if(previewBottom<0)0 else previewBottom+dp(64))
                if(backdrop.layoutParams.height!=glassHeight)backdrop.layoutParams=backdrop.layoutParams.apply {height=glassHeight}
                if (chrome.layoutParams.height != total)
                    chrome.layoutParams = chrome.layoutParams.apply { height = total }
                if (controlsParams.topMargin != top)
                    controls.layoutParams = controlsParams.apply { topMargin = top }
                pinned?.let { host ->
                    val params = host.layoutParams as FrameLayout.LayoutParams
                    if (params.topMargin != toolbarBottom + titleHeight + dp(8) || params.height != previewHeight) {
                        host.layoutParams = params.apply {
                            topMargin = toolbarBottom + titleHeight + dp(8)
                            height = previewHeight
                        }
                    }
                    backdrop.preview(host,inset.toFloat(),(toolbarBottom+titleHeight+dp(8)).toFloat(),
                        previewHeight.toFloat())
                }
            }
            glassHeaderInset = { top ->
                val expanded = top + dp(60) + expandedTitleHeight + if (pinned == null) dp(12)
                    else pinned.expandedHeight + dp(24)
                body.setPadding(inset, expanded, inset, dp(bottom) + bottomInset())
                updateHeader(top)
            }
            scroll.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                updateHeader(systemInsets.top)
                backdrop.invalidate()
                bottomPill?.invalidateBackdrop()
                activePopup?.invalidateBackdrop()
            })
            shell.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        } else shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(navigation.current){
            SettingsScreen.HOME -> buildOverview(body)
            SettingsScreen.MODULE -> buildModuleCategories(body)
            SettingsScreen.APP -> buildSettings(body)
            SettingsScreen.COLORS -> coloringSection(body)
            SettingsScreen.BLUR -> buildBlurSettings(body)
            SettingsScreen.ICONS -> buildIconSettings(body)
            SettingsScreen.FOLDERS -> folderSection(body)
            SettingsScreen.DOCK -> dockSection(body)
            SettingsScreen.WIDGETS -> buildWidgetSettings(body)
            SettingsScreen.DESKTOP -> desktopDesignerSection(body)
            SettingsScreen.POSITIONS -> desktopDesignerSection(body)
            SettingsScreen.WALLPAPERS -> buildWallpaperSettings(body)
            SettingsScreen.BEHAVIOR -> buildBehaviorSettings(body)
            SettingsScreen.DEVELOPER -> buildDeveloperSettings(body)
            SettingsScreen.ANIMATIONS -> buildAnimationSettings(body)
            SettingsScreen.ICON_STUDIO -> buildIconStudio(body)
            SettingsScreen.WORKSPACE -> buildWorkspaceCategories(body)
            SettingsScreen.PERSONALIZATION -> {
                buildWallpaperSettings(body)
                settingsTransferSection(body)
            }
        }
        if(!couiIconStudio() && navigation.current.tab==1 && navigation.current !in setOf(SettingsScreen.MODULE,SettingsScreen.WORKSPACE,SettingsScreen.PERSONALIZATION) &&
            (navigation.current!=SettingsScreen.ICON_STUDIO || iconPanel!=0))
            add(body,text(getString(R.string.ui_reset_hold_hint),12f,true),top=20)
        screenHost.addView(shell,FrameLayout.LayoutParams(-1,-1));activeShell=shell
        visibleScreen=navigation.current
        updateScreenInsets()
        val y=scrollOffsets[visibleScreen] ?: 0
        scroll.post {scroll.scrollTo(0,y)}
        updateGates();updateStatus();updateNavigationPill()
        if(animate && old!=null && ValueAnimator.areAnimatorsEnabled()){
            val distance=dp(24).toFloat()*if(backwards)-1 else 1
            shell.alpha=0f;shell.translationX=distance
            old.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            old.animate().alpha(0f).translationX(-distance*.5f).setDuration(180).withEndAction {screenHost.removeView(old)}.start()
            shell.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(CherryMotion).start()
        }else if(old!=null)screenHost.removeView(old)
    }
    private fun updateNavigationPill(){
        val tabs=listOf(SettingsScreen.HOME,SettingsScreen.MODULE,SettingsScreen.APP)
        if(bottomPill==null){
            val pill=FloatingNavigationPill(this,
                listOf(R.string.ui_nav_home,R.string.ui_nav_module,R.string.ui_settings).map(::getString),
                listOf(R.drawable.ic_nav_home,R.drawable.ic_nav_module,R.drawable.ic_settings),
                useCoui
            ){index -> navigate(tabs[index])}
            val width=minOf(resources.displayMetrics.widthPixels-dp(32),dp(if(useCoui) 312 else 384))
            page.addView(pill,FrameLayout.LayoutParams(width,dp(if(useCoui) 56 else 68),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin=dp(12) + bottomInset()
            })
            bottomPill=pill
        }
        bottomPill?.apply {
            val show = !useCoui || rootTab()
            select(navigation.current.tab, animate = show && visibility == View.VISIBLE)
            visibility = if (show) View.VISIBLE else View.GONE
        }
        moduleRestart?.let {page.removeView(it)}
        moduleRestart=if(navigation.current.tab==1 && !useCoui)restartButton().also {button ->
            page.addView(button,FrameLayout.LayoutParams(dp(56),dp(56),Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin=dp(92);marginStart=dp(20)
            })
        }else null
    }
    private fun category(body:LinearLayout,title:Int,description:Int,screen:SettingsScreen){
        if (useCoui) {
            add(body, card(couiValueRow(getString(title)) { navigate(screen) }), bottom = 12)
            return
        }
        val labels=column().apply {setPadding(dp(20),dp(16),dp(12),dp(16))}
        add(labels,text(getString(title),18f,bold=true))
        add(labels,text(getString(description),13f,true),top=6)
        val line=row()
        line.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        line.addView(text("›",28f,true).apply {gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(40),-1))
        add(body,card(line).apply {isClickable=true;isFocusable=true;contentDescription=getString(title)+". "+getString(description);setOnClickListener {haptic(it);navigate(screen)}},bottom=12)
    }
    private fun buildModuleCategories(body:LinearLayout){
        if (useCoui) {
            val surface=color(M.attr.colorSurfaceContainer)
            val placeholder = ModuleTilePreview.Photo()
            val icons = ModuleTilePreview(this, listOf(placeholder), modulePreviewImages,surface).apply {
                bindPreview(0, "icons", appearanceSample("tile_icons"))
            }
            val desktopScopes = listOf("dock", "widget_photo", "widget_clock", "widget_weather", "folders")
            val desktop = ModuleTilePreview(this, desktopScopes.map { placeholder }, modulePreviewImages,surface).apply {
                desktopScopes.forEachIndexed { index, scope -> bindPreview(index, scope, appearanceSample(scope)) }
            }
            val wallpapers = ModuleTilePreview(this, emptyList(), modulePreviewImages,surface)
            loadWallpaperTile(wallpapers)
            val other = ModuleTilePreview(this,emptyList(),modulePreviewImages,surface)
            loadOtherTile(other)
            val tiles = listOf(
                moduleTile(R.string.ui_group_icons, R.string.ui_tile_icons_detail,
                    SettingsScreen.ICON_STUDIO, icons),
                moduleTile(R.string.ui_group_workspace, R.string.ui_tile_desktop_detail,
                    SettingsScreen.WORKSPACE, desktop),
                moduleTile(R.string.ui_group_personalization, R.string.ui_tile_appearance_detail,
                    SettingsScreen.PERSONALIZATION, wallpapers),
                moduleTile(R.string.ui_group_misc, R.string.ui_tile_other_detail,
                    SettingsScreen.BEHAVIOR,other)
            )
            val config = resources.configuration
            val columns = if (config.screenWidthDp >= 340 && config.fontScale < 1.35f) 2 else 1
            for (tilesInRow in tiles.chunked(columns)) {
                val group = LinearLayout(this)
                tilesInRow.forEachIndexed { index, tile ->
                    group.addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply {
                        if (index < tilesInRow.lastIndex) marginEnd = dp(12)
                    })
                }
                add(body, group, bottom = 12)
            }
            return
        }
        val features=column()
        moduleRow(features,R.drawable.ic_nav_module,R.string.ui_group_icons,R.string.ui_group_icons_hint,SettingsScreen.ICON_STUDIO,ModuleFeatures.ICONS)
        moduleRow(features,R.drawable.ic_nav_home,R.string.ui_group_workspace,R.string.ui_group_workspace_hint,SettingsScreen.WORKSPACE,ModuleFeatures.DESKTOP)
        add(body,card(features),bottom=12)
        val settings=column()
        moduleRow(settings,R.drawable.ic_module_image,R.string.ui_group_personalization,R.string.ui_group_personalization_hint,SettingsScreen.PERSONALIZATION)
        moduleRow(settings,R.drawable.ic_settings,R.string.ui_group_misc,R.string.ui_group_misc_hint,SettingsScreen.BEHAVIOR)
        add(body,card(settings))
    }

    private fun moduleTile(title: Int, detail: Int, screen: SettingsScreen, preview: ModuleTilePreview): View {
        val label = getString(title)
        val radius=dp(22).toFloat()
        val content=object : FrameLayout(this) {
            private val contour=android.graphics.Path()
            override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
                super.onSizeChanged(w,h,oldw,oldh)
                com.cosmod.launcherglass.util.ContinuousCorners.path(contour,
                    android.graphics.RectF(0f,0f,w.toFloat(),h.toFloat()),radius)
            }
            override fun draw(canvas:android.graphics.Canvas) {
                val save=canvas.save()
                try {canvas.clipPath(contour);super.draw(canvas)}finally {canvas.restoreToCount(save)}
            }
        }.apply {minimumHeight=dp(156)}
        val labels = column().apply { setPaddingRelative(dp(16), dp(16), dp(14), dp(14)) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text(label, 18f, bold = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            rotation = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) 90f else -90f
            imageTintList = ColorStateList.valueOf(secondaryText)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        add(labels, heading)
        add(labels, text(getString(detail), 12f, muted = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, top = 4)
        content.addView(preview,FrameLayout.LayoutParams(-1,-1))
        content.addView(labels,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        rowRipple(content)
        content.foreground=content.background
        content.setBackgroundColor(color(M.attr.colorSurfaceContainer))
        content.isClickable = true
        content.isFocusable = true
        content.contentDescription = "$label. ${getString(detail)}"
        content.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
        content.setOnClickListener { haptic(it); navigate(screen) }
        return content
    }

    private fun loadWallpaperTile(tile: ModuleTilePreview) {
        val surface = color(M.attr.colorSurfaceContainerHigh)
        val images = modulePreviewImages
        executor.execute {
            val bitmaps = runCatching { WallpaperCarousel.tileBitmaps(this, surface).map(images::bake) }.getOrDefault(emptyList())
            if (bitmaps.isNotEmpty()) runOnUiThread {
                if (tile.isAttachedToWindow && !isDestroyed) tile.showMedia(bitmaps.map {
                    ModuleTilePreview.Photo(bitmap=it,contain=true)
                })
            }
        }
    }

    private fun loadOtherTile(tile: ModuleTilePreview) {
        val images=modulePreviewImages
        executor.execute {
            val media=images.otherMedia()
            runOnUiThread {
                if(tile.isAttachedToWindow && !isDestroyed)tile.showMedia(media)
            }
        }
    }

    private fun moduleRow(parent:LinearLayout,icon:Int,title:Int,description:Int,screen:SettingsScreen,key:String?=null){
        if(parent.childCount>0)parent.addView(View(this).apply {
            setBackgroundColor(color(M.attr.colorOutlineVariant))
            alpha=if(useCoui) .8f else .55f
        },LinearLayout.LayoutParams(-1,if(useCoui) 1 else dp(1)).apply {marginStart=dp(if(useCoui)56 else 60);marginEnd=dp(16)})
        val label=getString(title)
        val line=row().apply {setPadding(0,0,dp(12),0)}
        val target=row().apply {
            minimumHeight=dp(if(useCoui)56 else 72)
            setPaddingRelative(dp(if(useCoui)16 else 12),dp(if(useCoui)8 else 12),dp(8),dp(if(useCoui)8 else 12))
            rowRipple(this)
            isFocusable=true
            contentDescription="$label. ${getString(description)}"
            setOnClickListener {haptic(it);navigate(screen)}
        }
        val image=AppCompatImageView(this).apply {
            setImageResource(icon)
            val inset=dp(if(useCoui)0 else 10)
            setPadding(inset,inset,inset,inset)
            imageTintList=ColorStateList.valueOf(color(M.attr.colorOnSecondaryContainer))
            if (!useCoui) background=GradientDrawable().apply {
                cornerRadius=dp(14).toFloat()
                setColor(color(M.attr.colorSecondaryContainer))
            }
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val tileSize=dp(if(useCoui)24 else 40)
        target.addView(image,LinearLayout.LayoutParams(tileSize,tileSize).apply {marginEnd=dp(if(useCoui)16 else 12)})
        val labels=column()
        add(labels,text(label,16f,bold=!useCoui))
        add(labels,text(getString(description),if(useCoui)13f else 12f,true),top=4)
        target.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        target.addView(AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            rotation=if(resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL)90f else -90f
            imageTintList=ColorStateList.valueOf(secondaryText)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(20),dp(20)).apply {marginStart=dp(4)})
        line.addView(target,LinearLayout.LayoutParams(0,-2,1f))
        if(key!=null)line.addView(sectionSwitch(label,key),LinearLayout.LayoutParams(-2,dp(48)))
        parent.addView(line,LinearLayout.LayoutParams(-1,-2))
    }
    private fun addAppearancePreview(shell:LinearLayout,scope:String){
        val grid = couiIconStudio()
        val preview = appearanceSample(scope, grid = grid)
        appearancePreview = preview
        val height = if (grid) (minOf(resources.configuration.screenWidthDp - 32, 440) * .67f).toInt()
            .coerceIn(120, minOf(294, (resources.configuration.screenHeightDp / 2).coerceAtLeast(120)))
            else (resources.configuration.screenHeightDp / 5).coerceIn(112, 156)
        shell.addView(preview, LinearLayout.LayoutParams(-1, dp(height)).apply {
            if (!grid) setMargins(dp(20), dp(4), dp(20), dp(8)) else bottomMargin = dp(16)
        })
    }

    private fun appearanceSample(scope: String, grid: Boolean = false, mode: Int? = null,
        single: Boolean = false, automaticLook: Int? = null): ModuleAppearancePreview {
        val preview = ModuleAppearancePreview(this, prefs, executor, scope, grid, mode, single, automaticLook)
        visiblePreviews.add(preview)
        fun update() = preview.setSource(previewWallpaper, if (single) listOf(appPreviewIcon) else previewGlyphs)
        update()
        if (!previewWallpaperLoaded && !previewWallpaperLoading) reloadPreviewWallpaper()
        return preview
    }

    private fun reloadPreviewWallpaper() {
        modulePreviewImages.clearSnapshots()
        com.cosmod.launcherglass.util.AutoIconPalette.invalidateWallpaper()
        previewWallpaperRevision++
        if (previewWallpaperLoading || isDestroyed) return
        previewWallpaperLoading = true
        val revision = previewWallpaperRevision
        executor.execute {
            val image = runCatching { PreviewWallpaper.read(this) }.getOrNull()
            image?.let {com.cosmod.launcherglass.util.AutoIconPalette.useWallpaper(it)}
            val samples = if (previewGlyphs.isEmpty()) AppearanceIcons.system(this) else previewGlyphs
            runOnUiThread {
                previewWallpaperLoading = false
                if (!isDestroyed) {
                    if (revision == previewWallpaperRevision) {
                        previewWallpaper = image
                        previewGlyphs = samples
                        previewWallpaperLoaded = true
                        visiblePreviews.forEach { it.setLoadedSource(image, samples, appPreviewIcon) }
                        headerGlass?.invalidate()
                    } else reloadPreviewWallpaper()
                }
            }
        }
    }

    private fun addIconConfigurator(shell:LinearLayout){
        val labels=listOf(R.string.ui_choose,R.string.ui_nav_colors,R.string.ui_blur,R.string.ui_icon_design)
        val bar=row().apply {setPadding(dp(16),0,dp(8),0)}
        bar.addView(text(getString(R.string.ui_configure),16f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        val selector=com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonStyle).apply {
            text=getString(labels[iconPanel.coerceIn(labels.indices)]);isAllCaps=false;minHeight=dp(48)
            backgroundTintList=ColorStateList.valueOf(color(M.attr.colorSecondaryContainer))
            val foreground=color(M.attr.colorOnSecondaryContainer)
            setTextColor(foreground)
            iconTint=ColorStateList.valueOf(foreground)
            setIconResource(R.drawable.ic_expand_more)
            iconGravity=com.google.android.material.button.MaterialButton.ICON_GRAVITY_END
            iconSize=dp(18)
            setOnClickListener {anchor ->
                PopupMenu(this@MainActivity,anchor).apply {
                    labels.forEachIndexed {index,title -> menu.add(0,index,index,title).isChecked=iconPanel==index}
                    menu.setGroupCheckable(0,true,true)
                    setOnMenuItemClickListener {item ->iconPanel=item.itemId;showPage(false);true}
                }.show()
            }
        }
        bar.addView(selector,LinearLayout.LayoutParams(-2,-2))
        shell.addView(card(bar).apply {radius=dp(18).toFloat()},LinearLayout.LayoutParams(-1,-2).apply {marginStart=dp(20);marginEnd=dp(20)})
    }
    private fun buildIconStudio(body:LinearLayout){
        val engine = column().apply { setPadding(dp(12),dp(12),dp(12),dp(12)) }
        add(engine, autoChoice(AutoIconStyle.ENGINE, listOf(1 to getString(R.string.ui_auto_icons_auto),
            0 to getString(R.string.ui_auto_icons_custom))))
        add(body,card(engine),bottom=12)
        if(number(AutoIconStyle.ENGINE)==1){buildAutoIconStudio(body);return}
        if (useCoui) {
            buildCouiIconStudio(body)
            return
        }
        when(iconPanel){
            1 -> coloringSection(body)
            2 -> {buildBlurSettings(body);folderSection(body)}
            3 -> {buildIconSettings(body);category(body,R.string.ui_nav_folders,R.string.ui_nav_folders_hint,SettingsScreen.FOLDERS)}
        }
    }

    private fun autoChoice(key:String, choices:List<Pair<Int,String>>):View {
        val strip=row().apply {
            setPadding(dp(3),dp(3),dp(3),dp(3))
            background=GradientDrawable().apply {cornerRadius=dp(999).toFloat();setColor(color(M.attr.colorSurfaceContainerHigh))}
        }
        for((choice,label) in choices){
            val selected=number(key)==choice
            strip.addView(TextView(this).apply {
                text=label;textSize=14f;gravity=Gravity.CENTER
                setTextColor(if(selected)color(M.attr.colorSurface)else secondaryText)
                background=GradientDrawable().apply {
                    cornerRadius=dp(999).toFloat()
                    setColor(if(selected)onSurface else Color.TRANSPARENT)
                }
                isSelected=selected;isFocusable=true
                contentDescription=label
                setOnClickListener {
                    if(number(key)!=choice){haptic(it);save(key,choice);showPage(false)}
                }
            },LinearLayout.LayoutParams(0,dp(42),1f))
        }
        return strip
    }

    private fun buildAutoIconStudio(body:LinearLayout){
        val controls=column().apply {setPadding(0,dp(12),0,dp(12))}
        val looks=row().apply {gravity=Gravity.TOP;setPadding(dp(8),0,dp(8),0)}
        for((look,title) in listOf(AutoIconStyle.DEFAULT to R.string.ui_auto_icons_default,
            AutoIconStyle.DARK to R.string.ui_auto_icons_dark,AutoIconStyle.CLEAR to R.string.ui_auto_icons_clear,
            AutoIconStyle.TINTED to R.string.ui_auto_icons_tinted)){
            looks.addView(materialOption(number(SettingsProvider.KEY_BLUR_STYLE),getString(title),look){
                save(AutoIconStyle.LOOK,look);showPage(false)
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        add(controls,looks,top=8,bottom=16)
        val look=number(AutoIconStyle.LOOK)
        add(controls,text(getString(when(look){
            AutoIconStyle.DEFAULT->R.string.ui_auto_icons_default_hint
            AutoIconStyle.DARK->R.string.ui_auto_icons_dark_hint
            AutoIconStyle.CLEAR->R.string.ui_auto_icons_clear_hint
            else->R.string.ui_auto_icons_tinted_hint
        }),13f,true).apply {setPadding(dp(20),0,dp(20),dp(12))})
        if(look==AutoIconStyle.TINTED){
            divider(controls)
            add(controls,autoChoice(AutoIconStyle.SOURCE,listOf(0 to getString(R.string.ui_auto_icons_wallpaper),
                1 to getString(R.string.ui_auto_icons_manual))),top=14,bottom=10)
            if(number(AutoIconStyle.SOURCE)==1){
                val bands=mutableListOf<ColorBand>()
                fun updateBands(){bands.forEach {it.setColor(number(AutoIconStyle.HUE),number(AutoIconStyle.SATURATION),90)}}
                for((component,key) in listOf(0 to AutoIconStyle.HUE,1 to AutoIconStyle.SATURATION)){
                    val band=ColorBand(this,component,compact=true).apply {
                        contentDescription=getString(if(component==0)R.string.ui_auto_icons_hue else R.string.ui_auto_icons_saturation)
                        onChange={value,finished ->
                            prefs.edit().putInt(key,value).apply();updateBands()
                            if(finished)save(key,value)
                        }
                    }
                    bands.add(band);add(controls,band)
                }
                updateBands()
            }
        }
        if(look==AutoIconStyle.CLEAR || look==AutoIconStyle.TINTED){
            divider(controls)
            val themeTrack=row().apply {
                setPaddingRelative(dp(24),0,dp(24),0)
                addView(autoChoice(AutoIconStyle.THEME,listOf(1 to getString(R.string.ui_icons_light),
                    2 to getString(R.string.ui_icons_dark),0 to getString(R.string.ui_auto_icons_auto))),
                    LinearLayout.LayoutParams(-1,-2))
            }
            add(controls,themeTrack,top=14,bottom=8)
            add(controls,text(getString(R.string.ui_auto_icons_theme_hint),12f,true)
                .apply {setPadding(dp(20),dp(4),dp(20),dp(4))})
        }
        divider(controls)
        add(controls,couiValueRow(getString(R.string.ui_icon_design)) { navigate(SettingsScreen.ICONS) })
        add(body,card(controls))
    }

    private fun buildCouiIconStudio(body: LinearLayout) {
        val controls = column()
        val material = SettingsProvider.KEY_BLUR_STYLE
        val modes = listOf(
            SettingsProvider.BLUR_STYLE_KYANT to R.string.ui_coui_liquid,
            SettingsProvider.BLUR_STYLE_FROSTED to R.string.ui_coui_frosted,
            SettingsProvider.BLUR_STYLE_COLOR to R.string.ui_mode_color,
            null to R.string.ui_coui_off
        ).filter { it.first != SettingsProvider.BLUR_STYLE_KYANT || android.os.Build.VERSION.SDK_INT >= 33 }
        val materials = row().apply { gravity = Gravity.TOP; setPaddingRelative(dp(6), 0, dp(6), 0) }
        for ((mode, name) in modes) {
            val tile = materialOption(mode, getString(name)) {
                if (mode == null) save(ModuleFeatures.ICONS, false)
                else save(material, mode) {
                    putBoolean(ModuleFeatures.ICONS, true)
                    putBoolean(SettingsProvider.KEY_BLUR_ENABLED, true)
                }
            }
            materials.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
        }
        add(controls, materials, top = 12, bottom = 12)
        divider(controls)
        coloringSection(controls, inline = true)
        divider(controls)
        val iconSize = DesktopSizing.ranges.getValue(DesktopSizing.ICON_SCALE)
        val sizeControl = slider(getString(R.string.ui_coui_icon_size), DesktopSizing.ICON_SCALE,
            iconSize.first, iconSize.last, "%", restore = true)
        gate(sizeControl) { bool(ModuleFeatures.DESKTOP) && bool(ModuleFeatures.LAYOUT) }
        add(controls, sizeControl)
        divider(controls)
        add(controls, couiValueRow(getString(R.string.ui_background_blur)) { navigate(SettingsScreen.BLUR) })
        divider(controls)
        add(controls, couiValueRow(getString(R.string.ui_icon_design)) { navigate(SettingsScreen.ICONS) })
        divider(controls)
        add(controls, couiValueRow(getString(R.string.ui_folders)) { navigate(SettingsScreen.FOLDERS) })
        val surface = card(controls).apply { radius = dp(14).toFloat() }
        gates.add(0, Gate(surface, ::master))
        add(body, surface)
    }

    private fun materialOption(mode: Int?, label: String, automaticLook: Int? = null, select: () -> Unit): View {
        val tile = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            contentDescription = label
            rowRipple(this)
            setOnClickListener { haptic(it); select() }
        }
        val previewSize = dp(52)
        val preview = appearanceSample("icons", mode = mode, single = true, automaticLook = automaticLook)
        val border = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
        val holder = FrameLayout(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            foreground = border
            addView(preview, FrameLayout.LayoutParams(previewSize, previewSize, Gravity.CENTER))
        }
        tile.addView(holder, LinearLayout.LayoutParams(previewSize + dp(6), previewSize + dp(6)))
        val title = text(label, 12f, muted = true).apply {
            gravity = Gravity.CENTER
            setPaddingRelative(dp(2), 0, dp(2), 0)
        }
        add(tile, title, top = 5)
        val refresh = {
            val enabled = bool(ModuleFeatures.ICONS)
            val selected = if(automaticLook!=null) automaticLook==number(AutoIconStyle.LOOK)
                else if (mode == null) !enabled else enabled && mode == number(SettingsProvider.KEY_BLUR_STYLE)
            tile.isSelected = selected
            border.setStroke(dp(2), if (selected) primary else Color.TRANSPARENT)
            title.setTextColor(if (selected) onSurface else secondaryText)
        }
        refresh()
        refreshers.add(refresh)
        return tile
    }

    private fun couiValueRow(label: String, value: TextView? = null, dropdown: Boolean = false,
        icon: Int? = null, action: (View) -> Unit) =
        couiValueRow(text(label, 16f), value, dropdown, icon, action)

    private fun couiValueRow(label: TextView, value: TextView? = null, dropdown: Boolean = false,
        icon: Int? = null, action: (View) -> Unit): LinearLayout = row().apply {
        minimumHeight = dp(if (value == null) 52 else 58)
        setPaddingRelative(dp(16), dp(8), dp(16), dp(8))
        if (icon != null) addView(AppCompatImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(primary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = AppCompatImageView(context).apply {
            setImageResource(R.drawable.ic_expand_more)
            if (!dropdown) rotation = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) 90f else -90f
            imageTintList = ColorStateList.valueOf(secondaryText)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        var trigger: View = this
        if (dropdown && value != null) {
            val labelAndArrow = row().apply {
                minimumHeight = dp(CouiGeometry.CONTROL_HEIGHT_DP)
                setPaddingRelative(dp(12), dp(4), dp(10), dp(4))
                addView(value, LinearLayout.LayoutParams(-2, -2))
                addView(arrow, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
            }
            val selector = FrameLayout(context).apply {
                addView(UiLiquidGlass(this@MainActivity, { null }, 24f,
                    backdropColor = color(M.attr.colorSurfaceContainer)), FrameLayout.LayoutParams(-1, -1))
                addView(labelAndArrow, FrameLayout.LayoutParams(-2, -2))
                isFocusable = true
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.Spinner"
                        info.contentDescription = label.text
                        info.stateDescription = value.text
                    }
                }
                setOnClickListener { haptic(it); action(it) }
            }
            addView(selector, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            trigger = selector
        } else {
            if (value != null) addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            addView(arrow, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
        }
        isFocusable = true
        rowRipple(this)
        setOnClickListener { haptic(it); action(trigger) }
    }

    private fun couiPopup(anchor: View, options: List<Pair<Int, String>>, selected: Int, choose: (Int) -> Unit) {
        activePopup?.dismissImmediately()
        val popup = CouiGlassPopup(this, { screenHost }, options, selected, choose)
        activePopup = popup
        popup.setOnDismissListener { if (activePopup === popup) activePopup = null }
        popup.show(anchor)
    }
    private fun buildWorkspaceCategories(body:LinearLayout){
        if (useCoui) {
            val layout = column()
            val masterRow=row().apply {minimumHeight=dp(56);setPaddingRelative(dp(16),dp(4),dp(14),dp(4));rowRipple(this)}
            masterRow.addView(AppCompatImageView(this).apply {
                setImageResource(R.drawable.ic_nav_home);imageTintList=ColorStateList.valueOf(primary)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(24),dp(24)).apply {marginEnd=dp(16)})
            masterRow.addView(text(getString(R.string.ui_group_workspace),16f),LinearLayout.LayoutParams(0,-2,1f))
            val masterToggle=sectionSwitch(getString(R.string.ui_group_workspace),ModuleFeatures.DESKTOP)
            masterRow.addView(masterToggle)
            masterRow.setOnClickListener {masterToggle.performClick()}
            add(layout,masterRow)
            workspaceEntry(layout, R.drawable.ic_nav_module, R.string.ui_desktop_title, R.string.ui_nav_desktop_hint,
                SettingsScreen.DESKTOP, ModuleFeatures.LAYOUT, primary)
            workspaceEntry(layout, R.drawable.ic_module_dock, R.string.ui_nav_dock, R.string.ui_nav_dock_hint,
                SettingsScreen.DOCK, SettingsProvider.KEY_DOCK_ENABLED, primary)
            workspaceEntry(layout, R.drawable.ic_module_motion, R.string.ui_app_animation_title, R.string.ui_nav_animation_short,
                SettingsScreen.ANIMATIONS, SettingsProvider.KEY_APP_WINDOW_TILT, primary)
            workspaceEntry(layout, R.drawable.ic_module_image, R.string.ui_widgets, R.string.ui_nav_widgets_hint,
                SettingsScreen.WIDGETS, null, primary)
            workspaceEntry(layout, R.drawable.ic_module_folder, R.string.ui_nav_folders, R.string.ui_nav_folders_hint,
                SettingsScreen.FOLDERS, SettingsProvider.KEY_FOLDER_BLUR_ENABLED, primary)
            add(body, card(layout, contentInset = 8))
            return
        }
        val content=column()
        moduleRow(content,R.drawable.ic_nav_home,R.string.ui_desktop_title,R.string.ui_nav_desktop_hint,SettingsScreen.DESKTOP,ModuleFeatures.LAYOUT)
        moduleRow(content,R.drawable.ic_module_dock,R.string.ui_nav_dock,R.string.ui_nav_dock_hint,SettingsScreen.DOCK,SettingsProvider.KEY_DOCK_ENABLED)
        moduleRow(content,R.drawable.ic_module_motion,R.string.ui_app_animation_title,R.string.ui_nav_animation_short,SettingsScreen.ANIMATIONS,SettingsProvider.KEY_APP_WINDOW_TILT)
        moduleRow(content,R.drawable.ic_module_image,R.string.ui_widgets,R.string.ui_nav_widgets_hint,SettingsScreen.WIDGETS)
        moduleRow(content,R.drawable.ic_module_folder,R.string.ui_nav_folders,R.string.ui_nav_folders_hint,SettingsScreen.FOLDERS,SettingsProvider.KEY_FOLDER_BLUR_ENABLED)
        add(body,card(content))
    }

    private fun workspaceEntry(parent: LinearLayout, icon: Int, title: Int, hint: Int,
        screen: SettingsScreen, key: String?, tint: Int) {
        if (parent.childCount > 0) parent.addView(View(this).apply {
            setBackgroundColor(color(M.attr.colorOutlineVariant))
        }, LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(56); marginEnd = dp(16) })
        val entry = row().apply {
            minimumHeight = dp(56)
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            rowRipple(this)
            isFocusable = true
            setOnClickListener { haptic(it); navigate(screen) }
        }
        entry.addView(AppCompatImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        entry.addView(text(getString(title), 16f).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val status = text("", 13f, muted = true)
        entry.addView(status, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        entry.addView(AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            rotation = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) 90f else -90f
            imageTintList = ColorStateList.valueOf(secondaryText)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8) })
        fun update() {
            val enabled = bool(ModuleFeatures.DESKTOP) && if (key == null) widgetPreviewSwitches.any(::bool) else bool(key)
            status.text = if (enabled) "" else getString(R.string.ui_state_off)
            entry.contentDescription = "${getString(title)}. ${getString(if (enabled) R.string.ui_state_on else R.string.ui_state_off)}. ${getString(hint)}"
        }
        update()
        refreshers.add(::update)
        add(parent, entry)
    }
    private fun buildWallpaperSettings(body:LinearLayout){
        val loading=text(getString(R.string.ui_theme_loading),14f,true)
        add(body,loading,bottom=12)
        val carousel=WallpaperCarousel(this,{theme ->
            executor.execute {
                val copied=WallpaperProfiles.copyFrom(this,prefs,theme.profileKey)
                if(copied)contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null)
                runOnUiThread {if(!isDestroyed){
                    refreshControls()
                    Toast.makeText(this,if(copied)R.string.ui_theme_copied else R.string.ui_theme_no_profile,Toast.LENGTH_LONG).show()
                }}
            }
        },{theme,button ->
            button.isEnabled=false
            executor.execute {
                val opened=try {ThemeCatalog.preview(this,theme)}catch(_:Throwable){false}
                runOnUiThread {
                    button.isEnabled=true
                    if(!opened && !isDestroyed)Toast.makeText(this,R.string.ui_theme_open_failed,Toast.LENGTH_LONG).show()
                }
            }
        })
        add(body,carousel,bottom=16)
        executor.execute {
            val themes=try {ThemeCatalog.load(this)}catch(t:Throwable){com.cosmod.launcherglass.util.GlassLogger.e("Themes","Theme catalogue load failed",t);emptyList()}
            runOnUiThread {if(!isDestroyed && navigation.current in setOf(SettingsScreen.WALLPAPERS, SettingsScreen.PERSONALIZATION)){
                loading.text=getString(when {
                    themes.isEmpty() ->R.string.ui_theme_unavailable
                    themes.first().source=="android" ->R.string.ui_theme_android_hint
                    else ->R.string.ui_theme_system_apply_hint
                })
                carousel.show(themes)
            }}
        }
        val status=text(getString(R.string.ui_wallpaper_profiles_hint),14f,true)
        add(body,status,bottom=16)
        add(body,com.google.android.material.button.MaterialButton(this).apply {
            text=getString(R.string.ui_wallpaper_choose);isAllCaps=false
            setOnClickListener {
                haptic(it)
                try {startActivity(Intent(Intent.ACTION_SET_WALLPAPER))}
                catch(_:Exception){Toast.makeText(this@MainActivity,R.string.ui_wallpaper_picker_unavailable,Toast.LENGTH_LONG).show()}
            }
        },bottom=12)
        add(body,text(getString(R.string.ui_wallpaper_sources_hint),13f,true))
    }
    private fun buildOverview(body:LinearLayout){
        val statusInset = if (useCoui) 16 else 20
        val groupGap = if (useCoui) 12 else 16
        val status = row().apply { setPadding(dp(statusInset),dp(statusInset),dp(statusInset),dp(statusInset)) }
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) } }
        status.addView(dot, LinearLayout.LayoutParams(dp(10),dp(10)).apply { rightMargin = dp(12) }); statusDot = dot
        val statusLabels = column()
        lsposedText=text("LSPosed",12f,true)
        add(statusLabels,lsposedText!!)
        statusText=text(getString(R.string.ui_cherrypick_disabled),17f,bold=true)
        add(statusLabels,statusText!!,5)
        statusHint=text("",12f,true).apply { visibility=View.GONE }
        add(statusLabels,statusHint!!,6)
        status.addView(statusLabels,LinearLayout.LayoutParams(0,-2,1f))
        val activation=toggle(getString(R.string.ui_module),SettingsProvider.KEY_ENABLED)
        moduleToggle=activation
        status.addView(actions(listOf(activation)))
        add(body,card(status),bottom=groupGap)
        val warning=column().apply {setPadding(dp(20),dp(16),dp(20),dp(16))}
        add(warning,text(getString(R.string.ui_flux_only_title),16f,bold=true).apply {
            setTextColor(color(M.attr.colorOnTertiaryContainer))
        })
        add(warning,text(getString(R.string.ui_flux_only_warning),14f).apply {
            setTextColor(color(M.attr.colorOnTertiaryContainer))
        },6)
        val warningCard=card(warning).apply {setCardBackgroundColor(color(M.attr.colorTertiaryContainer))}
        fluxWarning=warningCard
        add(body,warningCard,bottom=groupGap)
        updateFluxWarning()
        val shortcuts=column().apply {
            val inset = dp(if (useCoui) CouiGeometry.GROUP_INSET_DP else 16)
            setPadding(inset,dp(8),inset,dp(8))
        }
        val restartShortcut = com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
            text=getString(R.string.ui_restart_launcher);isAllCaps=false;minHeight=dp(48);setIconResource(R.drawable.ic_restart)
            setOnClickListener {haptic(it);restart(it)}
        }
        val shortcutButtons = listOf(restartShortcut, telegramButton(), supportButton())
        shortcutButtons.forEachIndexed { index, button ->
            if (useCoui) {
                button.insetTop = 0; button.insetBottom = 0
                button.minimumHeight = dp(48)
            }
            add(shortcuts, button, bottom = if (index < shortcutButtons.lastIndex) (if (useCoui) 8 else 4) else 0)
        }
        val shortcutCard = card(shortcuts)
        if (useCoui) shortcutCard.doOnLayout {
            val radius = CouiGeometry.insetRadius(shortcutCard.radius, dp(CouiGeometry.GROUP_INSET_DP).toFloat()).roundToInt()
            shortcutButtons.forEach { it.cornerRadius = radius }
            shortcutButtons.forEach { button -> ContinuousOutline.apply(button) { button.cornerRadius.toFloat() } }
        }
        add(body,shortcutCard,bottom=groupGap)
        val about=column().apply { setPadding(dp(statusInset),dp(statusInset),dp(statusInset),dp(if (useCoui) 16 else 12)) }
        add(about,text("v${BuildConfig.VERSION_NAME}",12f,true))
        add(about,text("by @immorgue",14f).apply {
            setTextColor(primary);minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL
            contentDescription=getString(R.string.ui_author_profile)
            setOnClickListener {
                haptic(it)
                try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://t.me/naten_e_mire"))) }
                catch(_:Exception) { Toast.makeText(context,getString(R.string.ui_telegram_error),Toast.LENGTH_SHORT).show() }
            }
        })
        add(about,text(getString(R.string.ui_credits_title),16f,bold=true),top=12)
        val credits=listOf(
            "PixelLauncherEnhanced — Mahmud0808" to "https://github.com/Mahmud0808/PixelLauncherEnhanced",
            "@anime_killer2009" to "https://t.me/anime_killer2009",
            "Kostubet Topics" to "https://t.me/KostubetTopics",
            getString(R.string.ui_ios_animation_author) to "https://t.me/bazik1337")
        val ticker=CreditsTicker(this,credits,primary) {view,url ->
            haptic(view)
            try {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
            catch(_:Exception){Toast.makeText(this,R.string.ui_link_error,Toast.LENGTH_SHORT).show()}
        }
        creditsTicker=ticker;ticker.setRunning(overviewResumed)
        add(about,ticker)
        add(body,card(about),top=if (useCoui) 0 else 24)
    }
    private fun buildBlurSettings(body:LinearLayout){
        blurStyleSelector(body)
        val glass=column().apply {visibility=if(colorOnly())View.GONE else View.VISIBLE}
        refreshers.add {glass.visibility=if(colorOnly())View.GONE else View.VISIBLE}
        section(glass, getString(R.string.ui_background_blur), "", SettingsProvider.KEY_BLUR_ENABLED, blurExpanded,
            { blurExpanded = it }, ::master, ::blur) { content ->
            effectGroup(content,getString(R.string.ui_blur),SettingsProvider.KEY_BLUR_RADIUS_ENABLED,
                getString(R.string.ui_blur_radius),SettingsProvider.KEY_BLUR_RADIUS,max=50,allowed=::blur,min=0,suffix="") { details ->
                if(bool(SettingsProvider.KEY_DEVELOPER_MODE)) {
                    val quality=slider(getString(R.string.ui_blur_quality),SettingsProvider.KEY_BLUR_QUALITY,0,100,"%",getString(R.string.ui_blur_quality_hint))
                    gate(quality,::blur);add(details,quality)
                }

            }
            blurToneGroup(content)
            val locations=column()
            val locationDetails=column().apply { visibility=View.GONE }
            divider(locationDetails)
            add(locationDetails,switchRow(getString(R.string.ui_drawer_blur),SettingsProvider.KEY_DRAWER_BLUR_ENABLED))
            add(locationDetails,switchRow(getString(R.string.ui_folder_blur),SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED,getString(R.string.ui_folder_blur_description)))
            gate(locationDetails) { blur() && bool(SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED) }
            add(locations,switchRow(getString(R.string.ui_icon_blur),SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED,expanded=false) { open ->
                reveal(locationDetails,open)
            })
            add(locations,locationDetails)
            add(content,card(locations,true),top=8,bottom=4)
        }
        add(body,glass)
    }
    private fun buildIconSettings(body:LinearLayout){
        section(body, getString(R.string.ui_icon_design), getString(R.string.ui_icon_design_hint), SettingsProvider.KEY_DESIGN_ENABLED, designExpanded,
            { designExpanded = it }, ::master, ::design) { content ->
            effectGroup(content, getString(R.string.ui_icon_contour), SettingsProvider.KEY_ICON_STROKE,
                getString(R.string.ui_contour_opacity), SettingsProvider.KEY_STROKE_OPACITY)
            glyphOutlineGroup(content)
            effectGroup(content, getString(R.string.ui_logo_bottom_shade), SettingsProvider.KEY_GLYPH_BOTTOM_SHADE_ENABLED,
                getString(R.string.ui_logo_bottom_shade_strength), SettingsProvider.KEY_GLYPH_BOTTOM_SHADE)
            effectGroup(content, getString(R.string.ui_logo_shadow), SettingsProvider.KEY_GLYPH_SHADOW,
                getString(R.string.ui_logo_shadow_opacity), SettingsProvider.KEY_SHADOW_OPACITY)
            effectGroup(content, getString(R.string.ui_icon_depth), SettingsProvider.KEY_VOLUME_ENABLED,
                getString(R.string.ui_depth_strength), SettingsProvider.KEY_GRADIENT_OPACITY)
        }
    }
    private val widgetPreviewSwitches = listOf(SettingsProvider.KEY_PHOTO_WIDGET_BLUR,
        SettingsProvider.KEY_CLOCK_WIDGET_GLASS, SettingsProvider.KEY_WEATHER_WIDGET_GLASS)

    private fun buildWidgetSettings(body: LinearLayout) {
        val sections = listOf(
            Triple(WidgetKind.PHOTO, R.string.ui_photo_widget_blur, "widget_photo"),
            Triple(WidgetKind.CLOCK, R.string.ui_clock_widgets, "widget_clock"),
            Triple(WidgetKind.WEATHER, R.string.ui_weather_widgets, "widget_weather")
        )
        for ((kind, title, scope) in sections) {
            val switchKey = requireNotNull(kind.switchKey)
            val opacityKey = requireNotNull(kind.opacityKey)
            val photo = kind == WidgetKind.PHOTO
            val section = column()
            val preview = appearanceSample(scope).apply { setCornerRadius(dp(18).toFloat()) }
            val allowed = { master() && bool(ModuleFeatures.DESKTOP) &&
                (blur() || colorOnly() || number(AutoIconStyle.ENGINE) == 1) }
            val toggle = switchRow(getString(title), switchKey)
            gate(toggle, allowed)
            add(section, toggle)
            section.addView(preview, LinearLayout.LayoutParams(-1, dp(107)).apply {
                setMargins(dp(8), dp(4), dp(8), dp(6))
            })
            val controls = column()
            add(controls, slider(getString(if (photo) R.string.ui_photo_widget_opacity else R.string.ui_widget_background_opacity),
                opacityKey, 0, 100, "%", onPreview = { preview.refresh() }))
            if (photo) {
                divider(controls)
                add(controls, switchRow(getString(R.string.ui_photo_widget_tint), SettingsProvider.KEY_PHOTO_WIDGET_TINT,
                    getString(R.string.ui_photo_widget_tint_hint)))
            }
            gate(controls) { allowed() && bool(switchKey) }
            add(section, controls)
            refreshers.add(preview::refresh)
            add(body, card(section, contentInset = 8), bottom = 12)
        }
    }
    private fun buildBehaviorSettings(body:LinearLayout){
        if (useCoui) {
            buildCouiBehaviorSettings(body)
            return
        }
        add(body,text(getString(R.string.ui_blur_update),14f,true),bottom=24)
        val modes = column().apply { setPadding(dp(20),dp(20),dp(20),dp(12)) }
        val modeHeader = row()
        modeHeader.addView(text(getString(R.string.ui_blur_mode),18f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        modeHeader.addView(reset(SettingsProvider.KEY_BLUR_MODE))
        add(modes,modeHeader,bottom=12)
        val radioGroup = RadioGroup(this)
        val selected = number(SettingsProvider.KEY_BLUR_MODE)
        val modeIds=mutableMapOf<Int,Int>()
        for ((mode,label,description) in listOf(
            Triple(SettingsProvider.BLUR_MODE_LIVE,getString(R.string.ui_live_blur),getString(R.string.ui_live_blur_description)),
            Triple(SettingsProvider.BLUR_MODE_WALLPAPER,getString(R.string.ui_static_blur),getString(R.string.ui_static_blur_description)))) {
            val item = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                id=View.generateViewId(); text=label; textSize=16f; minHeight=dp(48); tag=mode
                isChecked=selected==mode
            }
            radioGroup.addView(item)
            modeIds[mode]=item.id
            // Native radio grouping owns selection and accessibility state.
            val subtitle = text(description,12f,true).apply { setPadding(dp(40),0,0,dp(12)) }
            radioGroup.addView(subtitle)
        }
        radioGroup.setOnCheckedChangeListener { group,id ->
            val button=group.findViewById<View>(id)
            val mode=button?.tag as? Int
            if(mode != null && !updatingUi) { haptic(button); save(SettingsProvider.KEY_BLUR_MODE,mode) }
        }
        refreshers.add { modeIds[number(SettingsProvider.KEY_BLUR_MODE)]?.let { radioGroup.check(it) } }
        add(modes,radioGroup)
        val modeCard=card(modes);gate(modeCard,::blur);add(body,modeCard,bottom=12)
        val animationBlur=card(switchRow(getString(R.string.ui_static_animation_blur),SettingsProvider.KEY_STATIC_ANIMATION_BLUR,
            getString(R.string.ui_static_animation_blur_hint)))
        gate(animationBlur,::blur)
        add(body,animationBlur,bottom=12)
        val swipe=card(slider(getString(R.string.ui_swipe_blur_fps),SettingsProvider.KEY_SWIPE_FPS,1,120," FPS",
            getString(R.string.ui_swipe_blur_fps_hint)))
        gate(swipe){blur() && number(SettingsProvider.KEY_BLUR_MODE)==SettingsProvider.BLUR_MODE_LIVE}
        add(body,swipe,bottom=12)
        val liveFps=card(slider(getString(R.string.ui_live_blur_fps),SettingsProvider.KEY_LIVE_FPS,1,60," FPS"))
        gate(liveFps){blur() && number(SettingsProvider.KEY_BLUR_MODE)==SettingsProvider.BLUR_MODE_LIVE}
        add(body,liveFps,bottom=12)
        val lead=card(switchRow(getString(R.string.ui_swipe_blur_lead),SettingsProvider.KEY_SWIPE_LEAD,
            getString(R.string.ui_swipe_blur_lead_hint)))
        gate(lead){blur() && number(SettingsProvider.KEY_BLUR_MODE)==SettingsProvider.BLUR_MODE_LIVE}
        add(body,lead,bottom=12)
        val idle=card(switchRow(getString(R.string.ui_energy_saving),SettingsProvider.KEY_IDLE_PAUSE,
            getString(R.string.ui_energy_saving_description)))
        gate(idle) { blur() && number(SettingsProvider.KEY_BLUR_MODE)==SettingsProvider.BLUR_MODE_LIVE }
        add(body,idle,bottom=24)
    }

    private fun buildCouiBehaviorSettings(body: LinearLayout) {
        val options = listOf(SettingsProvider.BLUR_MODE_LIVE to getString(R.string.ui_live_blur),
            SettingsProvider.BLUR_MODE_WALLPAPER to getString(R.string.ui_static_blur))
        val selected = text("", 14f, muted = true)
        val description = text("", 13f, muted = true).apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        fun updateMode() {
            val live = number(SettingsProvider.KEY_BLUR_MODE) == SettingsProvider.BLUR_MODE_LIVE
            selected.text = options.firstOrNull { it.first == number(SettingsProvider.KEY_BLUR_MODE) }?.second ?: options.first().second
            description.setText(if (live) R.string.ui_live_blur_description else R.string.ui_static_blur_description)
        }
        updateMode()
        refreshers.add(::updateMode)
        val modes = column()
        add(modes, couiValueRow(getString(R.string.ui_blur_mode), selected, dropdown = true) { anchor ->
            couiPopup(anchor, options, number(SettingsProvider.KEY_BLUR_MODE)) { mode ->
                save(SettingsProvider.KEY_BLUR_MODE, mode)
                updateMode()
            }
        })
        add(modes, description)
        val modeCard = card(modes, contentInset = 8)
        gate(modeCard, ::blur)
        add(body, modeCard, bottom = 12)
        val animation = card(switchRow(getString(R.string.ui_static_animation_blur), SettingsProvider.KEY_STATIC_ANIMATION_BLUR,
            getString(R.string.ui_static_animation_blur_hint)), contentInset = 8)
        gate(animation, ::blur)
        add(body, animation, bottom = 16)

        add(body, text(getString(R.string.ui_update_rate), 13f, muted = true).apply {
            setPaddingRelative(dp(16), 0, dp(16), 0)
        }, bottom = 8)
        val rates = column()
        add(rates, slider(getString(R.string.ui_swipe_blur_fps), SettingsProvider.KEY_SWIPE_FPS, 1, 120, " FPS",
            getString(R.string.ui_swipe_blur_fps_hint)))
        divider(rates)
        add(rates, slider(getString(R.string.ui_live_blur_fps), SettingsProvider.KEY_LIVE_FPS, 1, 60, " FPS"))
        val rateCard = card(rates, contentInset = 8)
        val live = { blur() && number(SettingsProvider.KEY_BLUR_MODE) == SettingsProvider.BLUR_MODE_LIVE }
        gate(rateCard, live)
        add(body, rateCard, bottom = 12)
        val behavior = column()
        add(behavior, switchRow(getString(R.string.ui_swipe_blur_lead), SettingsProvider.KEY_SWIPE_LEAD,
            getString(R.string.ui_swipe_blur_lead_hint)))
        divider(behavior)
        add(behavior, switchRow(getString(R.string.ui_energy_saving), SettingsProvider.KEY_IDLE_PAUSE,
            getString(R.string.ui_energy_saving_description)))
        val behaviorCard = card(behavior, contentInset = 8)
        gate(behaviorCard, live)
        add(body, behaviorCard)
    }
    private fun buildAnimationSettings(body:LinearLayout) {
        val enabled=SettingsProvider.KEY_APP_WINDOW_TILT
        add(body,card(switchRow(getString(R.string.ui_app_window_tilt),enabled,
            getString(R.string.ui_app_animation_hint))),bottom=12)
        add(body,text(getString(R.string.ui_app_animation_coloros16_untested),13f,true)
            .apply {setPaddingRelative(dp(20),0,dp(20),0)},bottom=12)
        val effects=column()
        add(effects,switchRow(getString(R.string.ui_app_genie),SettingsProvider.KEY_APP_GENIE))
        add(effects,slider(getString(R.string.ui_app_genie_strength),SettingsProvider.KEY_APP_GENIE_STRENGTH,0,200,"%")
            .also { gate(it){master() && bool(enabled) && bool(SettingsProvider.KEY_APP_GENIE)} })
        divider(effects)
        add(effects,switchRow(getString(R.string.ui_app_droplet),SettingsProvider.KEY_APP_DROPLET))
        add(effects,switchRow(getString(R.string.ui_app_gesture_bounce),SettingsProvider.KEY_APP_GESTURE_BOUNCE))
        val effectCard=card(effects)
        gate(effectCard){master() && bool(enabled)}
        add(body,effectCard,bottom=12)

        val timing=column()
        for((label,key,unit) in listOf(
            Triple(R.string.ui_app_open_response,SettingsProvider.KEY_APP_OPEN_RESPONSE," ms"),
            Triple(R.string.ui_app_close_speed,SettingsProvider.KEY_APP_CLOSE_SPEED,"%"),
            Triple(R.string.ui_app_animation_slowdown,SettingsProvider.KEY_APP_ANIMATION_SLOWDOWN,"%")
        )){
            val range=SettingsProvider.APP_ANIMATION_RANGES.getValue(key)
            add(timing,slider(getString(label),key,range.first,range.last,unit))
        }
        add(timing,text(getString(R.string.ui_app_animation_timing_hint),12f,true)
            .apply { setPadding(dp(20),0,dp(20),dp(16)) })
        val timingCard=card(timing)
        gate(timingCard){master() && bool(enabled)}
        add(body,timingCard,bottom=12)
        add(body,com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
            text=getString(R.string.ui_reset_defaults)
            isAllCaps=false
            setOnClickListener {haptic(it);confirmReset(enabled)}
        },bottom=24)
    }

    private fun desktopPositionEditor(body:LinearLayout) {
        val names=linkedMapOf(
            DesktopSizing.GRID_Y to getString(R.string.ui_desktop_grid_y),
            DesktopSizing.INDICATOR_Y to getString(R.string.ui_desktop_indicator_y),
            DesktopSizing.DOCK_Y to getString(R.string.ui_desktop_dock_y))
        add(body,text(getString(R.string.ui_desktop_positions_hint),14f,true),bottom=12)
        val content=column().apply {setPadding(dp(16),dp(16),dp(16),dp(16))}
        val preview=DesktopPositionPreview(this)
        content.addView(preview,LinearLayout.LayoutParams(-1,dp(260)))
        val choices=column()
        val buttons=linkedMapOf<String,com.google.android.material.button.MaterialButton>()
        val controls=column().apply {visibility=View.GONE}
        val label=text("",16f,bold=true).apply {accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        add(controls,label,top=12,bottom=4)
        val value=text("",14f,true)
        add(controls,value)
        val slider=(if (useCoui) CouiSlider(this, primary, color(M.attr.colorSurfaceContainerHigh))
            else Slider(this)).apply {valueFrom=-120f;valueTo=120f;stepSize=1f}
        add(controls,slider)
        add(controls,text(getString(R.string.ui_desktop_position_hint),12f,true),bottom=8)
        var selected:String?=null
        var changing=false
        var tracking=false
        fun formatted(n:Int)="${if(n>0)"+" else ""}$n dp"
        fun updatePreview(){
            preview.positions=names.keys.associateWith {number(it)}
            preview.cellWidthPercent=number(DesktopSizing.CELL_WIDTH)
            preview.cellHeightPercent=number(DesktopSizing.CELL_HEIGHT)
        }
        updatePreview()
        val cellControls=column()
        add(cellControls,text(getString(R.string.ui_desktop_grid_cells),18f,bold=true),top=20,bottom=8)
        add(cellControls,text(getString(R.string.ui_desktop_grid_cells_hint),14f,true),bottom=8)
        for((title,key) in listOf(R.string.ui_desktop_cell_width to DesktopSizing.CELL_WIDTH,
            R.string.ui_desktop_cell_height to DesktopSizing.CELL_HEIGHT))
            add(cellControls,slider(getString(title),key,80,120,"%",onPreview={updatePreview()}))
        add(controls,cellControls)
        refreshers.add {updatePreview()}
        fun select(key:String,animate:Boolean=true){
            selected=key;preview.selected=key
            cellControls.visibility=if(key==DesktopSizing.GRID_Y)View.VISIBLE else View.GONE
            changing=true;slider.value=number(key).coerceIn(-120,120).toFloat();changing=false
            label.text=names[key];value.text=formatted(number(key));slider.contentDescription=names[key]
            buttons.forEach {(id,button)->button.isChecked=id==key}
            if(animate)reveal(controls,true)else controls.visibility=View.VISIBLE
        }
        preview.onSelect={key -> haptic(preview);select(key)}
        for((key,name) in names){
            val button=com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
                text=name;isAllCaps=false;minHeight=dp(48);isCheckable=true
                setOnClickListener {haptic(it);select(key)}
            }
            buttons[key]=button;add(choices,button,bottom=4)
        }
        slider.setLabelFormatter {formatted(it.roundToInt())}
        slider.addOnChangeListener {_,position,user ->
            val key=selected
            if(user && !changing && key!=null){
                val n=position.roundToInt();prefs.edit().putInt(key,n).apply();value.text=formatted(n);updatePreview()
                if(!tracking)save(key,n)
            }
        }
        slider.addOnSliderTouchListener(object:Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider:Slider){tracking=true;haptic(slider,true)}
            override fun onStopTrackingTouch(slider:Slider){tracking=false;selected?.let {save(it,slider.value.roundToInt())}}
        })
        add(controls,com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
            text=getString(R.string.ui_reset_defaults);isAllCaps=false;minHeight=dp(48)
            setOnClickListener {
                val key=selected ?: return@setOnClickListener
                haptic(it)
                val keys=if(key==DesktopSizing.GRID_Y)listOf(key,DesktopSizing.CELL_WIDTH,DesktopSizing.CELL_HEIGHT)else listOf(key)
                resetToDefaults(keys)
                select(key,animate=false)
            }
        })
        // The position slider sits immediately below the diagram; the named
        // buttons remain available for screen readers and overlapping elements.
        add(content,controls)
        add(content,choices,top=12)
        add(body,card(content))
        select(DesktopSizing.GRID_Y,animate=false)
    }

    private fun desktopDesignerSection(body:LinearLayout) {
        if (useCoui) add(body, card(couiSwitchRow(getString(R.string.ui_desktop_title),
            ModuleFeatures.LAYOUT, null, false, null), contentInset = 8), bottom = 12)
        desktopPositionEditor(body)
        val content=column().apply {setPadding(dp(16),dp(16),dp(16),dp(8))}
        add(content,text(getString(R.string.ui_desktop_sizing),18f,bold=true),bottom=8)
        val sizing=column()
        val sizes=listOf(
            Triple(R.string.ui_desktop_icon_size,DesktopSizing.ICON_SCALE,R.string.ui_desktop_icon_size_hint),
            Triple(R.string.ui_desktop_widget_gaps,DesktopSizing.WIDGET_GAPS,R.string.ui_desktop_widget_gaps_hint),
            Triple(R.string.ui_desktop_bottom_bias,DesktopSizing.BOTTOM_BIAS,R.string.ui_desktop_bottom_bias_hint))
        for((label,key,hint) in sizes) {
            val range=DesktopSizing.ranges.getValue(key)
            add(sizing,slider(getString(label),key,range.first,range.last,"%",getString(hint)))
        }
        add(sizing,text(getString(R.string.ui_desktop_sizing_restart),13f,true),top=8,bottom=12)
        add(sizing,com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
            text=getString(R.string.ui_reset_defaults);isAllCaps=false;minHeight=dp(48)
            setOnClickListener {haptic(it);resetToDefaults(sizes.map {size -> size.second})}
        },bottom=12)
        add(content,sizing)
        add(content,com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
            text=getString(R.string.ui_desktop_open);isAllCaps=false;minHeight=dp(48)
            setOnClickListener {
                haptic(it)
                try {
                    check(isModuleActive())
                    check(contentResolver.call(SettingsProvider.CONTENT_URI,SettingsProvider.METHOD_OPEN_DESKTOP,null,null)?.getBoolean("pending")==true)
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }catch(_:Exception){Toast.makeText(this@MainActivity,R.string.ui_desktop_unavailable,Toast.LENGTH_LONG).show()}
            }
        },bottom=8)
        add(body,card(content),top=24)
    }

    private fun settingsTransferSection(body: LinearLayout) {
        val content = column().apply { setPadding(dp(20), dp(20), dp(20), dp(12)) }
        add(content, text(getString(R.string.ui_presets), 19f, bold = true))
        add(content, text(getString(R.string.ui_presets_hint), 14f, true), top = 8, bottom = 12)
        for ((label, action) in listOf<Pair<Int, () -> Unit>>(
            R.string.ui_export_settings to { shareSettings() },
            R.string.ui_import_settings to ::choosePreset
        )) {
            add(content, com.google.android.material.button.MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
                text = getString(label); isAllCaps = false; minHeight = dp(48)
                setOnClickListener {
                    haptic(it)
                    try { action() } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.ui_file_picker_error, Toast.LENGTH_LONG).show()
                    }
                }
            }, bottom = 8)
        }
        add(body, card(content), top = 24)
    }

    private fun shareSettings(){
        executor.execute {
            try {
                val file=java.io.File(cacheDir,"diagnostics/CherryPick_settings.json")
                file.parentFile?.mkdirs();file.writeText(SettingsTransfer.export(prefs),Charsets.UTF_8)
                val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.logs",file)
                val intent=Intent(Intent.ACTION_SEND).apply {
                    type="application/json";putExtra(Intent.EXTRA_STREAM,uri)
                    clipData=ClipData.newRawUri("CherryPick settings",uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread {if(!isDestroyed)startActivity(Intent.createChooser(intent,getString(R.string.ui_export_settings)))}
            }catch(e:Exception){runOnUiThread {Toast.makeText(this,getString(R.string.ui_settings_transfer_error,e.message ?: ""),Toast.LENGTH_LONG).show()}}
        }
    }
    private fun choosePreset() {
        val request = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        importSettings.launch(Intent.createChooser(request, getString(R.string.ui_import_settings)))
    }

    private fun importPreset(uri: Uri) {
        executor.execute {
            try {
                val values = contentResolver.openInputStream(uri)?.use(SettingsTransfer::read)
                    ?: error("Cannot open preset")
                SettingsTransfer.apply(prefs, values)
                WallpaperProfiles.saveCurrent(this,prefs)
                contentResolver.notifyChange(SettingsProvider.CONTENT_URI, null)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showPage(false)
                        Toast.makeText(this, R.string.ui_settings_imported, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this, getString(R.string.ui_settings_transfer_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun folderSection(body:LinearLayout){
        section(body,getString(R.string.ui_folders),"",SettingsProvider.KEY_FOLDER_BLUR_ENABLED,folderExpanded,
            {folderExpanded=it},::blur,{blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED)}) {content ->
            ownGlassControls(content,FolderSettingsKeys,getString(R.string.ui_folder_mode_hint),true,::folderOwn)
        }
    }

    private fun dockSection(body:LinearLayout){
        section(body,getString(R.string.ui_dock),"",SettingsProvider.KEY_DOCK_ENABLED,dockExpanded,
            {dockExpanded=it},::master,{master() && bool(SettingsProvider.KEY_DOCK_ENABLED)}) {content ->
            add(content,switchRow(getString(R.string.ui_dock_auto_length),SettingsProvider.KEY_DOCK_AUTO_LENGTH))
            collapsibleGroup(content,getString(R.string.ui_dock_size),SettingsProvider.KEY_DOCK_SIDE_PADDING) {details ->
                add(details,slider(getString(R.string.ui_dock_side_padding),SettingsProvider.KEY_DOCK_SIDE_PADDING,0,SettingsProvider.DOCK_PADDING_MAX,"%"))
                divider(details)
                add(details,slider(getString(R.string.ui_dock_vertical_padding),SettingsProvider.KEY_DOCK_VERTICAL_PADDING,0,SettingsProvider.DOCK_PADDING_MAX,"%"))
                divider(details)
                add(details,slider(getString(R.string.ui_dock_radius),SettingsProvider.KEY_DOCK_RADIUS,0,100,"%",getString(R.string.ui_dock_radius_hint)))
            }
            ownGlassControls(content,DockSettingsKeys,null,false,::dockOwn,top=8)
        }
    }

    /** Inner card with a title and a chevron: a collapsible group without a switch of its own. */
    private fun collapsibleGroup(parent:LinearLayout,label:String,resetKey:String,expanded:Boolean=false,content:(LinearLayout)->Unit) {
        val group=column()
        val details=column().apply {visibility=if(expanded)View.VISIBLE else View.GONE}
        divider(details)
        content(details)
        val line=row().apply {
            if(useCoui){setPadding(dp(16),dp(8),dp(8),dp(8));minimumHeight=dp(56)}
            else {setPadding(dp(16),dp(12),dp(8),dp(12));minimumHeight=dp(72)}
        }
        val labels=column()
        add(labels,text(label,16f))
        line.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        val chevron=expandButton(expanded,label) {open -> reveal(details,open)}
        line.addView(actions(listOf(chevron)))
        line.setOnLongClickListener {confirmReset(resetKey);true}
        if(useCoui)rowRipple(line)
        labels.isClickable=true;labels.isFocusable=true;labels.contentDescription=label
        labels.setOnClickListener {chevron.performClick()}
        if(useCoui)line.setOnClickListener {chevron.performClick()}
        add(group,line);add(group,details)
        add(parent,card(group,true),top=8,bottom=4)
    }

    /**
     * "Own settings" of a folder/dock scope. Off: the surface inherits the icon
     * glass. On: its own style, blur and tone groups, laid out like the icon ones.
     */
    private fun ownGlassControls(content:LinearLayout,scope:ScopedSettingsKeys,hint:String?,status:Boolean,
        allowed:()->Boolean,top:Int=0) {
        add(content,text(getString(if(scope===DockSettingsKeys)R.string.ui_dock_appearance else R.string.ui_folder_appearance),16f,bold=true),top=top,bottom=10)
        val automatic=if(scope===DockSettingsKeys)
            ModeChoiceCard(this,getString(R.string.ui_auto_icons_auto),getString(R.string.ui_dock_auto_appearance_hint)) else null
        val inherited=ModeChoiceCard(this,getString(R.string.ui_style_inherit),getString(R.string.ui_style_inherit_hint))
        val own=ModeChoiceCard(this,getString(R.string.ui_style_separate),getString(if(scope===DockSettingsKeys)R.string.ui_dock_separate_hint else R.string.ui_folder_separate_hint))
        fun chooseOwn(separate:Boolean) {
            save(scope.own,separate) {
                if(automatic!=null)putBoolean(SettingsProvider.KEY_DOCK_AUTO_APPEARANCE,false)
            }
        }
        automatic?.let { card ->
            card.setOnClickListener {
                haptic(it);save(SettingsProvider.KEY_DOCK_AUTO_APPEARANCE,true);refreshControls()
            }
            gate(card){master()}
            add(content,card,bottom=8)
        }
        inherited.setOnClickListener {haptic(it);chooseOwn(false)}
        own.setOnClickListener {haptic(it);chooseOwn(true)}
        gate(inherited){master()};gate(own){master()}
        add(content,inherited,bottom=8);add(content,own,bottom=12)
        val controls=column()
        fun selection(){
            val auto=automatic!=null && bool(SettingsProvider.KEY_DOCK_AUTO_APPEARANCE)
            val separate=!auto && bool(scope.own)
            automatic?.chosen=auto
            inherited.chosen=!auto && !separate
            own.chosen=separate
            controls.visibility=if(separate)View.VISIBLE else View.GONE
        }
        selection();refreshers.add {selection()}
        gate(controls,allowed)
        if(!status)space(controls,8)
        scopedStyleSelector(controls,scope,allowed)
        effectGroup(controls,getString(R.string.ui_blur),scope.key(SettingsProvider.KEY_BLUR_RADIUS_ENABLED),
            getString(R.string.ui_blur_radius),scope.key(SettingsProvider.KEY_BLUR_RADIUS),max=50,allowed=allowed,suffix="") {details ->
            if(bool(SettingsProvider.KEY_DEVELOPER_MODE))add(details,slider(getString(R.string.ui_blur_quality),scope.key(SettingsProvider.KEY_BLUR_QUALITY),0,100,"%"))
        }
        val tone=column()
        val details=column().apply {visibility=View.GONE}
        add(tone,switchRow(getString(R.string.ui_blur_tone),scope.key(SettingsProvider.KEY_BLUR_TONE_ENABLED),expanded=false){open -> reveal(details,open)})
        gate(details){allowed() && bool(scope.key(SettingsProvider.KEY_BLUR_TONE_ENABLED))}
        for((label,key) in listOf(R.string.ui_plate_opacity to SettingsProvider.KEY_BG_OPACITY,
            R.string.ui_blur_saturation to SettingsProvider.KEY_BLUR_SATURATION,
            R.string.ui_blur_vibrancy to SettingsProvider.KEY_BLUR_VIBRANCY,
            R.string.ui_blur_brightness to SettingsProvider.KEY_BLUR_LIGHTENING)){
            divider(details)
            add(details,slider(getString(label),scope.key(key),0,if(key==SettingsProvider.KEY_BLUR_SATURATION || key==SettingsProvider.KEY_BLUR_VIBRANCY)200 else 100,"%",
                if(key==SettingsProvider.KEY_BLUR_SATURATION && status)getString(R.string.ui_blur_saturation_hint)else null))
        }
        add(tone,details);add(controls,card(tone,true),top=8,bottom=4)
        add(content,controls)
    }

    private fun styleCards(parent:LinearLayout,key:String,scoped:Boolean,allowed:()->Boolean) {
        val labels=listOf(R.string.ui_mode_frosted,R.string.ui_mode_liquid,R.string.ui_mode_color,R.string.ui_mode_kyant)
        val hints=listOf(R.string.ui_mode_frosted_hint,R.string.ui_mode_liquid_hint,R.string.ui_mode_color_hint,R.string.ui_mode_kyant_hint)
        val modes=listOf(SettingsProvider.BLUR_STYLE_FROSTED,
            SettingsProvider.BLUR_STYLE_KYANT,SettingsProvider.BLUR_STYLE_COLOR).filterNot {scoped && it==SettingsProvider.BLUR_STYLE_COLOR}
        if (useCoui) {
            val available = modes.filter {it != SettingsProvider.BLUR_STYLE_KYANT || android.os.Build.VERSION.SDK_INT >= 33}
            val value = text("", 14f, muted = true).apply { maxWidth = dp(142); gravity = Gravity.END }
            val line = couiValueRow(getString(R.string.ui_material_mode), value, dropdown = true) { anchor ->
                couiPopup(anchor, available.map { it to getString(labels[it]) }, number(key)) { mode ->
                    if (!scoped) prefs.edit().putBoolean(SettingsProvider.KEY_BLUR_ENABLED, true).apply()
                    save(key, mode)
                    refreshControls()
                }
            }
            val update = { value.text = getString(labels[number(key).coerceIn(labels.indices)]) }
            update()
            refreshers.add(update)
            gate(line, allowed)
            add(parent, card(line), bottom = 12)
            return
        }
        val content=column().apply {setPadding(dp(16),dp(8),dp(16),dp(12))}
        val line=row()
        line.addView(text(getString(R.string.ui_material_mode),15f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        val selector=com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
            isAllCaps=false;textSize=13f;minHeight=dp(48)
            setIconResource(R.drawable.ic_expand_more)
            iconGravity=com.google.android.material.button.MaterialButton.ICON_GRAVITY_END
            setOnClickListener {anchor ->
                PopupMenu(this@MainActivity,anchor).apply {
                    modes.forEach {index ->if(index!=SettingsProvider.BLUR_STYLE_KYANT || android.os.Build.VERSION.SDK_INT>=33)menu.add(0,index,index,labels[index])}
                    setOnMenuItemClickListener {item ->
                        if(!scoped)prefs.edit().putBoolean(SettingsProvider.KEY_BLUR_ENABLED,true).apply()
                        save(key,item.itemId);refreshControls();true
                    }
                }.show()
            }
        }
        line.addView(selector,LinearLayout.LayoutParams(-2,-2))
        add(content,line)
        val hint=text("",12f,true)
        add(content,hint,top=4)
        val refresh={val index=number(key).coerceIn(labels.indices);selector.text=getString(labels[index]);hint.text=getString(hints[index])}
        refresh();refreshers.add(refresh)
        gate(content,allowed)
        add(parent,card(content),bottom=12)
    }
    private fun lensControls(parent:LinearLayout,key:(String)->String,allowed:()->Boolean) {
        val kyant=column()
        collapsibleGroup(kyant,getString(R.string.ui_mode_kyant),key(SettingsProvider.KEY_KYANT_WIDTH),expanded=true) {details ->
            add(details,slider(getString(R.string.ui_liquid_width),key(SettingsProvider.KEY_KYANT_WIDTH),0,300,"%"))
            add(details,slider(getString(R.string.ui_liquid_distortion),key(SettingsProvider.KEY_KYANT_DISTORTION),0,300,"%"))
            add(details,slider(getString(R.string.ui_kyant_highlight),key(SettingsProvider.KEY_KYANT_HIGHLIGHT),0,300,"%"))
            add(details,slider(getString(R.string.ui_kyant_dispersion),key(SettingsProvider.KEY_KYANT_DISPERSION),0,300,"%"))
            add(details,slider(getString(R.string.ui_kyant_vibrancy),key(SettingsProvider.KEY_KYANT_VIBRANCY),0,400,"%"))
        }
        fun kyantSelected()=number(key(SettingsProvider.KEY_BLUR_STYLE))==SettingsProvider.BLUR_STYLE_KYANT
        fun showKyant(){kyant.visibility=if(kyantSelected())View.VISIBLE else View.GONE}
        showKyant()
        refreshers.add(::showKyant)
        gate(kyant){allowed() && kyantSelected()}
        add(parent,kyant,bottom=8)
    }
    private fun scopedStyleSelector(parent:LinearLayout,scope:ScopedSettingsKeys,allowed:()->Boolean) {
        styleCards(parent,scope.key(SettingsProvider.KEY_BLUR_STYLE),true,allowed)
        lensControls(parent,scope::key,allowed)
    }
    private fun blurStyleSelector(parent:LinearLayout) {
        styleCards(parent,SettingsProvider.KEY_BLUR_STYLE,false,::master)
        lensControls(parent,{it},::blur)
    }

    private fun coloringSection(parent: LinearLayout, inline: Boolean = false) {
        fun activeKeys(): List<String> {
            val selected=SettingsProvider.colorKeys(darkIcons())
            return if(number(SettingsProvider.KEY_ICON_APPEARANCE)==0) selected.take(2)+SettingsProvider.KEY_CLEAR_BRIGHTNESS else selected
        }
        var keys = activeKeys()
        val group = column()
        val options = if(useCoui) column().apply {visibility=if(colorExpanded)View.VISIBLE else View.GONE} else group
        val colorKey = SettingsProvider.KEY_COLOR_MODE
        val choices = listOf(
            0 to getString(if(useCoui)R.string.ui_color_system else if (inline) R.string.ui_coui_color_wallpaper else R.string.ui_system),
            1 to getString(if(useCoui)R.string.ui_color_custom else R.string.ui_detailed)
        )
        val mode = if (useCoui) text("", 14f, muted = true).apply {
            maxWidth = dp(148)
            gravity = Gravity.END
        } else com.google.android.material.button.MaterialButton(this).apply {
            minimumWidth=0; minWidth=0; insetTop=0; insetBottom=0
            cornerRadius=dp(12); textSize=13f; isAllCaps=false
            setPadding(dp(14),0,dp(10),0)
            setIconResource(R.drawable.ic_expand_more)
            iconGravity=com.google.android.material.button.MaterialButton.ICON_GRAVITY_END
            iconSize=dp(20); iconPadding=dp(6)
        }
        val details = column().apply {
            setPadding(dp(if(useCoui)16 else 20), 0, dp(if(useCoui)16 else 20), dp(16))
            visibility = if (number(colorKey) == 1) View.VISIBLE else View.GONE
        }
        val themeButtons=mutableListOf<com.google.android.material.button.MaterialButton>()
        var shown = number(colorKey) == 1
        fun updateMode() {
            val detailed = number(colorKey) == 1
            mode.text = choices[if (detailed) 1 else 0].second
            mode.contentDescription = getString(R.string.ui_color_mode_accessibility, mode.text)
            if (shown != detailed) { shown = detailed; reveal(details, detailed) }
        }
        fun select(value: Int) {
            save(colorKey, value)
            refreshControls()
        }
        if (useCoui) {
            lateinit var header:LinearLayout
            fun updateHeader(){
                header.getChildAt(header.childCount-1).rotation=if(colorExpanded)180f else 0f
                header.contentDescription=getString(R.string.ui_coui_color)+", "+getString(if(colorExpanded)R.string.ui_collapse else R.string.ui_expand)
                header.stateDescription=getString(if(colorExpanded)R.string.ui_collapse else R.string.ui_expand)
            }
            header=couiValueRow(text(getString(R.string.ui_coui_color),16f,bold=true)) {
                colorExpanded=!colorExpanded
                val arrow=header.getChildAt(header.childCount-1)
                arrow.animate().cancel()
                arrow.animate().rotation(if(colorExpanded)180f else 0f).setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                header.contentDescription=getString(R.string.ui_coui_color)+", "+getString(if(colorExpanded)R.string.ui_collapse else R.string.ui_expand)
                header.stateDescription=getString(if(colorExpanded)R.string.ui_collapse else R.string.ui_expand)
                reveal(options,colorExpanded)
            }
            val theme=row().apply {
                setPadding(dp(2),dp(2),dp(2),dp(2))
                background=GradientDrawable().apply {cornerRadius=dp(999).toFloat();setColor(color(M.attr.colorSurfaceContainerHigh))}
            }
            for((choice,label) in listOf(1 to R.string.ui_icons_light,2 to R.string.ui_icons_dark)){
                val button=com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
                    text=getString(label);textSize=12f;isAllCaps=false
                    minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
                    insetTop=0;insetBottom=0;strokeWidth=0;cornerRadius=dp(999)
                    setPadding(0,0,0,0);isCheckable=true
                    contentDescription=getString(if(choice==1)R.string.ui_icons_light_accessibility else R.string.ui_icons_dark_accessibility)
                    setOnClickListener {haptic(it);save(SettingsProvider.KEY_ICON_COLOR_THEME,choice);refreshControls()}
                }
                themeButtons.add(button)
                theme.addView(button,LinearLayout.LayoutParams(dp(52),dp(36)))
            }
            header.addView(theme,header.childCount-1,LinearLayout.LayoutParams(-2,-2).apply {marginEnd=dp(12)})
            updateHeader()
            add(group,header)
            add(group,options)
            add(options,couiValueRow(getString(R.string.ui_color_palette),mode,dropdown=true) {anchor ->
                couiPopup(anchor,choices,number(colorKey),::select)
            })
        }
        else {
            val header = row().apply { setPadding(dp(20), dp(16), dp(16), dp(16)); minimumHeight = dp(80) }
            header.addView(text(getString(R.string.ui_coloring), 19f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(mode, LinearLayout.LayoutParams(-2, dp(48)))
            add(group, header)
            mode.setOnClickListener {
                haptic(it)
                androidx.appcompat.widget.PopupMenu(this, mode).apply {
                    choices.forEach { (id, name) -> menu.add(0, id, id, name) }
                    menu.setGroupCheckable(0, true, true)
                    menu.findItem(number(colorKey).coerceIn(0, 1)).isChecked = true
                    setOnMenuItemClickListener { item -> select(item.itemId); true }
                    show()
                }
            }
        }
        add(options, details)
        val previewRow=row()
        val preview=FrameLayout(this)
        val previewShape=GradientDrawable().apply { cornerRadius=dp(18).toFloat() }
        preview.background=previewShape
        val glyph=AppCompatImageView(this).apply { setImageResource(R.drawable.ic_check);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        preview.addView(glyph,FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER))
        previewRow.addView(preview,LinearLayout.LayoutParams(dp(64),dp(64)))
        val previewLabel=text("",13f,true).apply { setPadding(dp(16),0,dp(8),0) }
        previewRow.addView(previewLabel,LinearLayout.LayoutParams(0,-2,1f))
        previewRow.addView(actions(listOf(reset(SettingsProvider.KEY_COLOR_MODE))))
        if(navigation.current!=SettingsScreen.ICON_STUDIO)add(details,previewRow,4,16)
        val appearances:LinearLayout=if(useCoui)row().apply {minimumHeight=dp(48)} else com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection=true;isSelectionRequired=true
        }
        val tint=if(useCoui)CouiSwitch(this,controlAccent).apply {
            contentDescription=getString(R.string.ui_color_tint)
            isChecked=number(SettingsProvider.KEY_ICON_APPEARANCE)==1
            appearances.addView(text(getString(R.string.ui_color_tint),15f),LinearLayout.LayoutParams(0,-2,1f))
            appearances.addView(this)
        } else null
        val appearanceButtons=(if(useCoui)emptyList()else listOf(getString(R.string.ui_color_neutral),getString(R.string.ui_color_tinted))).map { label ->
            com.google.android.material.button.MaterialButton(this,null,M.attr.materialButtonOutlinedStyle).apply {
                id=View.generateViewId();text=label;isAllCaps=false;textSize=14f
                cornerRadius=dp(if(useCoui)999 else 12);insetTop=0;insetBottom=0;minWidth=0;minimumWidth=0
                if(useCoui){strokeWidth=0;isCheckable=true}
                appearances.addView(this,LinearLayout.LayoutParams(0,dp(if(useCoui)40 else 48),1f))
            }
        }
        if(appearances is com.google.android.material.button.MaterialButtonToggleGroup)
            appearances.check(appearanceButtons[number(SettingsProvider.KEY_ICON_APPEARANCE).coerceIn(0,1)].id)
        add(details,appearances,bottom=12)
        var values=keys.map { number(it) }.toMutableList()
        val bands=mutableListOf<ColorBand>()
        val labels=mutableListOf<TextView>()
        val bandGroups=mutableListOf<View>()
        fun paintPreview() {
            val palette=com.cosmod.launcherglass.util.IconPalette.from(1,values[0],values[1],values[2],darkIcons(),number(SettingsProvider.KEY_ICON_APPEARANCE)==0)
            if (number(colorKey) == 1) appearancePreview?.setPaletteOverride(palette)
            else appearancePreview?.refresh()
            previewShape.setColor(palette.background)
            glyph.imageTintList=ColorStateList.valueOf(palette.foreground)
            val clear=number(SettingsProvider.KEY_ICON_APPEARANCE)==0
            if(useCoui) {
                tint?.isChecked=!clear
                themeButtons.forEachIndexed {index,button ->
                    val checked=if(index==0)!darkIcons()else darkIcons()
                    button.isChecked=checked
                    button.backgroundTintList=ColorStateList.valueOf(if(checked)primary else Color.TRANSPARENT)
                    button.setTextColor(if(checked)color(M.attr.colorOnPrimary)else secondaryText)
                }
                bandGroups.forEachIndexed {index,view ->view.visibility=if(clear && index<2)View.GONE else View.VISIBLE}
            }
            val accent=Color.HSVToColor(floatArrayOf(values[0].toFloat(),if(clear)0f else values[1]/100f,values[2]/100f))
            previewLabel.text=String.format("#%06X",accent and 0xffffff)
            preview.contentDescription=getString(R.string.ui_color_preview,previewLabel.text)
            bands.forEachIndexed { index,band ->
                labels[index].setTextColor(if(clear && index<2)secondaryText else primary)
                band.monochrome=clear
                band.setColor(values[0],if(clear)0 else values[1],values[2])
                labels[index].text="${values[index]}${if(index==0) "°" else "%"}"
            }
        }
        fun chooseAppearance(index:Int) {
            if(!updatingUi) {
                haptic(appearances)
                save(SettingsProvider.KEY_ICON_APPEARANCE,index)
                keys=activeKeys();values=keys.map {number(it)}.toMutableList()
                paintPreview()
            }
        }
        if(useCoui){
            tint?.setOnCheckedChangeListener {_,checked ->chooseAppearance(if(checked)1 else 0)}
            appearances.setOnClickListener {tint?.performClick()}
            rowRipple(appearances)
        }
        else (appearances as com.google.android.material.button.MaterialButtonToggleGroup).addOnButtonCheckedListener {_,id,checked ->
            if(checked)chooseAppearance(if(id==appearanceButtons[0].id)0 else 1)
        }
        listOf(getString(R.string.ui_hue),getString(R.string.ui_saturation),getString(R.string.ui_brightness)).forEachIndexed { index,label ->
            val line=row()
            line.addView(text(label,if(useCoui)15f else 14f),LinearLayout.LayoutParams(0,-2,1f))
            val value=text("",14f,bold=true).apply {setTextColor(primary)}
            labels.add(value);line.addView(value)
            val bandGroup=column()
            bandGroups.add(bandGroup)
            add(bandGroup,line,10)
            add(details,bandGroup)
            if(index<2)gate(bandGroup) { master() && number(SettingsProvider.KEY_ICON_APPEARANCE)==1 }
            val band=ColorBand(this,index, compact = useCoui).apply {
                contentDescription=label
                onChange={ next,commit -> values[index]=next;paintPreview();if(commit)save(keys[index],next) }
            }
            bands.add(band);add(bandGroup,band)
        }
        refreshers.add {
            keys=activeKeys()
            values=keys.map {number(it)}.toMutableList()
            if(appearances is com.google.android.material.button.MaterialButtonToggleGroup)
                appearances.check(appearanceButtons[number(SettingsProvider.KEY_ICON_APPEARANCE).coerceIn(0,1)].id)
            updateMode();paintPreview()
        }
        updateMode();paintPreview()
        if (inline) add(parent, group)
        else {
            val section = card(group)
            gates.add(0, Gate(section, ::master))
            add(parent, section, bottom = 12)
        }
    }

    private fun section(parent:LinearLayout,title:String,subtitle:String,key:String,
        expanded:Boolean,onExpand:(Boolean)->Unit,headerAllowed:()->Boolean,
        contentAllowed:()->Boolean,content:(LinearLayout)->Unit) {
        val group=column()
        val header=row().apply {setPadding(dp(20),dp(16),dp(16),dp(16));minimumHeight=dp(64)}
        val labels=column()
        add(labels,text(title,19f,bold=true))
        if(subtitle.isNotBlank())add(labels,text(subtitle,14f,true),top=6)
        header.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        val control=toggle(title,key);header.addView(control)
        header.setOnClickListener {control.performClick()}
        header.setOnLongClickListener {confirmReset(key);true}
        add(group,header)
        val children=column().apply {setPadding(dp(8),0,dp(8),dp(12))}
        content(children);gate(children,contentAllowed);add(group,children)
        val container=card(group);gate(container,headerAllowed);add(parent,container,bottom=16)
    }

    private fun confirmReset(key:String) {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.ui_reset_defaults))
            .setMessage(getString(R.string.ui_reset_confirm))
            .setNegativeButton(getString(R.string.ui_cancel),null)
            .setPositiveButton(getString(R.string.ui_reset)){_,_->resetToDefaults(resetKeys(key))}.show()
    }

    private fun makeSwitch(label:String,key:String):SquareAction = SquareAction(this,
        if(bool(key))R.drawable.ic_check else R.drawable.ic_close,
        Color.WHITE,controlAccent,label).apply {
        isToggle=true;isChecked=bool(key)
        glyph.imageTintList=ColorStateList.valueOf(if(bool(key))Color.WHITE else secondaryText)
        backgroundTintList=ColorStateList.valueOf(if(bool(key))controlAccent else color(M.attr.colorSurfaceContainerHigh))
        setOnClickListener {
            haptic(it);pulse(it,500)
            val checked=!bool(key)
            animateChecked(checked)
            glyph.imageTintList=ColorStateList.valueOf(if(checked)Color.WHITE else secondaryText)
            backgroundTintList=ColorStateList.valueOf(if(checked)controlAccent else color(M.attr.colorSurfaceContainerHigh))
            save(key,checked)
        }
        toggleViews.add(this to key)
    }
    private fun resetKeys(key: String): List<String> = when (key) {
        SettingsProvider.KEY_COLOR_MODE -> listOf(key,
            SettingsProvider.KEY_ICON_COLOR_THEME,
            SettingsProvider.KEY_ICON_APPEARANCE, SettingsProvider.KEY_CLEAR_BRIGHTNESS,
            SettingsProvider.KEY_COLOR_HUE, SettingsProvider.KEY_COLOR_SATURATION, SettingsProvider.KEY_COLOR_BRIGHTNESS) +
            SettingsProvider.colorKeys(false) + SettingsProvider.colorKeys(true)
        SettingsProvider.KEY_APP_WINDOW_TILT -> SettingsProvider.APP_ANIMATION_SWITCHES+
            SettingsProvider.APP_ANIMATION_RANGES.keys
        SettingsProvider.KEY_BLUR_STYLE -> listOf(key,SettingsProvider.KEY_LIQUID_WIDTH,SettingsProvider.KEY_LIQUID_DISTORTION,
            SettingsProvider.KEY_KYANT_MAGNIFIER,SettingsProvider.KEY_KYANT_CONTROL_CENTER)+kyantOpticsKeys
        SettingsProvider.KEY_BLUR_ENABLED -> listOf(key, SettingsProvider.KEY_BLUR_STYLE,SettingsProvider.KEY_BLUR_QUALITY,SettingsProvider.KEY_LIQUID_WIDTH,SettingsProvider.KEY_LIQUID_DISTORTION,SettingsProvider.KEY_BLUR_RADIUS_ENABLED, SettingsProvider.KEY_BLUR_TONE_ENABLED, SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED, SettingsProvider.KEY_BLUR_RADIUS,
            SettingsProvider.KEY_BG_OPACITY, SettingsProvider.KEY_DRAWER_BLUR_ENABLED,
            SettingsProvider.KEY_FOLDER_BLUR_ENABLED, SettingsProvider.KEY_LIVE_FPS, SettingsProvider.KEY_SWIPE_FPS,
            SettingsProvider.KEY_BLUR_SATURATION,SettingsProvider.KEY_BLUR_VIBRANCY,SettingsProvider.KEY_BLUR_LIGHTENING)
        SettingsProvider.KEY_FOLDER_BLUR_ENABLED -> listOf(key,FolderSettingsKeys.OWN)+FolderSettingsKeys.keys
        SettingsProvider.KEY_DOCK_ENABLED -> listOf(key,SettingsProvider.KEY_DOCK_AUTO_LENGTH,SettingsProvider.KEY_DOCK_SIDE_PADDING,
            SettingsProvider.KEY_DOCK_VERTICAL_PADDING,SettingsProvider.KEY_DOCK_RADIUS,
            SettingsProvider.KEY_DOCK_AUTO_APPEARANCE,DockSettingsKeys.OWN)+DockSettingsKeys.keys
        // The size group of the dock resets its three sliders together.
        SettingsProvider.KEY_DOCK_SIDE_PADDING -> listOf(key,SettingsProvider.KEY_DOCK_VERTICAL_PADDING,SettingsProvider.KEY_DOCK_RADIUS)
        SettingsProvider.KEY_PHOTO_WIDGET_BLUR -> listOf(key,SettingsProvider.KEY_PHOTO_WIDGET_OPACITY,SettingsProvider.KEY_PHOTO_WIDGET_TINT,
            SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH,SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION)
        SettingsProvider.KEY_CLOCK_WIDGET_GLASS -> listOf(key, SettingsProvider.KEY_CLOCK_WIDGET_OPACITY)
        SettingsProvider.KEY_WEATHER_WIDGET_GLASS -> listOf(key, SettingsProvider.KEY_WEATHER_WIDGET_OPACITY)
        SettingsProvider.KEY_DESIGN_ENABLED -> listOf(key,
            SettingsProvider.KEY_GLYPH_STROKE_WIDTH,
            SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,
            SettingsProvider.KEY_GLYPH_BOTTOM_SHADE_ENABLED, SettingsProvider.KEY_GLYPH_BOTTOM_SHADE,
            SettingsProvider.KEY_GLYPH_STROKE, SettingsProvider.KEY_GLYPH_STROKE_OPACITY, SettingsProvider.KEY_ICON_STROKE,
            SettingsProvider.KEY_STROKE_OPACITY, SettingsProvider.KEY_GLYPH_SHADOW,
            SettingsProvider.KEY_SHADOW_OPACITY, SettingsProvider.KEY_VOLUME_ENABLED,
            SettingsProvider.KEY_GRADIENT_OPACITY)
        SettingsProvider.KEY_GLYPH_STROKE -> listOf(key, SettingsProvider.KEY_GLYPH_STROKE_OPACITY,
            SettingsProvider.KEY_GLYPH_STROKE_WIDTH,
            SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS)
        SettingsProvider.KEY_ICON_STROKE -> listOf(key, SettingsProvider.KEY_STROKE_OPACITY)
        SettingsProvider.KEY_GLYPH_SHADOW -> listOf(key, SettingsProvider.KEY_SHADOW_OPACITY)
        SettingsProvider.KEY_GLYPH_BOTTOM_SHADE_ENABLED -> listOf(key, SettingsProvider.KEY_GLYPH_BOTTOM_SHADE)
        SettingsProvider.KEY_BLUR_RADIUS_ENABLED -> listOf(key,SettingsProvider.KEY_BLUR_RADIUS,SettingsProvider.KEY_BLUR_QUALITY,SettingsProvider.KEY_LIVE_FPS)
        SettingsProvider.KEY_BLUR_TONE_ENABLED -> listOf(key,SettingsProvider.KEY_BG_OPACITY,
            SettingsProvider.KEY_BLUR_SATURATION,SettingsProvider.KEY_BLUR_VIBRANCY,SettingsProvider.KEY_BLUR_LIGHTENING)
        SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED -> listOf(key,SettingsProvider.KEY_DRAWER_BLUR_ENABLED,SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED)
        SettingsProvider.KEY_VOLUME_ENABLED -> listOf(key, SettingsProvider.KEY_GRADIENT_OPACITY)
        else -> ScopedSettingsKeys.scopeOf(key)?.let {scope -> when(ScopedSettingsKeys.baseOf(key)) {
            SettingsProvider.KEY_BLUR_TONE_ENABLED -> listOf(key)+listOf(SettingsProvider.KEY_BG_OPACITY,SettingsProvider.KEY_BLUR_SATURATION,
                SettingsProvider.KEY_BLUR_VIBRANCY,SettingsProvider.KEY_BLUR_LIGHTENING).map(scope::key)
            SettingsProvider.KEY_BLUR_RADIUS_ENABLED -> listOf(key,scope.key(SettingsProvider.KEY_BLUR_RADIUS),scope.key(SettingsProvider.KEY_BLUR_QUALITY))
            else -> null
        }} ?: listOf(key)
    }
    private fun resetButton(key:String):View {
        val keys=resetKeys(key)
        val button=SquareAction(this,R.drawable.ic_restart,primary,color(M.attr.colorSurfaceContainerHigh),
            getString(R.string.ui_reset_hold))
        var holding=false
        var completed=false
        var started=0L
        var lastHoldTick=0L
        fun cancel() {
            if(!holding)return
            holding=false
            button.isPressed=false
            button.glyph.animate().rotation(0f).setDuration(198).setInterpolator(CherryMotion).start()
            (button.parent as? ConnectedActions)?.release()
            button.parent?.requestDisallowInterceptTouchEvent(false)
        }
        val tick=object:Runnable {
            override fun run() {
                if(!holding)return
                if(!button.isAttachedToWindow || !button.isEnabled) { cancel();return }
                val now=SystemClock.uptimeMillis()
                if(now-lastHoldTick>=65L){haptic(button,true);lastHoldTick=now}
                val progress=((now-started)/1500f).coerceIn(0f,1f)
                button.glyph.rotation=-360f*CherryMotion.getInterpolation(progress)
                (button.parent as? ConnectedActions)?.hold(button,progress)
                if(progress>=1f) {
                    holding=false;completed=true
                    // Synchronize the existing views. Never detach or fade the
                    // page: that caused the whole interface to blink on reset.
                    resetToDefaults(keys)
                    haptic(button)
                    button.glyph.animate().rotation(-720f).setDuration(198).setInterpolator(CherryMotion)
                        .withEndAction { button.glyph.rotation=0f }.start()
                    (button.parent as? ConnectedActions)?.release()
                } else button.postDelayed(this,16)
            }
        }
        button.setOnTouchListener { view,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if(!view.isEnabled)return@setOnTouchListener false
                    holding=true;completed=false;started=SystemClock.uptimeMillis();lastHoldTick=started
                    button.removeCallbacks(tick)
                    button.glyph.animate().cancel();button.glyph.rotation=0f
                    view.isPressed=true;view.parent?.requestDisallowInterceptTouchEvent(true)
                    haptic(view,true);button.post(tick)
                }
                MotionEvent.ACTION_MOVE -> if(event.x<0 || event.y<0 || event.x>view.width || event.y>view.height) cancel()
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN -> {
                    if(!completed)cancel()
                    view.isPressed=false;view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
        button.setOnClickListener { /* A short tap deliberately cannot reset values. */ }
        cancelHolds.add { cancel();button.removeCallbacks(tick) }
        resets.add(button to keys)
        return button
    }
    private fun switchRow(label: String, key: String, subtitle: String? = null,
        expanded: Boolean = false, onExpand: ((Boolean) -> Unit)? = null): LinearLayout {
        if (useCoui) return couiSwitchRow(label, key, subtitle, expanded, onExpand)
        val wrapper = column()
        val line = row().apply { setPadding(dp(16),dp(12),dp(8),dp(12)); minimumHeight = dp(72) }
        val labels = column()
        add(labels, text(label, 16f, bold = key == SettingsProvider.KEY_ENABLED))
        if (key == SettingsProvider.KEY_ENABLED && subtitle != null) add(labels,text(subtitle,12f,true),5)
        line.addView(labels, LinearLayout.LayoutParams(0,-2,1f))
        val toggle = makeSwitch(label, key)
        val actions=mutableListOf<View>(resetButton(key),toggle)
        if (onExpand != null) actions.add(expandButton(expanded,label) { open ->
            onExpand(open)
        })
        line.addView(ConnectedActions(this,actions))
        add(wrapper,line)

        labels.isClickable = true; labels.isFocusable = true
        labels.contentDescription = label
        labels.setOnClickListener { toggle.performClick() }
        return wrapper
    }

    private fun expandButton(expanded: Boolean, label: String, action: (Boolean) -> Unit): View {
        var open = expanded
        val arrow = AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            imageTintList=ColorStateList.valueOf(if(useCoui)secondaryText else color(M.attr.colorOnSecondaryContainer))
            rotation=if(open)-180f else 0f
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(this).apply {
            layoutParams=LinearLayout.LayoutParams(dp(if(useCoui)48 else 40),dp(48)).apply { leftMargin=dp(4) }
            background=if(useCoui) RippleDrawable(ColorStateList.valueOf(((primary and 0x00ffffff) or 0x20000000)),null,
                GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(Color.WHITE) })
            else RippleDrawable(ColorStateList.valueOf(((primary and 0x00ffffff) or 0x20000000)),GradientDrawable().apply {
                setColor(color(M.attr.colorSecondaryContainer))
                cornerRadius=dp(12).toFloat()
            },null)
            addView(arrow,FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER))
            isFocusable=true
            contentDescription="$label, ${if(open) getString(R.string.ui_collapse) else getString(R.string.ui_expand)}"
            fun activate() {
                haptic(this)
                open=!open
                arrow.animate().cancel()
                val group=parent as? ConnectedActions
                group?.pulse(this,500)
                arrow.animate().rotation(if (open) -180f else 0f).setDuration(180)
                    .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start()
                contentDescription="$label, ${if(open) getString(R.string.ui_collapse) else getString(R.string.ui_expand)}"
                action(open)
            }
            setOnClickListener { activate() }
        }
    }

    private fun slider(label: String, key: String, min: Int, max: Int, suffix: String, hint: String? = null,
        restore: Boolean = false, onPreview: ((Int) -> Unit)? = null): LinearLayout {
        val group = column().apply { setPadding(dp(20),dp(if(useCoui)12 else 18),dp(20),dp(if(useCoui)6 else 10)) }
        val labels = row()
        labels.addView(text(label,if(useCoui)16f else 14f), LinearLayout.LayoutParams(0,-2,1f))
        fun formatted(n:Int)="${if(min<0 && n>0)"+" else ""}$n$suffix"
        val value = text(formatted(number(key)),14f,bold = !useCoui).apply {
            setTextColor(if(useCoui)secondaryText else primary); setPadding(dp(12),0,0,0)
        }
        labels.addView(value)
        if (restore) labels.addView(text(getString(R.string.ui_reset), 14f).apply {
            setPaddingRelative(dp(12), 0, 0, 0)
            minimumHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(primary)
            setOnClickListener { haptic(it); resetToDefaults(listOf(key)) }
        })
        add(group, labels)
        if(hint != null) add(group, text(hint,12f,true),6)
        val control = (if (useCoui) CouiSlider(this, primary, color(M.attr.colorSurfaceContainerHigh))
            else Slider(this)).apply {
            valueFrom = min.toFloat(); valueTo = max.toFloat(); stepSize = 1f
            this.value = number(key).coerceIn(min,max).toFloat()
            contentDescription = label
            setLabelFormatter { formatted(it.roundToInt()) }
        }
        var lastTick = 0L
        var tracking = false
        refreshers.add {
            if(!tracking) {
                control.value=number(key).coerceIn(min,max).toFloat()
                value.text=formatted(number(key))
            }
        }
        control.addOnChangeListener { view, changed, user ->
            val n = changed.roundToInt(); value.text = formatted(n)
            if(user) {
                val now = SystemClock.uptimeMillis()
                if (now-lastTick >= 35L || n == min || n == max) { haptic(view,true); lastTick=now }
                // Keep UI movement local. Notify the launcher once when released,
                // avoiding expensive model/blur resets for every finger pixel.
                prefs.edit().putInt(key,n).apply();onPreview?.invoke(n)
                if (!tracking) { contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null); updateGates() }
            }
        }
        control.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { tracking = true; haptic(slider,true) }
            override fun onStopTrackingTouch(slider: Slider) { tracking = false; save(key,slider.value.roundToInt()) }
        })
        control.setOnKeyListener { _, _, event ->
            if(event.action == KeyEvent.ACTION_UP) { contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null) }
            false
        }
        add(group, control, 6)
        return group
    }
    private fun effectGroup(parent: LinearLayout, label: String, switchKey: String, sliderLabel: String, sliderKey: String,
        max: Int = 100, allowed: () -> Boolean = { design() }, hint: String? = null, min: Int = 0, suffix: String = "%",
        extra: ((LinearLayout) -> Unit)? = null) {
        val group = column()
        val details=column().apply { visibility=View.GONE }
        divider(details)
        val control = slider(sliderLabel,sliderKey,min,max,suffix,hint)
        gate(control) { allowed() && bool(switchKey) }
        add(details,control)
        extra?.invoke(details)
        add(group, switchRow(label,switchKey,expanded=false) { open ->
            reveal(details,open)
        })
        add(group,details)
        add(parent,card(group,true),top=8,bottom=4)
    }

    private fun glyphOutlineGroup(parent: LinearLayout) {
        val group=column()
        val details=column().apply { visibility=View.GONE }
        gate(details) { design() && bool(SettingsProvider.KEY_GLYPH_STROKE) }
        divider(details)
        add(details,slider(getString(R.string.ui_logo_outline_opacity),SettingsProvider.KEY_GLYPH_STROKE_OPACITY,0,100,"%"))
        add(details,slider(getString(R.string.ui_logo_outline_width),SettingsProvider.KEY_GLYPH_STROKE_WIDTH,0,100,"%"))
        add(details,slider(getString(R.string.ui_logo_outline_brightness),SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,0,100,"%"))
        add(group,switchRow(getString(R.string.ui_logo_outline),SettingsProvider.KEY_GLYPH_STROKE,expanded=false) { open -> reveal(details,open) })
        add(group,details)
        add(parent,card(group,true),top=8,bottom=4)
    }

    private fun blurToneGroup(parent:LinearLayout) {
        val group=column()
        val title=getString(R.string.ui_blur_tone)
        val details=column().apply {visibility=View.GONE}
        add(group,switchRow(title,SettingsProvider.KEY_BLUR_TONE_ENABLED,expanded=false) {open -> reveal(details,open)})
        gate(details) {blur() && bool(SettingsProvider.KEY_BLUR_TONE_ENABLED)}
        for((label,sliderKey) in listOf(
            R.string.ui_plate_opacity to SettingsProvider.KEY_BG_OPACITY,
            R.string.ui_blur_saturation to SettingsProvider.KEY_BLUR_SATURATION,
            R.string.ui_blur_vibrancy to SettingsProvider.KEY_BLUR_VIBRANCY,
            R.string.ui_blur_brightness to SettingsProvider.KEY_BLUR_LIGHTENING)) {
            divider(details)
            val hint=getString(when(sliderKey) {
                SettingsProvider.KEY_BG_OPACITY -> R.string.ui_plate_opacity_hint
                SettingsProvider.KEY_BLUR_VIBRANCY -> R.string.ui_blur_vibrancy_hint
                SettingsProvider.KEY_BLUR_SATURATION -> R.string.ui_blur_saturation_hint
                else -> R.string.ui_blur_lightening_hint
            })
            val control=slider(getString(label),sliderKey,0,if(sliderKey==SettingsProvider.KEY_BLUR_SATURATION || sliderKey==SettingsProvider.KEY_BLUR_VIBRANCY)200 else 100,"%",hint)
            add(details,control)
        }
        add(group,details)
        add(parent,card(group,true),top=8,bottom=4)
    }

    /** Experimental variants for plate-less picture icons (games, photos, gradients). */
    private fun artworkIcons(parent:LinearLayout){
        add(parent,text(getString(R.string.ui_artwork_icons),16f,true).apply {setPadding(dp(20),dp(12),dp(20),0)})
        val control=slider(getString(R.string.ui_artwork_transparency),SettingsProvider.KEY_ARTWORK_TRANSPARENCY,0,100,"%")
        gate(control){bool(SettingsProvider.KEY_DEVELOPER_MODE)}
        add(parent,control)
        add(parent,text(getString(R.string.ui_artwork_icons_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
    }

    private fun telegramButton()=com.google.android.material.button.MaterialButton(this).apply {
        text="CherryPick TG";isAllCaps=false;setSingleLine(true)
        minHeight=dp(48);insetTop=0;insetBottom=0;cornerRadius=dp(16)
        setIconResource(R.drawable.ic_telegram);iconSize=dp(24)
        backgroundTintList=ColorStateList.valueOf(ContextCompat.getColor(context,R.color.ui_telegram))
        setTextColor(Color.WHITE);iconTint=ColorStateList.valueOf(Color.WHITE)
        setOnClickListener {
            haptic(it)
            try {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://t.me/CherryPickIcons")))}
            catch(_:Exception){Toast.makeText(context,R.string.ui_telegram_error,Toast.LENGTH_SHORT).show()}
        }
    }

    private fun supportButton() = com.google.android.material.button.MaterialButton(this).apply {
        text=getString(R.string.ui_support_project);isAllCaps=false;setSingleLine(true)
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,12,14,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        gravity=Gravity.CENTER;textAlignment=View.TEXT_ALIGNMENT_CENTER
        textSize=14f;minHeight=dp(48);insetTop=0;insetBottom=0;cornerRadius=dp(16)
        setPadding(dp(16),dp(6),dp(16),dp(6))
        backgroundTintList=ColorStateList.valueOf(color(M.attr.colorTertiary))
        val ink=color(M.attr.colorOnTertiary)
        setTextColor(ink)
        rippleColor=ColorStateList.valueOf((ink and 0x00ffffff) or 0x33000000)
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        contentDescription=getString(R.string.ui_support_project)
        setOnClickListener {haptic(it);openSupport()}
    }

    private fun openSupport() {
        try {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://pay.cloudtips.ru/p/e6230fb4")))}
        catch(_:Exception){Toast.makeText(this,getString(R.string.ui_link_error),Toast.LENGTH_SHORT).show()}
    }

    private fun shareReport(button: View, label: TextView) {
        button.isEnabled = false
        label.setText(R.string.ui_report_creating)
        executor.execute {
            try {
                val file = com.cosmod.launcherglass.util.DiagnosticReport.create(this, prefs)
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.logs", file)
                runOnUiThread {
                    button.isEnabled = true
                    label.setText(R.string.ui_logs)
                    if (!isFinishing && !isDestroyed) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "CherryPick Icons ${BuildConfig.VERSION_NAME} report")
                            clipData = ClipData.newUri(contentResolver, file.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(send, getString(R.string.ui_share_logs)))
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    button.isEnabled = true
                    label.setText(R.string.ui_logs)
                    if (!isFinishing && !isDestroyed) Toast.makeText(this,
                        getString(R.string.ui_log_error, error.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildSettings(body: LinearLayout) {
        if (useCoui) {
            val common = column()
            val language = text(if (UiLanguage.tag(this) == "ru") "Русский" else "English", 14f, muted = true)
            add(common, couiValueRow(getString(R.string.ui_language), language, icon = R.drawable.ic_language, action = ::showLanguageDialog))
            divider(common)
            val reportLabel = text(getString(R.string.ui_logs), 16f)
            add(common, couiValueRow(reportLabel) { shareReport(it, reportLabel) })
            add(body, card(common, contentInset = CouiGeometry.GROUP_INSET_DP), bottom = 12)
        } else {
            add(body, com.google.android.material.button.MaterialButton(this).apply {
                text = getString(R.string.ui_language)
                isAllCaps = false
                setOnClickListener { showLanguageDialog(it) }
            }, bottom = 12)
            add(body, com.google.android.material.button.MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.ui_logs)
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { haptic(it); shareReport(it, this) }
            }, bottom = 16)
        }
        val legacyLabel = getString(R.string.ui_legacy_interface)
        val legacyHint = getString(R.string.ui_legacy_interface_hint)
        val interfaceOption = if (useCoui) couiSwitchRow(legacyLabel, SettingsProvider.KEY_LEGACY_INTERFACE,
            legacyHint, false, null) else row().apply {
                setPadding(dp(16), dp(12), dp(12), dp(12))
                val labels = column()
                add(labels, text(legacyLabel))
                add(labels, text(legacyHint, 13f, true), top = 4)
                addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                addView(makeSwitch(legacyLabel, SettingsProvider.KEY_LEGACY_INTERFACE))
            }
        val groupInset = if (useCoui) CouiGeometry.GROUP_INSET_DP else 0
        add(body, card(interfaceOption, contentInset = groupInset), bottom = 12)
        if (useCoui) add(body, card(
            couiValueRow(getString(R.string.ui_developer_mode)) { navigate(SettingsScreen.DEVELOPER) },
            contentInset = groupInset), bottom = 12)
        else category(body,R.string.ui_developer_mode,R.string.ui_nav_developer_hint,SettingsScreen.DEVELOPER)
        val bootRestart=column()
        add(bootRestart,switchRow(getString(R.string.ui_boot_restart),SettingsProvider.KEY_BOOT_RESTART))
        val textInset = dp(if (useCoui) 16 else 20)
        add(bootRestart,text(getString(R.string.ui_boot_restart_hint),12f,true).apply {setPadding(textInset,0,textInset,dp(12))})
        divider(bootRestart)
        val bootToast=switchRow(getString(R.string.ui_boot_restart_toast),SettingsProvider.KEY_BOOT_RESTART_TOAST)
        gate(bootToast){bool(SettingsProvider.KEY_BOOT_RESTART)}
        add(bootRestart,bootToast)
        add(body,card(bootRestart,contentInset=groupInset),bottom=12)
        add(body,card(column().apply {
            add(this,switchRow(getString(R.string.ui_hide_home_gesture_bar),SettingsProvider.KEY_HIDE_HOME_GESTURE_BAR))
            add(this,text(getString(R.string.ui_hide_home_gesture_bar_hint),12f,true).apply {setPadding(textInset,0,textInset,dp(if (useCoui) 8 else 16))})
        },contentInset=groupInset),bottom=12)
        val resetLabel=text(getString(R.string.ui_reset_all_settings),16f).apply {setTextColor(color(M.attr.colorError))}
        val resetEntry=if(useCoui)couiValueRow(resetLabel) {confirmResetAll(it)}
            else row().apply {
                minimumHeight=dp(56);setPadding(dp(20),dp(12),dp(20),dp(12))
                addView(resetLabel);isFocusable=true;setOnClickListener {confirmResetAll(it)}
            }
        add(body,card(resetEntry,contentInset=groupInset),top=8,bottom=12)
    }
    private fun confirmResetAll(source:View) {
        val dialog=MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ui_reset_all_settings)
            .setMessage(R.string.ui_reset_all_settings_message)
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.ui_reset) {_,_ ->
                source.isEnabled=false
                executor.execute {
                    val success=runCatching {
                        SettingsReset.apply(prefs)
                        WallpaperProfiles.clearSaved(this)
                        contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null)
                        SuUtil.restartLauncher()
                    }.isSuccess
                    runOnUiThread {
                        source.isEnabled=true
                        Toast.makeText(this,if(success)R.string.ui_reset_all_done else R.string.ui_reset_all_error,Toast.LENGTH_LONG).show()
                        if(success)recreate()
                    }
                }
            }.create()
        dialog.setOnShowListener {dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(color(M.attr.colorError))}
        dialog.show()
    }
    private fun buildDeveloperSettings(body:LinearLayout){
        val developer=column()
        add(developer,switchRow(getString(R.string.ui_developer_mode),SettingsProvider.KEY_DEVELOPER_MODE))
        val developerOptions=column()
        divider(developerOptions)
        val transparency=slider(getString(R.string.ui_blur_transparency_experiment),SettingsProvider.KEY_BLUR_TRANSPARENCY,
            0,100,"%",getString(R.string.ui_blur_transparency_experiment_hint),restore=true)
        gate(transparency){number(AutoIconStyle.ENGINE)!=1}
        add(developerOptions,transparency)
        val haze=switchRow(getString(R.string.ui_ios_haze),SettingsProvider.KEY_HAZE_ENABLED,getString(R.string.ui_ios_haze_hint))
        gate(haze){android.os.Build.VERSION.SDK_INT>=33}
        add(developerOptions,haze)
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_music_island_expansion),SettingsProvider.KEY_MUSIC_ISLAND_EXPANSION,
            getString(R.string.ui_music_island_expansion_hint)))
        add(developerOptions,switchRow(getString(R.string.ui_music_island_blur),SettingsProvider.KEY_MUSIC_ISLAND_BLUR))
        add(developerOptions,switchRow(getString(R.string.ui_music_island_glow),SettingsProvider.KEY_MUSIC_ISLAND_GLOW))
        add(developerOptions,slider(getString(R.string.ui_music_island_outer),SettingsProvider.KEY_MUSIC_ISLAND_OUTER,0,48,"dp"))
        add(developerOptions,slider(getString(R.string.ui_music_island_content_x),SettingsProvider.KEY_MUSIC_ISLAND_CONTENT_X,0,48,"dp"))
        add(developerOptions,slider(getString(R.string.ui_music_island_content_y),SettingsProvider.KEY_MUSIC_ISLAND_CONTENT_Y,0,48,"dp"))
        add(developerOptions,slider(getString(R.string.ui_music_island_radius),SettingsProvider.KEY_MUSIC_ISLAND_RADIUS,0,48,"dp"))
        divider(developerOptions)
        add(developerOptions,slider(getString(R.string.ui_dock_blur_y),SettingsProvider.KEY_DOCK_BLUR_Y,-120,120,"dp"))
        add(developerOptions,slider(getString(R.string.ui_dock_blur_height),SettingsProvider.KEY_DOCK_BLUR_HEIGHT,50,200,"%"))
        divider(developerOptions)
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_module_logging),SettingsProvider.KEY_MODULE_LOGGING,
            getString(R.string.ui_module_logging_hint)))
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_gpu_effects),SettingsProvider.KEY_GPU_EFFECTS))
        add(developerOptions,text(getString(R.string.ui_gpu_effects_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_smooth_swipe_lead),SettingsProvider.KEY_SMOOTH_SWIPE_LEAD))
        add(developerOptions,text(getString(R.string.ui_smooth_swipe_lead_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_disable_edge_optimization),SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION))
        add(developerOptions,text(getString(R.string.ui_disable_edge_optimization_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_optimize_mini_icons),SettingsProvider.KEY_OPTIMIZE_MINI_ICONS))
        add(developerOptions,text(getString(R.string.ui_optimize_mini_icons_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_limit_closing_blur),SettingsProvider.KEY_LIMIT_CLOSING_BLUR))
        add(developerOptions,text(getString(R.string.ui_limit_closing_blur_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        add(developerOptions,switchRow(getString(R.string.ui_scene_drag_backdrop),SettingsProvider.KEY_SCENE_DRAG_BACKDROP))
        add(developerOptions,text(getString(R.string.ui_scene_drag_backdrop_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        artworkIcons(developerOptions)
        divider(developerOptions)
        developerOptions.visibility=if(bool(SettingsProvider.KEY_DEVELOPER_MODE))View.VISIBLE else View.GONE
        refreshers.add {developerOptions.visibility=if(bool(SettingsProvider.KEY_DEVELOPER_MODE))View.VISIBLE else View.GONE}
        gate(developerOptions){bool(SettingsProvider.KEY_DEVELOPER_MODE)}
        add(developer,developerOptions)
        add(body,card(developer),bottom=12)
    }
    private fun updateFluxWarning() {
        fluxWarning?.visibility=if(::prefs.isInitialized && prefs.getInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,0)==2)View.VISIBLE else View.GONE
    }
    private fun stopModuleBlink(){
        moduleBlink?.cancel();moduleBlink=null
        moduleBlinkTarget?.alpha=1f;moduleBlinkTarget=null
    }
    private fun updateModuleBlink(blink:Boolean){
        val target=moduleToggle?.let {if(it is SquareAction)it.glyph else it}
        if(!blink || target==null){stopModuleBlink();return}
        if(moduleBlinkTarget===target && moduleBlink!=null)return
        stopModuleBlink();moduleBlinkTarget=target
        moduleBlink=ValueAnimator.ofFloat(1f,.25f,1f).apply {
            duration=1300L;repeatCount=ValueAnimator.INFINITE
            addUpdateListener {target.alpha=it.animatedValue as Float}
            start()
        }
    }
    private fun updateStatus() {
        updateFluxWarning()
        val lspActive=scopeState?.enabled ?: isModuleActive()
        val missing=lspActive && scopeState?.launcherSelected==false
        val unverified=lspActive && scopeState==null
        val active=lspActive && !missing && master()
        val ready=scopeState?.let {it.enabled && it.launcherSelected}==true
        val prompt=ready && !master()
        updateModuleBlink(prompt && overviewResumed && navigation.current==SettingsScreen.HOME)
        lsposedText?.text=if(lspActive)getString(R.string.ui_lsposed_enabled) else getString(R.string.ui_lsposed_disabled)
        statusText?.text=if(active)getString(R.string.ui_cherrypick_enabled) else getString(R.string.ui_cherrypick_disabled)
        val dot=when {
            missing || unverified -> secondaryText
            active -> onSurface
            else -> secondaryText
        }
        statusDot?.backgroundTintList=ColorStateList.valueOf(dot)
        statusHint?.apply {
            visibility=if(missing || unverified || prompt)View.VISIBLE else View.GONE
            text=if(missing)getString(R.string.ui_scope_missing) else if(unverified)getString(R.string.ui_scope_unknown)
                else getString(R.string.ui_enable_module_hint)
        }
        statusText?.contentDescription="${lsposedText?.text}. ${statusText?.text}. ${statusHint?.text.takeIf { missing || unverified || prompt } ?: ""}"
    }
    private fun restartButton(): View {
        val shape=GradientDrawable().apply {
            setColor(color(M.attr.colorPrimaryContainer))
            cornerRadius=dp(32).toFloat()
        }
        val arrow=AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_restart)
            imageTintList=ColorStateList.valueOf(color(M.attr.colorOnPrimaryContainer))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(this).apply {
            layoutParams=LinearLayout.LayoutParams(dp(64),dp(64))
            contentDescription=getString(R.string.ui_restart_launcher)
            isFocusable=true
            background=RippleDrawable(ColorStateList.valueOf(((primary and 0x00ffffff) or 0x20000000)),shape,null)
            addView(arrow,FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER))
            setOnClickListener { haptic(it);restart(it) }
        }
    }
    private fun restart(view: View) {
        if(restartBusy)return
        restartBusy=true;view.isEnabled=false
        Thread({
            val success=SuUtil.restartLauncher()
            runOnUiThread { restartBusy=false;view.isEnabled=true
                Toast.makeText(this,if(success)getString(R.string.ui_launcher_restarted) else getString(R.string.ui_launcher_restart_error),Toast.LENGTH_SHORT).show()
            }
        },"CherryPickRestart").start()
    }
    /** Replaced by the LSPosed activation hook. */
    @Keep fun isModuleActive(): Boolean = scopeState?.let {it.enabled && it.launcherSelected} == true
}
