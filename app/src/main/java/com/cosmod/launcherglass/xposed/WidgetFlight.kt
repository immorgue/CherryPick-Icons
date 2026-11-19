package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.cosmod.launcherglass.bridge.HookCallback
import com.cosmod.launcherglass.bridge.HookReflection
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.WallpaperHelper
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Use the native widget flight and dissolve only its plate when the source lands. */
internal object WidgetFlight {
    private class Flight(view: View) {
        val view = WeakReference(view)
        var startedFade = -1L
        var boundToHomeAnimation = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val flights = WeakHashMap<View, Flight>()
    private val owners = WeakHashMap<Any, Flight>()
    private val closeResult = ThreadLocal<Any?>()
    private var launchedWidget: WeakReference<View>? = null
    private var widgetTasks: Set<Int> = emptySet()
    private var awaitingHome: Flight? = null
    private var awaitingSwipeStart = false

    fun remove(widget: View) { flights.remove(widget) }
    fun clear() {
        flights.clear(); owners.clear(); launchedWidget = null; awaitingHome = null
        awaitingSwipeStart = false; widgetTasks = emptySet()
    }

    fun onLauncherReturning() {
        val widget = launchedWidget?.get() ?: return
        if (awaitingHome == null && PhotoWidgetMaterial.flightHost(widget) != null) {
            hold(widget).also { awaitingHome = it }
        }
    }

    fun onLauncherFocused() {
        val flight = awaitingHome ?: return
        if (flight.boundToHomeAnimation) return
        // A launcher without a native callback can finish once its window
        // regains focus. Give a pending swipe time to bind its actual end.
        main.postDelayed({
            if (awaitingHome === flight && !flight.boundToHomeAnimation) finishAwaitingHome()
        }, 360L)
    }

    fun opacity(widget: View, snapshot: Boolean, style: GlassStyle): Float {
        if (snapshot) return 0f
        val flight = flights[widget] ?: return 1f
        if (flight.startedFade < 0L) return 0f
        // Start the dissolve on the first frame the real widget can draw.
        val now = SystemClock.uptimeMillis()
        if (flight.startedFade == 0L) {
            if (!IconMaterialRenderer.clearGlass(style) && WallpaperHelper.getBlurredWallpaper(widget.context) == null) return 0f
            flight.startedFade = now
        }
        val t = ((now - flight.startedFade) / 230f).coerceIn(0f, 1f)
        if (t == 1f) {
            flights.remove(widget)
            GlassLogger.animation("WidgetFlight") { "Glass restored in ${now - flight.startedFade} ms" }
        } else widget.postInvalidateOnAnimation()
        return t * t * (3f - 2f * t)
    }

    private fun begin(source: View?, owner: Any, route: String, opening: Boolean = false) {
        if (source == null && !opening) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { begin(source, owner, route, opening) }; return }
        val widget = PhotoWidgetMaterial.flightHost(source)
        if (opening) {
            launchedWidget = widget?.let(::WeakReference)
            widgetTasks = emptySet()
            finishAwaitingHome()
        }
        if (widget == null) return
        val old = owners[owner]
        if (old?.view?.get() === widget && flights[widget] === old) return
        val pending = awaitingHome?.takeIf { !opening && it.view.get() === widget }
        val flight = pending ?: hold(widget)
        if (pending != null) flight.boundToHomeAnimation = true
        owners[owner] = flight
        GlassLogger.animation("WidgetFlight") { "$route ${widget.javaClass.simpleName}" }
    }

    private fun hold(widget: View): Flight = Flight(widget).also { flight ->
        flights[widget] = flight
        widget.invalidate()
        main.postDelayed({
            if (awaitingHome === flight) { awaitingHome = null; awaitingSwipeStart = false }
            finish(flight)
        }, 2400L)
    }

    private fun finishAwaitingHome() {
        val flight = awaitingHome
        awaitingHome = null
        awaitingSwipeStart = false
        flight?.let(::finish)
    }

    private fun finish(flight: Flight) {
        val widget = flight.view.get() ?: return
        if (flights[widget] !== flight || flight.startedFade >= 0L) return
        if (ValueAnimator.areAnimatorsEnabled()) flight.startedFade = 0L else flights.remove(widget)
        widget.invalidate()
        GlassLogger.animation("WidgetFlight") { "Landed ${widget.javaClass.simpleName}" }
    }

    private fun finish(owner: Any?) {
        if (owner == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { finish(owner) }; return }
        val flight = owners.remove(owner) ?: return
        if (awaitingHome === flight) finishAwaitingHome() else finish(flight)
    }

    private fun installSwipeHome(loader: ClassLoader) {
        // The native animation engine's gesture path does not create an
        // AnimationResult. Bind the actual swipe request to its animation.
        val interceptor = HookReflection.findClassIfExists(
            "com.android.launcher3.anim.engine.interceptor.AnimTraceInterceptor", loader) ?: return
        ModuleHooks.hookAllMethods(interceptor, "onPreStart", object : HookCallback() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val request = param.args.firstOrNull() ?: return
                val type = runCatching { HookReflection.callMethod(request, "getType") as? Enum<*> }.getOrNull()?.name
                if (type != "LAUNCH_FROM_HOME" && type != "SWIPE_TO_HOME") return
                val tasks = runCatching { HookReflection.callMethod(request, "getTaskIds") as? Iterable<*> }.getOrNull()
                    ?.mapNotNull { (it as? Number)?.toInt() }?.toSet().orEmpty()
                if (type == "LAUNCH_FROM_HOME") {
                    // The launch runner already identified the clicked source.
                    if (launchedWidget?.get() != null) widgetTasks = tasks
                    return
                }
                val widget = launchedWidget?.get() ?: return
                if (tasks.isNotEmpty() && widgetTasks.isNotEmpty() && tasks.none(widgetTasks::contains)) {
                    finishAwaitingHome()
                    return
                }
                if (PhotoWidgetMaterial.flightHost(widget) == null) return
                val flight = awaitingHome?.takeIf { it.view.get() === widget } ?: hold(widget).also { awaitingHome = it }
                flight.boundToHomeAnimation = true
                awaitingSwipeStart = true
                GlassLogger.animation("WidgetFlight") { "Swipe home ${widget.javaClass.simpleName}" }
            }
        })
        ModuleHooks.hookAllMethods(interceptor, "onPostStart", object : HookCallback() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!awaitingSwipeStart) return
                val animation = param.args.firstOrNull() ?: return
                val type = runCatching { HookReflection.callMethod(animation, "getAnimationType") as? Enum<*> }.getOrNull()?.name
                if (type != "SWIPE_TO_HOME") return
                val flight = awaitingHome ?: return
                awaitingSwipeStart = false
                owners[animation] = flight
            }
        })
        ModuleHooks.hookAllMethods(interceptor, "onPostEnd", object : HookCallback() {
            override fun afterHookedMethod(param: MethodHookParam) = finish(param.args.firstOrNull())
        })
    }

    fun install(loader: ClassLoader) {
        installSwipeHome(loader)
        val result = HookReflection.findClassIfExists("com.android.launcher3.LauncherAnimationRunner\$AnimationResult", loader)
        val open = HookReflection.findClassIfExists("com.android.launcher3.anim.engine.factory.AppLaunchAnimRunner", loader)
        val close = HookReflection.findClassIfExists("com.android.launcher3.anim.engine.factory.AppCloseAnimRunner", loader)
        val manager = HookReflection.findClassIfExists("com.android.launcher3.QuickstepTransitionManager", loader)
        if (result != null) {
            ModuleHooks.hookAllMethods(result, "finish", object : HookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) = finish(param.thisObject)
            })
            if (open != null) ModuleHooks.hookAllMethods(open, "onCreateAnimation", object : HookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = param.args.firstOrNull(result::isInstance) ?: return
                    val source = runCatching { HookReflection.callMethod(param.thisObject, "getAnimatingView") as? View }.getOrNull()
                    begin(source, owner, "Open", opening = true)
                }
            })
            if (close != null && manager != null) {
                ModuleHooks.hookAllMethods(close, "createAnimationInternal", object : HookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.setObjectExtra("widgetPriorResult", closeResult.get())
                        closeResult.set(param.args.firstOrNull(result::isInstance))
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) finish(closeResult.get())
                        closeResult.set(param.getObjectExtra("widgetPriorResult"))
                    }
                })
                ModuleHooks.hookAllMethods(manager, "findLauncherView", object : HookCallback() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val owner = closeResult.get() ?: return
                        begin(param.result as? View, owner, "Close")
                    }
                })
            }
        }

        // Older launchers and platform RemoteViews use a floating widget with a native ghost.
        val floating = HookReflection.findClassIfExists("com.android.quickstep.views.FloatingWidgetView", loader) ?: return
        val factory = floating.declaredMethods.filter { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) && method.returnType == floating &&
                method.parameterTypes.any(android.appwidget.AppWidgetHostView::class.java::isAssignableFrom)
        }
        factory.forEach { method -> ModuleHooks.hookMethod(method, object : HookCallback() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = param.result ?: return
                begin(param.args.firstOrNull { it is android.appwidget.AppWidgetHostView } as? View, owner, "Floating")
            }
        }) }
        ModuleHooks.hookAllMethods(floating, "onDetachedFromWindow", object : HookCallback() {
            override fun afterHookedMethod(param: MethodHookParam) = finish(param.thisObject)
        })
    }
}
