package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Full drag-layer canvas: OEM effects must not crop an expanded dock to the hotseat cell. */
internal class DockLayer private constructor(owner:ViewGroup,root:ViewGroup,
    private val dockBounds:()->Rect,
    private val paintDock:(ViewGroup,Canvas)->Boolean):View(root.context) {
    private val owner=WeakReference(owner)
    private val root=WeakReference(root)
    private val pose=Matrix()
    // Reused across frames: a stationary launcher must not allocate here.
    private val world=Matrix()
    private val parentWorld=Matrix()
    private val inverse=Matrix()
    private val next=Matrix()
    private val posedBounds=RectF()
    private var cropTop=0
    private var effectKey=emptyList<RenderEffect>()
    private var observer:ViewTreeObserver?=null
    private val listener=ViewTreeObserver.OnPreDrawListener {sync();true}
    var active=true
    var retainWhenHidden=false
    private var retainedOpacity=1f
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable=false;isFocusable=false
        setWillNotDraw(false)
    }
    fun sync(){
        val owner=owner.get();val root=root.get()
        if(owner==null || root==null || !owner.isAttachedToWindow){
            visibility=GONE
            if(parent!=null)post {(parent as? ViewGroup)?.removeView(this)}
            return
        }
        visibility=if(active && (owner.isShown || retainWhenHidden))VISIBLE else INVISIBLE
        if(visibility!=VISIBLE)return
        world.reset()
        XposedHelpers.callMethod(owner,"transformMatrixToGlobal",world)
        // RenderEffect uses the View's buffer bounds, even if only a small
        // dock is painted in it. Keep horizontal room for expanded docks,
        // but do not allocate/filter a screen-high transparent input.
        val bounds=dockBounds()
        if(!PrefsHelper.compactDockEffects)cropTop=0
        else if(!bounds.isEmpty && root.width>0 && root.height>0 && !retainWhenHidden){
            var restingTop=bounds.top
            var ancestor:View?=owner
            while(ancestor!=null && ancestor!==root){
                val parent=ancestor.parent as? View
                restingTop+=ancestor.top-(parent?.scrollY ?: 0)
                ancestor=parent
            }
            val margin=maxOf(256,(128f*resources.displayMetrics.density).toInt(),bounds.height()/2)
            parentWorld.reset();inverse.reset()
            XposedHelpers.callMethod(root,"transformMatrixToGlobal",parentWorld)
            posedBounds.set(bounds)
            world.mapRect(posedBounds)
            if(parentWorld.invert(inverse))inverse.mapRect(posedBounds)
            else posedBounds.top=0f
            cropTop=DockEffectBounds.top(root.height,restingTop.toFloat(),posedBounds.top,margin)
        }
        val targetHeight=(root.height-cropTop).coerceAtLeast(1)
        if(width!=root.width || height!=targetHeight){
            layoutParams?.let {it.width=root.width;it.height=targetHeight}
            layout(left,top,left+root.width,top+targetHeight)
        }
        translationX=-left.toFloat()
        translationY=(cropTop-top).toFloat()
        // transformMatrixToGlobal() post-multiplies onto the given matrix, so
        // the reused buffers must start from identity every frame.
        parentWorld.reset();inverse.reset()
        // This view can be inset by DragLayer padding. Its Canvas is local
        // to the actual layer, not to its parent; otherwise padding was added
        // twice and the plate appeared below the icon centres.
        XposedHelpers.callMethod(this,"transformMatrixToGlobal",parentWorld)
        if(!parentWorld.invert(inverse))return
        next.setConcat(inverse,world)
        if(!retainWhenHidden && next!=pose){pose.set(next);invalidate()}
        var opacity=1f
        var chain:ArrayList<RenderEffect>?=null
        var current:View?=owner
        while(current!=null && current!==root){
            opacity*=current.alpha
            // Only pay for a chain when an OEM effect actually exists.
            effects[current]?.let {effect ->
                (chain ?: ArrayList<RenderEffect>(4).also {chain=it}).add(effect)
            }
            current=current.parent as? View
        }
        if(!retainWhenHidden)retainedOpacity=opacity.coerceIn(0f,1f)
        alpha=if(retainWhenHidden)retainedOpacity else opacity.coerceIn(0f,1f)
        val effectsNow=chain
        if(effectsNow==null){
            if(effectKey.isNotEmpty()){setRenderEffect(null);effectKey=emptyList()}
        }else if(effectsNow!=effectKey){
            var combined:RenderEffect?=null
            for(effect in effectsNow)combined=combined?.let {RenderEffect.createChainEffect(effect,it)} ?: effect
            setRenderEffect(combined);effectKey=effectsNow
        }
    }
    override fun onDraw(canvas:Canvas){
        val owner=owner.get() ?: return
        val save=canvas.save()
        try {canvas.concat(pose);paintDock(owner,canvas)}finally{canvas.restoreToCount(save)}
    }
    override fun onAttachedToWindow(){
        super.onAttachedToWindow()
        observer=viewTreeObserver.also {it.addOnPreDrawListener(listener)}
        sync()
    }
    override fun onDetachedFromWindow(){
        observer?.takeIf {it.isAlive}?.removeOnPreDrawListener(listener);observer=null
        super.onDetachedFromWindow()
    }
    companion object {
        private val effects=WeakHashMap<View,RenderEffect>()
        private var installed=false
        fun install(){
            if(installed)return
            installed=true
            // Preserve OEM unlock/drawer effects on the larger canvas. The
            // effect still runs, but its input is no longer the small hotseat.
            ModuleHooks.hookAllMethods(View::class.java,"setRenderEffect",object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as? View ?: return
                    if(view is DockLayer)return
                    val effect=param.args.firstOrNull() as? RenderEffect
                    if(effect==null)effects.remove(view)else effects[view]=effect
                }
            })
        }
        fun attach(owner:ViewGroup,bounds:()->Rect,paint:(ViewGroup,Canvas)->Boolean):DockLayer? {
            val root=generateSequence(owner.parent as? ViewGroup){it.parent as? ViewGroup}
                .firstOrNull {it.javaClass.name.contains("DragLayer")} ?: return null
            val layer=DockLayer(owner,root,bounds,paint)
            val params=(XposedHelpers.callMethod(root,"generateDefaultLayoutParams") as? ViewGroup.LayoutParams) ?: return null
            params.width=ViewGroup.LayoutParams.MATCH_PARENT;params.height=ViewGroup.LayoutParams.MATCH_PARENT
            root.addView(layer,0,params)
            GlassLogger.i("Dock","Expanded dock layer attached to ${root.javaClass.simpleName}: ${root.width}x${root.height}")
            return layer
        }
    }
}
