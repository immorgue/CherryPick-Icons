package com.cosmod.launcherglass.xposed

import android.os.Bundle
import android.os.Build
import com.cosmod.launcherglass.ui.*
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.MaterialMode

/**
 * Glass settings of a surface that may override the icon glass (folders, the
 * dock). While [own] is off the surface is tuned exactly like the icons.
 */
internal open class ScopedGlassSettings(private val keys:ScopedSettingsKeys,private val folder:Boolean) {
    data class Processing(val radius:Int,val quality:Int,val vibrancy:Int,val lightening:Int,val liquid:Boolean,val kyant:Boolean=false,val transparency:Int=0)
    @Volatile var own=false
        private set
    @Volatile var auto=false
        private set
    val hasOwnAppearance get()=own || auto
    val needsOwnBackdrop get()=hasOwnAppearance && selected?.needsBackdrop==true
    @Volatile private var selected:GlassStyle?=null
    @Volatile var processing=Processing(10,0,0,50,false)
        private set
    private var stamp:List<Any>?=null
    @Volatile private var saturation=100
    private data class Resolved(val base:GlassStyle,val palette:IconPalette,val tone:Int,val finish:GlassFinish,val style:GlassStyle)
    private var resolved:Resolved?=null
    private fun geometry(style:GlassStyle)=if(keys===DockSettingsKeys)style.copy(extendedLens=true,exactLensRadius=true)else style
    private fun inherited()=geometry(GlassStyle.icons().copy(folder=folder))
    fun style():GlassStyle {
        // Without own settings the surface uses the icon processing and plate tone.
        if(!hasOwnAppearance)return inherited()
        val style=selected ?: return inherited()
        if(auto)return style
        val palette=PrefsHelper.iconPalette
        if(folder && palette.autoLook>=0)return geometry(style.copy(plateColor=palette.background,
            opacity=if(style.colorOnly)100 else com.cosmod.launcherglass.util.AutoIconStyle.opacity(palette.autoLook,palette.autoDark),
            filter=PrefsHelper.backdropFilter(100,palette),finish=PrefsHelper.glassFinish.copy(transparency=processing.transparency)))
        val tone=SurfaceTone.base()
        val finish=PrefsHelper.glassFinish
        resolved?.takeIf {it.base===style && it.palette==palette && it.tone==tone && it.finish==finish}?.let {return it.style}
        return geometry(style.copy(plateColor=PrefsHelper.plateColor(tone,saturation),
            filter=PrefsHelper.backdropFilter(processing.vibrancy,palette,useAutoPreset=folder),
            finish=finish.copy(transparency=processing.transparency))).also {
                resolved=Resolved(style,palette,tone,finish,it)
            }
    }
    /** Blur radius actually used by the surface (0: the blur switch is off). */
    fun radius()=if(hasOwnAppearance)processing.radius else if(PrefsHelper.colorOnly)0 else PrefsHelper.blurRadius
    fun update(bundle:Bundle):Boolean {
        val signature=ArrayList<Any>()
        val autoKey=SettingsProvider.KEY_DOCK_AUTO_APPEARANCE
        val autoDock=keys===DockSettingsKeys && bundle.getBoolean(autoKey,SettingsDefaults.boolean(autoKey))
        val separate=bundle.getBoolean(keys.own,false)
        val automaticFolder=folder && PrefsHelper.iconPalette.autoLook>=0
        signature.add(autoDock)
        signature.add(separate)
        if(separate && !autoDock){
            signature.add(bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false))
            if(folder){
                signature.add(automaticFolder)
                signature.add(PrefsHelper.blurStyle)
                signature.add(PrefsHelper.kyant)
            }
            signature.add(bundle.getInt(SettingsProvider.KEY_BLUR_TRANSPARENCY,SettingsDefaults.integer(SettingsProvider.KEY_BLUR_TRANSPARENCY)))
            keys.integers.forEach {signature.add(bundle.getInt(keys.key(it),SettingsDefaults.integer(it)))}
            keys.booleans.forEach {signature.add(bundle.getBoolean(keys.key(it),SettingsDefaults.boolean(it)))}
        }
        if(stamp==signature)return false
        stamp=signature
        auto=autoDock
        own=separate
        if(autoDock) {
            val refract=Build.VERSION.SDK_INT>=33
            val radius=6
            val transparency=if(refract)SettingsDefaults.integer(SettingsProvider.KEY_BLUR_TRANSPARENCY)else 0
            processing=Processing(radius,0,25,50,refract,refract,transparency)
            selected=geometry(GlassStyle(refract,0,0,radius,0,null,plateColor=0,
                kyant=KyantOptics.automatic.takeIf {refract},finish=GlassFinish(transparency)))
            return true
        }
        if(!separate){
            selected=null
            resolved=null
            return true
        }
        fun number(key:String,max:Int)=bundle.getInt(keys.key(key),SettingsDefaults.integer(key)).coerceIn(0,max)
        fun bool(key:String)=bundle.getBoolean(keys.key(key),SettingsDefaults.boolean(key))
        val developer=bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false)
        val tone=bool(SettingsProvider.KEY_BLUR_TONE_ENABLED)
        val quality=if(developer)number(SettingsProvider.KEY_BLUR_QUALITY,100)else 0
        val mode=if(automaticFolder)PrefsHelper.blurStyle else MaterialMode.normalize(number(SettingsProvider.KEY_BLUR_STYLE,SettingsProvider.BLUR_STYLE_KYANT))
        val colorOnly=mode==SettingsProvider.BLUR_STYLE_COLOR
        val radius=if(!colorOnly && bool(SettingsProvider.KEY_BLUR_RADIUS_ENABLED))number(SettingsProvider.KEY_BLUR_RADIUS,50)else 0
        val kyant=!colorOnly && Build.VERSION.SDK_INT>=33
        val liquid=kyant && mode!=SettingsProvider.BLUR_STYLE_FROSTED
        val vibrancy=if(tone)number(SettingsProvider.KEY_BLUR_VIBRANCY,200)else 0
        val transparency=MaterialMode.blurTransparency(mode,automaticFolder,developer,
            bundle.getInt(SettingsProvider.KEY_BLUR_TRANSPARENCY,SettingsDefaults.integer(SettingsProvider.KEY_BLUR_TRANSPARENCY)))
        processing=Processing(radius,quality,vibrancy,if(tone)number(SettingsProvider.KEY_BLUR_LIGHTENING,100)else 50,liquid,kyant,transparency)
        saturation=if(tone)number(SettingsProvider.KEY_BLUR_SATURATION,200)else 100
        selected=GlassStyle(liquid,number(SettingsProvider.KEY_LIQUID_WIDTH,100),
            number(SettingsProvider.KEY_LIQUID_DISTORTION,300),radius,if(tone)number(SettingsProvider.KEY_BG_OPACITY,100)else 0,
            null,folder,
            PrefsHelper.plateColor(PrefsHelper.iconPalette.background,saturation),
            colorOnly=colorOnly,
            kyant=if(kyant)(if(automaticFolder)PrefsHelper.kyant else KyantOptics.read(bundle,keys::key)).forMode(mode)else null)
        return true
    }
}

internal object FolderSettings:ScopedGlassSettings(FolderSettingsKeys,true)

internal object DockSettings:ScopedGlassSettings(DockSettingsKeys,false)
