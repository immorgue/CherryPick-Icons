package com.cosmod.launcherglass.xposed

import android.app.Activity
import android.content.*
import android.database.ContentObserver
import android.os.*
import android.view.View
import com.cosmod.launcherglass.ui.SettingsProvider
import de.robv.android.xposed.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Suppress only handle paint. Never change navigation modes, insets or touch regions. */
internal object HomeGestureBar {
    private const val ACTION="com.cosmod.launcherglass.HOME_GESTURE_BAR"
    private val main=Handler(Looper.getMainLooper())
    private var activity:WeakReference<Activity>?=null
    private var sent:Boolean?=null
    private var desktop=false
    private var hidden=false
    private var registered=false
    private var transitioning=false
    private var appReady=false
    private var transitionId=0
    private var opacity=1f
    private var opacityTarget=1f
    private var opacityAnimator:android.animation.ValueAnimator?=null
    private val transitionAlpha by lazy {
        try {View::class.java.getDeclaredMethod("setTransitionAlpha",Float::class.javaPrimitiveType).apply {isAccessible=true}}
        catch(_:Throwable){null}
    }
    private val handles=WeakHashMap<View,Boolean>()
    private val homes=setOf("com.android.launcher","com.oppo.launcher","com.oplus.launcher","com.coloros.launcher","com.android.launcher3")

    fun installLauncher(loader:ClassLoader){
        val type=XposedHelpers.findClassIfExists("com.android.launcher.Launcher",loader) ?: return
        for(name in listOf("onResume","onPause","onWindowFocusChanged"))XposedBridge.hookAllMethods(type,name,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val owner=p.thisObject as? Activity ?: return
                activity=WeakReference(owner)
                if(name=="onPause"){
                    publish(owner,false)
                    val id=transitionId
                    main.postDelayed({
                        if(id==transitionId && !transitioning && !NativeSurfaceLive.active && !owner.hasWindowFocus()){
                            appReady=true;publish(owner,false)
                        }
                    },900L)
                }
                else if(name=="onResume")publish(owner,true,force=true)
                else refresh()
            }
        })
        for(name in listOf("OplusLauncherAppTransitionHelper","OplusLauncherAppTransitionHelperV2")){
            val helper=XposedHelpers.findClassIfExists("com.android.launcher3.anim.$name",loader) ?: continue
            for(method in helper.declaredMethods.filter {it.name in setOf("startAppLaunchWindowAnim","startAppCloseWindowAnim","startTaskViewLaunchWindowAnim")}){
                val opening=method.name!="startAppCloseWindowAnim"
                XposedBridge.hookMethod(method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        transitionStarted()
                        p.setObjectExtra("handleTransitionId",transitionId)
                    }
                    override fun afterHookedMethod(p:MethodHookParam){
                        val id=p.getObjectExtra("handleTransitionId") as? Int ?: return
                        if(p.hasThrowable()){transitionFinished(id,false);return}
                        val callbackType=XposedHelpers.findClassIfExists("com.android.launcher3.anim.NullableAnimatorListener",loader)
                        if(callbackType?.isInterface==true){
                            val wrapper=p.args.firstOrNull {arg -> arg!=null && arg.javaClass.declaredMethods.any {
                                it.parameterTypes.contentEquals(arrayOf(callbackType))
                            }}
                            val subscribe=wrapper?.javaClass?.declaredMethods?.firstOrNull {
                                it.parameterTypes.contentEquals(arrayOf(callbackType))
                            }
                            if(subscribe!=null)try {
                                val listener=java.lang.reflect.Proxy.newProxyInstance(loader,arrayOf(callbackType)){proxy,method,args ->
                                    when(method.name){
                                        "hashCode"->System.identityHashCode(proxy)
                                        "equals"->proxy===args?.firstOrNull()
                                        "toString"->"CherryPick-gesture-handle"
                                        "onAnimationEnd"->{main.post {transitionFinished(id,opening)};null}
                                        "onAnimationCancel"->{main.post {transitionFinished(id,false)};null}
                                        else->null
                                    }
                                }
                                subscribe.isAccessible=true;subscribe.invoke(wrapper,listener)
                                return
                            }catch(_:Throwable){}
                        }
                        val animator=p.args.firstNotNullOfOrNull {arg ->
                            if(arg is android.animation.Animator)arg
                            else arg?.javaClass?.declaredFields?.firstNotNullOfOrNull {field ->
                                if(android.animation.Animator::class.java.isAssignableFrom(field.type))try {
                                    field.isAccessible=true;field.get(arg) as? android.animation.Animator
                                }catch(_:Throwable){null}else null
                            }
                        }
                        if(animator!=null)animator.addListener(object:android.animation.AnimatorListenerAdapter(){
                            override fun onAnimationEnd(animation:android.animation.Animator){
                                animation.removeListener(this);main.post {transitionFinished(id,opening)}
                            }
                        })else main.postDelayed({transitionFinished(id,opening)},1000L)
                    }
                })
            }
        }
        // This event precedes launcher resume and the first home animation frame.
        for(name in listOf("com.android.quickstep.AbsSwipeUpHandler","com.android.quickstep.SwipeUpAnimationLogic")){
            XposedHelpers.findClassIfExists(name,loader)?.let {type ->
                XposedBridge.hookAllMethods(type,"onGestureStarted",object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){transitionStarted()}
                })
            }
        }
        XposedHelpers.findClassIfExists("com.android.launcher3.statemanager.StateManager",loader)?.let {state ->
            XposedBridge.hookAllMethods(state,"goToState",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    val owner=activity?.get() ?: return
                    val normal=XposedHelpers.findClassIfExists("com.android.launcher3.LauncherState",loader)?.let {
                        try {XposedHelpers.getStaticObjectField(it,"NORMAL")}catch(_:Throwable){null}
                    }
                    publish(owner,normal!=null && p.args.firstOrNull()===normal && owner.hasWindowFocus())
                }
            })
        }
    }
    fun refresh(){
        val owner=activity?.get() ?: return
        val normal=try {
            val state=XposedHelpers.callMethod(XposedHelpers.callMethod(owner,"getStateManager"),"getState")
            val type=XposedHelpers.findClass("com.android.launcher3.LauncherState",owner.classLoader)
            state===XposedHelpers.getStaticObjectField(type,"NORMAL")
        }catch(_:Throwable){false}
        publish(owner,normal && owner.hasWindowFocus())
    }
    private fun publish(owner:Activity,home:Boolean,force:Boolean=false){
        val visible=home || transitioning || !appReady
        if(sent==visible && !force)return
        sent=visible
        owner.sendBroadcast(Intent(ACTION).setPackage("com.android.systemui").putExtra("desktop",visible))
    }
    private fun transitionStarted(){
        transitionId++;transitioning=true;appReady=false
        activity?.get()?.let {publish(it,true,force=true)}
    }
    private fun transitionFinished(id:Int,opening:Boolean){
        if(id!=transitionId)return
        transitioning=false
        val owner=activity?.get() ?: return
        appReady=opening && !owner.hasWindowFocus()
        publish(owner,!appReady)
    }
    fun leaving(){transitionStarted()}
    fun nativeReturnStarted(){
        transitionId++;appReady=false;transitioning=false
        activity?.get()?.let {publish(it,true,force=true)}
    }
    fun nativeReturnFinished(){
        val id=transitionId
        main.postDelayed({
            val owner=activity?.get() ?: return@postDelayed
            if(id==transitionId && !NativeSurfaceLive.active && !com.cosmod.launcherglass.util.LiveBlurEngine.isResumed && !owner.hasWindowFocus()){
                appReady=true;publish(owner,false)
            }
        },120L)
    }

    fun installSystemUi(loader:ClassLoader){
        val names=listOf("com.android.systemui.navigationbar.gestural.NavigationHandle",
            "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle",
            "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle")
        for(name in names){
            val type=XposedHelpers.findClassIfExists(name,loader) ?: continue
            XposedBridge.hookAllConstructors(type,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    val view=p.thisObject as? View ?: return
                    handles[view]=true
                    transitionAlpha?.invoke(view,opacity)
                    register(view.context.applicationContext)
                }
            })
            XposedBridge.hookAllMethods(type,"onDraw",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(!hidden)return
                    val keyguard=try {XposedHelpers.getBooleanField(p.thisObject,"isKeyguardShowing")}catch(_:Throwable){false}
                    val top=try {XposedHelpers.getObjectField(p.thisObject,"mLastAppPkgName") as? String}catch(_:Throwable){null}
                    val suppress=keyguard || desktop || top in homes
                    animateOpacity(if(suppress)0f else 1f)
                    if(suppress && opacity<=.001f)p.result=null
                    else if(transitionAlpha==null && opacity<.999f){
                        val view=p.thisObject as View
                        val canvas=p.args.firstOrNull() as? android.graphics.Canvas ?: return
                        p.setObjectExtra("handleFadeSave",canvas.saveLayerAlpha(0f,0f,view.width.toFloat(),view.height.toFloat(),(opacity*255).toInt()))
                    }
                }
                override fun afterHookedMethod(p:MethodHookParam){
                    val save=p.getObjectExtra("handleFadeSave") as? Int ?: return
                    (p.args.firstOrNull() as? android.graphics.Canvas)?.restoreToCount(save)
                }
            })
        }
    }
    private fun invalidate(){handles.keys.toList().forEach {
        transitionAlpha?.invoke(it,opacity)
        it.invalidate()
    }}
    private fun animateOpacity(target:Float){
        if(opacityTarget==target)return
        opacityTarget=target
        opacityAnimator?.cancel()
        opacityAnimator=android.animation.ValueAnimator.ofFloat(opacity,target).apply {
            duration=if(target==0f)100L else 160L
            interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener {opacity=it.animatedValue as Float;invalidate()}
            start()
        }
    }
    private fun register(context:Context){
        if(registered)return
        registered=true
        val worker=Executors.newSingleThreadExecutor {r->Thread(r,"CherryPick-handle-settings").apply {isDaemon=true}}
        fun load(){worker.execute {
            val value=try {context.contentResolver.call(SettingsProvider.CONTENT_URI,SettingsProvider.METHOD_GET_ALL,null,null)?.let {
                it.getBoolean(SettingsProvider.KEY_ENABLED,true) && it.getBoolean(SettingsProvider.KEY_HIDE_HOME_GESTURE_BAR,false)
            } ?: false}catch(_:Throwable){false}
            main.post {hidden=value;animateOpacity(if(hidden && desktop)0f else 1f);invalidate()}
        }}
        context.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI,true,object:ContentObserver(main){
            override fun onChange(selfChange:Boolean){load()}
        })
        val receiver=object:BroadcastReceiver(){
            override fun onReceive(c:Context,i:Intent){
                desktop=i.action==ACTION && i.getBooleanExtra("desktop",false)
                animateOpacity(if(hidden && desktop)0f else 1f)
                invalidate()
            }
        }
        val filter=IntentFilter(ACTION).apply {addAction(Intent.ACTION_SCREEN_OFF)}
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver,filter)
        load()
    }
}
