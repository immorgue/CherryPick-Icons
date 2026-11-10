package com.cosmod.launcherglass.xposed

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.DesktopSizing
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Native layout bounds move; OEM animation translations and touch geometry remain native. */
internal object DesktopOffsets {
    private data class Applied(val view:WeakReference<View>,val delta:Int)
    private val roots=WeakHashMap<View,List<Applied>>()
    @Volatile private var offsets=DesktopSizing.positions.associateWith {0}
    @Volatile private var enabled=true
    fun load(bundle:Bundle) {
        val next=DesktopSizing.positions.associateWith {bundle.getInt(it,0).coerceIn(-120,120)}
        val active=bundle.getBoolean(SettingsProvider.KEY_ENABLED,true)
        val changed=offsets!=next || enabled!=active
        offsets=next;enabled=active
        if(changed){
            val views=synchronized(roots){roots.keys.toList()}
            views.forEach {view -> view.post {view.requestLayout()}}
        }
    }
    fun install(loader:ClassLoader) {
        var owner:Class<*>?=XposedHelpers.findClassIfExists("com.android.launcher3.OplusDragLayer",loader)
            ?: XposedHelpers.findClassIfExists("com.android.launcher3.dragndrop.DragLayer",loader)
        val signature=arrayOf(Boolean::class.javaPrimitiveType,Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        while(owner!=null){
            val method=owner.declaredMethods.firstOrNull {it.name=="onLayout" && it.parameterTypes.contentEquals(signature)}
            if(method!=null){
                XposedBridge.hookMethod(method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(param:MethodHookParam){
                        val root=param.thisObject as? View ?: return
                        // Undo before OEM layout, even when it retains some child bounds.
                        val previous=synchronized(roots){roots.put(root,emptyList())}.orEmpty()
                        previous.forEach {entry -> entry.view.get()?.offsetTopAndBottom(-entry.delta)}
                    }
                    override fun afterHookedMethod(param:MethodHookParam){
                        if(param.hasThrowable())return
                        val root=param.thisObject as? ViewGroup ?: return
                        if(!enabled || root.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)return
                        val requested=offsets
                        if(requested.values.all {it==0})return
                        val density=root.resources.displayMetrics.density
                        val targets=listOf("workspace" to DesktopSizing.GRID_Y,
                            "hotseat" to DesktopSizing.DOCK_Y,"page_indicator" to DesktopSizing.INDICATOR_Y).mapNotNull {(id,key) ->
                            @Suppress("DiscouragedApi")
                            val resource=root.resources.getIdentifier(id,"id",root.context.packageName)
                            if(resource==0)null else root.findViewById<View>(resource)?.let {it to (requested.getValue(key)*density).roundToInt()}
                        }
                        val applied=targets.map {(view,desired) ->
                            // An indicator nested under workspace must keep its own offset.
                            var inherited=0;var parent=view.parent as? View
                            while(parent!=null && parent!==root){
                                val ancestor=parent
                                val target=targets.firstOrNull {it.first===ancestor}
                                if(target!=null){inherited=target.second;break}
                                parent=ancestor.parent as? View
                            }
                            Applied(WeakReference(view),desired-inherited)
                        }
                        applied.forEach {it.view.get()?.offsetTopAndBottom(it.delta)}
                        synchronized(roots){roots[root]=applied}
                    }
                })
                return
            }
            owner=owner.superclass
        }
    }
}
