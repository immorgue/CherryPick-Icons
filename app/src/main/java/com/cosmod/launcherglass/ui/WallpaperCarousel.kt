package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.cosmod.launcherglass.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M
import java.util.concurrent.Executors

/** Native-theme previews; scrolling selects a card, never applies a wallpaper. */
internal class WallpaperCarousel(context:Context,private val copy:(ThemeCatalog.Theme)->Unit,
    private val apply:(ThemeCatalog.Theme,View)->Unit):LinearLayout(context) {
    private val worker=Executors.newSingleThreadExecutor()
    private val cards=ArrayList<FrameLayout>()
    private val images=ArrayList<ImageView>()
    private val settings=ArrayList<View>()
    private var themes=emptyList<ThemeCatalog.Theme>()
    private var selected=-1
    private var cardWidth=0
    private var gap=0
    private var loaded=HashSet<Int>()
    private var generation=0
    private val row=LinearLayout(context)
    private val caption=TextView(context)
    private val actions=FrameLayout(context)
    private val applyButton=MaterialButton(context).apply {
        text="✓";contentDescription=context.getString(R.string.ui_theme_apply);textSize=24f;cornerRadius=dp(28)
        insetTop=0;insetBottom=0;minWidth=0;minimumWidth=0;setPadding(0,0,0,0)
        setOnClickListener {themes.getOrNull(selected)?.let {theme ->apply(theme,this)}}
    }
    private val snap:Runnable=Runnable {if(cardWidth>0){val index=kotlin.math.round(scroll.scrollX.toFloat()/(cardWidth+gap)).toInt().coerceIn(0,(themes.size-1).coerceAtLeast(0));scroll.smoothScrollTo(index*(cardWidth+gap),0)}}
    private val scroll:HorizontalScrollView=object:HorizontalScrollView(context){
        override fun onTouchEvent(event:MotionEvent):Boolean {
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN -> removeCallbacks(snap)
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> postDelayed(snap,180)
            }
            return super.onTouchEvent(event)
        }
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    init {
        orientation=VERTICAL;clipChildren=false;clipToPadding=false
        scroll.isHorizontalScrollBarEnabled=false;scroll.clipToPadding=false;scroll.overScrollMode=OVER_SCROLL_NEVER
        scroll.addView(row,FrameLayout.LayoutParams(-2,-1));addView(scroll,LayoutParams(-1,dp(420)))
        caption.setTextColor(MaterialColors.getColor(this,M.attr.colorOnSurface));caption.textSize=14f
        actions.addView(caption,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL).apply {marginEnd=dp(72)})
        actions.addView(applyButton,FrameLayout.LayoutParams(dp(56),dp(56),Gravity.END or Gravity.CENTER_VERTICAL))
        addView(actions,LayoutParams(-1,dp(80)))
        actions.visibility=GONE;scroll.visibility=GONE
        scroll.setOnScrollChangeListener {_,_,_,_,_ ->
            updateCards();scroll.removeCallbacks(snap);scroll.postDelayed(snap,140)
        }
    }
    fun show(items:List<ThemeCatalog.Theme>){
        generation++;themes=items;row.removeAllViews();cards.clear();images.clear();settings.clear();loaded.clear()
        if(items.isEmpty())return
        actions.visibility=VISIBLE;scroll.visibility=VISIBLE
        val width=(resources.displayMetrics.widthPixels-dp(40)).coerceAtLeast(dp(220))
        cardWidth=(width*.64f).toInt();gap=dp(16)
        scroll.setPadding((width-cardWidth)/2,dp(12),(width-cardWidth)/2,dp(12))
        for((index,theme) in items.withIndex()){
            val card=FrameLayout(context).apply {
                clipToOutline=true
                background=GradientDrawable().apply {setColor(MaterialColors.getColor(this@WallpaperCarousel,M.attr.colorSurfaceContainerHigh));cornerRadius=dp(28).toFloat()}
                contentDescription=theme.label ?: context.getString(R.string.ui_theme_number,theme.id)
                setOnClickListener {scroll.smoothScrollTo(index*(cardWidth+gap),0)}
            }
            val image=ImageView(context).apply {scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
            card.addView(image,FrameLayout.LayoutParams(-1,-1));images.add(image)
            val badge=TextView(context).apply {
                text=if(theme.current)context.getString(R.string.ui_theme_current)else ""
                setTextColor(Color.WHITE);textSize=12f;gravity=Gravity.CENTER
                setShadowLayer(4f,0f,1f,Color.BLACK)
            }
            card.addView(badge,FrameLayout.LayoutParams(-1,dp(40),Gravity.TOP))
            val menu=LinearLayout(context).apply {orientation=VERTICAL;gravity=Gravity.END;setPadding(dp(8),dp(8),dp(8),dp(8))}
            val copyButton=MaterialButton(context).apply {
                text=context.getString(R.string.ui_theme_copy);isAllCaps=false;textSize=12f;visibility=GONE
                setOnClickListener {copy(theme);visibility=GONE}
            }
            menu.addView(copyButton,LayoutParams(-2,-2))
            val gear=MaterialButton(context).apply {
                setIconResource(R.drawable.ic_settings);iconPadding=0;iconGravity=MaterialButton.ICON_GRAVITY_TEXT_START
                contentDescription=context.getString(R.string.ui_theme_options);minWidth=0;minimumWidth=0;cornerRadius=dp(22)
                setPadding(dp(10),0,dp(10),0);insetTop=0;insetBottom=0
                setOnClickListener {
                    val open=copyButton.visibility!=VISIBLE
                    copyButton.visibility=if(open)VISIBLE else GONE
                    if(open){copyButton.alpha=0f;copyButton.translationY=dp(8).toFloat();copyButton.animate().alpha(1f).translationY(0f).setDuration(180).start()}
                }
            }
            menu.addView(gear,LayoutParams(dp(44),dp(44)))
            card.addView(menu,FrameLayout.LayoutParams(-2,-2,Gravity.END or Gravity.BOTTOM));settings.add(menu)
            row.addView(card,LayoutParams(cardWidth,-1).apply {marginEnd=if(index==items.lastIndex)0 else gap});cards.add(card)
        }
        selected=items.indexOfFirst {it.current}.coerceAtLeast(0)
        post {scroll.scrollTo(selected*(cardWidth+gap),0);updateCards()}
    }
    private fun updateCards(){
        if(themes.isEmpty() || cardWidth<=0)return
        val position=scroll.scrollX.toFloat()/(cardWidth+gap)
        val nearest=kotlin.math.round(position).toInt().coerceIn(themes.indices)
        for((index,card) in cards.withIndex()){
            val distance=kotlin.math.abs(position-index).coerceAtMost(1f)
            card.scaleY=1f-distance*.1f;card.scaleX=1f-distance*.04f;card.alpha=1f-distance*.25f
            val alpha=if(themes[index].current)0f else (1f-distance*3f).coerceIn(0f,1f)
            settings[index].alpha=alpha;settings[index].visibility=if(alpha>.01f)VISIBLE else INVISIBLE
        }
        if(nearest!=selected){selected=nearest;actions.alpha=0f;actions.animate().alpha(1f).setDuration(180).start()}
        val theme=themes[selected]
        caption.text=theme.label ?: context.getString(if(theme.current)R.string.ui_theme_current_number else R.string.ui_theme_number,theme.id)
        applyButton.visibility=if(theme.current)INVISIBLE else VISIBLE
        for(index in (selected-2).coerceAtLeast(0)..(selected+2).coerceAtMost(themes.lastIndex))loadImage(index)
    }
    private fun loadImage(index:Int){
        if(!loaded.add(index))return
        val ticket=generation;val theme=themes[index]
        if(worker.isShutdown)return
        worker.execute {
            val image=try {ThemeCatalog.thumbnail(context,theme)}catch(_:Throwable){null}
            post {if(ticket==generation)images.getOrNull(index)?.setImageBitmap(image)}
        }
    }
    override fun onDetachedFromWindow(){generation++;worker.shutdown();removeCallbacks(snap);super.onDetachedFromWindow()}
}
