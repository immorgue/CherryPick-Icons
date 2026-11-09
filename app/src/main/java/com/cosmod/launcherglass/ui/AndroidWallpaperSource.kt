package com.cosmod.launcherglass.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.wallpaper.WallpaperService

/** Public Android fallback: installed live wallpaper services and the current
 * wallpaper. Android exposes no portable catalogue of full OEM themes. */
internal object AndroidWallpaperSource:SystemThemeSource {
    override fun available(context:Context)=true
    override fun load(context:Context):List<ThemeCatalog.Theme> {
        val manager=WallpaperManager.getInstance(context)
        val active=manager.wallpaperInfo?.component
        val list=ArrayList<ThemeCatalog.Theme>()
        if(active==null)list.add(ThemeCatalog.Theme(manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM),0,true,"","",
            "android",context.getString(com.cosmod.launcherglass.R.string.ui_theme_current),"android:current"))
        for(service in context.packageManager.queryIntentServices(Intent(WallpaperService.SERVICE_INTERFACE),android.content.pm.PackageManager.GET_META_DATA)){
            val info=service.serviceInfo ?: continue
            val component=ComponentName(info.packageName,info.name)
            list.add(ThemeCatalog.Theme(component.flattenToString().hashCode(),list.size,component==active,"",component.flattenToString(),
                "android",service.loadLabel(context.packageManager).toString(),"android:live:${component.flattenToString()}"))
        }
        return list
    }
    @android.annotation.SuppressLint("MissingPermission") // Optional read; denied current-image access returns null.
    override fun thumbnail(context:Context,theme:ThemeCatalog.Theme):Bitmap? {return try {
        val manager=WallpaperManager.getInstance(context)
        val drawable=(if(theme.current)manager.drawable else {
            val component=ComponentName.unflattenFromString(theme.wallpaper) ?: return null
            val resolved=context.packageManager.resolveService(Intent(WallpaperService.SERVICE_INTERFACE).setComponent(component),android.content.pm.PackageManager.GET_META_DATA) ?: return null
            android.app.WallpaperInfo(context,resolved).loadThumbnail(context.packageManager)
        }) ?: return null
        val width=drawable.intrinsicWidth.coerceIn(1,600);val height=drawable.intrinsicHeight.coerceIn(1,1100)
        Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {image ->drawable.setBounds(0,0,width,height);drawable.draw(Canvas(image))}
    }catch(_:Exception){null}}
    override fun preview(context:Context,theme:ThemeCatalog.Theme):Boolean=try {
        val component=ComponentName.unflattenFromString(theme.wallpaper)
        context.startActivity(if(component==null)Intent(Intent.ACTION_SET_WALLPAPER)else
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,component))
        true
    }catch(_:Exception){false}
}
