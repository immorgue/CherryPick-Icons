package com.cosmod.launcherglass.xposed

import android.os.Bundle
import com.cosmod.launcherglass.ui.ModuleFeatures

internal object FeaturePolicy {
    @Volatile var icons=true; private set
    @Volatile var desktop=true; private set
    @Volatile var layout=true; private set
    fun load(bundle:Bundle):Boolean {
        val i=bundle.getBoolean(ModuleFeatures.ICONS,true)
        val d=bundle.getBoolean(ModuleFeatures.DESKTOP,true)
        val l=d && bundle.getBoolean(ModuleFeatures.LAYOUT,true)
        val changed=i!=icons || d!=desktop || l!=layout
        icons=i;desktop=d;layout=l
        return changed
    }
    private val ALWAYS=java.util.function.BooleanSupplier {true}
    @JvmStatic fun guard(owner:String):java.util.function.BooleanSupplier {
        val type=owner.substringAfterLast('.').substringBefore('$')
        return when(type) {
            "IconGlassHook","DynamicIconForeground","FluxIconSupport","Oxygen16IconSupport","SpecialIconSupport","NativeIconAppearance" -> java.util.function.BooleanSupplier {icons}
            else -> ALWAYS // Bootstrap, cleanup and native animation pairing stay active.
        }
    }
}
