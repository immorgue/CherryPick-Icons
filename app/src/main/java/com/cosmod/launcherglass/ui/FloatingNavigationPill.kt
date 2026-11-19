package com.cosmod.launcherglass.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.cosmod.launcherglass.R
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.min

/** The selection is a movable view, independent of the page being displayed. */
internal class FloatingNavigationPill(
    context:Context, labels:List<String>, icons:List<Int>?, private val useCoui:Boolean,
    backdrop: (() -> View?)?=null, private val labelColor:Int?=null,
    private val trackBackdrop:Boolean=true, compactHeight:Int=44, private val refract:Boolean=true,
    private val choose:(Int)->Unit
):FrameLayout(context) {
    private val compact=icons==null
    private val last=labels.lastIndex
    private fun dp(n:Int)=(n*resources.displayMetrics.density).roundToInt()
    private fun color(attr:Int)=MaterialColors.getColor(this,attr)
    private val inset=dp(if(useCoui)4 else 6)
    private val tabHeight=dp(if(compact)compactHeight else if(useCoui)48 else 56)
    private val glass = if (useCoui && backdrop!=null) UiLiquidGlass(context, backdrop, 999f,
        trackBackdrop=trackBackdrop,initialSurface=UiLiquidGlass.Surface.TAB_TRACK,refract=refract) else null
    private val refractedThumb=glass!=null && refract
    private val row=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;setPadding(inset,inset,inset,inset)}
    private val indicator= (if (refractedThumb) UiLiquidGlass(context,{glass},999f,selection=true,
        initialSurface=UiLiquidGlass.Surface.TAB_THUMB, foreground=row) else View(context)).apply {
        if (!refractedThumb) background=GradientDrawable().apply {
            val dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
            setColor(when {
                glass!=null -> if(dark)0x39ffffff else 0x65ffffff
                useCoui -> ContextCompat.getColor(context,R.color.coui_accent_surface)
                else -> color(M.attr.colorSecondaryContainer)
            })
            cornerRadius=tabHeight/2f
        }
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
    private var downY=0f
    private var downPosition=0f
    private var detent=-1
    private var tracker:VelocityTracker?=null
    private var press=0f
    private var pressMotion:ValueAnimator?=null
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val slot get()=((width-2*inset)/buttons.size.toFloat()).coerceAtLeast(1f)
    private fun visual(index:Int)=if(layoutDirection==LAYOUT_DIRECTION_RTL)last-index else index
    private fun logical(index:Int)=visual(index)
    init {
        minimumHeight=tabHeight+2*inset
        elevation=if(compact)0f else dp(if(useCoui)2 else 6).toFloat()
        if (glass != null) addView(glass, LayoutParams(-1, -1))
        else background=GradientDrawable().apply {
            setColor(if(useCoui)ContextCompat.getColor(context,R.color.coui_card)else color(M.attr.colorSurfaceContainerHigh))
            cornerRadius=dp(36).toFloat()
        }
        val thumbLayout=LayoutParams(0,tabHeight).apply {topMargin=inset;leftMargin=inset}
        if(!refractedThumb)addView(indicator,thumbLayout)
        labels.forEachIndexed { index,label ->
            val button=object:LinearLayout(context){
                override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
                    super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.Button"
                }
            }.apply {
                orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER
                contentDescription=label;isFocusable=true
                setOnClickListener {if(this@FloatingNavigationPill.isEnabled && (!compact || selected!=index)){
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);select(index);choose(index)
                }}
            }
            if(icons!=null)button.addView(ImageView(context).apply {setImageResource(icons[index]);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(24),dp(24)))
            button.addView(TextView(context).apply {
                text=label
                textSize=if(compact)13f else if(useCoui)10f else 11f
                gravity=android.view.Gravity.CENTER
                importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
                if(compact){
                    maxLines=1
                    ellipsize=TextUtils.TruncateAt.END
                    includeFontPadding=false
                    setPadding(dp(4),0,dp(4),0)
                    setAutoSizeTextTypeUniformWithConfiguration(8,13,1,TypedValue.COMPLEX_UNIT_SP)
                }
            },LinearLayout.LayoutParams(-1,if(compact)-1 else -2).apply {if(!compact)topMargin=dp(2)})
            row.addView(button,LinearLayout.LayoutParams(0,tabHeight,1f));buttons.add(button)
        }
        addView(row,LayoutParams(-1,-1))
        if(refractedThumb)addView(indicator,thumbLayout)
        select(0,false)
    }
    fun select(index:Int,animate:Boolean=true){
        selected=index.coerceIn(0,last)
        buttons.forEachIndexed { i,button ->
            button.isSelected=i==selected
            val tint=labelColor ?: color(if(i!=selected)M.attr.colorOnSurfaceVariant
                else if(useCoui)M.attr.colorOnSurface else M.attr.colorOnSecondaryContainer)
            (button.getChildAt(0) as? ImageView)?.imageTintList=ColorStateList.valueOf(tint)
            (button.getChildAt(button.childCount-1) as TextView).setTextColor(tint)
        }
        (indicator as? UiLiquidGlass)?.invalidateBackdrop()
        if(!dragging) settle(visual(selected).toFloat(),animate && width>0)
    }
    private fun place(){
        if(!useCoui || !refract){indicator.translationX=position*slot;return}
        if(width<=0)return
        val stretch=if(isEnabled).10f*press + min(.11f,abs(velocity)*.014f) else 0f
        val thumbWidth=indicator.measuredWidth.toFloat().coerceAtLeast(1f)
        val guard=dp(1).toFloat().coerceAtMost(width/4f)
        val scaleX=(1f+stretch).coerceAtMost((width-2*guard)/thumbWidth)
        val halfWidth=thumbWidth*scaleX/2f
        val center=(inset+position*slot+thumbWidth/2f).coerceIn(guard+halfWidth,
            (width-guard-halfWidth).coerceAtLeast(guard+halfWidth))
        indicator.translationX=center-inset-thumbWidth/2f
        indicator.scaleX=scaleX
        indicator.scaleY=1f+(if(isEnabled).065f*press else 0f)-stretch*.22f
        (indicator as? UiLiquidGlass)?.invalidateBackdrop()
    }
    private fun animatePress(pressed:Boolean){
        pressMotion?.cancel()
        val goal=if(pressed)1f else 0f
        if(!ValueAnimator.areAnimatorsEnabled()){press=0f;place();return}
        pressMotion=ValueAnimator.ofFloat(press,goal).apply {
            duration=if(pressed)125L else 220L
            interpolator=android.view.animation.PathInterpolator(.2f,0f,.2f,1f)
            addUpdateListener {press=it.animatedValue as Float;place()}
            start()
        }
    }

    fun invalidateBackdrop() {
        glass?.invalidateBackdrop()
        (indicator as? UiLiquidGlass)?.invalidateBackdrop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action=event.actionMasked
        if(action==MotionEvent.ACTION_DOWN){tracker?.recycle();tracker=VelocityTracker.obtain()}
        tracker?.addMovement(event)
        if (useCoui && isEnabled) {
            val pressed = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
            glass?.touch(event.x, event.y, pressed)
            (indicator as? UiLiquidGlass)?.touch(event.x - inset - indicator.translationX, event.y - inset, pressed)
            if (action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                if(refract)animatePress(pressed)
            }
        }
        val handled=super.dispatchTouchEvent(event)
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL){tracker?.recycle();tracker=null}
        return handled
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        // Size before measurement: requesting a new layout from onSizeChanged
        // can be swallowed by the current pass, leaving a zero-width indicator.
        indicator.layoutParams.width=((MeasureSpec.getSize(widthMeasureSpec)-2*inset)/buttons.size).coerceAtLeast(0)
        val height=if(compact && MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.UNSPECIFIED)
            MeasureSpec.makeMeasureSpec(minimumHeight,MeasureSpec.EXACTLY)else heightMeasureSpec
        super.onMeasure(widthMeasureSpec,height)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        if(!dragging){position=visual(selected).toFloat();target=position;velocity=0f}
        place()
    }
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean {
        if(!isEnabled)return false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN -> {
                downX=event.x;downY=event.y;downPosition=position;detent=position.roundToInt()
                candidate=event.x>=inset+position*slot && event.x<=inset+(position+1)*slot
            }
            MotionEvent.ACTION_MOVE -> if(candidate && abs(event.x-downX)>slop && abs(event.x-downX)>abs(event.y-downY)){
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
            MotionEvent.ACTION_MOVE -> {
                tracker?.computeCurrentVelocity(1000)
                velocity=((tracker?.xVelocity ?: 0f)/slot).coerceIn(-9f,9f)
                val raw=(downPosition+(event.x-downX)/slot).coerceIn(0f,last.toFloat())
                val center=raw.roundToInt()
                val distance=abs(raw-center)
                // A shallow magnetic well preserves continuous finger control.
                val attraction=(1f-distance/.36f).coerceIn(0f,1f)
                position=raw+(center-raw)*attraction*.65f
                if(distance<.08f && detent!=center){
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    detent=center
                }else if(distance>.20f)detent=-1
                place()
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                val released=event.actionMasked==MotionEvent.ACTION_UP
                tracker?.computeCurrentVelocity(1000)
                velocity=if(released)((tracker?.xVelocity ?: 0f)/slot).coerceIn(-7f,7f)else 0f
                val index=if(released)logical((position+velocity*.095f).roundToInt().coerceIn(0,last))else selected
                dragging=false;candidate=false;parent?.requestDisallowInterceptTouchEvent(false)
                select(index)
                if(event.actionMasked==MotionEvent.ACTION_UP){
                    if(detent!=visual(index))performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    choose(index)
                }
            }
        }
        return true
    }
    private fun settle(destination:Float,animate:Boolean){
        target=destination
        if(!animate || !ValueAnimator.areAnimatorsEnabled()){
            settling=false;removeCallbacks(spring);position=target;velocity=0f;place();return
        }
        if(!settling){settling=true;lastFrame=0L;postOnAnimation(spring)}
    }
    private val spring=object:Runnable {
        override fun run(){
            if(!settling)return
            val now=System.nanoTime()
            val dt=if(lastFrame==0L)1f/120f else ((now-lastFrame)/1e9f).coerceIn(0f,.032f)
            lastFrame=now
            // Small integration steps keep the damped spring stable at both 60 and 120 Hz.
            repeat(4){velocity+=(-420f*(position-target)-31f*velocity)*(dt/4);position+=velocity*(dt/4)}
            place()
            if(abs(position-target)<.001f && abs(velocity)<.01f){
                position=target;place();settling=false;velocity=0f
                if(!trackBackdrop)(indicator as? UiLiquidGlass)?.invalidateBackdrop()
            }
            else postOnAnimation(this)
        }
    }
    override fun onDetachedFromWindow(){
        settling=false;removeCallbacks(spring)
        tracker?.recycle();tracker=null;pressMotion?.cancel();press=0f
        super.onDetachedFromWindow()
    }
}
