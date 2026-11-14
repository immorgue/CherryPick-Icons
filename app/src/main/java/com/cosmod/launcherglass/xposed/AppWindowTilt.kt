package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceControl
import android.view.View
import com.cosmod.launcherglass.util.AppGenieMotion
import com.cosmod.launcherglass.util.AppWindowDeformation
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.reflect.Method
import kotlin.math.PI

/** Adds perspective to the app leash in the OEM transaction for the current animation frame. */
internal object AppWindowTilt {
    private data class Frame(val matrix:Matrix,val crop:Rect,val minimized:Double,
        val iconLeft:Double,val iconWidth:Double,val row:Double,val openResponse:Double,val closeResponse:Double,
        val damping:Double,val width:Float,val height:Float,
        val intensity:Double,val droplet:Float,val opening:Boolean,val fromIcon:Boolean,val time:Long,
        val iconBatch:IconBatch?,val radius:Float,val transaction:SurfaceControl.Transaction?) {
        var radiusTarget:Any?=null
    }
    private data class SpringTuning(val stiffness:Float,val damping:Float)
    private data class IconFade(val windowAlpha:Float,val opening:Boolean)
    private data class Source(val x:Float,val y:Float,val width:Float)
    private class IconBatch(val params:Any,val setAlpha:Method) {
        var icon:IconTarget?=null
        var deferred=false
    }
    private data class IconTarget(val builder:Any,val leash:SurfaceControl,val bounds:Rect,
        val affine:Matrix,val alpha:Float,val needsContainment:Boolean,val transaction:SurfaceControl.Transaction)
    private data class IconPass(val batch:IconBatch,val leash:SurfaceControl,val matrix:Matrix,
        val bounds:Rect,val width:Int,val height:Int,val needsContainment:Boolean,val transaction:SurfaceControl.Transaction) {
        var alpha=1f
    }
    private val frame=ThreadLocal<Frame>()
    private val pendingSource=ThreadLocal<Source>()
    private val sources=Collections.synchronizedMap(WeakHashMap<Any,Source>())
    private val motions=WeakHashMap<SurfaceControl,AppGenieMotion>()
    private val scratch=ThreadLocal.withInitial {FloatArray(16)}
    private val iconBounds=ThreadLocal.withInitial {FlyingIconBounds()}
    private val iconBatch=ThreadLocal<IconBatch>()
    private val iconPass=ThreadLocal<IconPass>()
    private val iconPitched=Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<SurfaceControl,Boolean>()))
    private val identity=FloatArray(16).apply {for(i in 0..3)this[i*5]=1f}
    private val pitched=Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<SurfaceControl,Boolean>()))
    private val loggedOpen=AtomicBoolean()
    private val loggedClose=AtomicBoolean()
    private val opening=setOf("LAUNCH_FROM_HOME","PRE_START3_LAUNCH","SWIPE_BREAK_LAUNCH")
    private val closing=setOf("REMOTE_CLOSE_TO_HOME","PREDICTIVE_BACK_TO_HOME","SWIPE_TO_HOME","SWIPE_TO_CLIENT_HOME")

    fun install(loader:ClassLoader) {
        val helper=listOf("com.android.quickstep.util.animation.RectTransformHelper","m69")
            .firstNotNullOfOrNull { name ->
                val type=XposedHelpers.findClassIfExists(name,loader) ?: return@firstNotNullOfOrNull null
                type.takeIf { it.declaredFields.any { field -> field.name=="d" && field.type==Matrix::class.java } }
            } ?: return
        val transform=helper.declaredMethods.singleOrNull { method ->
            val p=method.parameterTypes
            p.size==9 && p[1].isArray && p[2]==Int::class.javaPrimitiveType &&
                p[5]==RectF::class.java && p[6]==Boolean::class.javaPrimitiveType &&
                p[7]==RectF::class.java && p[1].componentType==p[8] && method.returnType==Void.TYPE
        } ?: return
        val params=transform.parameterTypes[0]
        val transaction=transform.parameterTypes[3]
        val nativeTransaction=transaction.declaredFields.singleOrNull {it.type==SurfaceControl.Transaction::class.java}
            ?.apply {isAccessible=true}
        val builder=transaction.declaredMethods.singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(SurfaceControl::class.java)) && it.returnType!=Void.TYPE
        }?.returnType ?: return
        val setMatrix=builder.declaredMethods.singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(Matrix::class.java)) && it.returnType==builder
        } ?: return
        val setPerspective=builder.declaredMethods.singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java)) && it.returnType==Void.TYPE
        }?.apply {isAccessible=true} ?: return
        val surface=builder.declaredFields.singleOrNull {it.type==SurfaceControl::class.java}?.apply {isAccessible=true} ?: return
        val native=helper.getDeclaredField("d").apply {isAccessible=true}
        val crop=helper.getDeclaredField("c").apply {isAccessible=true}
        val animType=helper.getDeclaredField("e").apply {isAccessible=true}
        val full=helper.declaredMethods.singleOrNull {it.name=="f" && it.parameterCount==0 && it.returnType==Rect::class.java}
            ?.apply {isAccessible=true} ?: return
        val rotating=params.getDeclaredField("J").apply {isAccessible=true}
        val height=params.getDeclaredField("d").apply {isAccessible=true}
        val radius=params.declaredFields.singleOrNull {it.name=="j" && it.type==Float::class.javaPrimitiveType}
            ?.apply {isAccessible=true}
        if(crop.type!=Rect::class.java || !animType.type.isEnum || rotating.type!=Boolean::class.javaPrimitiveType ||
            height.type!=Float::class.javaPrimitiveType)return
        hookSource(loader,helper)
        installDroplet(builder)
        installSpringTuning(loader,animType.type)
        if(nativeTransaction!=null)installFlyingIcon(loader,builder,params,transaction,setPerspective,nativeTransaction)
        installFrameReplay(loader,transform.parameterTypes[4],setPerspective)

        ModuleHooks.hookMethod(transform,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                param.setObjectExtra("priorAppTilt",frame.get())
                frame.remove()
                if(!enabled())return
                try {
                    val kind=(animType.get(param.thisObject) as Enum<*>).name
                    if(kind !in opening && kind !in closing)return
                    val input=param.args[0]
                    if(rotating.getBoolean(input))return
                    val window=full.invoke(param.thisObject) as? Rect ?: return
                    val current=param.args[5] as? RectF ?: return
                    if(window.isEmpty || current.isEmpty || window.width()>=window.height())return
                    // A single vertical size signal avoids crossing between
                    // two different spring curves as the square icon unfolds.
                    val rawAnchor=height.getFloat(input)/window.height()
                    val anchor=rawAnchor.takeIf {it in .015f.. .30f} ?: .065f
                    val scale=current.height()/window.height()
                    val isOpening=kind in opening
                    val source=sources[param.thisObject]
                    // If an OEM gesture constructs its helper outside the
                    // source-view call, recover the origin from its travel.
                    val remaining=(1f-scale).coerceAtLeast(.20f)
                    val row=(source?.let {(it.y-window.exactCenterY())*2f/window.height()}
                        ?: ((current.centerY()-window.exactCenterY())*2f/window.height()/remaining))
                    val column=(source?.let {(it.x-window.exactCenterX())*2f/window.width()}
                        ?: ((current.centerX()-window.exactCenterX())*2f/window.width()/remaining))
                    if(!scale.isFinite() || !column.isFinite() || !row.isFinite())return
                    val progress=((scale-anchor)/(1f-anchor)).coerceIn(0f,1f)
                    val iconWidth=(source?.width ?: anchor*window.height()).toDouble()/window.width()
                    val iconLeft=(1.0+column-iconWidth)*.5
                    val intensity=if(PrefsHelper.appGenie)PrefsHelper.appGenieStrength*.01 else 0.0
                    val droplet=if(PrefsHelper.appDroplet)AppWindowDeformation.droplet(progress,isOpening) else 0f
                    if(intensity==0.0 && droplet==0f)return
                    frame.set(Frame(native.get(param.thisObject) as Matrix,crop.get(param.thisObject) as Rect,
                        1.0-progress,iconLeft,iconWidth,row.toDouble(),response(true),response(false),
                        if(isSwipe(kind))1.1 else 1.0,current.width(),current.height(),intensity,droplet,
                        isOpening,source!=null,System.nanoTime(),iconBatch.get(),
                        radius?.getFloat(input)?.takeIf {it.isFinite()} ?: 0f,
                        nativeTransaction?.get(param.args[3]) as? SurfaceControl.Transaction))
                }catch(error:Exception){
                    GlassLogger.animationLimited("AppWindowTilt","tiltPoseFailure"){ "Skipped native pose: ${error.javaClass.simpleName}" }
                }
            }
            override fun afterHookedMethod(param:MethodHookParam) {
                val prior=param.getObjectExtra("priorAppTilt") as? Frame
                if(prior==null)frame.remove()else frame.set(prior)
            }
        })
        ModuleHooks.hookMethod(setMatrix,object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam) {
                if(param.hasThrowable())return
                val current=frame.get()?.takeIf {it.matrix===param.args[0]}
                val icon=iconPass.get()?.takeIf {it.matrix===param.args[0]}
                if(current==null && icon==null && pitched.isEmpty() && iconPitched.isEmpty())return
                try {
                    val leash=surface.get(param.thisObject) as? SurfaceControl ?: return
                    if(!leash.isValid)return
                    if(icon!=null && leash===icon.leash){
                        val bounds=if(!icon.bounds.isEmpty)Rect(icon.bounds) else Rect(0,0,icon.width,icon.height)
                        icon.batch.icon=IconTarget(param.thisObject,leash,bounds,Matrix(icon.matrix),
                            icon.alpha,icon.needsContainment,icon.transaction)
                        return
                    }
                    if(iconPitched.remove(leash))setPerspective.invoke(param.thisObject,identity)
                    if(current!=null)current.radiusTarget=param.thisObject
                    val matrix=perspective(leash,current,current?.crop,setPerspective,current?.matrix) ?: return
                    setPerspective.invoke(param.thisObject,matrix)
                }catch(error:Exception){
                    GlassLogger.animationLimited("AppWindowTilt","tiltMatrixFailure"){ "Skipped native perspective: ${error.javaClass.simpleName}" }
                }
            }
        })
        GlassLogger.i("AppWindowTilt","Native perspective available via ${helper.name}/${builder.name}")
    }

    private fun perspective(leash:SurfaceControl,current:Frame?,bounds:Rect?,iconSetter:Method,
                            affine:Matrix?,replayRadius:Float?=null,
                            appTransaction:SurfaceControl.Transaction?=current?.transaction):FloatArray? {
        if(current==null || current.intensity==0.0 || bounds==null || bounds.isEmpty){
            finishIconBatch(current?.iconBatch,null,iconSetter)
            return identity.takeIf {pitched.remove(leash)}
        }
        val motion=synchronized(motions){motions.getOrPut(leash){AppGenieMotion()}}
        val deformation=motion.update(current.minimized,current.iconLeft,current.iconWidth,
            current.row,current.intensity,current.opening,current.openResponse,current.closeResponse,current.damping,current.time)
            ?.takeIf {it.isVisible(current.width,current.height)}
        val radius=cornerRadius(replayRadius ?: current.radius,bounds,current.droplet)
        finishIconBatch(current.iconBatch,deformation,iconSetter,bounds,affine,radius,appTransaction)
        if(deformation==null)
            return identity.takeIf {pitched.remove(leash)}
        val matrix=scratch.get()!!
        deformation.writeMatrix(matrix,bounds.left.toFloat(),bounds.top.toFloat(),
            bounds.width().toFloat(),bounds.height().toFloat())
        pitched.add(leash)
        val logged=if(current.opening)loggedOpen else loggedClose
        if(GlassLogger.enabled && logged.compareAndSet(false,true)){
            val key=if(current.opening)"open" else "close"
            GlassLogger.animation("AppWindowTilt","Applied $key deformation, row=${current.row}, sourceIcon=${current.fromIcon}")
        }
        return matrix
    }

    private fun finishIconBatch(batch:IconBatch?,deformation:AppWindowDeformation?,setter:Method,
                                appCrop:Rect?=null,appAffine:Matrix?=null,radius:Float=0f,
                                appTransaction:SurfaceControl.Transaction?=null) {
        val icon=batch?.icon ?: return
        batch.icon=null
        if(!icon.leash.isValid)return
        // A recycled leash must never regain alpha from a different frame.
        if(appTransaction!=null && appTransaction!==icon.transaction)return
        val bounds=icon.bounds
        try {
            val fit=iconBounds.get()!!
            if(!icon.needsContainment || icon.alpha<.015f || appCrop==null || appAffine==null ||
                !fit.fit(bounds,icon.affine,appCrop,appAffine,radius,deformation)){
                if(iconPitched.remove(icon.leash))setter.invoke(icon.builder,identity)
                return
            }
            setter.invoke(icon.builder,fit.matrix)
            batch.setAlpha.invoke(icon.builder,icon.alpha)
            iconPitched.add(icon.leash)
        }catch(error:Exception){
            GlassLogger.animationLimited("AppWindowTilt","iconMatrixFailure"){ "Skipped icon perspective: ${error.javaClass.simpleName}" }
        }
    }

    private fun cornerRadius(radius:Float,crop:Rect,droplet:Float):Float {
        val limit=minOf(crop.width(),crop.height())*.5f
        val native=radius.takeIf {it.isFinite()}?.coerceIn(0f,limit) ?: limit
        return native+(limit-native)*droplet
    }

    private fun installFrameReplay(loader:ClassLoader,record:Class<*>,iconSetter:Method) {
        if(record.name!="y2c")return
        val replay=record.declaredMethods.singleOrNull { it.name=="a" && it.returnType==Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(SurfaceControl.Transaction::class.java,SurfaceControl::class.java)) } ?: return
        val store=record.declaredMethods.singleOrNull {
            it.name=="e" && it.returnType==Void.TYPE && it.parameterCount==11 && it.parameterTypes[6]==Rect::class.java
        } ?: return
        val active=record.getDeclaredField("a").takeIf {it.type==Boolean::class.javaPrimitiveType}
            ?.apply {isAccessible=true} ?: return
        val crop=record.getDeclaredField("j").takeIf {it.type==Rect::class.java}
            ?.apply {isAccessible=true} ?: return
        fun floatField(name:String)=record.declaredFields.singleOrNull {
            it.name==name && it.type==Float::class.javaPrimitiveType
        }?.apply {isAccessible=true}
        val x=floatField("b") ?: return
        val y=floatField("c") ?: return
        val scale=floatField("d") ?: return
        val radius=floatField("f") ?: return
        val angle=floatField("g") ?: return
        val pivotX=floatField("q") ?: return
        val pivotY=floatField("r") ?: return
        val replayAffine=ThreadLocal.withInitial {Matrix()}
        val wrapper=XposedHelpers.findClassIfExists("com.oplus.wrapper.view.SurfaceControl\$Transaction",loader) ?: return
        val setMatrix=wrapper.declaredMethods.singleOrNull { it.name=="setTransformationMatrix4x4" &&
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(
                SurfaceControl.Transaction::class.java,SurfaceControl::class.java,FloatArray::class.java)) } ?: return
        val deferred=Collections.synchronizedMap(WeakHashMap<Any,Frame>())
        ModuleHooks.hookMethod(store,object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam) {
                val current=frame.get()
                if(current==null || current.intensity==0.0 || param.hasThrowable())deferred.remove(param.thisObject)
                else {
                    current.iconBatch?.deferred=true
                    deferred[param.thisObject]=current
                }
            }
        })
        ModuleHooks.hookMethod(replay,object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam) {
                if(param.hasThrowable())return
                val leash=param.args[1] as? SurfaceControl ?: return
                if(!leash.isValid)return
                val current=deferred[param.thisObject]?.takeIf {enabled() && active.getBoolean(param.thisObject)}
                val record=param.thisObject
                val affine=if(current?.iconBatch?.icon!=null)replayAffine.get()!!.apply {
                    val zoom=scale.getFloat(record)
                    setScale(zoom,zoom)
                    postTranslate(x.getFloat(record),y.getFloat(record))
                    postRotate(angle.getFloat(record),pivotX.getFloat(record),pivotY.getFloat(record))
                } else null
                val matrix=perspective(leash,current,if(current!=null)crop.get(param.thisObject) as Rect else null,
                    iconSetter,affine,if(affine!=null)radius.getFloat(record) else null,
                    param.args[0] as? SurfaceControl.Transaction) ?: return
                setMatrix.invoke(null,param.args[0],leash,matrix)
            }
        })
    }

    private fun installFlyingIcon(loader:ClassLoader,builder:Class<*>,params:Class<*>,transaction:Class<*>,
                                  setter:Method,nativeTransaction:java.lang.reflect.Field) {
        if(builder.name!="cla" || params.name!="l69")return
        val updater=XposedHelpers.findClassIfExists("ko4",loader) ?: return
        val float=Float::class.javaPrimitiveType
        val bool=Boolean::class.javaPrimitiveType
        val launch=updater.declaredFields.singleOrNull {it.name=="a" && it.type==bool}
            ?.apply {isAccessible=true} ?: return
        val appAlpha=params.declaredFields.singleOrNull {it.name=="b" && it.type==float}
            ?.apply {isAccessible=true} ?: return
        val update=updater.declaredMethods.singleOrNull { method ->
            val p=method.parameterTypes
            method.name=="f" && method.returnType==Void.TYPE && p.size==8 && p[0]==params &&
                p[1]==bool && p[2]==RectF::class.java && p[3]==SurfaceControl::class.java &&
                p[4]==transaction && p[5]==bool && p[6]==Matrix::class.java &&
                p[7].simpleName=="RotationDirection"
        } ?: return
        val setAlpha=builder.declaredMethods.singleOrNull { it.name=="b" && it.returnType==builder &&
            it.parameterTypes.contentEquals(arrayOf(float)) } ?: return
        installIconBatch(loader,updater,update,params,setter,setAlpha,appAlpha,nativeTransaction)
        val fade=ThreadLocal<IconFade>()
        fun factor(alpha:Float):Float {
            val t=((alpha-.08f)/.45f).coerceIn(0f,1f)
            return 1f-t*t*(3f-2f*t)
        }
        fun installFadeScope(method:Method,alpha:(XC_MethodHook.MethodHookParam)->IconFade?) {
            ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam) {
                    param.setObjectExtra("priorGenieIconFade",fade.get())
                    fade.remove()
                    if(!enabled() || !PrefsHelper.appGenie || PrefsHelper.appGenieStrength==0)return
                    val state=alpha(param) ?: return
                    if(state.windowAlpha.isFinite())fade.set(state)
                }
                override fun afterHookedMethod(param:MethodHookParam) {
                    val prior=param.getObjectExtra("priorGenieIconFade") as? IconFade
                    if(prior==null)fade.remove()else fade.set(prior)
                }
            })
        }
        val applyFade=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                val state=fade.get() ?: return
                val alpha=(param.args[0] as Float)*if(state.opening)factor(state.windowAlpha) else 1f
                val icon=iconPass.get()
                if(icon!=null)icon.alpha=alpha
                // Keep the native overlay hidden until its own transaction
                // contains the fitted bounds from this exact app frame.
                param.args[0]=if(icon?.needsContainment==true ||
                    (icon==null && state.opening && state.windowAlpha>.005f))0f else alpha
            }
        }
        installFadeScope(update){IconFade(appAlpha.getFloat(it.args[0]),launch.getBoolean(it.thisObject))}
        ModuleHooks.hookMethod(setAlpha,applyFade)

        val fallback=XposedHelpers.findClassIfExists("eh7",loader) ?: return
        val view=XposedHelpers.findClassIfExists("com.android.launcher3.views.FloatingIconView",loader) ?: return
        val fallbackAlpha=fallback.declaredFields.singleOrNull {it.name=="s" && it.type==float}
            ?.apply {isAccessible=true} ?: return
        val run=fallback.declaredMethods.singleOrNull {
            it.name=="run" && it.parameterCount==0 && it.returnType==Void.TYPE
        } ?: return
        val viewUpdate=view.declaredMethods.singleOrNull { it.name=="update" &&
            it.parameterTypes.contentEquals(arrayOf(float,float,float,float,RectF::class.java,bool)) } ?: return
        installFadeScope(run){IconFade(fallbackAlpha.getFloat(it.thisObject),true)}
        ModuleHooks.hookMethod(viewUpdate,applyFade)
    }

    private fun installIconBatch(loader:ClassLoader,updater:Class<*>,update:Method,params:Class<*>,setter:Method,
                                 setAlpha:Method,appAlpha:java.lang.reflect.Field,nativeTransaction:java.lang.reflect.Field) {
        val dispatcher=XposedHelpers.findClassIfExists("ldb",loader) ?: return
        val dispatch=dispatcher.declaredMethods.singleOrNull {
            val p=it.parameterTypes
            it.name=="f" && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.returnType==Void.TYPE && p.size==6 && p[2]==params && p[3]==RectF::class.java &&
                p[4]==Runnable::class.java && p[5].name=="kotlin.jvm.functions.Function2"
        } ?: return
        fun field(name:String,type:Class<*>?)=updater.declaredFields.singleOrNull {it.name==name && it.type==type}
            ?.apply {isAccessible=true}
        val matrix=field("e",Matrix::class.java) ?: return
        val crop=field("d",Rect::class.java) ?: return
        val width=field("g",Int::class.javaPrimitiveType) ?: return
        val height=field("h",Int::class.javaPrimitiveType) ?: return
        // The native dispatcher invokes the icon before the app window. Hold
        // its builder until the app computes the pose in this frame.
        ModuleHooks.hookMethod(dispatch,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                param.setObjectExtra("priorGenieIconBatch",iconBatch.get())
                iconBatch.remove()
                if(enabled() && PrefsHelper.appGenie && PrefsHelper.appGenieStrength>0){
                    iconBatch.set(IconBatch(param.args[2],setAlpha))
                }
            }
            override fun afterHookedMethod(param:MethodHookParam) {
                val batch=iconBatch.get()
                val prior=param.getObjectExtra("priorGenieIconBatch") as? IconBatch
                if(prior==null)iconBatch.remove()else iconBatch.set(prior)
                if(batch!=null && !batch.deferred)finishIconBatch(batch,null,setter)
            }
        })
        ModuleHooks.hookMethod(update,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                param.setObjectExtra("priorGenieIconPass",iconPass.get())
                iconPass.remove()
                val batch=iconBatch.get()?.takeIf {it.params===param.args[0]} ?: return
                val leash=param.args[3] as? SurfaceControl ?: return
                val transaction=nativeTransaction.get(param.args[4]) as? SurfaceControl.Transaction ?: return
                val owner=param.thisObject
                iconPass.set(IconPass(batch,leash,matrix.get(owner) as Matrix,crop.get(owner) as Rect,
                    width.getInt(owner),height.getInt(owner),appAlpha.getFloat(param.args[0])>.005f,transaction))
            }
            override fun afterHookedMethod(param:MethodHookParam) {
                val prior=param.getObjectExtra("priorGenieIconPass") as? IconPass
                if(prior==null)iconPass.remove()else iconPass.set(prior)
            }
        })
    }

    private fun enabled()=PrefsHelper.enabled && PrefsHelper.appWindowTilt && ValueAnimator.areAnimatorsEnabled()

    private fun isSwipe(kind:String)=kind=="SWIPE_TO_HOME" || kind=="SWIPE_TO_CLIENT_HOME"

    private fun response(opening:Boolean):Double {
        val seconds=if(opening)PrefsHelper.appOpenResponse*.001 else .336/(PrefsHelper.appCloseSpeed*.01)
        return seconds*PrefsHelper.appAnimationSlowdown*.01
    }

    private fun installDroplet(builder:Class<*>) {
        if(builder.name!="cla")return
        val float=Float::class.javaPrimitiveType
        val smooth=builder.declaredMethods.singleOrNull { method ->
            method.name=="f" && method.returnType==Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(float,float,Int::class.javaPrimitiveType))
        } ?: return
        val round=builder.declaredMethods.singleOrNull { method ->
            method.name=="d" && method.returnType==builder && method.parameterTypes.contentEquals(arrayOf(float))
        } ?: return
        val hook=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                val current=frame.get() ?: return
                if(current.radiusTarget!==param.thisObject || current.droplet<=0f || current.crop.isEmpty)return
                current.radiusTarget=null
                val radius=param.args[0] as Float
                val target=minOf(current.crop.width(),current.crop.height())*.5f
                if(radius.isFinite() && radius>=0f && radius<target)
                    param.args[0]=radius+(target-radius)*current.droplet
            }
        }
        ModuleHooks.hookMethod(smooth,hook)
        ModuleHooks.hookMethod(round,hook)
    }

    private fun installSpringTuning(loader:ClassLoader,animationType:Class<*>) {
        val animator=XposedHelpers.findClassIfExists("nj2",loader) ?: return
        val spring=XposedHelpers.findClassIfExists("h8a",loader) ?: return
        val kind=animator.declaredFields.singleOrNull {it.name=="v0" && it.type==animationType}
            ?.apply {isAccessible=true} ?: return
        if(animator.declaredFields.count {it.type==spring}!=7)return
        val setup=animator.declaredMethods.filter {
            it.name in setOf("E","H") && it.parameterCount==0 && it.returnType==Void.TYPE
        }
        if(setup.size!=2)return
        val float=Float::class.javaPrimitiveType
        val setters=spring.declaredMethods.filter {
            it.name in setOf("b","c") && it.parameterTypes.contentEquals(arrayOf(float)) && it.returnType==Void.TYPE
        }
        if(setters.size!=2)return
        val tuning=ThreadLocal<SpringTuning>()
        val scope=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                param.setObjectExtra("priorSpringTuning",tuning.get())
                tuning.remove()
                if(!enabled())return
                val name=(kind.get(param.thisObject) as? Enum<*>)?.name ?: return
                if(name !in opening && name !in closing)return
                val frequency=(2*PI/response(name in opening)).toFloat()
                val damping=if(isSwipe(name) && PrefsHelper.appGestureBounce) .85f else 1f
                tuning.set(SpringTuning(frequency*frequency,damping))
            }
            override fun afterHookedMethod(param:MethodHookParam) {
                val prior=param.getObjectExtra("priorSpringTuning") as? SpringTuning
                if(prior==null)tuning.remove()else tuning.set(prior)
            }
        }
        for(method in setup)ModuleHooks.hookMethod(method,scope)
        for(method in setters){
            val isStiffness=method.name=="c"
            ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam) {
                    val config=tuning.get() ?: return
                    param.args[0]=if(isStiffness)config.stiffness else config.damping
                }
            })
        }
    }

    private fun hookSource(loader:ClassLoader,helper:Class<*>){
        val transitions=listOf("OplusLauncherAppTransitionHelper","OplusLauncherAppTransitionHelperV2")
        for(name in transitions){
            val owner=XposedHelpers.findClassIfExists("com.android.launcher3.anim.$name",loader) ?: continue
            for(method in owner.declaredMethods){
                if(method.name!="startAppLaunchWindowAnim" && method.name!="startAppCloseWindowAnim")continue
                val index=method.parameterTypes.indexOfFirst {View::class.java.isAssignableFrom(it)}
                if(index<0)continue
                ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                    override fun beforeHookedMethod(param:MethodHookParam) {
                        param.setObjectExtra("priorTiltSource",pendingSource.get())
                        if(!PrefsHelper.appWindowTilt)return
                        val view=param.args[index] as? View ?: return
                        if(!view.isAttachedToWindow || view.width<=0 || view.height<=0)return
                        val location=IntArray(2)
                        view.getLocationOnScreen(location)
                        val icon=IconGlassHook.displayedIconBounds(view)
                        val x=icon?.exactCenterX() ?: view.width*.5f
                        val y=icon?.exactCenterY() ?: view.height*.5f
                        pendingSource.set(Source(location[0]+x,location[1]+y,icon?.width()?.toFloat() ?: view.width.toFloat()))
                    }
                    override fun afterHookedMethod(param:MethodHookParam) {
                        val prior=param.getObjectExtra("priorTiltSource") as? Source
                        if(prior==null)pendingSource.remove()else pendingSource.set(prior)
                    }
                })
            }
        }
        ModuleHooks.hookAllConstructors(helper,object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                pendingSource.get()?.let {sources[param.thisObject]=it}
            }
        })
    }

}
