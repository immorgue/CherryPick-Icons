package com.cosmod.launcherglass.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import java.io.File
import java.util.concurrent.TimeUnit

internal object PreviewWallpaper {
    /** Read only the wallpaper asset: theme thumbnails can contain a baked clock. */
    fun read(context:Context):Bitmap? {
        val manager=WallpaperManager.getInstance(context)
        val live = manager.wallpaperInfo
        val sources = if (live == null) listOf("wallpaper") else listOf("wallpaper_screenshot.png")
        for (name in sources) {
            val file=File(context.cacheDir,"appearance-wallpaper-preview")
            fun quote(path:String)="'"+path.replace("'","'\\''")+"'"
            try {
                file.writeBytes(byteArrayOf())
                val user=android.os.Process.myUid()/100000
                val source="/data/system/users/$user/$name"
                val process=ProcessBuilder("su","-c","cat $source > ${quote(file.path)}").start()
                if(!process.waitFor(8,TimeUnit.SECONDS))process.destroyForcibly()
                else if(process.exitValue()==0 && file.length()>0){
                    val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeFile(file.path,options)
                    options.inSampleSize=1
                    while(options.outHeight/options.inSampleSize>1200)options.inSampleSize*=2
                    options.inJustDecodeBounds=false
                    BitmapFactory.decodeFile(file.path,options)?.let {return it}
                }
            }catch(_:Exception){}finally {file.delete()}
        }
        if (live != null) {
            com.cosmod.launcherglass.util.WallpaperHelper.readSystemSnapshot(context)?.let { return it }
            val thumbnail = runCatching { live.loadThumbnail(context.packageManager) }.getOrNull()
            if (thumbnail != null) {
                val width = thumbnail.intrinsicWidth.coerceIn(1, 600)
                val height = thumbnail.intrinsicHeight.coerceIn(1, 1100)
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    thumbnail.setBounds(0, 0, width, height)
                    thumbnail.draw(Canvas(it))
                }
            }
        }
        try { ThemeCatalog.load(context).firstOrNull { it.current }?.let { ThemeCatalog.thumbnail(context,it) } }
            catch (_: Exception) { null }?.let { return it }
        // Wallpaper providers can deny both image and thumbnail access.
        // Keep a useful background using their published wallpaper colors.
        val colors = runCatching { manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
        val first = colors?.primaryColor?.toArgb() ?: return null
        val last = colors.tertiaryColor?.toArgb() ?: colors.secondaryColor?.toArgb() ?: first
        return Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888).also {
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, 360f, 720f, first, last, Shader.TileMode.CLAMP)
            }
            Canvas(it).drawRect(0f, 0f, 360f, 720f, paint)
        }
    }
}
