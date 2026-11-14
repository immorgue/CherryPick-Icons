package com.cosmod.launcherglass.xposed

import android.os.Looper
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.LiveBlurEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

internal object WorkspaceBlurScroll {
    fun install(loader:ClassLoader,workspaceNames:List<String>){
        val workspaces=workspaceNames.mapNotNull { XposedHelpers.findClassIfExists(it,loader) }
        val oplus=XposedHelpers.findClassIfExists("com.android.launcher3.OplusWorkspace",loader)
        val lifecycle=(workspaces+listOfNotNull(oplus)).flatMap { type ->
            generateSequence(type as Class<*>?) { it.superclass }.takeWhile { it!=View::class.java }.toList()
        }.distinct()
        for(type in lifecycle)for(method in type.declaredMethods){
            if(method.parameterCount!=0 || method.name !in listOf("onPageBeginTransition","onPageEndTransition"))continue
            val begin=method.name=="onPageBeginTransition"
            ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as? View ?: return
                    if(workspaces.none { it.isInstance(view) })return
                    if(begin)LiveBlurEngine.onWorkspacePageBegin(view) else LiveBlurEngine.onWorkspacePageEnd(view)
                }
            })
        }

        for(type in workspaces)ModuleHooks.hookAllMethods(type,"computeScroll",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as View
                if(LiveBlurEngine.settlingWorkspace(view))param.setObjectExtra("workspaceScrollX",view.scrollX)
            }
            override fun afterHookedMethod(param:MethodHookParam){
                val before=param.getObjectExtra("workspaceScrollX") as? Int ?: return
                val view=param.thisObject as View
                val final=LiveBlurEngine.finalWorkspaceFrame(view.rootView)
                if(view.scrollX==before && !final)return
                // View.draw() can advance the OEM pager after our pre-draw
                // pass. Refresh only this subtree with the presented pose.
                val displayHz=view.display?.refreshRate ?: 120f
                IconGlassHook.invalidateActiveIcons(onlyChangedDrawerSamples=true,beforeDraw=view.rootView,
                    workspace=view,correctWorkspacePose=final || PrefsHelper.swipeFps>=displayHz-.5f)
                if(final)LiveBlurEngine.onWorkspaceFrameSampled(view.rootView)
                GlassLogger.animationLimited("WorkspaceBlur","workspacePostCompute",1500){
                    "Resampled workspace after computeScroll: $before -> ${view.scrollX}, final=$final"
                }
            }
        })

        val rtScroller=XposedHelpers.findClassIfExists("com.android.launcher3.util.RtLauncherOverScroller",loader) ?: return
        val rtEnabled=XposedHelpers.findFieldIfExists(rtScroller,"mRtScrollOptimizationEnabled") ?: return
        if(rtEnabled.type!=Boolean::class.javaPrimitiveType)return
        val startMethods=setOf("startScroll","startScrollSpring","fling","flingSpring","startBackSpring")
        val hook=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val scroller=param.thisObject
                if(Looper.myLooper()!=Looper.getMainLooper() || !rtEnabled.getBoolean(scroller) ||
                    !LiveBlurEngine.isTrackedWorkspaceScroller(scroller))return
                // The OEM already runs the same scroller on the UI thread.
                // Suppress only the RenderThread mirror for this start call,
                // so the blur can use the position of the presented frame.
                rtEnabled.setBoolean(scroller,false)
                param.setObjectExtra("uiWorkspaceScroll",true)
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("uiWorkspaceScroll")!=true)return
                rtEnabled.setBoolean(param.thisObject,true)
                GlassLogger.animationLimited("WorkspaceBlur","uiWorkspaceSettle",30){
                    "Workspace settle follows UI frames (${param.method.name})"
                }
            }
        }
        for(method in rtScroller.declaredMethods)if(method.name in startMethods)ModuleHooks.hookMethod(method,hook)
    }
}
