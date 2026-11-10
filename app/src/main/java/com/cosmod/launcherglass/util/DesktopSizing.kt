package com.cosmod.launcherglass.util

import kotlin.math.roundToInt

internal object DesktopSizing {
    const val ICON_SCALE="desktop_icon_scale"
    const val WIDGET_GAPS="desktop_widget_gaps"
    const val BOTTOM_BIAS="desktop_bottom_bias"
    const val GRID_Y="desktop_grid_y"
    const val INDICATOR_Y="desktop_indicator_y"
    const val DOCK_Y="desktop_dock_y"
    const val CELL_WIDTH="desktop_cell_width"
    const val CELL_HEIGHT="desktop_cell_height"
    val positions=setOf(GRID_Y,INDICATOR_Y,DOCK_Y)
    val defaults=mapOf(ICON_SCALE to 100,WIDGET_GAPS to 100,BOTTOM_BIAS to 0,
        GRID_Y to 0,INDICATOR_Y to 0,DOCK_Y to 0,CELL_WIDTH to 100,CELL_HEIGHT to 100)
    val ranges=defaults.mapValues {(key,_) -> when(key){ICON_SCALE->100..160;CELL_WIDTH,CELL_HEIGHT->80..120;in positions->-120..120;else->0..100}}
    fun cellPixels(base:Int,percent:Int)=(base*percent.coerceIn(80,120)/100f).roundToInt().coerceAtLeast(1)
    fun cellOriginShift(base:Int,scaled:Int,count:Int)=-((scaled-base)*count.coerceAtLeast(1)/2f).roundToInt()
    fun iconPixels(base:Int,percent:Int,cellWidth:Int,gap:Int)=
        (base*percent.coerceIn(100,160)/100f).roundToInt().coerceAtMost(maxOf(base,cellWidth-gap))
    fun padding(base:Int,percent:Int)=(base.coerceAtLeast(0)*percent.coerceIn(0,100)/100f).roundToInt()
    fun downwardShift(topFree:Int,totalFree:Int,percent:Int)=
        ((totalFree-topFree).coerceAtLeast(0)*percent.coerceIn(0,100)/100f).roundToInt()
}
