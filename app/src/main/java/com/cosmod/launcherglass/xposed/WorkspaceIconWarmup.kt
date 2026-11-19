package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.LiveBlurEngine
import com.cosmod.launcherglass.bridge.HookReflection
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Decode one native icon at a time, with the current and adjacent pages first. */
internal object WorkspaceIconWarmup {
    private class Work(view: View, var urgent: Boolean) {
        val owners = arrayListOf(WeakReference(view))

        fun add(view: View, visible: Boolean) {
            if (owners.none { it.get() === view }) owners.add(WeakReference(view))
            urgent = urgent || visible
        }
    }
    private class Capture(val read: () -> Bitmap?, val ready: (Bitmap?) -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy {
        Handler(HandlerThread("CherryPickWorkspaceIcons",Process.THREAD_PRIORITY_BACKGROUND).apply {start()}.looper)
    }
    private val queued = LinkedHashMap<Bitmap,Work>()
    private val failed = WeakHashMap<Bitmap,Int>()
    private val warmViews = LinkedHashMap<View,ViewGroup>()
    private val captures = ArrayDeque<Capture>()
    private var active: Pair<Bitmap,Work>? = null
    private var capturing = false
    private var workspace: WeakReference<ViewGroup>? = null
    private val pump = Runnable { prepareNext() }
    private val scan = Runnable {
        val pager = workspace?.get() ?: return@Runnable
        if (!pager.isAttachedToWindow || !PrefsHelper.enabled || !FeaturePolicy.icons) return@Runnable
        // In RTL and on desktop variants the visible child's index is not
        // necessarily getCurrentPage(). Use the native layout positions.
        val center = pager.scrollX + pager.width / 2
        (0 until pager.childCount).map(pager::getChildAt)
            .sortedBy { abs((it.left + it.right) / 2 - center) }
            .forEach(::visit)
        schedule()
    }

    fun observe(pager: ViewGroup) {
        if (workspace?.get() !== pager) {
            warmViews.clear()
            workspace = WeakReference(pager)
            pager.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
                if (workspace?.get() === pager) scanSoon()
            }
        }
        scanSoon()
    }

    private fun scanSoon() {
        main.removeCallbacks(scan)
        main.postDelayed(scan,32)
        schedule()
    }

    private fun visit(view: View) {
        if (view.javaClass.name.contains("BubbleTextView")) {
            val drawable = NativeIconAccess.call(view,"getIcon") as? Drawable ?: return
            val bitmap = (NativeIconAccess.field(drawable,"mBitmap") as? Bitmap) ?: (drawable as? BitmapDrawable)?.bitmap ?: return
            if (SpecialIconSupport.packageOf(drawable) == null && NativeIconAccess.field(drawable,"mMonoIcon") == null)
                offer(bitmap,view,false)
        } else if (view is ViewGroup) {
            // The workspace also contains app-widget hierarchies. Descend
            // into cells/folders, never into a RemoteViews widget host.
            if (view is android.appwidget.AppWidgetHostView) return
            for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
    }

    fun deferColdIcon(bitmap: Bitmap, host: View?): Boolean {
        val type = host?.javaClass?.simpleName ?: return false
        if (host == null || Looper.myLooper() != Looper.getMainLooper() || !LiveBlurEngine.isResumed ||
            LiveBlurEngine.isAppLaunchInProgress || !(type.contains("BubbleTextView") || type.contains("FolderIcon") || type.contains("BigFolderItemIcon")) ||
            IconBitmapProcessor.isPrepared(bitmap) || !isDesktopIcon(host)) return false
        return offer(bitmap,host,true).also { if (it) schedule() }
    }

    fun waiting(bitmap: Bitmap?): Boolean = Looper.myLooper() == Looper.getMainLooper() &&
        bitmap != null && (active?.first === bitmap || queued.containsKey(bitmap))

    fun canCaptureOffThread(host: View?): Boolean = host != null && Looper.myLooper() == Looper.getMainLooper() &&
        LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress && isDesktopIcon(host)

    fun capture(read: () -> Bitmap?, ready: (Bitmap?) -> Unit): Boolean {
        if (captures.size >= 32) return false
        captures.addLast(Capture(read,ready))
        schedule()
        return true
    }

    private fun isDesktopIcon(host: View): Boolean {
        var parent = host.parent as? View
        repeat(13) {
            val current = parent ?: return false
            val name = current.javaClass.simpleName
            if (name.contains("Workspace") || name.contains("Hotseat")) return true
            if (name.contains("FolderPagedView") || name.contains("DragView")) return false
            parent = current.parent as? View
        }
        return false
    }

    private fun offer(bitmap: Bitmap, owner: View, urgent: Boolean): Boolean {
        if (bitmap.isRecycled || failed[bitmap] == bitmap.generationId || IconBitmapProcessor.isPrepared(bitmap)) return false
        active?.takeIf { it.first === bitmap }?.second?.let { it.add(owner,urgent); return true }
        queued[bitmap]?.let { it.add(owner,urgent); return true }
        if (queued.size >= 120) {
            if (!urgent) return false
            val last = queued.entries.lastOrNull { !it.value.urgent } ?: return false
            queued.remove(last.key)
        }
        queued[bitmap] = Work(owner,urgent)
        return true
    }

    private fun schedule(delay: Long = 10) {
        if (active == null && !capturing && (queued.isNotEmpty() || warmViews.isNotEmpty() || captures.isNotEmpty()) &&
            !main.hasCallbacks(pump)) main.postDelayed(pump,delay)
    }

    fun deferPage(pager: ViewGroup, page: ViewGroup) {
        if (workspace?.get() !== pager) observe(pager)
        for (index in 0 until page.childCount) {
            val container = page.getChildAt(index) as? ViewGroup ?: continue
            if (!container.javaClass.simpleName.contains("ShortcutAndWidgetContainer")) continue
            for (item in 0 until container.childCount) {
                if (warmViews.size >= 80) break
                val child = container.getChildAt(item)
                if (child !is android.appwidget.AppWidgetHostView) warmViews[child] = page
            }
        }
        schedule()
    }

    fun repaintPrepared(host: View) {
        host.invalidate()
        val pager = workspace?.get() ?: return
        var child = host
        var cell = host
        var parent = child.parent as? View
        while (parent != null && parent !== pager) {
            if (parent.javaClass.simpleName.contains("ShortcutAndWidgetContainer")) cell = child
            child = parent
            parent = child.parent as? View
        }
        if (parent === pager && (child.right <= pager.scrollX || child.left >= pager.scrollX + pager.width) &&
            warmViews.size < 80 && child is ViewGroup) {
            // The first recording can use the native icon while the texture
            // is in flight. Record its finished material before the first swipe.
            warmViews[cell] = child
            schedule()
        }
    }

    private fun warm(view: View) {
        warmViews.remove(view)
        if (view.isAttachedToWindow && view.isHardwareAccelerated && view.width > 0 && view.height > 0) {
            runCatching { HookReflection.callMethod(view,"updateDisplayListIfDirty") }
        }
        schedule(20)
    }

    private fun prepareNext() {
        if (active != null || capturing || !PrefsHelper.enabled || !FeaturePolicy.icons || !LiveBlurEngine.isResumed) return
        if (LiveBlurEngine.activePointerCount > 0 || LiveBlurEngine.isWorkspaceScrolling || LiveBlurEngine.isAppLaunchInProgress) {
            schedule(45)
            return
        }
        val urgent = queued.entries.firstOrNull { it.value.urgent }
        if (urgent == null && captures.isNotEmpty()) {
            val task = captures.removeFirst()
            capturing = true
            worker.post {
                val bitmap = runCatching(task.read).getOrNull()
                main.post {
                    capturing = false
                    task.ready(bitmap)
                    schedule()
                }
            }
            return
        }
        if (urgent == null) {
            val pager = workspace?.get()
            val center = pager?.let { it.scrollX + it.width / 2 } ?: 0
            val wideIcon = warmViews.entries.filter {
                pager != null && it.key.width > pager.width / 3 && it.key.javaClass.simpleName.contains("BubbleTextView")
            }.minByOrNull { abs((it.value.left + it.value.right) / 2 - center) }
            if (wideIcon != null) {
                // The wide clock/calendar icons compile a separate artwork
                // shader. Prime them before the larger folder preview queue.
                warm(wideIcon.key)
                return
            }
        }
        val next = urgent ?: queued.entries.firstOrNull()
        if (next == null) {
            val center = workspace?.get()?.let { it.scrollX + it.width / 2 } ?: 0
            val view = warmViews.entries.minByOrNull { abs((it.value.left + it.value.right) / 2 - center) }?.key ?: return
            warm(view)
            return
        }
        val bitmap = next.key
        val request = next.value
        queued.remove(bitmap)
        if (request.owners.none { it.get()?.isAttachedToWindow == true }) {
            schedule()
            return
        }
        active = bitmap to request
        val generation = bitmap.generationId
        worker.post {
            val loaded = runCatching { IconBitmapProcessor.prepare(bitmap) }.isSuccess
            main.post {
                if (!loaded) failed[bitmap] = generation
                active = null
                if (request.urgent) request.owners.forEach { it.get()?.let(::repaintPrepared) }
                schedule()
            }
        }
    }
}
