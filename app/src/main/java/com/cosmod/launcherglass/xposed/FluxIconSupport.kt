package com.cosmod.launcherglass.xposed

import android.content.Context
import de.robv.android.xposed.XposedHelpers

object FluxIconSupport {
    const val UNKNOWN=0
    const val FLUX=1
    const val OTHER=2

    fun read(context:Context):Int = try {
        val type=NativeLauncherIcons.type(context.classLoader)
        val instance=type?.let {NativeLauncherIcons.instance(it)}
        val config=instance?.let {NativeLauncherIcons.config(it)}
        // This is the native isThemedIconEnabled predicate (theme == 3), read
        // directly so CherryPick's own themed-icon hook cannot falsify it.
        val theme=config?.let {XposedHelpers.callMethod(it,"getTheme") as? Int}
        when(theme){null->UNKNOWN;3->FLUX;else->OTHER}
    } catch(_:Throwable){UNKNOWN}
}
