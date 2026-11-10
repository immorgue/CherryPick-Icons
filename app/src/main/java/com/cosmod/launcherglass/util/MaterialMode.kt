package com.cosmod.launcherglass.util

/** Stored values 0/1 retain their meaning in existing presets and wallpaper profiles. */
internal object MaterialMode {
    const val FROSTED = 0
    const val LIQUID = 1
    const val COLOR = 2
    fun normalize(value:Int)=value.coerceIn(FROSTED,COLOR)
    fun needsBackdrop(value:Int,enabled:Boolean,folderGlass:Boolean=false,dockGlass:Boolean=false)=
        enabled && (normalize(value)!=COLOR || folderGlass || dockGlass)
}
