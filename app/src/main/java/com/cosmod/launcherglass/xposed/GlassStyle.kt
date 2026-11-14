package com.cosmod.launcherglass.xposed

import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider

data class GlassStyle(val liquid:Boolean,val width:Int,val distortion:Int,
    val radius:Int,val opacity:Int,val filter:ColorMatrixColorFilter?,val folder:Boolean=false,val plateColor:Int?=null,val extendedLens:Boolean=false,val exactLensRadius:Boolean=false,val colorOnly:Boolean=false,val kyant:KyantOptics?=null){
    val experimentalOptics get()=kyant==null && PrefsHelper.experimentalLiquid
    companion object {
        fun icons()=GlassStyle(PrefsHelper.liquidGlass,PrefsHelper.liquidWidth,PrefsHelper.liquidDistortion,
            if(PrefsHelper.colorOnly)0 else PrefsHelper.blurRadius,if(PrefsHelper.colorOnly)100 else PrefsHelper.bgOpacity,
            if(PrefsHelper.colorOnly)null else PrefsHelper.blurColorFilter,plateColor=PrefsHelper.plateColor(SurfaceTone.base()),colorOnly=PrefsHelper.colorOnly,
            kyant=PrefsHelper.kyant.takeIf {PrefsHelper.kyantGlass})
    }
}

data class KyantOptics @JvmOverloads constructor(val width:Int,val distortion:Int,val highlight:Int,val magnifier:Boolean=false,
    val controlCenter:Boolean=false,val dispersion:Int=50,val zoom:Int=28,val vibrancy:Int=100){
    val needsBackdrop get()=distortion>0 || highlight>0 || magnifier || controlCenter || dispersion>0 || zoom>0 || vibrancy!=100
    val light get()=highlight*.0035f
    val dispersionFactor get()=dispersion*.01f
    val zoomFactor get()=zoom*.01f
    val vibrancyFactor get()=if(controlCenter)(vibrancy*.015f)else (vibrancy*.01f)
    fun band(edge:Float)=edge*(if(controlCenter).045f+width*.003f else .025f+width*.00225f)
    fun amount(edge:Float)=edge*distortion*(if(controlCenter).0034f else .0018f)

    companion object {
        internal fun read(bundle:Bundle?=null,key:(String)->String={it}):KyantOptics {
            fun number(base:String):Int {
                val default=SettingsDefaults.integer(base)
                return (bundle?.getInt(key(base),default) ?: default).coerceIn(0,200)
            }
            val center=bundle?.getBoolean(key(SettingsProvider.KEY_KYANT_CONTROL_CENTER),false)==true
            val magnifier=!center && bundle?.getBoolean(key(SettingsProvider.KEY_KYANT_MAGNIFIER),false)==true
            return KyantOptics(number(SettingsProvider.KEY_KYANT_WIDTH),number(SettingsProvider.KEY_KYANT_DISTORTION),
                number(SettingsProvider.KEY_KYANT_HIGHLIGHT),magnifier,center,
                number(SettingsProvider.KEY_KYANT_DISPERSION),number(SettingsProvider.KEY_KYANT_ZOOM),
                number(SettingsProvider.KEY_KYANT_VIBRANCY))
        }
    }
}
