package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceControl
import android.view.View
import com.cosmod.launcherglass.util.AppGenieMotion
import com.cosmod.launcherglass.util.AppWindowDeformation
import com.cosmod.launcherglass.util.FlyingIconPose
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.bridge.HookCallback
import com.cosmod.launcherglass.bridge.HookReflection
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import kotlin.math.PI

/** Adds perspective to the app leash in the OEM transaction for the current animation frame. */
@androidx.annotation.RequiresApi(33)
internal object AppWindowTilt {
    private data class Frame(val matrix:Matrix,val crop:Rect,val minimized:Double,
        val iconLeft:Double,val iconWidth:Double,val row:Double,val openResponse:Double,val closeResponse:Double,
        val damping:Double,val width:Float,val height:Float,
        val intensity:Double,val droplet:Float,val opening:Boolean,val fromIcon:Boolean,val time:Long,
        val openingOffsetY:Float,val closingOffsetY:Float,val iconBatch:IconBatch?,val radius:Float,
        val transaction:SurfaceControl.Transaction?,val iconHeight:Float,val pivotX:Float,val pivotY:Float,
        val launchIconWidth:Float) {
        var radiusTarget:Any?=null
        var deformation:AppWindowDeformation?=null
        var evaluated=false
        var appliedAffine:Matrix?=null
        var appliedCrop:Rect?=null
        var appliedRadius=radius
        var openingMix=0f
        var drawnWindowAlpha=Float.NaN
        val nativeProgress = (1.0 - minimized).toFloat()
        var progress=nativeProgress
        var growthScale=1f
        var launchOriginWeight=0f

        fun appliedWindowOffsetY() = FlyingIconPose.windowOffsetY(openingOffsetY, closingOffsetY, openingMix)
        val adjustsWindowScale get() = growthScale != 1f || launchOriginWeight > 0f
    }
    private data class SpringTuning(val stiffness:Float,val damping:Float)
    private data class Source(val x:Float,val y:Float,val width:Float,val measuredIcon:Boolean=false)
    private data class WorkspaceTransition(val progress:Float,val time:Long)
    private data class LegacyBreakPose(val leash:WeakReference<SurfaceControl>,val matrix:FloatArray,
        val motion:AppGenieMotion?,val time:Long,val affine:Matrix?)
    private class IconBatch(val params:Any) {
        var icon:IconTarget?=null
        var window:Frame?=null
        var applied=false
        var windowX=Float.NaN
        var windowY=Float.NaN
        var windowScale=Float.NaN
        var drawnIconAlpha=Float.NaN
        var time=System.nanoTime()
    }
    private data class IconTarget(val leash:SurfaceControl,val bounds:Rect,
        val affine:Matrix,val alpha:Float,val windowAlpha:Float,val transaction:SurfaceControl.Transaction)
    private data class IconPass(val batch:IconBatch,val leash:SurfaceControl,val matrix:Matrix,
        val bounds:Rect,val width:Int,val height:Int,val windowAlpha:Float,val transaction:SurfaceControl.Transaction) {
        var alpha=1f
    }
    private val frame=ThreadLocal<Frame>()
    private val pendingSource=ThreadLocal<Source>()
    private val sources=Collections.synchronizedMap(WeakHashMap<Any,Source>())
    private val legacyCloseTargets = Collections.synchronizedMap(WeakHashMap<Any,Source>())
    private val motions=WeakHashMap<SurfaceControl,AppGenieMotion>()
    private val scratch=ThreadLocal.withInitial {FloatArray(16)}
    private val windowPosition=ThreadLocal.withInitial { FloatArray(9) }
    private val iconWindowAffine=ThreadLocal.withInitial { Matrix() }
    private val launchWindowBounds=ThreadLocal.withInitial { RectF() }
    private val iconBounds=ThreadLocal.withInitial {FlyingIconBounds()}
    private val pendingIconBatches=WeakHashMap<SurfaceControl.Transaction,MutableMap<Any,IconBatch>>()
    private val dispatchIconBatch=ThreadLocal<IconBatch>()
    private val iconPass=ThreadLocal<IconPass>()
    private val iconFrames=Collections.synchronizedMap(WeakHashMap<SurfaceControl,WeakReference<IconBatch>>())
    private var writeNativeCorner:Method?=null
    private var writeNativeAffine:Method?=null
    @Volatile private var workspaceTransition:WorkspaceTransition?=null
    private val identity=FloatArray(16).apply {for(i in 0..3)this[i*5]=1f}
    private val pitched=Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<SurfaceControl,Boolean>()))
    private val loggedOpen=AtomicBoolean()
    private val loggedClose=AtomicBoolean()
    private val loggedHandoff=AtomicBoolean()
    private val opening=setOf("LAUNCH_FROM_HOME","PRE_START3_LAUNCH","SWIPE_BREAK_LAUNCH")
    private val legacyOpening=setOf("OPEN_FROM_HOME","REVERSE_TO_OPEN","REVERSE_TO_OPEN_REMOTE")
    private val closing=setOf("REMOTE_CLOSE_TO_HOME","PREDICTIVE_BACK_TO_HOME","SWIPE_TO_HOME","SWIPE_TO_CLIENT_HOME")

    // Optional OEM transaction API. Unavailable reflection leaves native radii in use.
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    fun install(loader:ClassLoader) {
        val float = Float::class.javaPrimitiveType!!
        writeNativeAffine = SurfaceControl.Transaction::class.java.method("setMatrix", SurfaceControl::class.java,
            float, float, float, float)
        writeNativeCorner=runCatching {SurfaceControl.Transaction::class.java.getDeclaredMethod(
            "setCornerRadius",SurfaceControl::class.java,Float::class.javaPrimitiveType).apply {isAccessible=true}}.getOrNull()
        installWorkspaceBlur(loader)
        val helper=listOf("com.android.quickstep.util.animation.RectTransformHelper","m69")
            .firstNotNullOfOrNull { name ->
                val type=HookReflection.findClassIfExists(name,loader) ?: return@firstNotNullOfOrNull null
                type.takeIf { it.declaredFields.any { field -> field.name=="d" && field.type==Matrix::class.java } }
            } ?: return installColorOs16(loader)
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
        installDroplet(builder,surface)
        installSpringTuning(loader,animType.type)
        if(nativeTransaction!=null)installFlyingIcon(loader,builder,params,transaction,nativeTransaction)
        installFrameReplay(loader,transform.parameterTypes[4])

        ModuleHooks.hookMethod(transform,object:HookCallback(){
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
                    val nativeFrameTransaction=nativeTransaction?.get(param.args[3]) as? SurfaceControl.Transaction
                    // Window update may precede the icon in this dispatch. Keep
                    // its empty batch: the following icon pass fills the SAME frame.
                    // OEM callbacks may copy TransformParams inside this
                    // dispatch. Scope identity, not mutable params identity,
                    // joins the app and icon passes for this frame.
                    val paired=nativeFrameTransaction?.let {pairBatch(it,input)}
                    if(GlassLogger.enabled)GlassLogger.animationLimited("AppWindowTilt","pairLookup$kind",1000) {
                        "Icon lookup: tx=${System.identityHashCode(nativeFrameTransaction)}, params=${System.identityHashCode(input)}, paired=${paired!=null}"
                    }
                    makeFrame(param.thisObject,kind,kind in opening,window,current,height.getFloat(input),
                        radius?.getFloat(input) ?: 0f,native.get(param.thisObject) as Matrix,
                        crop.get(param.thisObject) as Rect,
                        nativeFrameTransaction,pairedBatch=paired)?.let {
                            paired?.window=it
                            frame.set(it)
                        }
                }catch(error:Exception){
                    GlassLogger.animationLimited("AppWindowTilt","tiltPoseFailure"){ "Skipped native pose: ${error.javaClass.simpleName}" }
                }
            }
            override fun afterHookedMethod(param:MethodHookParam) {
                val prior=param.getObjectExtra("priorAppTilt") as? Frame
                if(prior==null)frame.remove()else frame.set(prior)
            }
        })
        ModuleHooks.hookMethod(setMatrix,object:HookCallback(){
            override fun afterHookedMethod(param:MethodHookParam) {
                if(param.hasThrowable())return
                val current=frame.get()?.takeIf {it.matrix===param.args[0]}
                val icon=iconPass.get()?.takeIf {it.matrix===param.args[0]}
                if(current==null && icon==null && pitched.isEmpty())return
                try {
                    val leash=surface.get(param.thisObject) as? SurfaceControl ?: return
                    if(!leash.isValid)return
                    if(icon!=null && leash===icon.leash){
                        captureIconPose(icon)
                        return
                    }
                    if(current!=null){
                        current.radiusTarget=param.thisObject
                        current.appliedAffine=Matrix(current.matrix)
                        current.appliedCrop=Rect(current.crop)
                    }
                    val matrix=perspective(leash,current,current?.crop,current?.matrix) ?: return
                    setPerspective.invoke(param.thisObject,matrix)
                }catch(error:Exception){
                    GlassLogger.animationLimited("AppWindowTilt","tiltMatrixFailure"){ "Skipped native perspective: ${error.javaClass.simpleName}" }
                }
            }
        })
        GlassLogger.i("AppWindowTilt","Native perspective available via ${helper.name}/${builder.name}")
    }

    private fun makeFrame(owner: Any, kind: String, opening: Boolean, window: Rect, current: RectF,
                          originHeight: Float, radius: Float, matrix: Matrix, crop: Rect,
                          transaction: SurfaceControl.Transaction?, targetSource: Source? = null,
                          pairedBatch:IconBatch? = null): Frame? {
        if (window.isEmpty || current.isEmpty || window.width() >= window.height()) return null
        val rawAnchor = originHeight / window.height()
        val anchor = rawAnchor.takeIf { it in .015f.. .30f } ?: .065f
        // Both icon and window use vertical size to read progress across the spring.
        val scale = current.height() / window.height()
        val source = targetSource ?: sources[owner]
        val remaining = (1f - scale).coerceAtLeast(.20f)
        val sourceRow = source?.let { (it.y - window.exactCenterY()) * 2f / window.height() }
        val row = sourceRow ?: (current.centerY() - window.exactCenterY()) * 2f / window.height() / remaining
        val column = source?.let { (it.x - window.exactCenterX()) * 2f / window.width() }
            ?: (current.centerX() - window.exactCenterX()) * 2f / window.width() / remaining
        if (!scale.isFinite() || !column.isFinite() || !row.isFinite()) return null
        val progress = ((scale - anchor) / (1f - anchor)).coerceIn(0f, 1f)
        val iconWidth = (source?.width ?: anchor * window.height()).toDouble() / window.width()
        val iconLeft = (1.0 + column - iconWidth) * .5
        val intensity = if (PrefsHelper.appGenie) PrefsHelper.appGenieStrength * .01 else 0.0
        val droplet = if (PrefsHelper.appDroplet) AppWindowDeformation.droplet(progress, opening) else 0f
        if (intensity == 0.0 && droplet == 0f) return null
        val roomBelow = window.bottom - current.bottom
        val closingOffset = if (intensity > 0.0 && (source != null || rawAnchor in .015f.. .30f))
            FlyingIconPose.closingWindowOffsetY(row, progress, source?.width ?: originHeight, roomBelow)
        else 0f
        // Use the same small positive offset through a reversal if the OEM
        // keeps the gesture animator and does not report an origin View.
        val openingOffset = if (intensity > 0.0 && source != null)
            FlyingIconPose.openingWindowOffsetY(sourceRow, progress, source.width,
                current.top - window.top, roomBelow)
        else closingOffset
        val launchIconWidth = if (kind == "LAUNCH_FROM_HOME" || kind == "PRE_START3_LAUNCH" || kind == "OPEN_FROM_HOME") {
            source?.takeIf { it.measuredIcon }?.width ?: 0f
        } else 0f
        val result=Frame(matrix, crop, 1.0 - progress, iconLeft, iconWidth, row.toDouble(), response(true),
            response(false), if (isSwipe(kind)) 1.1 else 1.0, current.width(), current.height(), intensity,
            droplet, opening, source != null, System.nanoTime(), openingOffset, closingOffset, pairedBatch,
            radius.takeIf { it.isFinite() } ?: 0f, transaction, anchor * window.height(),
            source?.x ?: current.centerX(), source?.y ?: current.centerY(), launchIconWidth)
        pairedBatch?.window=result
        return result
    }

    private fun perspective(leash:SurfaceControl,current:Frame?,bounds:Rect?,
                            affine:Matrix?,replayRadius:Float?=null,
                            appTransaction:SurfaceControl.Transaction?=current?.transaction,
                            windowAlpha:Float=Float.NaN):FloatArray? {
        if(current==null || current.intensity==0.0 || bounds==null || bounds.isEmpty){
            return identity.takeIf {pitched.remove(leash)}
        }
        val motion=synchronized(motions){motions.getOrPut(leash){AppGenieMotion()}}
        if(!current.evaluated){
            synchronized(motion) {
                current.deformation=motion.update(current.minimized,current.iconLeft,current.iconWidth,
                    current.row,current.intensity,current.opening,current.openResponse,current.closeResponse,current.damping,current.time)
                    ?.takeIf {it.isVisible(current.width,current.height)}
                current.openingMix=motion.openingMix
            }
            if (affine!=null && appTransaction!=null && writeNativeAffine!=null) {
                current.progress=FlyingIconPose.openingGrowth(current.nativeProgress,current.openingMix)
                current.growthScale=FlyingIconPose.growthScale(
                    current.nativeProgress,current.progress,current.height,current.iconHeight)
            }
            if (current.launchIconWidth > 0f && writeNativeAffine != null) {
                current.launchOriginWeight = FlyingIconPose.launchOriginWeight(current.nativeProgress) * current.openingMix
            }
            val previous=workspaceTransition
            if (previous==null || current.time>=previous.time)
                workspaceTransition=WorkspaceTransition(current.progress,current.time)
            current.evaluated=true
        }
        val deformation=current.deformation
        val radius=cornerRadius(replayRadius ?: current.radius,bounds,current.droplet)
        if (affine!=null && appTransaction!=null) {
            val adjusted=windowAffine(current,affine,bounds)
            if (adjusted!==affine) writeWindowAffine(appTransaction,leash,adjusted,current.adjustsWindowScale)
        }
        if (windowAlpha.isFinite()) {
            current.drawnWindowAlpha=FlyingIconPose.windowReveal(windowAlpha,current.progress)
            appTransaction?.setAlpha(leash,current.drawnWindowAlpha)
        }
        finishIconBatch(current.iconBatch,deformation,bounds,affine,radius,appTransaction)
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

    private fun windowAffine(frame:Frame, native:Matrix, crop:Rect):Matrix {
        val offset=frame.appliedWindowOffsetY()
        if (offset==0f && !frame.adjustsWindowScale) return native
        return iconWindowAffine.get()!!.apply {
            set(native)
            postTranslate(0f,offset)
            if (frame.growthScale!=1f)
                postScale(frame.growthScale,frame.growthScale,frame.pivotX,frame.pivotY)
            val weight = frame.launchOriginWeight
            if (weight > 0f) {
                val projected = launchWindowBounds.get()!!
                projected.set(crop)
                mapRect(projected)
                val width = projected.width()
                if (width.isFinite() && width > 0f) {
                    // Start in the resting icon frame, then converge to the original launch pose.
                    val x = projected.centerX()
                    val y = projected.centerY()
                    val scale = 1f + (frame.launchIconWidth / width - 1f) * weight
                    postScale(scale,scale,x,y)
                    postTranslate((frame.pivotX - x) * weight,(frame.pivotY - y) * weight)
                }
            }
        }
    }

    private fun writeWindowAffine(tx:SurfaceControl.Transaction, leash:SurfaceControl, affine:Matrix, scale:Boolean) {
        val values=windowPosition.get()!!
        affine.getValues(values)
        if (scale) writeNativeAffine?.invoke(tx,leash,values[Matrix.MSCALE_X],values[Matrix.MSKEW_Y],
            values[Matrix.MSKEW_X],values[Matrix.MSCALE_Y])
        tx.setPosition(leash,values[Matrix.MTRANS_X],values[Matrix.MTRANS_Y])
    }

    private fun installColorOs16(loader: ClassLoader) {
        val prefix = "com.android.quickstep.util."
        val helper = HookReflection.findClassIfExists(prefix + "animation.RectTransformHelper", loader) ?: return
        val transaction = HookReflection.findClassIfExists(prefix + "SurfaceTransaction", loader) ?: return
        val params = HookReflection.findClassIfExists(helper.name + "\$TransformParams", loader) ?: return
        val target = HookReflection.findClassIfExists("com.android.systemui.shared.system.RemoteAnimationTargetCompat", loader) ?: return
        val builderMethod = transaction.method("forSurface", SurfaceControl::class.java) ?: return
        val builder = builderMethod.returnType
        val setMatrix = builder.method("setMatrix", Matrix::class.java) ?: return
        val setPerspective = builder.method("setMatrix4x4", FloatArray::class.java) ?: return
        // Use the OEM API so the window remains in the transaction's modified
        // surface set. Fork-specific tilt helpers own independent cleanup transactions.
        val writePerspective = setPerspective
        val update = helper.declaredMethods.singleOrNull { method ->
            val types = method.parameterTypes
            method.name.startsWith("applySurfaceParams\$lambda") &&
                java.lang.reflect.Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                types.size == 8 && types[0] == target && types[1] == helper && types[2] == transaction &&
                types[3] == params && types[4] == Boolean::class.javaPrimitiveType &&
                types[6] == Float::class.javaPrimitiveType && types[7] == RectF::class.java
        } ?: return
        val leashField = target.getField("leash")
        val surfaceField = builder.field("mSurface", SurfaceControl::class.java) ?: return
        val kindField = helper.declaredFields.singleOrNull { it.name == "mAnimType" && it.type.isEnum }
            ?.apply { isAccessible = true } ?: return
        val cropField = helper.field("mClipRect", Rect::class.java) ?: return
        val fullWindow = helper.method("getRealWindowRect")?.takeIf { it.returnType == Rect::class.java } ?: return
        val sourceWindow = helper.field("mSourceWindowRect", Rect::class.java) ?: return
        val matrixField = helper.field("mTmpMatrix", Matrix::class.java) ?: return
        val nativeTransaction = transaction.field("mTransaction", SurfaceControl.Transaction::class.java)
        val heightField = params.field("originHeight", Float::class.javaPrimitiveType!!) ?: return
        val alphaField = params.field("alpha", Float::class.javaPrimitiveType!!)
        val radiusField = params.field("radius", Float::class.javaPrimitiveType!!) ?: return
        val taskView = params.field("isTaskView", Boolean::class.javaPrimitiveType!!) ?: return
        val flexible = params.field("isFlexibleWindowScene", Boolean::class.javaPrimitiveType!!) ?: return
        val positionX = params.field("x", Float::class.javaPrimitiveType!!)
        val positionY = params.field("y", Float::class.javaPrimitiveType!!)
        val scale = params.field("scale", Float::class.javaPrimitiveType!!)
        val corner = builder.method("setCornerRadius", Float::class.javaPrimitiveType!!)
        val breakPoses = Collections.synchronizedMap(WeakHashMap<Any, LegacyBreakPose>())
        installColorOs16Break(loader, update.parameterTypes[5], breakPoses)
        val cropStrategy = helper.method("getWindowCropStrategy", params, RectF::class.java,
            Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        hookSource(loader, helper)
        installColorOs16Spring(loader, kindField.type)
        val pendingIcons = installColorOs16Icons(loader, params, transaction, builder, setMatrix, surfaceField)
        cropStrategy?.let { method ->
            ModuleHooks.hookMethod(method, object : HookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val input = param.args[0]
                    legacyCloseTargets.remove(input)
                    if (!enabled() || param.args[2] != 0 || param.args[3] == true) return
                    val kind = (kindField.get(param.thisObject) as? Enum<*>)?.name ?: return
                    if (kind !in closing) return
                    val rect = param.args[1] as RectF
                    val window = fullWindow.invoke(param.thisObject) as Rect
                    val x = rect.centerX()
                    val y = rect.centerY()
                    val width = rect.width()
                    val height = rect.height()
                    if (window.isEmpty || window.width() >= window.height() ||
                        x !in window.left.toFloat()..window.right.toFloat() ||
                        y !in window.top.toFloat()..window.bottom.toFloat() ||
                        width / window.width() !in .015f.. .40f ||
                        height / window.height() !in .015f.. .30f) return
                    // Gesture setup already knows the landing icon's bounds.
                    legacyCloseTargets[input] = Source(x, y, width)
                }
            })
        }

        // ColorOS 16 dispatches the app update through a Runnable. Hook its body so
        // the additional matrix lands in the same transaction after the launcher writes its pose.
        ModuleHooks.hookMethod(update, object : HookCallback() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || !enabled()) return
                try {
                    val owner = param.args[1]
                    val input = param.args[3]
                    if (taskView.getBoolean(input) || flexible.getBoolean(input)) return
                    val kind = (kindField.get(owner) as Enum<*>).name
                    val opening = kind in legacyOpening
                    if (!opening && kind !in closing) return
                    val leash = leashField.get(param.args[0]) as? SurfaceControl ?: return
                    if (!leash.isValid) return
                    // Open and swipe-to-home use the constructor that leaves mRealWindowRect empty.
                    // The OEM getter falls back to the source window in those scenes.
                    val window = fullWindow.invoke(owner) as Rect
                    val crop = (cropField.get(owner) as Rect).takeUnless { it.isEmpty }
                        ?: sourceWindow.get(owner) as Rect
                    val matrix = matrixField.get(owner) as Matrix
                    val batch = pendingIcons?.remove(param.args[2])?.takeIf {
                        it.params === input && it.windowX == positionX?.getFloat(input) &&
                            it.windowY == positionY?.getFloat(input) && it.windowScale == scale?.getFloat(input) &&
                            System.nanoTime() - it.time in 0L..50_000_000L
                    }
                    val appTransaction = nativeTransaction?.get(param.args[2]) as? SurfaceControl.Transaction
                    val current = makeFrame(
                        owner, kind, opening, window, param.args[7] as RectF,
                        heightField.getFloat(input), radiusField.getFloat(input), matrix, crop, appTransaction,
                        if (opening) null else legacyCloseTargets[input], batch
                    ) ?: return
                    val tilt = perspective(leash, current, crop, matrix,
                        windowAlpha=alphaField?.getFloat(input) ?: Float.NaN)
                    param.args[5]?.let { state ->
                        if (tilt == null || !pitched.contains(leash)) breakPoses.remove(state)
                        else breakPoses[state] = LegacyBreakPose(WeakReference(leash), tilt.copyOf(),
                            synchronized(motions) { motions[leash] }, current.time,
                            if (current.adjustsWindowScale) Matrix(windowAffine(current,matrix,crop)) else null)
                    }
                    if (tilt == null && current.droplet <= 0f) return
                    val properties = builderMethod.invoke(param.args[2], leash) ?: return
                    if (tilt != null) writePerspective.invoke(properties, tilt)
                    if (current.droplet > 0f && !crop.isEmpty && current.radius in 0f..(minOf(crop.width(), crop.height()) * .5f)) {
                        corner?.invoke(properties, cornerRadius(current.radius, crop, current.droplet))
                    }
                } catch (error: Exception) {
                    GlassLogger.animationLimited("AppWindowTilt", "colorOs16TiltFailure") {
                        "Skipped ColorOS 16 perspective: ${error.javaClass.simpleName}"
                    }
                }
            }
        })
        // Retire our preceding pose in the same transaction as the next OEM
        // pose, including gesture interruption and animation-record replay.
        ModuleHooks.hookMethod(setMatrix, object : HookCallback() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (pitched.isEmpty()) return
                val leash = surfaceField.get(param.thisObject) as? SurfaceControl ?: return
                if (pitched.remove(leash) && leash.isValid) setPerspective.invoke(param.thisObject, identity)
            }
        })
        GlassLogger.i("AppWindowTilt", "Native perspective available via ColorOS 16")
    }

    private fun Class<*>.method(name: String, vararg types: Class<*>) =
        declaredMethods.singleOrNull { it.name == name && it.parameterTypes.contentEquals(types) }
            ?.apply { isAccessible = true }

    private fun Class<*>.field(name: String, type: Class<*>) =
        declaredFields.singleOrNull { it.name == name && it.type == type }?.apply { isAccessible = true }

    private fun installColorOs16Break(loader:ClassLoader,type:Class<*>,
                                     poses:MutableMap<Any,LegacyBreakPose>) {
        val wrapper = HookReflection.findClassIfExists(
            "com.oplus.wrapper.view.SurfaceControl\$Transaction", loader) ?: return
        val write = wrapper.method("setTransformationMatrix4x4", SurfaceControl.Transaction::class.java,
            SurfaceControl::class.java, FloatArray::class.java) ?: return
        val apply = type.method("applyToTaskLeashForBreak", SurfaceControl::class.java,
            SurfaceControl.Transaction::class.java) ?: return
        val restore = type.method("restoreTaskLeash", SurfaceControl::class.java,
            SurfaceControl.Transaction::class.java) ?: return
        val valid = type.field("isValid", Boolean::class.javaPrimitiveType!!) ?: return
        ModuleHooks.hookMethod(apply, object:HookCallback() {
            override fun afterHookedMethod(param:MethodHookParam) {
                if (param.hasThrowable() || !enabled() || !valid.getBoolean(param.thisObject)) return
                val pose = poses[param.thisObject] ?: return
                val age = System.nanoTime() - pose.time
                if (age !in 0L..250_000_000L) return
                val target = param.args[0] as? SurfaceControl ?: return
                if (!target.isValid) return
                val transaction = param.args[1] as SurfaceControl.Transaction
                try {
                    // The task leash takes over the OEM affine pose. Move our
                    // perspective with it, avoiding a second transform on its child.
                    val source = pose.leash.get()
                    if (source != null && source != target && source.isValid && pitched.contains(source)) {
                        write.invoke(null, transaction, source, identity)
                        pitched.remove(source)
                    }
                    pose.affine?.let { writeWindowAffine(transaction,target,it,true) }
                    write.invoke(null, transaction, target, pose.matrix)
                    pitched.add(target)
                    pose.motion?.let { motion -> synchronized(motions) { motions[target] = motion } }
                } catch (error:Exception) {
                    GlassLogger.animationLimited("AppWindowTilt", "legacyBreakFailure") {
                        "Skipped ColorOS 16 handoff: ${error.javaClass.simpleName}"
                    }
                }
            }
        })
        ModuleHooks.hookMethod(restore, object:HookCallback() {
            override fun afterHookedMethod(param:MethodHookParam) {
                if (param.hasThrowable()) return
                val leash = param.args[0] as? SurfaceControl ?: return
                if (!pitched.contains(leash) || !leash.isValid) return
                write.invoke(null, param.args[1], leash, identity)
                pitched.remove(leash)
                synchronized(motions) { motions.remove(leash) }
            }
        })
        type.method("clear")?.let { clear ->
            ModuleHooks.hookMethod(clear, object:HookCallback() {
                override fun afterHookedMethod(param:MethodHookParam) {
                    poses.remove(param.thisObject)
                }
            })
        }
    }

    private fun installColorOs16Spring(loader: ClassLoader, animationType: Class<*>) {
        val animator = animationType.enclosingClass ?: return
        val property = HookReflection.findClassIfExists(animator.name + "\$RectAnimType", loader) ?: return
        val float = Float::class.javaPrimitiveType!!
        val apply = animator.method("setSpringHolderParamByType", property, float, float) ?: return
        val kindField = animator.field("mAnimType", animationType) ?: return
        ModuleHooks.hookMethod(apply, object : HookCallback() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                // The OEM crossfade uses its own spring and a separate start delay.
                if ((param.args[0] as? Enum<*>)?.name == "RECT_ALPHA") return
                val kind = (kindField.get(param.thisObject) as? Enum<*>)?.name ?: return
                val opening = kind in legacyOpening
                if (!opening && kind !in closing) return
                val frequency = (2 * PI / response(opening)).toFloat()
                param.args[1] = frequency * frequency
                param.args[2] = if (isSwipe(kind) && PrefsHelper.appGestureBounce) .85f else 1f
            }
        })
    }

    private fun installColorOs16Icons(loader:ClassLoader,params:Class<*>,transaction:Class<*>,
                                    builder:Class<*>,setMatrix:Method,
                                    surface:java.lang.reflect.Field):MutableMap<Any,IconBatch>? {
        val updater = HookReflection.findClassIfExists(
            "com.android.quickstep.util.animation.IconLayerUpdater", loader) ?: return null
        val bool=Boolean::class.javaPrimitiveType!!
        val update=updater.method("updateIconLayer",params,bool,RectF::class.java,SurfaceControl::class.java,
            transaction,bool,Matrix::class.java) ?: return null
        val setAlpha=builder.method("setAlpha",Float::class.javaPrimitiveType!!) ?: return null
        val nativeTransaction=transaction.field("mTransaction",SurfaceControl.Transaction::class.java) ?: return null
        val matrix=updater.field("mMatrix",Matrix::class.java) ?: return null
        val crop=updater.field("mIconLayerCrop",Rect::class.java) ?: return null
        val width=updater.field("mIconLayerWidth",Int::class.javaPrimitiveType!!) ?: return null
        val height=updater.field("mIconLayerHeight",Int::class.javaPrimitiveType!!) ?: return null
        val windowAlpha=params.field("alpha",Float::class.javaPrimitiveType!!) ?: return null
        val positionX=params.field("x",Float::class.javaPrimitiveType!!) ?: return null
        val positionY=params.field("y",Float::class.javaPrimitiveType!!) ?: return null
        val scale=params.field("scale",Float::class.javaPrimitiveType!!) ?: return null
        val pending=Collections.synchronizedMap(WeakHashMap<Any,IconBatch>())
        val scope=ThreadLocal<IconPass>()
        ModuleHooks.hookMethod(update,object:HookCallback() {
            override fun beforeHookedMethod(param:MethodHookParam) {
                param.setObjectExtra("priorLegacyIconPass",scope.get())
                scope.remove()
                if(!enabled() || !PrefsHelper.appGenie || PrefsHelper.appGenieStrength==0)return
                val control=param.args[3] as? SurfaceControl ?: return
                if(!control.isValid)return
                val native=nativeTransaction.get(param.args[4]) as? SurfaceControl.Transaction ?: return
                val owner=param.thisObject
                val batch=IconBatch(param.args[0]).apply {
                    windowX=positionX.getFloat(param.args[0])
                    windowY=positionY.getFloat(param.args[0])
                    windowScale=scale.getFloat(param.args[0])
                    time=System.nanoTime()
                }
                scope.set(IconPass(batch,control,matrix.get(owner) as Matrix,crop.get(owner) as Rect,
                    width.getInt(owner),height.getInt(owner),windowAlpha.getFloat(param.args[0]),native))
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val pass=scope.get()
                val prior=param.getObjectExtra("priorLegacyIconPass") as? IconPass
                if(prior==null)scope.remove()else scope.set(prior)
                if(pass==null || param.hasThrowable())return
                // The app update can run asynchronously. Retain only a bounded
                // set and match the transaction, params and affine pose at consumption.
                synchronized(pending) {
                    if(pending.size>=8)pending.clear()
                    pending[param.args[4]]=pass.batch
                }
            }
        })
        ModuleHooks.hookMethod(setAlpha,object:HookCallback() {
            override fun beforeHookedMethod(param:MethodHookParam) {
                val pass=scope.get() ?: return
                if(surface.get(param.thisObject)===pass.leash)pass.alpha=param.args[0] as Float
            }
        })
        ModuleHooks.hookMethod(setMatrix,object:HookCallback() {
            override fun afterHookedMethod(param:MethodHookParam) {
                if(param.hasThrowable())return
                val pass=scope.get() ?: return
                if(surface.get(param.thisObject)!==pass.leash || param.args[0]!==pass.matrix)return
                captureIconPose(pass)
            }
        })
        return pending
    }

    private fun captureIconPose(pass: IconPass) {
        val bounds = if (pass.bounds.isEmpty) Rect(0,0,pass.width,pass.height) else Rect(pass.bounds)
        val affine = Matrix(pass.matrix)
        val writeAffine = writeNativeAffine
        val fit = iconBounds.get()!!
        if (writeAffine != null && fit.normalize(bounds, affine)) {
            val pose = fit.surfaceAffine
            writeAffine.invoke(pass.transaction,pass.leash,pose[0],0f,0f,pose[1])
            pass.transaction.setPosition(pass.leash,pose[2],pose[3])
            affine.setScale(pose[0],pose[1])
            affine.postTranslate(pose[2],pose[3])
        }
        pass.batch.drawnIconAlpha=Float.NaN
        pass.batch.icon = IconTarget(pass.leash,bounds,affine,pass.alpha,pass.windowAlpha,pass.transaction)
        iconFrames[pass.leash]=WeakReference(pass.batch)
    }

    private fun finishIconBatch(batch:IconBatch?,deformation:AppWindowDeformation?,
                                appCrop:Rect,appAffine:Matrix?,radius:Float,
                                appTransaction:SurfaceControl.Transaction?) {
        val icon=batch?.icon ?: return
        val writeAffine = writeNativeAffine ?: return
        if(!icon.leash.isValid)return
        // Replay can use a new transaction. Only the latest native icon frame
        // may join it; a superseded batch must never revive an older icon.
        if(iconFrames[icon.leash]?.get()!==batch)return
        val transaction=appTransaction ?: icon.transaction
        val bounds=icon.bounds
        try {
            val fit=iconBounds.get()!!
            val window=batch.window ?: return
            val followWindow = FlyingIconPose.upperIconFollow(window.row.toFloat())
            val windowAffine = if (appAffine != null && appTransaction != null)
                windowAffine(window,appAffine,appCrop) else appAffine
            val weight = FlyingIconPose.windowFollowWeight(window.progress)
            val contentScale=1f-.04f*weight
            if(windowAffine==null ||
                !fit.fit(bounds,icon.affine,appCrop,windowAffine,radius*weight,deformation,contentScale,followWindow,weight)){
                if(GlassLogger.enabled)GlassLogger.animationLimited("AppWindowTilt","pairFit",1000) {
                    "Icon fit fallback: alpha=${icon.windowAlpha}, icon=$bounds, app=$appCrop, affine=${appAffine!=null}"
                }
                transaction.setAlpha(icon.leash,icon.alpha)
                batch.drawnIconAlpha=Float.NaN
                return
            }
            if(GlassLogger.enabled)GlassLogger.animationLimited("AppWindowTilt","fitPose${batch.window?.opening}",1000) {
                "Icon fit: icon=$bounds affine=${icon.affine} app=$appCrop affine=$appAffine radius=$radius alpha=${icon.windowAlpha} sameTx=${transaction===icon.transaction}"
            }
            val pose = fit.surfaceAffine
            writeAffine.invoke(transaction,icon.leash,pose[0],0f,0f,pose[1])
            transaction.setPosition(icon.leash,pose[2],pose[3])
            val windowAlpha=window.drawnWindowAlpha.takeIf {it.isFinite()}
                ?: FlyingIconPose.windowReveal(icon.windowAlpha,window.progress)
            val iconAlpha=FlyingIconPose.iconReveal(windowAlpha)
            transaction.setAlpha(icon.leash,iconAlpha)
            batch.drawnIconAlpha=iconAlpha
            if(GlassLogger.enabled && loggedHandoff.compareAndSet(false,true))
                GlassLogger.animation("AppWindowTilt","Paired icon/window handoff active in the native transaction")
        }catch(error:Exception){
            GlassLogger.animationLimited("AppWindowTilt","iconMatrixFailure"){ "Skipped icon perspective: ${error.javaClass.simpleName}" }
        }
    }

    private fun cornerRadius(radius:Float,crop:Rect,droplet:Float):Float {
        val limit=minOf(crop.width(),crop.height())*.5f
        val native=radius.takeIf {it.isFinite()}?.coerceIn(0f,limit) ?: limit
        return native+(limit-native)*droplet
    }

    private fun installFrameReplay(loader:ClassLoader,record:Class<*>) {
        val storeName=when(record.name){
            "y2c" -> "e"
            "ni.e" -> "f"
            else -> return
        }
        val replay=record.declaredMethods.singleOrNull { it.name=="a" && it.returnType==Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(SurfaceControl.Transaction::class.java,SurfaceControl::class.java)) } ?: return
        val store=record.declaredMethods.singleOrNull {
            it.name==storeName && it.returnType==Void.TYPE && it.parameterCount==11 && it.parameterTypes[6]==Rect::class.java
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
        val alpha=floatField("e")
        val radius=floatField("f") ?: return
        val angle=floatField("g") ?: return
        val pivotX=floatField("q") ?: return
        val pivotY=floatField("r") ?: return
        val replayAffine=ThreadLocal.withInitial {Matrix()}
        val wrapper=HookReflection.findClassIfExists("com.oplus.wrapper.view.SurfaceControl\$Transaction",loader) ?: return
        val setMatrix=wrapper.declaredMethods.singleOrNull { it.name=="setTransformationMatrix4x4" &&
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(
                SurfaceControl.Transaction::class.java,SurfaceControl::class.java,FloatArray::class.java)) } ?: return
        val deferred=Collections.synchronizedMap(WeakHashMap<Any,Frame>())
        ModuleHooks.hookMethod(store,object:HookCallback(){
            override fun afterHookedMethod(param:MethodHookParam) {
                val current=frame.get()
                if(current==null || current.intensity==0.0 || param.hasThrowable())deferred.remove(param.thisObject)
                else {
                    deferred[param.thisObject]=current
                }
            }
        })
        ModuleHooks.hookMethod(replay,object:HookCallback(){
            override fun afterHookedMethod(param:MethodHookParam) {
                if(param.hasThrowable())return
                val leash=param.args[1] as? SurfaceControl ?: return
                if(!leash.isValid)return
                val current=deferred[param.thisObject]?.takeIf {enabled() && active.getBoolean(param.thisObject)}
                val record=param.thisObject
                val affine=if(current!=null && (current.iconBatch?.icon!=null || current.nativeProgress<.48f ||
                    current.openingOffsetY!=0f || current.closingOffsetY!=0f))replayAffine.get()!!.apply {
                    val zoom=scale.getFloat(record)
                    setScale(zoom,zoom)
                    postTranslate(x.getFloat(record),y.getFloat(record))
                    postRotate(angle.getFloat(record),pivotX.getFloat(record),pivotY.getFloat(record))
                } else null
                val matrix=perspective(leash,current,if(current!=null)crop.get(param.thisObject) as Rect else null,
                    affine,if(affine!=null)radius.getFloat(record) else null,
                    param.args[0] as? SurfaceControl.Transaction,alpha?.getFloat(record) ?: Float.NaN) ?: return
                setMatrix.invoke(null,param.args[0],leash,matrix)
                if(current?.deformation!=null){
                    val tx=param.args[0] as? SurfaceControl.Transaction
                    val bounds=crop.get(record) as Rect
                    if(tx!=null)writeNativeCorner?.invoke(tx,leash,cornerRadius(radius.getFloat(record),bounds,current.droplet))
                }
            }
        })
    }

    private fun installFlyingIcon(loader:ClassLoader,builder:Class<*>,params:Class<*>,transaction:Class<*>,
                                  nativeTransaction:java.lang.reflect.Field) {
        if(builder.name!="cla" || params.name!="l69")return
        val updater=HookReflection.findClassIfExists("ko4",loader) ?: return
        val float=Float::class.javaPrimitiveType
        val bool=Boolean::class.javaPrimitiveType
        val appAlpha=params.declaredFields.singleOrNull {it.name=="b" && it.type==float}
            ?.apply {isAccessible=true} ?: return
        val update=updater.declaredMethods.singleOrNull { method ->
            val p=method.parameterTypes
            method.name=="f" && method.returnType==Void.TYPE && p.size==8 && p[0]==params &&
                p[1]==bool && p[2]==RectF::class.java && p[3]==SurfaceControl::class.java &&
                p[4]==transaction && p[5]==bool && p[6]==Matrix::class.java &&
                p[7].name=="com.oplus.animation.core.util.RotationAnimUtil\$RotationDirection"
        } ?: return
        val setAlpha=builder.declaredMethods.singleOrNull { it.name=="b" && it.returnType==builder &&
            it.parameterTypes.contentEquals(arrayOf(float)) } ?: return
        val surface=builder.declaredFields.singleOrNull {it.type==SurfaceControl::class.java}
            ?.apply {isAccessible=true} ?: return
        // Cancellation/reversal also uses dispatch paths outside ldb.f. Pair
        // both update orders by the native transaction, whose lifetime is the
        // actual frame boundary. Never carry a pose into a reused transaction.
        ModuleHooks.hookAllMethods(SurfaceControl.Transaction::class.java,"apply",object:HookCallback(){
            override fun beforeHookedMethod(param:MethodHookParam){
                synchronized(pendingIconBatches){
                    pendingIconBatches.remove(param.thisObject)?.values?.forEach {it.applied=true}
                }
            }
        })
        val dispatcher=HookReflection.findClassIfExists("ldb",loader)
        val dispatch=dispatcher?.declaredMethods?.singleOrNull {
            val p=it.parameterTypes
            it.name=="f" && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.returnType==Void.TYPE && p.size==6 && p[2]==params && p[3]==RectF::class.java &&
                p[4]==Runnable::class.java && p[5].name=="kotlin.jvm.functions.Function2"
        }
        dispatch?.let { method ->
            ModuleHooks.hookMethod(method,object:HookCallback(){
                override fun beforeHookedMethod(param:MethodHookParam) {
                    param.setObjectExtra("priorIconDispatch",dispatchIconBatch.get())
                    dispatchIconBatch.remove()
                    if(enabled() && PrefsHelper.appGenie && PrefsHelper.appGenieStrength>0)
                        dispatchIconBatch.set(IconBatch(param.args[2]))
                }
                override fun afterHookedMethod(param:MethodHookParam) {
                    val prior=param.getObjectExtra("priorIconDispatch") as? IconBatch
                    if(prior==null)dispatchIconBatch.remove()else dispatchIconBatch.set(prior)
                }
            })
        }
        installIconBatch(updater,update,appAlpha,nativeTransaction)
        ModuleHooks.hookMethod(setAlpha,object:HookCallback(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                val icon=iconPass.get()
                if(icon!=null && surface.get(param.thisObject)===icon.leash){
                    icon.alpha=param.args[0] as Float
                    return
                }
                // The OEM writes alpha after the app matrix, before the corner
                // radius. Match that builder so the icon and other layers keep their timing.
                val current=frame.get() ?: return
                val alpha=param.args[0] as Float
                if(current.intensity<=0.0 || current.radiusTarget!==param.thisObject || !alpha.isFinite())return
                val windowAlpha=FlyingIconPose.windowReveal(alpha,current.progress)
                current.drawnWindowAlpha=windowAlpha
                param.args[0]=windowAlpha
                // IconLayerUpdater can run first with a different stock alpha.
                // Reconcile its alpha with the window in the same transaction.
                val batch=current.iconBatch ?: return
                val pairedIcon=batch.icon ?: return
                val tx=current.transaction ?: return
                val iconAlpha=FlyingIconPose.iconReveal(windowAlpha)
                if(batch.drawnIconAlpha.isFinite() && batch.drawnIconAlpha!=iconAlpha &&
                    pairedIcon.leash.isValid && iconFrames[pairedIcon.leash]?.get()===batch) {
                    tx.setAlpha(pairedIcon.leash,iconAlpha)
                    batch.drawnIconAlpha=iconAlpha
                }
            }
        })
    }

    private fun installIconBatch(updater:Class<*>,update:Method,
                                 appAlpha:java.lang.reflect.Field,nativeTransaction:java.lang.reflect.Field) {
        fun field(name:String,type:Class<*>?)=updater.declaredFields.singleOrNull {it.name==name && it.type==type}
            ?.apply {isAccessible=true}
        val matrix=field("e",Matrix::class.java) ?: return
        val crop=field("d",Rect::class.java) ?: return
        val width=field("g",Int::class.javaPrimitiveType) ?: return
        val height=field("h",Int::class.javaPrimitiveType) ?: return
        ModuleHooks.hookMethod(update,object:HookCallback(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                param.setObjectExtra("priorGenieIconPass",iconPass.get())
                iconPass.remove()
                if(!enabled() || !PrefsHelper.appGenie || PrefsHelper.appGenieStrength==0)return
                val leash=param.args[3] as? SurfaceControl ?: return
                if(!leash.isValid)return
                val transaction=nativeTransaction.get(param.args[4]) as? SurfaceControl.Transaction ?: return
                val batch=pairBatch(transaction,param.args[0]).apply {time=System.nanoTime()}
                val owner=param.thisObject
                iconPass.set(IconPass(batch,leash,matrix.get(owner) as Matrix,crop.get(owner) as Rect,
                    width.getInt(owner),height.getInt(owner),appAlpha.getFloat(param.args[0]),transaction))
            }
            override fun afterHookedMethod(param:MethodHookParam) {
                val pass=iconPass.get()
                if(GlassLogger.enabled)GlassLogger.animationLimited("AppWindowTilt","pairCapture",1000) {
                    "Icon capture: tx=${pass?.transaction?.let(System::identityHashCode)}, params=${pass?.batch?.params?.let(System::identityHashCode)}, captured=${pass?.batch?.icon!=null}, error=${param.hasThrowable()}"
                }
                val prior=param.getObjectExtra("priorGenieIconPass") as? IconPass
                if(prior==null)iconPass.remove()else iconPass.set(prior)
                if(pass!=null && !param.hasThrowable() && pass.batch.icon!=null){
                    // Finish while the OEM is still building this transaction,
                    // not after the dispatch may already have applied it.
                    val window=pass.batch.window
                    if(window!=null && window.evaluated)finishIconBatch(pass.batch,window.deformation,
                        window.appliedCrop ?: window.crop,window.appliedAffine ?: window.matrix,
                        window.appliedRadius,window.transaction)
                }
            }
        })
    }

    private fun pairBatch(transaction:SurfaceControl.Transaction,params:Any):IconBatch = synchronized(pendingIconBatches){
        if(pendingIconBatches.size>=8 && transaction !in pendingIconBatches)pendingIconBatches.clear()
        val candidates=pendingIconBatches.getOrPut(transaction){HashMap()}
        val now=System.nanoTime()
        candidates.entries.removeAll {it.value.applied || now-it.value.time !in 0L..50_000_000L}
        val scoped=dispatchIconBatch.get()?.takeUnless {it.applied}
        val batch=scoped ?: candidates[params] ?: candidates.values.singleOrNull() ?: IconBatch(params)
        // Copies of TransformParams are common on the interrupted close path.
        // A unique icon/window pair in this transaction is the same frame.
        candidates[batch.params]=batch
        batch
    }

    private fun enabled()=PrefsHelper.enabled && PrefsHelper.appWindowTilt && ValueAnimator.areAnimatorsEnabled()

    private fun installWorkspaceBlur(loader:ClassLoader) {
        val controller=HookReflection.findClassIfExists(
            "com.android.launcher3.uioverrides.states.OplusDepthController",loader) ?: return
        val blur=controller.method("setBlur",Float::class.javaPrimitiveType!!,Boolean::class.javaPrimitiveType!!) ?: return
        ModuleHooks.hookMethod(blur,object:HookCallback(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                val sample=workspaceTransition ?: return
                if (System.nanoTime()-sample.time !in 0L..80_000_000L ||
                    !enabled() || !PrefsHelper.appGenie || PrefsHelper.appGenieStrength==0) return
                val native=param.args[0] as Float
                if (native in 0f..1f) param.args[0]=FlyingIconPose.workspaceBlur(native,sample.progress)
            }
        })
    }

    private fun isSwipe(kind:String)=kind=="SWIPE_TO_HOME" || kind=="SWIPE_TO_CLIENT_HOME"

    private fun response(opening:Boolean):Double {
        val seconds=if(opening)PrefsHelper.appOpenResponse*.001 else .336/(PrefsHelper.appCloseSpeed*.01)
        return seconds*PrefsHelper.appAnimationSlowdown*.01
    }

    private fun installDroplet(builder:Class<*>,surface:java.lang.reflect.Field) {
        if(builder.name !in setOf("cla","xi.q\$b","xi.s\$b"))return
        val float=Float::class.javaPrimitiveType
        val smooth=builder.declaredMethods.singleOrNull { method ->
            method.name=="f" && method.returnType==Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(float,float,Int::class.javaPrimitiveType))
        } ?: return
        val round=builder.declaredMethods.singleOrNull { method ->
            method.name=="d" && method.returnType==builder && method.parameterTypes.contentEquals(arrayOf(float))
        } ?: return
        val hook=object:HookCallback(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                iconPass.get()?.let { pass ->
                    if(surface.get(param.thisObject)===pass.leash){
                        return
                    }
                }
                val current=frame.get() ?: return
                if(current.radiusTarget!==param.thisObject || current.crop.isEmpty)return
                current.radiusTarget=null
                val radius=param.args[0] as Float
                val target=minOf(current.crop.width(),current.crop.height())*.5f
                if(radius.isFinite() && radius>=0f && radius<target)
                    param.args[0]=radius+(target-radius)*current.droplet
                current.appliedRadius=param.args[0] as Float
                param.setObjectExtra("tiltCornerFrame",current)
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.hasThrowable())return
                val current=param.getObjectExtra("tiltCornerFrame") as? Frame ?: return
                val leash=surface.get(param.thisObject) as? SurfaceControl ?: return
                val tx=current.transaction
                if(current.deformation!=null && tx!=null && leash.isValid && current.appliedRadius.isFinite() && current.appliedRadius>=0f)
                    writeNativeCorner?.invoke(tx,leash,current.appliedRadius)
                // Conversely, the icon can arrive before the window's final
                // corner radius. Keep releasing its extra clip even after the
                // perspective is too small to be visible in the final frames.
                finishIconBatch(current.iconBatch,current.deformation,
                    current.appliedCrop ?: current.crop,current.appliedAffine ?: current.matrix,current.appliedRadius,tx)
            }
        }
        ModuleHooks.hookMethod(smooth,hook)
        ModuleHooks.hookMethod(round,hook)
    }

    private fun installSpringTuning(loader:ClassLoader,animationType:Class<*>) {
        val modern=HookReflection.findClassIfExists("nj2",loader)
        val animator=modern ?: HookReflection.findClassIfExists(
            "com.android.quickstep.util.animation.CustomRectFSpringAnim",loader) ?: return
        val spring=HookReflection.findClassIfExists(if(modern!=null)"h8a" else "vi.f",loader) ?: return
        val setupNames=if(modern!=null)setOf("E","H") else setOf("V","X")
        val kind=animator.declaredFields.singleOrNull {it.name=="v0" && it.type==animationType}
            ?.apply {isAccessible=true} ?: return
        if(animator.declaredFields.count {it.type==spring}!=7)return
        val setup=animator.declaredMethods.filter {
            it.name in setupNames && it.parameterCount==0 && it.returnType==Void.TYPE
        }
        if(setup.size!=2)return
        val float=Float::class.javaPrimitiveType
        val setters=spring.declaredMethods.filter {
            it.name in setOf("b","c") && it.parameterTypes.contentEquals(arrayOf(float)) && it.returnType==Void.TYPE
        }
        if(setters.size!=2)return
        val tuning=ThreadLocal<SpringTuning>()
        val scope=object:HookCallback(){
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
            ModuleHooks.hookMethod(method,object:HookCallback(){
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
            val owner=HookReflection.findClassIfExists("com.android.launcher3.anim.$name",loader) ?: continue
            for(method in owner.declaredMethods){
                if(method.name!="startAppLaunchWindowAnim" && method.name!="startAppCloseWindowAnim")continue
                val index=method.parameterTypes.indexOfFirst {View::class.java.isAssignableFrom(it)}
                if(index<0)continue
                ModuleHooks.hookMethod(method,object:HookCallback(){
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
                        pendingSource.set(Source(location[0]+x,location[1]+y,
                            icon?.width()?.toFloat() ?: view.width.toFloat(),measuredIcon=icon!=null))
                        if (method.name=="startAppLaunchWindowAnim" && enabled() && PrefsHelper.appGenie) {
                            val now=System.nanoTime()
                            val active=workspaceTransition
                            if (active==null || now-active.time>80_000_000L)
                                workspaceTransition=WorkspaceTransition(0f,now)
                        }
                    }
                    override fun afterHookedMethod(param:MethodHookParam) {
                        val prior=param.getObjectExtra("priorTiltSource") as? Source
                        if(prior==null)pendingSource.remove()else pendingSource.set(prior)
                    }
                })
            }
        }
        ModuleHooks.hookAllConstructors(helper,object:HookCallback(){
            override fun afterHookedMethod(param:MethodHookParam){
                pendingSource.get()?.let {sources[param.thisObject]=it}
            }
        })
    }

}
