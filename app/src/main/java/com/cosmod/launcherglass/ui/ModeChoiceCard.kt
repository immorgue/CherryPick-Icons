package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cosmod.launcherglass.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M

/** Wrapping title/supporting text instead of a multi-line button label that can ellipsize. */
internal class ModeChoiceCard(context:Context,title:String,description:String):MaterialCardView(context) {
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun color(attribute:Int)=MaterialColors.getColor(this,attribute)
    private val mark=ImageView(context).apply {
        setImageResource(R.drawable.ic_check)
        imageTintList=ColorStateList.valueOf(color(M.attr.colorPrimary))
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    var chosen=false
        set(value){
            if(field==value && initialized)return
            field=value
            strokeColor=color(if(value)M.attr.colorPrimary else M.attr.colorOutlineVariant)
            strokeWidth=dp(if(value)2 else 1)
            setCardBackgroundColor(color(if(value)M.attr.colorSecondaryContainer else M.attr.colorSurfaceContainer))
            mark.visibility=if(value)View.VISIBLE else View.INVISIBLE
            refreshDrawableState()
        }
    private var initialized=false
    init {
        radius=dp(20).toFloat();cardElevation=0f;isClickable=true;isFocusable=true
        rippleColor=ColorStateList.valueOf(color(M.attr.colorPrimary) and 0x00ffffff or 0x18000000)
        contentDescription="$title. $description"
        val line=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            minimumHeight=dp(88);setPadding(dp(20),dp(18),dp(16),dp(18))
        }
        val labels=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        labels.addView(TextView(context).apply {
            text=title;textSize=18f;setTextColor(color(M.attr.colorOnSurface))
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            includeFontPadding=false;ellipsize=null;setSingleLine(false)
        },LinearLayout.LayoutParams(-1,-2))
        labels.addView(TextView(context).apply {
            text=description;textSize=14f;setTextColor(color(M.attr.colorOnSurfaceVariant))
            includeFontPadding=false;ellipsize=null;setSingleLine(false)
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(6)})
        line.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        line.addView(mark,LinearLayout.LayoutParams(dp(24),dp(24)).apply {leftMargin=dp(16)})
        addView(line);chosen=false;initialized=true
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className="android.widget.RadioButton";info.isCheckable=true;info.isChecked=chosen
    }
}
