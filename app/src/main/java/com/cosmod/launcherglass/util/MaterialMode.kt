package com.cosmod.launcherglass.util

/** Stable material IDs stored in presets and wallpaper profiles. */
internal object MaterialMode {
    const val FROSTED = 0
    const val LIQUID = 1
    const val COLOR = 2
    const val KYANT = 3
    fun normalize(value:Int)=when(value){LIQUID->KYANT;FROSTED,COLOR,KYANT->value;else->FROSTED}
    fun needsBackdrop(value:Int,enabled:Boolean,folderGlass:Boolean=false,dockGlass:Boolean=false)=
        enabled && (normalize(value)!=COLOR || folderGlass || dockGlass)
}
