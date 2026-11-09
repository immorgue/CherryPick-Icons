package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import java.util.WeakHashMap

/** A composed stationary surface keeps its own wallpaper origin while pages scroll. */
internal object BackdropCoordinates {
    private val offsets=WeakHashMap<Bitmap,Float>()
    @Synchronized fun register(bitmap:Bitmap,offset:Float){offsets[bitmap]=offset}
    @Synchronized fun offset(bitmap:Bitmap):Float?=offsets[bitmap]
}
