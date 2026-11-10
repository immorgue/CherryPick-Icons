package com.cosmod.launcherglass.xposed

import android.graphics.ColorMatrixColorFilter

data class GlassStyle(val liquid:Boolean,val width:Int,val distortion:Int,
    val radius:Int,val opacity:Int,val filter:ColorMatrixColorFilter?,val folder:Boolean=false,val plateColor:Int?=null,val extendedLens:Boolean=false,val exactLensRadius:Boolean=false,val colorOnly:Boolean=false){
    companion object {
        fun icons()=GlassStyle(PrefsHelper.liquidGlass,PrefsHelper.liquidWidth,PrefsHelper.liquidDistortion,
            if(PrefsHelper.colorOnly)0 else PrefsHelper.blurRadius,if(PrefsHelper.colorOnly)100 else PrefsHelper.bgOpacity,
            if(PrefsHelper.colorOnly)null else PrefsHelper.blurColorFilter,plateColor=PrefsHelper.plateColor(SurfaceTone.base()),colorOnly=PrefsHelper.colorOnly)
    }
}
