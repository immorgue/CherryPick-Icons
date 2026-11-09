package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M
import kotlin.math.abs
import kotlin.math.roundToInt

/** The selection is a movable view, independent of the page being displayed. */
internal class FloatingNavigationPill(
    context:Context, labels:List<String>, icons:List<Int>, private val choose:(Int)->Unit
):FrameLayout(context) {
    private fun dp(n:Int)=(n*resources.displayMetrics.density).roundToInt()
    private fun color(attr:Int)=MaterialColors.getColor(this,attr)
    private val inset=dp(6)
    private val indicator=View(context).apply {
        background=GradientDrawable().apply {setColor(color(M.attr.colorSecondaryContainer));cornerRadius=dp(28).toFloat()}
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val buttons=ArrayList<LinearLayout>()
    private var selected=0
    private var position=0f
    private var velocity=0f
    private var target=0f
    private var lastFrame=0L
    private var settling=false
    private var dragging=false
    private var candidate=false
    private var downX=0f
    private var downPosition=0f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val slot get()=((width-2*inset)/3f).coerceAtLeast(1f)
    private fun visual(index:Int)=if(layoutDirection==LAYOUT_DIRECTION_RTL)2-index else index
    private fun logical(index:Int)=visual(index)
    init {
        elevation=dp(6).toFloat()
        background=GradientDrawable().apply {setColor(color(M.attr.colorSurfaceContainerHigh));cornerRadius=dp(36).toFloat()}
        addView(indicator,LayoutParams(0,dp(56)).apply {topMargin=inset;leftMargin=inset})
        val row=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;setPadding(inset,inset,inset,inset)}
        labels.forEachIndexed { index,label ->
            val button=object:LinearLayout(context){
                override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
                    super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.Button"
                }
            }.apply {
                orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER
                contentDescription=label;isFocusable=true
                setOnClickListener {performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);select(index);choose(index)}
            }
            button.addView(ImageView(context).apply {setImageResource(icons[index]);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(24),dp(24)))
            button.addView(TextView(context).apply {text=label;textSize=11f;gravity=android.view.Gravity.CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(2)})
            row.addView(button,LinearLayout.LayoutParams(0,dp(56),1f));buttons.add(button)
        }
        addView(row,LayoutParams(-1,-1))
        select(0,false)
    }
    fun select(index:Int,animate:Boolean=true){
        selected=index.coerceIn(0,2)
        buttons.forEachIndexed { i,button ->
            button.isSelected=i==selected
            val tint=color(if(i==selected)M.attr.colorOnSecondaryContainer else M.attr.colorOnSurfaceVariant)
            (button.getChildAt(0) as ImageView).imageTintList=ColorStateList.valueOf(tint)
            (button.getChildAt(1) as TextView).setTextColor(tint)
        }
        if(!dragging) settle(visual(selected).toFloat(),animate && width>0)
    }
    private fun place(){indicator.translationX=position*slot}
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        // Size before measurement: requesting a new layout from onSizeChanged
        // can be swallowed by the current pass, leaving a zero-width indicator.
        indicator.layoutParams.width=((MeasureSpec.getSize(widthMeasureSpec)-2*inset)/3).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        if(!dragging){position=visual(selected).toFloat();target=position;velocity=0f}
        place()
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN -> {
                downX=event.x;downPosition=position
                candidate=event.x>=inset+position*slot && event.x<=inset+(position+1)*slot
            }
            MotionEvent.ACTION_MOVE -> if(candidate && abs(event.x-downX)>slop){
                dragging=true;settling=false;removeCallbacks(spring)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> candidate=false
        }
        return false
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!dragging)return super.onTouchEvent(event)
        when(event.actionMasked){
            MotionEvent.ACTION_MOVE -> {position=(downPosition+(event.x-downX)/slot).coerceIn(0f,2f);place()}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                val index=if(event.actionMasked==MotionEvent.ACTION_UP)logical(position.roundToInt())else selected
                dragging=false;candidate=false;parent?.requestDisallowInterceptTouchEvent(false)
                select(index)
                if(event.actionMasked==MotionEvent.ACTION_UP){performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);choose(index)}
            }
        }
        return true
    }
    private fun settle(destination:Float,animate:Boolean){
        target=destination
        if(!animate || !ValueAnimator.areAnimatorsEnabled()){
            settling=false;removeCallbacks(spring);position=target;velocity=0f;place();return
        }
        if(!settling){settling=true;velocity=0f;lastFrame=0L;postOnAnimation(spring)}
    }
    private val spring=object:Runnable {
        override fun run(){
            if(!settling)return
            val now=System.nanoTime()
            val dt=if(lastFrame==0L)1f/120f else ((now-lastFrame)/1e9f).coerceIn(0f,.032f)
            lastFrame=now
            // Small integration steps keep the damped spring stable at both 60 and 120 Hz.
            repeat(4){velocity+=(-400f*(position-target)-34f*velocity)*(dt/4);position+=velocity*(dt/4)}
            place()
            if(abs(position-target)<.001f && abs(velocity)<.01f){position=target;place();settling=false}
            else postOnAnimation(this)
        }
    }
    override fun onDetachedFromWindow(){settling=false;removeCallbacks(spring);super.onDetachedFromWindow()}
}
