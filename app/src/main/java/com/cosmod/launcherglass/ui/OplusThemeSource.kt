package com.cosmod.launcherglass.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.concurrent.TimeUnit

/** Read-only OEM catalogue. No database writes and no direct wallpaper replacement. */
internal object OplusThemeSource:SystemThemeSource {

    private val ROOT get()="/data/user/${android.os.Process.myUid()/100000}/com.oplus.wallpapers/"
    private fun quote(value:String)="'"+value.replace("'","'\\''")+"'"
    fun readFile(path:String,limit:Int):ByteArray {
        require(path.startsWith(ROOT) && !path.contains("..") && !path.contains('\n'))
        val process=ProcessBuilder("su","-M","-c","cat "+quote(path)).redirectErrorStream(true).start()
        val output=java.io.ByteArrayOutputStream()
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val reader=Thread {
            try {process.inputStream.use {input ->
                val bytes=ByteArray(16384)
                while(true){val n=input.read(bytes);if(n<0)break;check(output.size()+n<=limit);output.write(bytes,0,n)}
            }}catch(t:Throwable){failure.set(t);process.destroyForcibly()}
        }.apply {isDaemon=true;start()}
        if(!process.waitFor(8,TimeUnit.SECONDS)){process.destroyForcibly();error("Theme catalogue read timed out")}
        reader.join(1000);check(!reader.isAlive && failure.get()==null && process.exitValue()==0){
            "Theme catalogue unavailable: exit=${process.exitValue()}, reader=${failure.get()?.javaClass?.simpleName}, detail=${output.toByteArray().take(180).map {if(it.toInt() in 32..126)it.toInt().toChar()else ' '}.joinToString("")}"
        }
        return output.toByteArray()
    }
    override fun available(context:Context):Boolean=try {
        val activity=context.packageManager.getActivityInfo(android.content.ComponentName("com.oplus.wallpapers","com.oplus.wallpapers.themes.edit.ThemeEditActivity"),0)
        activity.enabled && activity.applicationInfo.enabled
    }catch(_:Exception){false}
    override fun load(context:Context):List<ThemeCatalog.Theme> {
        val directory=File(context.cacheDir,"theme-catalog").apply {mkdirs()}
        val name="InspirationTheme-db"
        for(suffix in listOf("","-wal","-shm")){
            val target=File(directory,name+suffix)
            try {target.writeBytes(readFile(ROOT+"databases/"+name+suffix,32*1024*1024))}
            catch(t:Throwable){if(suffix.isEmpty())throw t;target.delete()}
        }
        val themes=ArrayList<ThemeCatalog.Theme>()
        SQLiteDatabase.openDatabase(File(directory,name).path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
            db.rawQuery("SELECT id,rank,inUse,thumbnail,homeWallpaper FROM InspirationThemeData ORDER BY rank,id",null).use {cursor ->
                while(cursor.moveToNext() && themes.size<300)themes.add(ThemeCatalog.Theme(cursor.getInt(0),cursor.getInt(1),cursor.getInt(2)!=0,cursor.getString(3),cursor.getString(4)))
            }
        }
        return themes
    }
    private fun resource(uri:String):String {
        val parsed=Uri.parse(uri)
        require(parsed.scheme=="content" && parsed.authority=="com.oplus.wallpapers.fileProvider")
        val segments=parsed.pathSegments
        require(segments.size>=3 && segments[0]=="theme_path" && segments.drop(1).all {it.matches(Regex("[a-zA-Z0-9_.-]+")) && it!=".."})
        return ROOT+"files/theme_resource/"+segments.drop(1).joinToString("/")
    }
    override fun thumbnail(context:Context,theme:ThemeCatalog.Theme):Bitmap? {
        val data=readFile(resource(theme.thumbnail),12*1024*1024)
        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(data,0,data.size,options)
        check(options.outWidth>0 && options.outHeight>0)
        options.inSampleSize=maxOf(1,options.outHeight/1000);options.inJustDecodeBounds=false
        return BitmapFactory.decodeByteArray(data,0,data.size,options)
    }
    /** Static theme identity is the same content hash reported by the launcher. */
    fun identity(theme:ThemeCatalog.Theme):String?=try {
        val data=readFile(resource(theme.wallpaper),64*1024*1024)
        "static:"+java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it.toInt() and 255)}
    }catch(_:Throwable){null}
    override fun preview(context:Context,theme:ThemeCatalog.Theme):Boolean {
        val id=theme.id;require(id>0)
        val apk=context.applicationInfo.sourceDir
        val command="CLASSPATH="+quote(apk)+" app_process /system/bin com.cosmod.launcherglass.ui.SystemThemePreview "+id
        val process=ProcessBuilder("su","-c",command).redirectErrorStream(true).start()
        val drain=Thread {try {process.inputStream.use {input ->val buffer=ByteArray(4096);while(input.read(buffer)>=0){}}}catch(_:Throwable){}}.apply {isDaemon=true;start()}
        val done=process.waitFor(12,TimeUnit.SECONDS)
        if(!done)process.destroyForcibly();drain.join(500)
        return done && process.exitValue()==0
    }
}
