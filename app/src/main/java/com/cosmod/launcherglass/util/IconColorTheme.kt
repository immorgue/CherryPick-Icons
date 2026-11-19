package com.cosmod.launcherglass.util

/** Explicit module appearance is independent of native launcher appearance reports. */
object IconColorTheme {
    const val FOLLOW_SYSTEM=0
    const val LIGHT=1
    const val DARK=2
    fun isDark(choice:Int,nativeDark:Boolean):Boolean=when(choice){
        LIGHT->false
        DARK->true
        else->nativeDark
    }
}
