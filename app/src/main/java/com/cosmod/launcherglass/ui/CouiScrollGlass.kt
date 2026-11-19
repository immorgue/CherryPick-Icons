package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.widget.NestedScrollView

/** Navigation edge and a separate weak tail. Never samples or filters the preview itself. */
internal class CouiScrollGlass(context:Context,private val source:NestedScrollView,private val surface:Int):View(context) {
    var scrollingTitle:View?=null
    var titleCoverage=0f
    private val density=resources.displayMetrics.density
    private val extra=(40*density).toInt()
    private val contentNode=RenderNode("CherryPick.uiHeaderContent")
    private val backdrop=RenderNode("CherryPick.uiHeaderBlur").apply {
        setRenderEffect(RenderEffect.createBlurEffect(16*density,16*density,Shader.TileMode.CLAMP))
    }
    private val softBackdrop=RenderNode("CherryPick.uiHeaderSoftBlur").apply {
        setRenderEffect(RenderEffect.createBlurEffect(8*density,8*density,Shader.TileMode.CLAMP))
    }
    private val fullMask=Paint().apply {blendMode=BlendMode.DST_IN}
    private val softMask=Paint().apply {blendMode=BlendMode.DST_IN}
    private val previewShape=RenderNode("CherryPick.uiPreviewFalloff").apply {
        setRenderEffect(RenderEffect.createBlurEffect(16*density,16*density,Shader.TileMode.DECAL))
        setUseCompositingLayer(true,Paint().apply {blendMode=BlendMode.DST_IN})
    }
    private val previewPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=0xb8ffffff.toInt()}
    private val previewPath=Path()
    private val previewBounds=RectF()
    private val previewFalloffBounds=RectF()
    private var previewHost:PinnedAppearancePreview?=null
    private val obscuring=Paint()
    private val titleLocation=IntArray(2)
    private val ownLocation=IntArray(2)
    private var statusBottom=0f
    private var navigationBottom=0f
    private var previewBottom=-1f
    private val navigationEnd get()=navigationBottom+12*density
    private val previewEnd get()=previewBottom+64*density
    fun preview(host:PinnedAppearancePreview,left:Float,top:Float,height:Float){
        previewHost=host
        previewBounds.set(left,top,width-left,top+height)
    }
    private val sourceInvalidation=ViewTreeObserver.OnPreDrawListener {
        if(source.isDirty)invalidate()
        true
    }
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}

    fun regions(status:Float,navigation:Float,preview:Float){
        if(statusBottom==status && navigationBottom==navigation && previewBottom==preview)return
        statusBottom=status;navigationBottom=navigation;previewBottom=preview
        updateMasks();invalidate()
    }
    private fun updateMasks(){
        if(navigationBottom<=0f)return
        val stops=floatArrayOf(0f,.2f,.4f,.6f,.8f,1f)
        val falloff=intArrayOf(0xff000000.toInt(),0xf0000000.toInt(),0xc4000000.toInt(),0x79000000,0x29000000,0)
        fullMask.shader=LinearGradient(0f,navigationBottom-40*density,0f,navigationEnd,falloff,stops,Shader.TileMode.CLAMP)
        softMask.shader=LinearGradient(0f,navigationBottom-28*density,0f,navigationEnd,falloff,stops,Shader.TileMode.CLAMP)
        val end=navigationEnd
        fun tint(alpha:Int)=(surface and 0xffffff) or (alpha shl 24)
        obscuring.shader=LinearGradient(0f,0f,0f,end,
            intArrayOf(tint(254),tint(250),tint(170),tint(50),tint(0)),
            floatArrayOf(0f,(statusBottom/end).coerceIn(.01f,.45f),
                ((statusBottom+navigationBottom)*.5f/end).coerceIn(.46f,.75f),
                ((navigationBottom-12*density)/end).coerceIn(.76f,.99f),1f),Shader.TileMode.CLAMP)
    }
    override fun onAttachedToWindow(){
        super.onAttachedToWindow();source.viewTreeObserver.addOnPreDrawListener(sourceInvalidation)
    }
    override fun onDetachedFromWindow(){
        if(source.viewTreeObserver.isAlive)source.viewTreeObserver.removeOnPreDrawListener(sourceInvalidation)
        backdrop.discardDisplayList();softBackdrop.discardDisplayList();contentNode.discardDisplayList();previewShape.discardDisplayList()
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        if(w<=0 || h<=0)return
        backdrop.setPosition(0,0,w,h+extra);softBackdrop.setPosition(0,0,w,h+extra)
        contentNode.setPosition(0,0,w,h+extra)
        previewShape.setPosition(0,0,w,h+extra)
    }
    override fun onDraw(canvas:Canvas){
        if(width<=0 || height<=0 || source.height==0 || navigationBottom<=0f)return
        if(canvas.isHardwareAccelerated){
            val recording=contentNode.beginRecording(width,height+extra)
            try {
                recording.drawColor(surface)
                source.getChildAt(0)?.let { content ->
                    val saved=recording.save()
                    recording.translate((content.left-source.scrollX).toFloat(),(content.top-source.scrollY).toFloat())
                    content.draw(recording);recording.restoreToCount(saved)
                }
                drawMovingTitle(recording)
            }finally{contentNode.endRecording()}
            for(node in arrayOf(softBackdrop,backdrop)){
                val target=node.beginRecording(width,height+extra)
                try{target.drawRenderNode(contentNode)}finally{node.endRecording()}
            }
            drawBlurLayer(canvas,softBackdrop,softMask,0f,navigationEnd)
            drawBlurLayer(canvas,backdrop,fullMask,0f,navigationEnd)
            if(previewBottom>=0f)drawPreviewBlur(canvas)
        }
        // Content fades independently of blur, almost disappearing by the status bar.
        canvas.drawRect(0f,0f,width.toFloat(),navigationEnd,obscuring)
    }
    private fun drawMovingTitle(canvas:Canvas){
        scrollingTitle?.takeIf {titleCoverage>0f}?.let { title ->
            title.getLocationInWindow(titleLocation);getLocationInWindow(ownLocation)
            val saved=canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(titleCoverage*255).toInt().coerceIn(0,255))
            canvas.translate((titleLocation[0]-ownLocation[0]).toFloat(),(titleLocation[1]-ownLocation[1]).toFloat())
            title.draw(canvas);canvas.restoreToCount(saved)
        }
    }
    private fun drawPreviewBlur(canvas:Canvas){
        val host=previewHost ?: return
        if(previewBounds.height()<=0 || width<=2*previewBounds.left)return
        previewBounds.right=width-previewBounds.left
        // Start the soft blur before the exposed edge, including its rounded
        // corner cutouts. The preview itself is painted sharply above this mask.
        val spread=14*density
        previewFalloffBounds.set(previewBounds);previewFalloffBounds.inset(-spread,-spread)
        com.cosmod.launcherglass.util.ContinuousCorners.path(previewPath,previewFalloffBounds,host.cornerRadius+spread)
        val mask=previewShape.beginRecording(width,height+extra)
        try{mask.drawPath(previewPath,previewPaint)}finally{previewShape.endRecording()}
        val top=(previewBounds.top-64*density).coerceAtLeast(0f)
        val layer=canvas.saveLayer(0f,top,width.toFloat(),previewEnd,null)
        canvas.clipRect(0f,top,width.toFloat(),previewEnd)
        canvas.drawRenderNode(softBackdrop)
        canvas.drawRenderNode(previewShape)
        canvas.restoreToCount(layer)
    }
    private fun drawBlurLayer(canvas:Canvas,node:RenderNode,mask:Paint,top:Float,bottom:Float){
        val layer=canvas.saveLayer(0f,top,width.toFloat(),bottom,null)
        canvas.clipRect(0f,top,width.toFloat(),bottom)
        canvas.drawRenderNode(node)
        canvas.drawRect(0f,top,width.toFloat(),bottom,mask)
        canvas.restoreToCount(layer)
    }
}
