package com.cosmod.launcherglass.xposed

/** Keep the outgoing dock alive until its fade reaches zero. */
internal class DockPresence {
    private var initialized=false
    var hasItems=false
        private set
    var fraction=0f
        private set
    val target:Float get()=if(hasItems)1f else 0f
    val drawable:Boolean get()=hasItems || fraction>0f
    fun observe(present:Boolean):Boolean {
        if(!initialized){initialized=true;hasItems=present;fraction=target;return false}
        if(hasItems==present)return false
        hasItems=present
        return true
    }
    fun update(value:Float){if(value.isFinite())fraction=value.coerceIn(0f,1f)}
}
