package com.cosmod.launcherglass.xposed

import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Retarget the native capsule/card animation, including its native touch region. */
internal object MusicIslandExpansion {
    private const val PLUGIN="com.oplus.systemui.plugins.seedling."
    private val main=Handler(Looper.getMainLooper())
    private val loaders=WeakHashMap<ClassLoader,Boolean>()
    private val containers=WeakHashMap<View,Method>()
    private val retargeted=WeakHashMap<View,Boolean>()
    private var settingsContext:Context?=null
    private var enabled=false
    private var musicOnly=false
    private var anchor:Rect?=null
    private var anchorDisplay=0
    private val layoutScope=ThreadLocal<View?>()

    fun install(loader:ClassLoader){
        val manager=XposedHelpers.findClassIfExists("com.oplus.systemui.statusbar.seeding.SeedlingPluginManager",loader) ?: return
        XposedBridge.hookAllMethods(manager,"onPluginConnectedImpl",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val plugin=p.args.firstOrNull() ?: return
                val context=p.args.filterIsInstance<Context>().firstOrNull() ?: return
                observe(context.applicationContext)
                try {installPlugin(plugin.javaClass.classLoader ?: return)}
                catch(t:Throwable){GlassLogger.e("MusicIsland","Native capsule integration unavailable",t)}
            }
        })
    }

    private fun observe(context:Context){
        if(settingsContext!=null)return
        settingsContext=context
        val worker=Executors.newSingleThreadExecutor {r->Thread(r,"CherryPick-island-settings").apply {isDaemon=true}}
        fun load(){worker.execute {
            val value=try {context.contentResolver.call(SettingsProvider.CONTENT_URI,SettingsProvider.METHOD_GET_ALL,null,null)?.let {
                it.getBoolean(SettingsProvider.KEY_ENABLED,false) && it.getBoolean(SettingsProvider.KEY_MUSIC_ISLAND_EXPANSION,false)
            } ?: false}catch(_:Throwable){false}
            main.post {
                if(enabled!=value){
                    enabled=value
                    containers.entries.toList().forEach {(view,update)->
                        if(view.isAttachedToWindow)try {update.invoke(view);view.requestLayout()}catch(_:Throwable){}
                    }
                }
            }
        }}
        context.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI,true,object:ContentObserver(main){
            override fun onChange(selfChange:Boolean){load()}
        })
        load()
    }

    private fun installPlugin(loader:ClassLoader){
        if(loaders.containsKey(loader))return
        val card=XposedHelpers.findClassIfExists(PLUGIN+"card.ui.view.CardView",loader) ?: return
        val container=XposedHelpers.findClassIfExists(PLUGIN+"card.ui.view.CardContainer",loader) ?: return
        val capsuleRoot=XposedHelpers.findClassIfExists(PLUGIN+"capsule.ui.view.CapsuleContainerRoot",loader) ?: return
        val utils=XposedHelpers.findClassIfExists(PLUGIN+"util.c",loader) ?: return
        val top=utils.declaredMethods.singleOrNull {
            it.name=="b" && Modifier.isStatic(it.modifiers) && it.returnType==Int::class.javaPrimitiveType &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        }?.apply {isAccessible=true} ?: return
        val update=container.declaredMethods.singleOrNull {
            it.name=="k" && it.parameterCount==0 && it.returnType==Void.TYPE
        }?.apply {isAccessible=true} ?: return
        // Refuse a different OEM API rather than guessing an obfuscated binding.
        if(container.methods.none {it.name=="getCardUiState"} || card.methods.none {it.name=="getContentView"})return
        loaders[loader]=true
        XposedBridge.hookAllMethods(capsuleRoot,"dispatchTouchEvent",object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(!enabled)return
                val event=p.args.firstOrNull() as? MotionEvent ?: return
                if(event.actionMasked!=MotionEvent.ACTION_DOWN)return
                val root=p.thisObject as? ViewGroup ?: return
                if(!portraitUnlocked(root.context))return
                findCapsule(root,event.rawX.toInt(),event.rawY.toInt())?.let {view->
                    val rect=Rect()
                    if(view.getGlobalVisibleRect(rect) && !rect.isEmpty){anchor=rect;anchorDisplay=view.display?.displayId ?: 0}
                }
            }
        })
        XposedBridge.hookMethod(top,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(!enabled || p.hasThrowable())return
                val context=p.args.firstOrNull() as? Context ?: return
                val scope=layoutScope.get()
                if(scope!=null)musicOnly=isOnlyMusic(scope)
                val rect=anchor ?: return
                val display=try {context.display?.displayId ?: 0}catch(_:Throwable){0}
                if(musicOnly && portraitUnlocked(context) && display==anchorDisplay){
                    p.result=rect.top
                    if(scope!=null)retargeted[scope]=true
                }
            }
        })
        XposedBridge.hookMethod(update,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                containers[view]=update
                p.setObjectExtra("islandLayoutScope",layoutScope.get());layoutScope.set(view)
            }
            override fun afterHookedMethod(p:MethodHookParam){
                layoutScope.set(p.getObjectExtra("islandLayoutScope") as? View)
                if(p.hasThrowable())return
                val view=p.thisObject as? View ?: return
                // OEM drag/spring recovery also caches its resting Y.
                // Keep that cache consistent with the retargeted native layout.
                if(retargeted[view]==true){
                    try {XposedHelpers.setIntField(view,"r",view.translationY.toInt())}catch(_:Throwable){}
                    if(!enabled || !musicOnly)retargeted.remove(view)
                }
            }
        })
        val bind=card.declaredMethods.singleOrNull {method->
            method.parameterCount==1 && method.returnType==Void.TYPE && method.parameterTypes[0].declaredFields.any {
                View::class.java.isAssignableFrom(it.type)
            } && !View::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        if(bind!=null)XposedBridge.hookMethod(bind,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                val content=try {XposedHelpers.callMethod(view,"getContentView") as? View}catch(_:Throwable){null}
                musicOnly=content?.let(::hasMediaContent)==true
            }
        })
    }

    private fun portraitUnlocked(context:Context)=context.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT &&
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked!=true

    private fun isOnlyMusic(view:View):Boolean {
        val state=try {XposedHelpers.callMethod(view,"getCardUiState")}catch(_:Throwable){null} ?: return false
        val items=try {XposedHelpers.getObjectField(state,"a") as? List<*>}catch(_:Throwable){null} ?: return false
        if(items.size!=1)return false
        val item=items.firstOrNull() ?: return false
        val content=item.javaClass.declaredFields.firstNotNullOfOrNull {
            if(View::class.java.isAssignableFrom(it.type))try {it.isAccessible=true;it.get(item) as? View}catch(_:Throwable){null}else null
        } ?: return false
        return hasMediaContent(content)
    }

    private fun hasMediaContent(view:View,depth:Int=0):Boolean {
        if(view.javaClass.name.contains(".template.section.media.") || view.javaClass.name.contains(".component.media."))return true
        if(depth>=8 || view !is ViewGroup)return false
        for(i in 0 until minOf(view.childCount,32))if(hasMediaContent(view.getChildAt(i),depth+1))return true
        return false
    }

    private fun findCapsule(view:View,x:Int,y:Int,depth:Int=0):View? {
        if(depth>8 || !view.isShown)return null
        if(view.javaClass.simpleName=="CapsuleView" && view.width>0 && view.height>0){
            val rect=Rect()
            if(view.getGlobalVisibleRect(rect) && rect.contains(x,y))return view
        }
        if(view is ViewGroup)for(i in 0 until view.childCount)findCapsule(view.getChildAt(i),x,y,depth+1)?.let {return it}
        return null
    }
}
