package com.cosmod.launcherglass.xposed

import android.content.Context
import android.content.res.Configuration
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/** Read the same resolved icon appearance as Flux, including its independent light/dark setting. */
object NativeIconAppearance {
    private var attempted = false
    private var configClass: Class<*>? = null
    private var darkMethod: Method? = null

    fun read(context: Context, configuration: Configuration): Boolean? {
        if (!attempted) {
            attempted = true
            configClass = NativeLauncherIcons.type(context.classLoader)
            darkMethod = try {
                configClass?.let {it.getDeclaredMethod(if(NativeLauncherIcons.compact(it))"r" else "isDarkIconEnable", Configuration::class.java)}?.apply { isAccessible = true }
            } catch (_: Throwable) { null }
        }
        return try {
            // Do not initialize native icon configuration before the launcher itself is ready.
            val type = configClass ?: return null
            val instance = NativeLauncherIcons.instance(type) ?: return null
            NativeLauncherIcons.config(instance) ?: return null
            darkMethod?.invoke(null, configuration) as? Boolean
        } catch (_: Throwable) { null }
    }
}
