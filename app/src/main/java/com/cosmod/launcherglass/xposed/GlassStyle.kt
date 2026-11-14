package com.cosmod.launcherglass.xposed

import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider

data class GlassStyle(val liquid:Boolean,val width:Int,val distortion:Int,
    val radius:Int,val opacity:Int,val filter:ColorMatrixColorFilter?,val folder:Boolean=false,val plateColor:Int?=null,val extendedLens:Boolean=false,val exactLensRadius:Boolean=false,val colorOnly:Boolean=false,val kyant:KyantOptics?=null){
    companion object {
        fun icons()=GlassStyle(PrefsHelper.liquidGlass,PrefsHelper.liquidWidth,PrefsHelper.liquidDistortion,
            if(PrefsHelper.colorOnly)0 else PrefsHelper.blurRadius,if(PrefsHelper.colorOnly)100 else PrefsHelper.bgOpacity,
            if(PrefsHelper.colorOnly)null else PrefsHelper.blurColorFilter,plateColor=PrefsHelper.plateColor(SurfaceTone.base()),colorOnly=PrefsHelper.colorOnly,
            kyant=PrefsHelper.kyant.forMode(PrefsHelper.blurStyle).takeIf {PrefsHelper.kyantGlass})
    }
}

data class KyantOptics @JvmOverloads constructor(val width:Int,val distortion:Int,val highlight:Int,val dispersion:Int=50,val vibrancy:Int=100){
    fun forMode(mode:Int)=if(mode==SettingsProvider.BLUR_STYLE_FROSTED)copy(width=0,distortion=0,highlight=0,dispersion=0,vibrancy=100)else this
    val needsBackdrop get()=distortion>0 || highlight>0 || dispersion>0 || vibrancy!=100
    val light get()=highlight*.0035f
    val dispersionFactor get()=dispersion*.01f
    val vibrancyFactor get()=vibrancy*.01f
    fun band(edge:Float)=edge*(.025f+width*.00225f)
    fun amount(edge:Float)=edge*distortion*.0018f

    companion object {
        internal fun read(bundle:Bundle?=null,key:(String)->String={it}):KyantOptics {
            fun number(base:String):Int {
                val default=SettingsDefaults.integer(base)
                return (bundle?.getInt(key(base),default) ?: default).coerceIn(0,if(base==SettingsProvider.KEY_KYANT_VIBRANCY)400 else 300)
            }
            return KyantOptics(number(SettingsProvider.KEY_KYANT_WIDTH),number(SettingsProvider.KEY_KYANT_DISTORTION),
                number(SettingsProvider.KEY_KYANT_HIGHLIGHT),
                number(SettingsProvider.KEY_KYANT_DISPERSION),
                number(SettingsProvider.KEY_KYANT_VIBRANCY))
        }
    }
}
