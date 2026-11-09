package com.cosmod.launcherglass.xposed

import android.app.WallpaperManager
import android.content.Context
import android.os.Bundle
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.GlassLogger
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Wallpaper files are read by the privileged launcher, never from an animation callback. */
internal object WallpaperProfileClient {
    private val worker=Executors.newSingleThreadExecutor()
    private val epoch=AtomicInteger()
    @android.annotation.SuppressLint("MissingPermission") // Executed with the launcher's identity; guarded below.
    fun sync(context:Context){
        val ticket=epoch.incrementAndGet()
        worker.execute {
            try {
                val manager=WallpaperManager.getInstance(context)
                val before=manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
                val live=manager.wallpaperInfo
                val identity:String
                val label:String
                if(live!=null){
                    // A service may host many videos. Do not wrongly restore a different
                    // video's profile merely because its service component matches.
                    identity="live:${live.component.flattenToString()}:$before"
                    label=live.loadLabel(context.packageManager).toString()+" · #$before"
                }else{
                    val granted=android.content.pm.PackageManager.PERMISSION_GRANTED
                    if(context.checkSelfPermission("android.permission.READ_WALLPAPER_INTERNAL")!=granted &&
                        context.checkSelfPermission(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)!=granted)return@execute
                    val digest=MessageDigest.getInstance("SHA-256")
                    val file=manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) ?: return@execute
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(file).use {input ->
                        val buffer=ByteArray(32768);var total=0L
                        while(true){val n=input.read(buffer);if(n<0)break;total+=n;check(total<=128L*1024*1024);digest.update(buffer,0,n)}
                        check(total>0)
                    }
                    identity="static:"+digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
                    label="Wallpaper · "+identity.takeLast(8)
                }
                if(ticket!=epoch.get() || manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)!=before)return@execute
                context.contentResolver.call(SettingsProvider.CONTENT_URI,SettingsProvider.METHOD_WALLPAPER_CHANGED,null,
                    Bundle().apply {putString("identity",identity);putString("label",label)})
            }catch(t:Throwable){GlassLogger.limited("WallpaperProfiles","detect"){"Wallpaper identity unavailable: ${t.javaClass.simpleName}"}}
        }
    }
}
