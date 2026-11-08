package com.cosmod.launcherglass.util

import android.graphics.Bitmap
import android.graphics.ColorSpace

/** Keep an immutable GPU frame alive after its OEM Bitmap wrapper is recycled. */
internal object HardwareBitmapLease {
    fun retain(source:Bitmap):Bitmap? {
        if(source.isRecycled)return null
        if(source.config==Bitmap.Config.HARDWARE){
            // Bitmap.copy(HARDWARE) goes through a readback/upload on this OEM.
            // A new wrapper owns a reference to the same immutable buffer;
            // acquiring/closing the temporary handle does not copy pixels.
            try {
                source.hardwareBuffer?.use {buffer ->
                    Bitmap.wrapHardwareBuffer(buffer,source.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))?.let {return it}
                }
            }catch(_:Exception){ /* Compatibility fallback retains its own pixels. */ }
        }
        return source.copy(if(source.config==Bitmap.Config.HARDWARE)Bitmap.Config.HARDWARE else Bitmap.Config.ARGB_8888,false)
    }
}
