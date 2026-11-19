package com.cosmod.launcherglass.util



import android.app.WallpaperManager

import android.content.Context

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.graphics.BitmapShader

import android.graphics.Canvas

import java.util.WeakHashMap

import android.graphics.Point

import android.os.Handler

import android.os.Looper

import android.os.SystemClock

import android.os.ParcelFileDescriptor

import android.provider.Settings

import java.util.concurrent.Executors

import android.graphics.Matrix

import android.graphics.Shader

import com.cosmod.launcherglass.xposed.PrefsHelper

import java.io.File

import java.lang.ref.WeakReference

import kotlin.math.roundToInt



object WallpaperHelper {



    @Volatile

    var appContext: Context? = null

    @Volatile private var liveWallpaper: Boolean? = null
    /** Wallpaper type changes on ACTION_WALLPAPER_CHANGED, not on every frame. */
    fun isLiveWallpaper(context:Context):Boolean {
        liveWallpaper?.let {return it}
        return try {WallpaperManager.getInstance(context).wallpaperInfo!=null}
            catch(_:Throwable){false}.also {liveWallpaper=it}
    }



    /**
     * Wallpaper processed with the own settings of a folder/dock scope. It is
     * built next to every shared wallpaper candidate and published together
     * with it, so both always come from the same frame.
     */
    private class OwnWallpaper(val settings:com.cosmod.launcherglass.xposed.ScopedGlassSettings,val enabled:()->Boolean,val tag:String) {
        @Volatile var published:Bitmap?=null
        val candidates=java.util.WeakHashMap<Bitmap,Bitmap>()
        fun active()=settings.needsOwnBackdrop && enabled() && PrefsHelper.enabled
    }
    private val folderOwn=OwnWallpaper(com.cosmod.launcherglass.xposed.FolderSettings,{PrefsHelper.folderBlurEnabled},"FolderMaterial")
    private val dockOwn=OwnWallpaper(com.cosmod.launcherglass.xposed.DockSettings,{PrefsHelper.dockEnabled},"DockMaterial")
    private fun scoped(own:OwnWallpaper,context:Context?):Bitmap? {
        if(!PrefsHelper.backdropEnabled)return null
        val shared=getBlurredWallpaper(context)
        return if(own.settings.needsOwnBackdrop)own.published?.takeUnless {it.isRecycled} ?: shared else shared
    }
    fun getFolderWallpaper(context:Context?=null):Bitmap?=scoped(folderOwn,context)
    @Volatile private var staticDockWallpaper:Bitmap?=null
    @Volatile private var reuseStaticDock = true
    fun getDockWallpaper(context:Context?=null):Bitmap? {
        if(!PrefsHelper.backdropEnabled)return null
        val ctx=context ?: appContext
        val live=ctx?.let {isLiveWallpaper(it)} ?: true
        if(!live)staticDockWallpaper?.takeUnless {it.isRecycled}?.let {return it}
        val image=scoped(dockOwn,context)
        // While a replacement loads, scoped() may still return the preceding
        // shared image. It is drawable, but must not become the pinned dock.
        if(!live && image!=null && cachedRevision==sourceRevision &&
            (!dockOwn.settings.needsOwnBackdrop || dockOwn.published===image))staticDockWallpaper=image
        return image
    }
    private data class Backdrops(val shared: Bitmap, val folder: Bitmap?, val dock: Bitmap?)
    private class Dissolve(val previous: Backdrops, val next: Backdrops, val startedAt: Long, val captureEpoch: Long)
    @Volatile private var dissolve: Dissolve? = null
    private var wakeNeedsLiveFrame = false

    private fun applyBackdrops(frame: Backdrops) {
        blurredWallpaper = frame.shared
        folderOwn.published = frame.folder
        dockOwn.published = frame.dock
        staticDockWallpaper = null
        wallpaperStamp++
    }

    // Runs with the wallpaper lock. Usual live frames are never dissolved.
    private fun publishBackdrops(candidate: Bitmap, animated: Boolean) {
        reuseStaticDock = true
        val next = Backdrops(candidate, synchronized(folderOwn.candidates) { folderOwn.candidates.remove(candidate) },
            synchronized(dockOwn.candidates) { dockOwn.candidates.remove(candidate) })
        val old = blurredWallpaper?.takeUnless(Bitmap::isRecycled)
        if (!animated || old == null || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            dissolve = null
            applyBackdrops(next)
            return
        }
        val previous = Backdrops(old, folderOwn.published, dockOwn.published)
        val swap = Dissolve(previous, next, SystemClock.uptimeMillis(), LauncherWallpaperCapture.captureEpoch)
        dissolve = swap
        advanceDissolve(swap)
    }

    private fun advanceDissolve(swap: Dissolve) {
        mainHandler.postDelayed({
            if (dissolve !== swap) return@postDelayed
            loader.execute {
                if (dissolve !== swap) return@execute
                val progress = ((SystemClock.uptimeMillis() - swap.startedAt) / 180f).coerceIn(0f, 1f)
                val visible = LauncherWallpaperCapture.acceptsCapture(swap.captureEpoch) &&
                    LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress && PrefsHelper.enabled
                val metrics = appContext?.resources?.displayMetrics
                val intermediate = if (visible && progress < 1f && metrics != null) runCatching {
                    val mix = progress * progress * (3f - 2f * progress)
                    val old = swap.previous
                    val next = swap.next
                    fun blend(from: Bitmap, to: Bitmap) = if (from === to) to else
                        GpuWallpaperBlur.dissolve(from, to, metrics.widthPixels, metrics.heightPixels, mix)
                    Backdrops(blend(old.shared, next.shared), next.folder?.let { blend(old.folder ?: old.shared, it) },
                        next.dock?.let { blend(old.dock ?: old.shared, it) })
                }.getOrNull() else null
                synchronized(this) {
                    if (dissolve !== swap) return@execute
                    // Intermediates can never outlive a new wallpaper or the
                    // sleep epoch that started this transition.
                    val frame = intermediate?.takeIf { LauncherWallpaperCapture.acceptsCapture(swap.captureEpoch) }
                    applyBackdrops(frame ?: swap.next)
                    if (frame == null) dissolve = null
                }
                if (visible) mainHandler.post { LiveBlurEngine.onWallpaperFrameReady() }
                if (dissolve === swap) advanceDissolve(swap)
            }
        }, 28L)
    }
    private fun withFolder(candidate:Bitmap,raw:Bitmap):Bitmap {
        for(own in listOf(folderOwn,dockOwn))if(own.active())withOwn(own,candidate,raw)
        return candidate
    }
    private fun withOwn(own:OwnWallpaper,candidate:Bitmap,raw:Bitmap) {
        // Static dock processing is independent of transient wallpaper captures
        // during unlock. Keep the completed texture until settings/wallpaper
        // invalidation; the native hotseat animates its retained layer.
        if(own===dockOwn && reuseStaticDock && appContext?.let { !isLiveWallpaper(it) }==true){
            own.published?.takeUnless {it.isRecycled}?.let {stable ->
                synchronized(own.candidates){own.candidates[candidate]=stable}
                return
            }
        }
        val options=own.settings.processing
        val h=minOf(600+options.quality*12,raw.height)
        val w=(raw.width.toFloat()*h/raw.height).roundToInt().coerceAtLeast(1)
        val radius=LiquidOptics.blurRadius(options.radius,h,options.liquid)
        val gpuImage=if(PrefsHelper.gpuEffects)try {
            val input=if(android.os.Build.VERSION.SDK_INT<33)BackdropVibrancy.applyCpu(raw,options.vibrancy)else raw
            val gpu=try {
                if(own===dockOwn)GpuWallpaperBlur.blurDock(input,w,h,radius,options.lightening,options.vibrancy,options.transparency)
                else GpuWallpaperBlur.blurFolder(input,w,h,radius,options.lightening,options.vibrancy,options.transparency)
            }finally{if(input!==raw)input.recycle()}
            if(ShadowTone.gpuSupported)gpu else ShadowTone.applyCpu(gpu,options.lightening).also {if(it!==gpu)gpu.recycle()}
        }catch(t:Throwable){
            GlassLogger.e(own.tag,"Own GPU processing unavailable; using CPU",t)
            null
        }else null
        val image=gpuImage ?: run {
            val scaled=Bitmap.createScaledBitmap(raw,w,h,true)
            val software=if(scaled.config==Bitmap.Config.HARDWARE)scaled.copy(Bitmap.Config.ARGB_8888,false) ?: return else scaled
            val vivid=BackdropVibrancy.applyCpu(software,options.vibrancy)
            val input=vivid
            val actualRadius=radius
            val blurred=BlurOpacity.blurCpu(input,actualRadius,options.transparency)
            val toned=ShadowTone.applyCpu(blurred,options.lightening)
            val output=toned
            if(toned!==blurred && output!==toned)toned.recycle()
            if(output!==blurred)blurred.recycle()
            if(input!==vivid)input.recycle()
            if(vivid!==software)vivid.recycle()
            if(software!==raw)software.recycle()
            if(scaled!==raw && scaled!==software)scaled.recycle()
            output
        }
        synchronized(own.candidates){own.candidates[candidate]=image}
    }
    @Volatile private var blurredWallpaper: Bitmap? = null

    @Volatile

    private var cachedBlurRadius: Int = -1

    private val loader = Executors.newSingleThreadExecutor { runnable ->

        Thread(runnable, "CosmodWallpaper").apply { isDaemon = true }

    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isReloading = false
    private var prefsReady = false
    private var initialFailures = 0
    private val retryInitial = Runnable {
        synchronized(this) { appContext?.let(::requestLoad) }
    }

    private var settlingReloadPosted = false
    private val settlingReload = Runnable {
        synchronized(this) { settlingReloadPosted = false }
        appContext?.let { getBlurredWallpaper(it) }
    }

    private var sourceRevision = 0L

    /**
     * Monotonic stamp of the wallpaper content. Surfaces that cache a
     * wallpaper composite compare it instead of rebuilding every frame.
     */
    @Volatile private var wallpaperStamp = 0L
    fun stamp(): Long = wallpaperStamp
    @Synchronized fun sourceReady():Boolean = blurredWallpaper?.isRecycled==false && cachedRevision==sourceRevision

    private var cachedRevision = -1L

    private var retryAfterMs = 0L

    private var launcherWallpaper: WeakReference<Bitmap>? = null
    private var launcherWallpaperGeneration = 0
    private var launcherWallpaperWidth = 0
    private var launcherWallpaperHeight = 0
    private var launcherStaticWallpaperId = -1

    @Volatile private var systemSnapshot: Bitmap? = null

    @Volatile private var snapshotTimeMs = 0L

    private var logCount = 0

    @Volatile private var liveFrameSignature: Long? = null

    private var livePublishSerial=0L

    private val softwareSnapshots = WeakHashMap<Bitmap, Bitmap>()

    private var gpuFailureLogged = false






    fun processLiveFrame(frame: Bitmap, valid:()->Boolean, finished: (Long) -> Unit) {

        loader.execute {

            var signature = 17L

            var retained = false

            try {

                if (dissolve != null || LiveBlurEngine.freezeAnimationWallpaper || !valid() || !PrefsHelper.enabled || !PrefsHelper.backdropEnabled || (!LiveBlurEngine.isResumed && !com.cosmod.launcherglass.xposed.NativeSurfaceLive.active) ||

                    (LiveBlurEngine.isAppLaunchInProgress && !com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) return@execute

                signature = GpuWallpaperBlur.fingerprint(frame)

                val radius = (if(PrefsHelper.colorOnly)0 else PrefsHelper.blurRadius).coerceIn(0, 50)

                val revision = synchronized(this) { sourceRevision }

                if (signature != liveFrameSignature || cachedBlurRadius != radius || cachedRevision != revision) {

                    val context = appContext

                    val candidate = if (context != null) loadAndBlurWallpaper(context, radius, frame) else null

                    if (candidate != null) synchronized(this) {

                        if (!LiveBlurEngine.freezeAnimationWallpaper && valid() && revision == sourceRevision && radius == (if(PrefsHelper.colorOnly)0 else PrefsHelper.blurRadius).coerceIn(0, 50) &&

                            PrefsHelper.enabled && ((LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress) || com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) {

                            systemSnapshot = frame

                            snapshotTimeMs = SystemClock.uptimeMillis()

                            retained = true

                            publishBackdrops(candidate, wakeNeedsLiveFrame || cachedRevision != revision)
                            wakeNeedsLiveFrame = false

                            cachedBlurRadius = radius

                            cachedRevision = revision

                            liveFrameSignature = signature

                            livePublishSerial++

                            mainHandler.post {

                                if (PrefsHelper.enabled && ((LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress) || com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) {

                                    refreshLiveFrame()

                                }

                            }

                        } else candidate.recycle()

                    }

                } else synchronized(this) { if (valid()) wakeNeedsLiveFrame = false }

            } catch (t: Throwable) {

                GlassLogger.e("WallpaperHelper", "Failed to process live wallpaper frame", t)

            } finally {

                if (!retained && !frame.isRecycled) frame.recycle()

                finished(signature)

            }

        }

    }



    private fun refreshLiveFrame() {

        LiveBlurEngine.onWallpaperFrameReady()

    }



    fun forCanvas(bitmap: Bitmap, canvas: Canvas?): Bitmap {

        if (canvas == null || canvas.isHardwareAccelerated || bitmap.config != Bitmap.Config.HARDWARE) return bitmap

        // Only native offscreen software snapshots need this compatibility copy.

        return synchronized(softwareSnapshots) {
            softwareSnapshots[bitmap]?.takeUnless { it.isRecycled }
                ?: (bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap).also { softwareSnapshots[bitmap] = it }
        }

    }



    @Volatile private var processingActive=false
    fun onProcessingSettingsChanged() {

        val active=PrefsHelper.enabled && PrefsHelper.backdropEnabled
        if(processingActive && !active)loader.execute { GpuWallpaperBlur.close() }
        processingActive=active

    }



    @Synchronized

    fun invalidate(preserveStaticDock:Boolean=false) {
        dissolve = null

        if (!preserveStaticDock) {
            reuseStaticDock = false
            staticDockWallpaper = null
        }

        // Keep the last completed bitmap until its replacement is ready. Display

        // lists may still reference it, so never recycle a published wallpaper.

        sourceRevision++

        wallpaperStamp++

        retryAfterMs = 0L
        initialFailures = 0
        mainHandler.removeCallbacks(retryInitial)

        appContext?.let { requestLoad(it) }

    }



    @Synchronized

    fun onSystemWallpaperChanged() {

        liveWallpaper=null

        launcherWallpaper = null
        launcherStaticWallpaperId = -1

        systemSnapshot = null

        snapshotTimeMs = 0L

        invalidate()

        mainHandler.post { LauncherWallpaperCapture.startMotionWatch() }

    }



    fun hasSystemWallpaperEffect(context: Context): Boolean = try {

        Settings.Secure.getInt(context.contentResolver, "is_desktop_wallpaper_blur_enable", 0) != 0 ||

            Settings.Secure.getInt(context.contentResolver, "com.oplus.wallpapers.isLauncherEffectWallpaper", 0) != 0 ||

            WallpaperManager.getInstance(context).wallpaperInfo != null

    } catch (_: Throwable) { false }



    // This API opens the system's wallpaper-only screenshot. It neither hides

    // launcher surfaces nor captures icons, unlike a full-screen screenshot.

    fun readSystemSnapshot(context: Context): Bitmap? {

        val cached = systemSnapshot?.takeUnless { it.isRecycled }

        if (Looper.myLooper() == Looper.getMainLooper()) {

            return cached?.copy(Bitmap.Config.ARGB_8888, false)

        }

        if (cached != null && SystemClock.uptimeMillis() - snapshotTimeMs < 500L) {

            return cached.copy(Bitmap.Config.ARGB_8888, false)

        }

        return try {

            val managerClass = Class.forName("android.app.OplusWallpaperManager")

            val manager = managerClass.getDeclaredConstructor().newInstance()

            val userClass = Class.forName("android.os.UserHandle")

            val userId = userClass.getDeclaredMethod("myUserId").invoke(null) as Int

            val descriptor = managerClass.getMethod("getWallpaperScreenShot", Int::class.javaPrimitiveType)

                .invoke(manager, userId) as? ParcelFileDescriptor

            descriptor?.use {

                BitmapFactory.decodeFileDescriptor(it.fileDescriptor)?.takeUnless { bitmap -> bitmap.isRecycled }

            } ?: LauncherWallpaperCapture.capture(context)

        } catch (t: Throwable) {

            if (logCount < 3) GlassLogger.i("WallpaperHelper", "System screenshot unavailable: ${t.javaClass.simpleName}")

            LauncherWallpaperCapture.capture(context)

        }

    }



    @Synchronized

    fun onLauncherWallpaper(bitmap: Bitmap) {

        if (bitmap.isRecycled) return

        // OEM resume/unlock can deliver the same retained bitmap repeatedly.
        // Its content, not the callback count, determines a new blur revision.
        if (launcherWallpaper?.get() === bitmap && launcherWallpaperGeneration == bitmap.generationId) return

        // Resume after an app hands back the same wallpaper as a new wrapper.
        // Re-blurring identical content is a full-screen burst in the middle of
        // the return animation, so only a real content change invalidates.
        val staticId=try {
            appContext?.takeIf {!isLiveWallpaper(it)}?.let {
                WallpaperManager.getInstance(it).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            } ?: -1
        }catch(_:Throwable){-1}
        val firstStaticDelivery = staticId >= 0 && launcherStaticWallpaperId < 0
        val sameContent = if(staticId>=0)staticId==launcherStaticWallpaperId && launcherWallpaper?.get()!=null
        else launcherWallpaperGeneration==bitmap.generationId && launcherWallpaperWidth==bitmap.width &&
            launcherWallpaperHeight==bitmap.height && launcherWallpaper?.get()!=null
        launcherWallpaper = WeakReference(bitmap)
        launcherWallpaperGeneration = bitmap.generationId
        launcherWallpaperWidth = bitmap.width
        launcherWallpaperHeight = bitmap.height
        launcherStaticWallpaperId = staticId
        if (sameContent || (firstStaticDelivery && (isReloading || cachedRevision == sourceRevision))) return

        // Resume may deliver a new wrapper of the same static wallpaper.
        // Its moving capture is useful for icons, but does not change the dock.
        // Real wallpaper changes and settings use the full invalidation above.
        invalidate(preserveStaticDock=appContext?.let { !isLiveWallpaper(it) }==true)

        mainHandler.post { LauncherWallpaperCapture.startMotionWatch() }

    }



    @Synchronized

    fun onLauncherWindowReady() {

        if (blurredWallpaper == null || cachedRevision != sourceRevision ||

            cachedBlurRadius != (if(PrefsHelper.colorOnly)0 else PrefsHelper.blurRadius).coerceIn(0, 50)) {

            // Window readiness does not change the wallpaper. Incrementing
            // here rejected the in-flight load on every rapid pause/resume.

            retryAfterMs = 0L

            appContext?.let { requestLoad(it) }

        }

    }

    @Synchronized
    internal fun onHomeWallpaperVisible(watchingLiveWallpaper: Boolean) {
        val context = appContext ?: return
        if (isLiveWallpaper(context)) {
            // Force the first clean frame to win over cached screenshots or
            // wallpaper frames retained from before the screen turned off.
            systemSnapshot = null
            snapshotTimeMs = 0L
            if (!watchingLiveWallpaper) invalidate() else wakeNeedsLiveFrame = true
        } else {
            // A static asset has no lock-screen tint/zoom to settle. Reuse a
            // valid texture, or start its pending load on this home frame.
            retryAfterMs = 0L
            requestLoad(context)
        }
        LiveBlurEngine.onWallpaperFrameReady()
    }



    @Synchronized

    fun getBlurredWallpaper(context: Context? = null): Bitmap? {
        if(!PrefsHelper.backdropEnabled)return null

        val ctx = context ?: appContext ?: return blurredWallpaper

        if (appContext == null) appContext = ctx.applicationContext ?: ctx

        requestLoad(ctx)

        return blurredWallpaper?.takeUnless { it.isRecycled }

    }



    @Synchronized internal fun onPrefsReady() {
        prefsReady = true
        appContext?.let(::requestLoad)
    }

    private fun requestLoad(context: Context) {

        if (!prefsReady || !PrefsHelper.enabled || !PrefsHelper.backdropEnabled) return

        val radius = (if(PrefsHelper.colorOnly)0 else PrefsHelper.blurRadius).coerceIn(0, 50)

        if (isReloading || SystemClock.uptimeMillis() < retryAfterMs ||

            (cachedRevision == sourceRevision && cachedBlurRadius == radius &&

                blurredWallpaper?.isRecycled == false)) return

        // Keep the completed bitmap during app transitions. Static loads use
        // the asset, so launcher touch or scroll motion cannot taint their crop.
        val animated = isLiveWallpaper(context)
        val coldStatic = !animated && blurredWallpaper == null
        if (!coldStatic && !LauncherWallpaperCapture.canCapture) return
        if (!coldStatic && (LiveBlurEngine.freezeAnimationWallpaper || (!animated && (!LiveBlurEngine.isResumed ||
                LiveBlurEngine.isAppLaunchInProgress)))) {
            // Resume/window-ready requests the pending revision again. Do not
            // poll in the background while another application is in front.
            if (!LiveBlurEngine.isResumed || LiveBlurEngine.isAppLaunchInProgress) return
            if (!settlingReloadPosted) {
                settlingReloadPosted = true
                mainHandler.postDelayed(settlingReload, 120L)
                GlassLogger.animationLimited("WallpaperHelper", "settled-snapshot", 50) {
                    "Deferred static wallpaper snapshot until workspace settles (revision=$sourceRevision)"
                }
            }
            return
        }

        isReloading = true

        val revision = sourceRevision

        val liveSerial=livePublishSerial
        val captureTicket = LauncherWallpaperCapture.captureEpoch

        val ctx = context.applicationContext ?: context

        loader.execute {

            val candidate = loadAndBlurWallpaper(ctx, radius)

            var published = false

            synchronized(this) {

                isReloading = false

                val stable = coldStatic || (LauncherWallpaperCapture.acceptsCapture(captureTicket) &&
                    !LiveBlurEngine.freezeAnimationWallpaper &&
                    (animated || (LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress)))
                if (stable && PrefsHelper.enabled && PrefsHelper.backdropEnabled && revision == sourceRevision &&
                    radius == (if(PrefsHelper.colorOnly)0 else PrefsHelper.blurRadius).coerceIn(0, 50) && liveSerial==livePublishSerial) {

                    if (candidate != null) {

                        publishBackdrops(candidate, animated = true)

                        cachedBlurRadius = radius

                        cachedRevision = revision
                        initialFailures = 0
                        mainHandler.removeCallbacks(retryInitial)

                        published = true

                    } else {

                        val delay = if (blurredWallpaper == null && initialFailures < 4) 120L shl initialFailures++ else 2000L
                        retryAfterMs = SystemClock.uptimeMillis() + delay
                        if (delay < 2000L) {
                            mainHandler.removeCallbacks(retryInitial)
                            mainHandler.postAtTime(retryInitial,retryAfterMs + 1L)
                        }

                    }

                } else {

                    candidate?.recycle()
                    // loadRawWallpaper may have cached a snapshot while a new
                    // animation/sleep started. Never reuse that zoomed frame.
                    if (!stable) { systemSnapshot = null; snapshotTimeMs = 0L }

                    requestLoad(ctx)

                }

            }

            if (published) mainHandler.post {

                if (PrefsHelper.enabled && ((LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress) || com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) {

                    LiveBlurEngine.invalidate()

                }

            }

        }

    }



    fun getRawScaledWallpaper(context: Context): Bitmap? {

        val raw = loadRawWallpaper(context) ?: return null

        val targetH = 800

        val targetW = ((raw.width.toFloat() / raw.height.toFloat()) * targetH).roundToInt().coerceAtLeast(360)

        return Bitmap.createScaledBitmap(raw, targetW, targetH, true)

    }



    @Synchronized

    fun getShader(context: Context, screenWidth: Int, screenHeight: Int, screenX: Int, screenY: Int): BitmapShader? {

        val blurred = getBlurredWallpaper(context) ?: return null



        val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        val matrix = Matrix()

        val scaleX = screenWidth.toFloat() / blurred.width

        val scaleY = screenHeight.toFloat() / blurred.height

        matrix.postScale(scaleX, scaleY)

        matrix.postTranslate(-screenX.toFloat(), -screenY.toFloat())

        shader.setLocalMatrix(matrix)

        return shader

    }



    private fun loadAndBlurWallpaper(context: Context, radius: Int, frame: Bitmap? = null): Bitmap? {

        try {

            val rawBitmap = frame ?: loadRawWallpaper(context)
            if(rawBitmap!=null && PrefsHelper.iconPalette.autoLook>=0 && AutoIconPalette.useWallpaper(rawBitmap))
                mainHandler.post { PrefsHelper.loadPrefs(context) }

            if (rawBitmap == null) {

                if (logCount < 3) {

                    GlassLogger.i("WallpaperHelper", "Raw wallpaper could not be loaded")

                    logCount++

                }

                return null

            }



            // Never upscale the already downsampled live capture just to blur it.

            val targetH = minOf(600+PrefsHelper.blurQuality*12, rawBitmap.height)

            val targetW = ((rawBitmap.width.toFloat() / rawBitmap.height.toFloat()) * targetH).roundToInt().coerceAtLeast(1)

            val effectRadius = LiquidOptics.blurRadius(radius,targetH,PrefsHelper.liquidGlass)

            if(PrefsHelper.gpuEffects)try {

                val input=if(android.os.Build.VERSION.SDK_INT<33)BackdropVibrancy.applyCpu(rawBitmap,PrefsHelper.blurVibrancy)else rawBitmap
                val gpu=try {GpuWallpaperBlur.blur(input, targetW, targetH, effectRadius)}finally{if(input!==rawBitmap)input.recycle()}

                return (if(ShadowTone.gpuSupported)gpu else ShadowTone.applyCpu(gpu,PrefsHelper.blurLightening).also {

                    if(it !== gpu)gpu.recycle()

                }).let {withFolder(it,rawBitmap)}

            } catch (t: Throwable) {

                if (!gpuFailureLogged) {

                    GlassLogger.e("GpuBlur", "GPU blur failed; using compatibility CPU path", t)

                    gpuFailureLogged = true

                }

            }



            val scaled = Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true)

            val software = if (scaled.config == Bitmap.Config.HARDWARE) {

                scaled.copy(Bitmap.Config.ARGB_8888, false) ?: return null

            } else scaled

            val vibrant=BackdropVibrancy.applyCpu(software,PrefsHelper.blurVibrancy)
            val input=vibrant
            val actualRadius=effectRadius

            val blurred = try {

                BlurOpacity.blurCpu(input,actualRadius)

            } finally {

                if(input!==vibrant)input.recycle()
                if (vibrant !== software)vibrant.recycle()
                if (software !== rawBitmap) software.recycle()

                if (scaled !== rawBitmap && scaled !== software) scaled.recycle()

            }



            if (logCount < 5) {

                GlassLogger.i("WallpaperHelper", "Blurred wallpaper ready: ${blurred.width}x${blurred.height}, radius=$radius")

                logCount++

            }

            val toned=ShadowTone.applyCpu(blurred,PrefsHelper.blurLightening)
            val processed=toned
            if(toned!==blurred && processed!==toned)toned.recycle()
            if(processed!==blurred)blurred.recycle()
            return withFolder(processed,rawBitmap)

        } catch (t: Throwable) {

            GlassLogger.e("WallpaperHelper", "Failed to blur wallpaper", t)

            return null

        }

    }



    // Wallpaper access uses the hooked launcher's permissions; denied fallback calls are caught.
    @android.annotation.SuppressLint("MissingPermission")
    private fun loadRawWallpaper(context: Context): Bitmap? {

        // A wallpaper-only screenshot still contains the system wallpaper
        // engine's zoom/dim. Static materials must use the wallpaper asset;
        // even an idle screenshot may retain an interrupted app's zoom.
        val animated = isLiveWallpaper(context)
        if (animated) readSystemSnapshot(context)?.let {

            systemSnapshot = it

            snapshotTimeMs = SystemClock.uptimeMillis()

            GlassLogger.i("WallpaperHelper", "Loaded wallpaper-only system screenshot (${it.width}x${it.height})")

            return it

        }

        if (animated) synchronized(this) { launcherWallpaper?.get()?.takeUnless { it.isRecycled } }?.let {

            GlassLogger.i("WallpaperHelper", "Using processed wallpaper delivered to launcher (${it.width}x${it.height})")

            return it

        }

        if (animated && hasSystemWallpaperEffect(context)) {

            // If we already have a valid blurred wallpaper, retain it and retry the screenshot.

            // If blurredWallpaper is null, allow initial static fallback so icons don't lose blur completely!

            if (blurredWallpaper?.isRecycled == false) {

                return null

            }

        }

        // ColorOS effects are rendered by a separate wallpaper service. Its

        // processed bitmap is exposed through the same OEM API the launcher uses;

        // WallpaperManager.drawable can return an unrelated static fallback.

        try {

            val managerClass = Class.forName("android.app.OplusWallpaperManager")

            val manager = managerClass.getDeclaredConstructor().newInstance()

            val userClass = Class.forName("android.os.UserHandle")

            val userId = userClass.getDeclaredMethod("myUserId").invoke(null) as Int

            val metrics = context.resources.displayMetrics

            val sizes = arrayListOf(Point(metrics.widthPixels, metrics.heightPixels))

            val method = managerClass.getMethod("getWallpaperBitmap", Context::class.java,

                java.util.List::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

            val bitmap = method.invoke(manager, context, sizes, WallpaperManager.FLAG_SYSTEM, userId) as? Bitmap

            if (bitmap != null && !bitmap.isRecycled) {

                GlassLogger.i("WallpaperHelper", "Loaded processed system wallpaper from ColorOS (${bitmap.width}x${bitmap.height})")

                return bitmap

            }

        } catch (t: Throwable) {

            if (logCount < 3) GlassLogger.i("WallpaperHelper", "ColorOS wallpaper source unavailable: ${t.javaClass.simpleName}")

        }



        // Compatibility fallback for launchers without the ColorOS API.

        try {

            val wm = WallpaperManager.getInstance(context)

            val d = wm.drawable ?: wm.peekDrawable()

            if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {

                val bmp = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)

                val c = Canvas(bmp)

                d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)

                d.draw(c)

                GlassLogger.i("WallpaperHelper", "Loaded raw wallpaper from WallpaperManager (${bmp.width}x${bmp.height})")

                return bmp

            }

        } catch (t: Throwable) {

            GlassLogger.i("WallpaperHelper", "WallpaperManager.drawable fallback: ${t.message}")

        }



        // Source 2: Dynamic system wallpaper file / fallback cache

        try {

            val cacheFiles = listOf(

                File("/data/system/users/0/wallpaper"),

                File(context.cacheDir, "cosmod_wallpaper.png"),

                File(context.filesDir, "cosmod_wallpaper.png")

            )

            for (file in cacheFiles) {

                if (file.canRead() && file.length() > 1000) {

                    val bmp = BitmapFactory.decodeFile(file.absolutePath)

                    if (bmp != null) {

                        GlassLogger.i("WallpaperHelper", "Loaded raw wallpaper from ${file.absolutePath} (${bmp.width}x${bmp.height})")

                        return bmp

                    }

                }

            }

        } catch (_: Throwable) {}



        return null

    }

}
