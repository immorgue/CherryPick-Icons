package com.cosmod.launcherglass.xposed

import com.cosmod.launcherglass.bridge.HookReflection

/** 17.5.4 moves LauncherIconConfig into the verified to5 class. */
internal object NativeLauncherIcons {
    fun type(loader:ClassLoader)=HookReflection.findClassIfExists("com.android.launcher.theme.LauncherIconConfig",loader)
        ?: HookReflection.findClassIfExists("to5",loader)?.takeIf {type ->
            type.declaredFields.any {it.name=="q" && it.type.name=="com.oplus.uxicon.helper.IconConfig"}
        }
    fun compact(type:Class<*>)=type.name=="to5"
    fun instance(type:Class<*>)=HookReflection.getStaticObjectField(type,if(compact(type))"D" else "sInstance")
    fun config(instance:Any)=HookReflection.getObjectField(instance,if(compact(instance.javaClass))"q" else "mIconConfig")
}
