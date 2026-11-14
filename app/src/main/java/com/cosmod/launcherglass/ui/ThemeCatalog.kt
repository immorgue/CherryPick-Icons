package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Bitmap

/** Carousel and profiles depend on capabilities, never on a vendor database. */
internal interface SystemThemeSource {
    fun available(context:Context):Boolean
    fun load(context:Context):List<ThemeCatalog.Theme>
    fun thumbnail(context:Context,theme:ThemeCatalog.Theme):Bitmap?
    fun preview(context:Context,theme:ThemeCatalog.Theme):Boolean
}
internal object ThemeCatalog {
    data class Theme(val id:Int,val rank:Int,val current:Boolean,val thumbnail:String,val wallpaper:String,
        val source:String="oplus",val label:String?=null,val profileKey:String="system-theme:$id")
    private fun source(context:Context):SystemThemeSource =
        if(OplusThemeSource.available(context))OplusThemeSource else AndroidWallpaperSource
    fun load(context:Context):List<Theme> {
        val backend=source(context)
        return try {backend.load(context)}catch(t:Throwable){
            com.cosmod.launcherglass.util.GlassLogger.e("Themes","OEM theme catalogue unavailable; using public Android wallpaper capabilities",t)
            if(backend===AndroidWallpaperSource)emptyList()else AndroidWallpaperSource.load(context)
        }
    }
    fun thumbnail(context:Context,theme:Theme)=
        (if(theme.source=="oplus")OplusThemeSource else AndroidWallpaperSource).thumbnail(context,theme)
    fun preview(context:Context,theme:Theme)=
        (if(theme.source=="oplus")OplusThemeSource else AndroidWallpaperSource).preview(context,theme)
}
