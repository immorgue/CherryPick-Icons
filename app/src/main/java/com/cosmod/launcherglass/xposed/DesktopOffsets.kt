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
    private class DockLayout(val view:View,val desired:Int,val previous:Int,var laidOut:Boolean=false)
    private val dockLayouts=ThreadLocal<DockLayout?>()
    private val dockOffsets=WeakHashMap<View,Int>()
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
    @Suppress("DiscouragedApi")
    fun find(root:View,name:String):View? {
        val id=root.resources.getIdentifier(name,"id",root.context.packageName)
        return if(id==0)null else root.findViewById<View>(id)
    }
    fun install(loader:ClassLoader) {
        // OEM spring drops calculate absolute Y from DeviceProfile rather
        // than the laid-out hotseat. Apply the same desktop offset there.
        XposedHelpers.findClassIfExists("com.android.launcher3.OplusHotseat",loader)?.let {hotseat ->
            ModuleHooks.hookAllMethods(hotseat,"getFinalXY",object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as? View ?: return
                    if(!enabled || param.hasThrowable() || view.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)return
                    val delta=(offsets.getValue(DesktopSizing.DOCK_Y)*view.resources.displayMetrics.density).roundToInt()
                    when(val xy=param.args.getOrNull(1)){
                        is FloatArray->if(xy.size>=2)xy[1]=xy[1]+delta.toFloat()
                        is IntArray->if(xy.size>=2)xy[1]+=delta
                    }
                }
            })
            ModuleHooks.hookAllMethods(hotseat,"getFinalDestRect",object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    if(!enabled || param.hasThrowable())return
                    val rect=param.args.firstOrNull() as? android.graphics.Rect ?: return
                    val child=param.args.getOrNull(1) as? View ?: return
                    if(child.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)return
                    val bounds=android.graphics.Rect()
                    try {XposedHelpers.callMethod(child,"getWorkspaceVisualDragBounds",bounds)}catch(_:Throwable){return}
                    if(!bounds.isEmpty)rect.set(bounds)
                }
            })
        }
        var owner:Class<*>?=XposedHelpers.findClassIfExists("com.android.launcher3.OplusDragLayer",loader)
            ?: XposedHelpers.findClassIfExists("com.android.launcher3.dragndrop.DragLayer",loader)
        val signature=arrayOf(Boolean::class.javaPrimitiveType,Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
        while(owner!=null){
            val method=owner.declaredMethods.firstOrNull {it.name=="onLayout" && it.parameterTypes.contentEquals(signature)}
            if(method!=null){
                val layout=View::class.java.getDeclaredMethod("layout",Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
                XposedBridge.hookMethod(layout,object:XC_MethodHook(){
                    override fun beforeHookedMethod(param:MethodHookParam){
                        val dock=dockLayouts.get() ?: return
                        if(param.thisObject!==dock.view)return
                        param.args[1]=(param.args[1] as Int)+dock.desired
                        param.args[3]=(param.args[3] as Int)+dock.desired
                        dock.laidOut=true
                    }
                })
                XposedBridge.hookMethod(method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(param:MethodHookParam){
                        val root=param.thisObject as? View ?: return
                        param.setObjectExtra("previousDockLayout",dockLayouts.get())
                        val dock=find(root,"hotseat")
                        val desired=if(enabled && root.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT)
                            (offsets.getValue(DesktopSizing.DOCK_Y)*root.resources.displayMetrics.density).roundToInt() else 0
                        dockLayouts.set(dock?.let {DockLayout(it,desired,dockOffsets[it] ?: 0)})
                        // Undo before OEM layout, even when it retains some child bounds.
                        val previous=synchronized(roots){roots.put(root,emptyList())}.orEmpty()
                        previous.forEach {entry -> entry.view.get()?.offsetTopAndBottom(-entry.delta)}
                    }
                    override fun afterHookedMethod(param:MethodHookParam){
                        val dock=dockLayouts.get()
                        dockLayouts.set(param.getObjectExtra("previousDockLayout") as? DockLayout)
                        if(param.hasThrowable())return
                        if(dock!=null){
                            // Some OEM passes skip unchanged children.
                            if(!dock.laidOut) dock.view.offsetTopAndBottom(dock.desired-dock.previous)
                            dockOffsets[dock.view]=dock.desired
                        }
                        val root=param.thisObject as? ViewGroup ?: return
                        if(!enabled || root.resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)return
                        val requested=offsets
                        if(requested.values.all {it==0})return
                        val density=root.resources.displayMetrics.density
                        val targets=listOf("workspace" to DesktopSizing.GRID_Y,
                            "page_indicator" to DesktopSizing.INDICATOR_Y).mapNotNull {(id,key) ->
                            find(root,id)?.let {it to (requested.getValue(key)*density).roundToInt()}
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
