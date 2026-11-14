package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Duplicate the complete row so the wrap has no visible seam. */
internal class CreditsTicker(context:Context,credits:List<Pair<String,String>>,color:Int,
    open:(View,String)->Unit):FrameLayout(context) {
    private val density=resources.displayMetrics.density
    private val track=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL}
    private var first:LinearLayout?=null
    private var animator:ValueAnimator?=null
    private var running=true
    private var cycle=0
    init {
        clipChildren=true;clipToPadding=true
        repeat(2){copy ->
            val group=LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                if(copy==1)importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            credits.forEach {(label,url) ->
                group.addView(TextView(context).apply {
                    text=label;textSize=14f;setTextColor(color);setSingleLine(true)
                    gravity=Gravity.CENTER_VERTICAL;minHeight=(48*density).toInt()
                    setPadding(0,0,(32*density).toInt(),0)
                    setOnClickListener {open(it,url)}
                },LinearLayout.LayoutParams(-2,-2))
            }
            if(copy==0)first=group
            track.addView(group,LinearLayout.LayoutParams(-2,-2))
        }
        addView(track,LayoutParams(-2,-2))
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        track.measure(MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),heightMeasureSpec)
        setMeasuredDimension(resolveSize(suggestedMinimumWidth,widthMeasureSpec),resolveSize(track.measuredHeight,heightMeasureSpec))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        track.layout(0,0,track.measuredWidth,track.measuredHeight)
        val next=first?.measuredWidth ?: 0
        if(cycle!=next){stop();cycle=next}
        start()
    }
    fun setRunning(value:Boolean){running=value;if(value)start()else stop()}
    private fun start(){
        if(!running || !isAttachedToWindow || windowVisibility!=VISIBLE || cycle<=0 || animator!=null)return
        val offset=(-track.translationX).coerceIn(0f,cycle.toFloat())
        animator=ValueAnimator.ofFloat(0f,cycle.toFloat()).apply {
            duration=(cycle/density/32f*1000f).toLong().coerceAtLeast(1000L)
            interpolator=LinearInterpolator();repeatCount=ValueAnimator.INFINITE
            addUpdateListener {track.translationX=-(it.animatedValue as Float)}
            start();currentPlayTime=(duration*offset/cycle).toLong()
        }
    }
    private fun stop(){animator?.cancel();animator=null}
    override fun onAttachedToWindow(){super.onAttachedToWindow();start()}
    override fun onDetachedFromWindow(){stop();super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(visibility:Int){
        super.onWindowVisibilityChanged(visibility)
        if(visibility==VISIBLE)start()else stop()
    }
}
