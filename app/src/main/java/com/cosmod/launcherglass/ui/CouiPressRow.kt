package com.cosmod.launcherglass.ui

import android.content.Context
import android.view.MotionEvent
import android.widget.LinearLayout

/** A switch or other child also presses the entire settings row. */
internal class CouiPressRow(context: Context) : LinearLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN)isPressed=true
        val handled=super.dispatchTouchEvent(event)
        // ViewGroup.setPressed(false) propagates to children. Deliver UP first,
        // while the button still owns its pressed state and can performClick().
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)isPressed=false
        return handled
    }
}
