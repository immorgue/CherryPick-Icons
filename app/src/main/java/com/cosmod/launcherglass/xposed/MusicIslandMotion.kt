package com.cosmod.launcherglass.xposed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.view.animation.LinearInterpolator
import com.cosmod.launcherglass.util.IslandSpring
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.bridge.HookCallback
import com.cosmod.launcherglass.bridge.HookRuntime
import com.cosmod.launcherglass.bridge.HookReflection
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** One interruptible morph inside the OEM task graph, retaining its completion/cancel lifecycle. */
internal object MusicIslandMotion {
    private class State(val outline:ViewOutlineProvider?,val clip:Boolean,val bounds:Rect?,val foreground:Drawable?){
        var progress=0f
        var velocity=0f
        var heightProgress=0f
        var heightVelocity=0f
        var retargeting=false
        var openingVisual=false
        var cancelled=false
        var writingAlpha=false
        var opacity=1f
        var contentOpacity=1f
        var content:WeakReference<View>?=null
        var animator:ValueAnimator?=null
        val rect=RectF()
        var radius=0f
        val provider=object:ViewOutlineProvider(){
            override fun getOutline(view:View,out:Outline){
                out.setRoundRect(rect.left.toInt(),rect.top.toInt(),rect.right.toInt(),rect.bottom.toInt(),radius)
            }
        }
    }
    private val states=WeakHashMap<View,State>()
    private val curve=PathInterpolator(.22f,.75f,.18f,1f)
    private val status=WeakHashMap<View,Float>()
    private var lastClosed=0L
    fun canCaptureAnchor()=states.isEmpty() && android.os.SystemClock.uptimeMillis()-lastClosed>350L
    private var nativeStateType:Class<*>?=null
    private val setTransition=try {View::class.java.getDeclaredMethod("setTransitionAlpha",Float::class.javaPrimitiveType).apply {isAccessible=true}}catch(_:Throwable){null}
    private val getTransition=try {View::class.java.getDeclaredMethod("getTransitionAlpha").apply {isAccessible=true}}catch(_:Throwable){null}
    fun ownsRunningMotion(card:View)=states[card]?.let {it.retargeting || (!it.cancelled && it.animator?.isRunning==true)}==true

    fun captureStatusBar(root:View){
        fun visit(view:View,depth:Int){
            if(depth>14)return
            val name=try {view.resources.getResourceEntryName(view.id)}catch(_:Throwable){""}
            if(name in setOf("clock","system_icons","notification_icon_area")){
                if(!status.containsKey(view))status[view]=try {(getTransition?.invoke(view) as? Float) ?: view.alpha}catch(_:Throwable){view.alpha}
                return
            }
            if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i),depth+1)
        }
        visit(root,0)
    }

    private fun statusOpacity(value:Float){
        status.forEach {(view,base)->
            if(setTransition!=null)try {setTransition.invoke(view,base*value)}catch(_:Throwable){}
            else view.alpha=base*value
        }
    }

    fun install(loader:ClassLoader,enabled:()->Boolean,anchor:()->Rect?,media:(View)->Boolean){
        val prefix="com.oplus.systemui.plugins.seedling."
        val graph=HookReflection.findClassIfExists(prefix+"engine.animatorengine.h",loader) ?: return
        val fragment=HookReflection.findClassIfExists(prefix+"card.animation.b",loader) ?: return
        nativeStateType=HookReflection.findClassIfExists(prefix+"capsule.animation.c",loader)
        // A real OEM fragment retains the engine's view ownership and interruption bookkeeping.
        HookRuntime.hookAllMethods(fragment,"g",object:HookCallback(){
            override fun beforeHookedMethod(p:MethodHookParam){
                @Suppress("UNCHECKED_CAST")
                val payload=HookReflection.getAdditionalInstanceField(p.thisObject,"CherryPickMusicMorph") as? Array<Any> ?: return
                p.result=animate(payload[0] as View,payload[1] as View,payload[2] as Rect,payload[3] as Boolean)
                GlassLogger.i("MusicIsland","fragment animator created")
            }
        })
        @Suppress("UNCHECKED_CAST")
        fun parts(scene: Any) = HookReflection.getObjectField(scene, "o") as MutableList<Any>

        for((name,opening) in listOf("H" to true,"G" to true,"I" to false,"k" to false)){
            val factory=HookReflection.findClassIfExists(prefix+"card.animation."+name,loader) ?: continue
            val build=factory.declaredMethods.singleOrNull {it.name=="k" && it.parameterCount==0 && it.returnType==graph} ?: continue
            HookRuntime.hookMethod(build,object:HookCallback(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(!enabled() || p.hasThrowable())return
                    val target=HookReflection.getObjectField(p.thisObject,"b")
                    val card=(if(name=="k") (target as? List<*>)?.singleOrNull() else target) as? View ?: return
                    val content=try {HookReflection.callMethod(card,"getContentView") as? View}catch(_:Throwable){null} ?: return
                    val source=anchor() ?: return
                    GlassLogger.i("MusicIsland","factory $name media=${media(content)} size=${card.measuredWidth}x${card.measuredHeight}")
                    if(!media(content) || card.measuredWidth<=0 || card.measuredHeight<=0)return
                    val root=p.result ?: return
                    // The interrupted close factory returns a parent graph. Keep its
                    // listeners and replace only the fragments belonging to the music card.
                    val scene=if(name=="k") parts(root).singleOrNull()?.takeIf(graph::isInstance) ?: return else root
                    val part=HookReflection.newInstance(fragment,card,1f,1f,420L,curve,0)
                    HookReflection.setAdditionalInstanceField(part,"CherryPickMusicMorph",arrayOf(card,content,source,opening))
                    parts(scene).apply {clear();add(part)}
                    // Native factories create an opaque black foreground while building discarded
                    // fragments. Restore it before returning, not later when the chosen animator starts.
                    card.foreground=null
                }
            })
        }
        val card=HookReflection.findClassIfExists(prefix+"card.ui.view.CardView",loader) ?: return
        HookRuntime.hookAllMethods(card,"dispatchTouchEvent",object:HookCallback(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? ViewGroup ?: return
                val state=states[view] ?: return
                val event=p.args.firstOrNull() as? MotionEvent ?: return
                if(event.actionMasked!=MotionEvent.ACTION_UP || !state.openingVisual ||
                    state.progress<.985f || state.heightProgress<.985f)return
                val visual=state.animator?.takeIf {it.isRunning} ?: return
                if(MusicIslandMaterial.isTrackTitleHit(view,event.x,event.y)){
                    // Hand the settled pose to the native app launch before it snapshots the card.
                    visual.end()
                }
            }
        })
        HookRuntime.hookAllMethods(card,"dispatchDraw",object:HookCallback(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                val state=states[view] ?: return
                if(state.cancelled && !state.retargeting)return
                // Discard stale native dimming writes at the paint boundary. The native
                // interrupted scene may finish after our replacement has already started.
                if(view.foreground!=null){
                    GlassLogger.i("MusicIsland","discard stale foreground")
                    view.foreground=null
                }
                if(view.outlineProvider!==state.provider || !view.clipToOutline){
                    view.outlineProvider=state.provider;view.clipToOutline=true;view.invalidateOutline()
                }
                state.content?.get()?.let {if(it.alpha!=state.contentOpacity)it.alpha=state.contentOpacity}
            }
        })
        HookRuntime.hookAllMethods(card,"setAlpha",object:HookCallback(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                val state=states[view] ?: return
                if(!state.writingAlpha && ownsRunningMotion(view))p.args[0]=state.opacity
            }
        })
        HookReflection.findClassIfExists(prefix+"animatorscenes.I",loader)?.let {listener->
            HookRuntime.hookAllMethods(listener,"onAnimationEnd",object:HookCallback(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    val view=try {HookReflection.getObjectField(p.thisObject,"b") as? View}catch(_:Throwable){null} ?: return
                    // Completion of a cancelled scene must not hide/reset its replacement.
                    if(ownsRunningMotion(view))p.result=null
                }
            })
        }
        HookRuntime.hookAllMethods(card,"g",object:HookCallback(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val state=states[p.thisObject as? View] ?: return
                if(!state.cancelled || state.retargeting)p.result=null
            }
        })
        for(method in listOf("f","k"))HookRuntime.hookAllMethods(card,method,object:HookCallback(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                if(ownsRunningMotion(view))p.result=null
            }
        })
        HookRuntime.hookAllMethods(card,"j",object:HookCallback(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                cleanup(view)
            }
        })
        HookRuntime.hookAllMethods(card,"onDetachedFromWindow",object:HookCallback(){
            override fun afterHookedMethod(p:MethodHookParam){cleanup(p.thisObject as? View ?: return)}
        })
        HookRuntime.hookAllMethods(card,"onLayout",object:HookCallback(){
            override fun afterHookedMethod(p:MethodHookParam){
                val view=p.thisObject as? View ?: return
                val state=states[view] ?: return
                if(state.animator==null && state.progress>.999f){
                    state.rect.set(0f,0f,view.width.toFloat(),view.height.toFloat())
                    state.radius=MusicIslandMaterial.radiusPixels(view)
                    MusicIslandMaterial.updateBlurGeometry(view,state.rect,state.radius,1f)
                    view.invalidateOutline()
                }
            }
        })
    }

    private fun animate(card:View,content:View,source:Rect,opening:Boolean):ValueAnimator {
        val state=states.getOrPut(card){State(card.outlineProvider,card.clipToOutline,card.clipBounds?.let(::Rect),card.foreground).apply {
            progress=if(opening)0f else 1f
            heightProgress=progress
        }}
        state.retargeting=true
        state.animator?.cancel()
        state.retargeting=false
        state.cancelled=false
        state.openingVisual=opening
        state.content=WeakReference(content)
        card.scaleX=1f;card.scaleY=1f;card.translationX=0f;card.translationY=0f;card.alpha=1f
        card.foreground=null;card.clipBounds=null
        content.setRenderEffect(null)
        val position=IntArray(2);card.getLocationOnScreen(position)
        val collapsed=RectF(source).apply {offset(-position[0].toFloat(),-position[1].toFloat())}
        val width=card.width.takeIf {it>0} ?: card.measuredWidth
        val height=card.height.takeIf {it>0} ?: card.measuredHeight
        val from=state.progress
        val to=if(opening)1f else 0f
        val spring=IslandSpring(from,if(opening)state.velocity else state.velocity.coerceAtMost(0f),to,opening)
        val heightSpring=IslandSpring(state.heightProgress,
            if(opening)state.heightVelocity else state.heightVelocity.coerceAtMost(0f),to,opening,if(opening)1.0 else 1.35)
        val nativeState=nativeStateType?.let {HookReflection.newInstance(it,width.toFloat(),height.toFloat())}
        if(nativeState!=null)HookReflection.callMethod(card,"setAnimatorState",nativeState)
        fun smooth(value:Float):Float=value.coerceIn(0f,1f).let {it*it*(3-2*it)}
        fun frame(progress:Float,heightProgress:Float){
            state.progress=progress
            state.heightProgress=heightProgress
            val horizontal=progress.coerceIn(0f,1f)
            val vertical=heightProgress.coerceIn(0f,1f)
            val currentWidth=card.width.takeIf {it>0} ?: width
            val currentHeight=card.height.takeIf {it>0} ?: height
            val endRadius=MusicIslandMaterial.radiusPixels(card)
            // The top stays attached to the capsule; the bottom retreats first.
            state.rect.set(collapsed.left*(1-horizontal),collapsed.top*(1-vertical),
                collapsed.right+(currentWidth-collapsed.right)*horizontal,collapsed.bottom+(currentHeight-collapsed.bottom)*vertical)
            state.radius=(collapsed.height()/2+(endRadius-collapsed.height()/2)*vertical).coerceAtLeast(0f)
            val overshoot=(progress-1f).coerceAtLeast(0f)
            card.pivotX=currentWidth/2f;card.pivotY=0f
            card.scaleX=1+overshoot;card.scaleY=1+(heightProgress-1f).coerceAtLeast(0f)*.7f
            card.outlineProvider=state.provider;card.clipToOutline=true;card.invalidateOutline()
            val reveal=smooth((progress-.12f)/.76f)
            state.contentOpacity=minOf(reveal,smooth((vertical-.32f)/.62f))
            content.alpha=state.contentOpacity
            // Fade into the native capsule only once the widths are almost equal.
            state.opacity=smooth((horizontal-.01f)/.09f)
            MusicIslandMaterial.updateBlurGeometry(card,state.rect,state.radius,smooth((vertical-.10f)/.80f))
            state.writingAlpha=true
            try {card.alpha=state.opacity}finally {state.writingAlpha=false}
            if(nativeState!=null){
                HookReflection.setFloatField(nativeState,"a",state.rect.width())
                HookReflection.setFloatField(nativeState,"b",state.rect.height())
                HookReflection.setFloatField(nativeState,"c",0f)
                HookReflection.setFloatField(nativeState,"d",0f)
            }
            val hide=(progress/.3f).coerceIn(0f,1f)
            statusOpacity(1-hide*hide*(3-2*hide))
            card.invalidate()
        }
        frame(from,state.heightProgress)
        GlassLogger.i("MusicIsland","animate opening=$opening from=$from to=$to position=${position.contentToString()} source=$collapsed")
        val visual=ValueAnimator.ofFloat(0f,1f).apply {
            duration=if(opening)1100L else 480L
            interpolator=LinearInterpolator()
            addUpdateListener {
                // OEM AnimatorSet drives child fractions directly; getCurrentPlayTime() stays
                // zero on this path. The animated value is the actual linear timeline.
                val seconds=(it.animatedValue as Float)*it.duration/1000.0
                state.velocity=spring.velocity(seconds)
                state.heightVelocity=heightSpring.velocity(seconds)
                frame(spring.position(seconds),heightSpring.position(seconds))
            }
            addListener(object:AnimatorListenerAdapter(){
                private var cancelled=false
                override fun onAnimationStart(animation:Animator){GlassLogger.i("MusicIsland","spring start duration=$duration")}
                override fun onAnimationCancel(animation:Animator){cancelled=true;state.cancelled=true}
                override fun onAnimationEnd(animation:Animator){
                    GlassLogger.i("MusicIsland","spring end cancelled=$cancelled progress=${state.progress} time=$currentPlayTime")
                    if(cancelled){
                        // Keep the last position/velocity until the replacement scene takes over.
                    }else if(state.animator===animation)state.animator=null
                    if(!cancelled){
                        state.velocity=0f;state.heightVelocity=0f;frame(to,to)
                        if(opening)content.alpha=1f
                    }
                }
            })
            state.animator=this
        }
        if(!opening)return visual
        // The spring tail is visual only. Holding the native request open for its full
        // settling time queues user taps in ColorOS and replays obsolete open/close tasks.
        return ValueAnimator.ofFloat(0f,1f).apply {
            duration=400L
            interpolator=LinearInterpolator()
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationStart(animation:Animator){
                    if(states[card]===state && state.animator===visual)visual.start()
                }
                override fun onAnimationCancel(animation:Animator){
                    if(state.animator===visual)visual.cancel()
                }
            })
        }
    }

    private fun cleanup(card:View){
        val state=states.remove(card) ?: return
        lastClosed=android.os.SystemClock.uptimeMillis()
        state.animator?.cancel()
        // Native removal can complete a frame after its animation. Never expose the full card
        // while restoring its reusable outline/content for that pending removal.
        card.alpha=0f;card.scaleX=1f;card.scaleY=1f
        card.outlineProvider=state.outline;card.clipToOutline=state.clip;card.clipBounds=state.bounds
        card.foreground=null;card.invalidateOutline()
        try {(HookReflection.callMethod(card,"getContentView") as? View)?.alpha=1f}catch(_:Throwable){}
        statusOpacity(1f)
        status.clear()
    }
}
