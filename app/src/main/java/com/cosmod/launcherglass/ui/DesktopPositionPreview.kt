package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.cosmod.launcherglass.util.DesktopSizing
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M

/** A selectable layout diagram; the controls below provide accessible equivalents. */
internal class DesktopPositionPreview(context:Context):View(context) {
    var positions:Map<String,Int> = emptyMap()
        set(value){field=value;invalidate()}
    var selected:String?=null
        set(value){field=value;invalidate()}
    var onSelect:((String)->Unit)?=null
    var cellWidthPercent=100
        set(value){field=value;invalidate()}
    var cellHeightPercent=100
        set(value){field=value;invalidate()}
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(value:Float)=value*density
    private fun color(attr:Int)=MaterialColors.getColor(this,attr)
    private fun phone():RectF {
        val h=(height-dp(16f)).coerceAtLeast(1f)
        val w=minOf(width-dp(32f),h*.46f)
        return RectF((width-w)/2f,dp(8f),(width+w)/2f,dp(8f)+h)
    }
    private fun regions():Map<String,RectF> {
        val p=phone()
        val scale=p.height()/(resources.displayMetrics.heightPixels/density)
        fun region(key:String,top:Float,bottom:Float,inset:Float):RectF {
            val shift=(positions[key] ?: 0)*scale
            return RectF(p.left+p.width()*inset,p.top+p.height()*top+shift,
                p.right-p.width()*inset,p.top+p.height()*bottom+shift).apply {
                if(key==DesktopSizing.GRID_Y){
                    inset(-width()*(cellWidthPercent/100f-1f)/2f,-height()*(cellHeightPercent/100f-1f)/2f)
                }
            }
        }
        return linkedMapOf(
            DesktopSizing.GRID_Y to region(DesktopSizing.GRID_Y,.12f,.76f,.07f),
            DesktopSizing.INDICATOR_Y to region(DesktopSizing.INDICATOR_Y,.825f,.845f,.37f),
            DesktopSizing.DOCK_Y to region(DesktopSizing.DOCK_Y,.88f,.965f,.05f))
    }
    override fun onDraw(canvas:Canvas) {
        val p=phone();val radius=dp(22f)
        paint.style=Paint.Style.FILL;paint.color=color(M.attr.colorSurfaceContainerHighest)
        canvas.drawRoundRect(p,radius,radius,paint)
        val saved=canvas.save()
        canvas.clipPath(Path().apply {addRoundRect(p,radius,radius,Path.Direction.CW)})
        val regions=regions()
        for((key,rect) in regions){
            val active=key==selected
            paint.color=color(if(active)M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHigh)
            paint.alpha=if(active)255 else 160
            canvas.drawRoundRect(rect,dp(10f),dp(10f),paint)
            paint.color=color(if(active)M.attr.colorOnPrimaryContainer else M.attr.colorOnSurfaceVariant);paint.alpha=210
            when(key){
                DesktopSizing.GRID_Y -> {
                    val cellW=rect.width()/4f;val cellH=rect.height()/6f
                    for(y in 0..5)for(x in 0..3){
                        val cx=rect.left+(x+.5f)*cellW;val cy=rect.top+(y+.5f)*cellH
                        val edge=minOf(cellW,cellH)*.57f
                        canvas.drawRoundRect(cx-edge/2,cy-edge/2,cx+edge/2,cy+edge/2,edge*.25f,edge*.25f,paint)
                    }
                }
                DesktopSizing.DOCK_Y -> repeat(4){i ->
                    val cx=rect.left+(i+.5f)*rect.width()/4
                    canvas.drawCircle(cx,rect.centerY(),minOf(rect.height()*.28f,rect.width()*.07f),paint)
                }
                else -> repeat(3){i -> canvas.drawCircle(rect.centerX()+(i-1)*dp(8f),rect.centerY(),dp(2f),paint)}
            }
            if(active){
                paint.color=color(M.attr.colorPrimary);paint.alpha=255;paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2f)
                canvas.drawRoundRect(rect,dp(10f),dp(10f),paint);paint.style=Paint.Style.FILL
            }
        }
        paint.color=color(M.attr.colorOnSurface);paint.alpha=150;paint.strokeWidth=dp(2f)
        canvas.drawLine(p.centerX()-dp(12f),p.bottom-dp(5f),p.centerX()+dp(12f),p.bottom-dp(5f),paint)
        canvas.restoreToCount(saved);paint.alpha=255
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN)return true
        if(event.actionMasked==MotionEvent.ACTION_UP){
            performClick()
            val regions=regions()
            val exact=regions.entries.lastOrNull {it.value.contains(event.x,event.y)}
            val closest=exact ?: regions.entries.filter {(_,r) ->
                RectF(r).apply {inset(0f,-maxOf(0f,(dp(48f)-height())/2f))}.contains(event.x,event.y)
            }.minByOrNull {kotlin.math.abs(it.value.centerY()-event.y)}
            closest?.key?.let {onSelect?.invoke(it)}
            return true
        }
        return event.actionMasked==MotionEvent.ACTION_MOVE || event.actionMasked==MotionEvent.ACTION_CANCEL
    }
    override fun performClick():Boolean {super.performClick();return true}
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
}
