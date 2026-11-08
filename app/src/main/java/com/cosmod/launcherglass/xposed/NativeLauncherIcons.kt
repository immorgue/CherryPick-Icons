package com.cosmod.launcherglass.xposed

import de.robv.android.xposed.XposedHelpers

/** 17.5.4 moves LauncherIconConfig into the verified to5 class. */
internal object NativeLauncherIcons {
    fun type(loader:ClassLoader)=XposedHelpers.findClassIfExists("com.android.launcher.theme.LauncherIconConfig",loader)
        ?: XposedHelpers.findClassIfExists("to5",loader)?.takeIf {type ->
            type.declaredFields.any {it.name=="q" && it.type.name=="com.oplus.uxicon.helper.IconConfig"}
        }
    fun compact(type:Class<*>)=type.name=="to5"
    fun instance(type:Class<*>)=XposedHelpers.getStaticObjectField(type,if(compact(type))"D" else "sInstance")
    fun config(instance:Any)=XposedHelpers.getObjectField(instance,if(compact(instance.javaClass))"q" else "mIconConfig")
}
