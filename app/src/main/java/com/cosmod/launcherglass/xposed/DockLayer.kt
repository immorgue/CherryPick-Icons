package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RenderEffect
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
    private val paintDock:(ViewGroup,Canvas)->Boolean):View(root.context) {
    private val owner=WeakReference(owner)
    private val root=WeakReference(root)
    private val pose=Matrix()
    private var effectKey=emptyList<RenderEffect>()
    private var observer:ViewTreeObserver?=null
    private val listener=ViewTreeObserver.OnPreDrawListener {sync();true}
    var active=true
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
        visibility=if(active && owner.isShown)VISIBLE else INVISIBLE
        if(visibility!=VISIBLE)return
        val world=Matrix();val parentWorld=Matrix();val inverse=Matrix()
        XposedHelpers.callMethod(owner,"transformMatrixToGlobal",world)
        // This view can be inset by DragLayer padding. Its Canvas is local
        // to the actual layer, not to its parent; otherwise padding was added
        // twice and the plate appeared below the icon centres.
        XposedHelpers.callMethod(this,"transformMatrixToGlobal",parentWorld)
        if(!parentWorld.invert(inverse))return
        val next=Matrix().apply {setConcat(inverse,world)}
        if(next!=pose){pose.set(next);invalidate()}
        var opacity=1f
        val chain=ArrayList<RenderEffect>()
        var current:View?=owner
        while(current!=null && current!==root){
            opacity*=current.alpha
            effects[current]?.let {chain.add(it)}
            current=current.parent as? View
        }
        alpha=opacity.coerceIn(0f,1f)
        if(chain!=effectKey){
            var combined:RenderEffect?=null
            for(effect in chain)combined=combined?.let {RenderEffect.createChainEffect(effect,it)} ?: effect
            setRenderEffect(combined);effectKey=chain
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
        fun attach(owner:ViewGroup,paint:(ViewGroup,Canvas)->Boolean):DockLayer? {
            val root=generateSequence(owner.parent as? ViewGroup){it.parent as? ViewGroup}
                .firstOrNull {it.javaClass.name.contains("DragLayer")} ?: return null
            val layer=DockLayer(owner,root,paint)
            val params=(XposedHelpers.callMethod(root,"generateDefaultLayoutParams") as? ViewGroup.LayoutParams) ?: return null
            params.width=ViewGroup.LayoutParams.MATCH_PARENT;params.height=ViewGroup.LayoutParams.MATCH_PARENT
            root.addView(layer,0,params)
            GlassLogger.i("Dock","Expanded dock layer attached to ${root.javaClass.simpleName}: ${root.width}x${root.height}")
            return layer
        }
    }
}
