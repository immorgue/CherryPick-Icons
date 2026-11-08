package com.cosmod.launcherglass.util

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streams APKs without loading them into memory; publishes only a complete ZIP. */
internal object ReportArchive {
    data class Apk(val entry:String,val file:File)

    fun write(destination:File,diagnostics:String,device:String,apks:List<Apk>):File {
        require(apks.isNotEmpty()) {"Installed launcher APK was not found"}
        require(apks.map {it.entry}.toSet().size==apks.size) {"Duplicate APK names"}
        val partial=File(destination.parentFile,destination.name+".partial")
        try {
            ZipOutputStream(partial.outputStream().buffered()).use {zip ->
                fun text(name:String,value:String) {
                    zip.putNextEntry(ZipEntry(name));zip.write(value.toByteArray(Charsets.UTF_8));zip.closeEntry()
                }
                text("diagnostics.txt",diagnostics)
                text("device.txt",device)
                val checksums=StringBuilder()
                // APKs are compressed already. Avoid recompressing tens of MB on the phone.
                zip.setLevel(0)
                val buffer=ByteArray(128*1024)
                for(apk in apks) {
                    require(apk.entry.startsWith("launcher/") && apk.entry.endsWith(".apk") &&
                        apk.entry.split('/').none {it==".." || it.isEmpty()} && '\\' !in apk.entry) {"Invalid APK entry"}
                    val digest=MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(apk.entry))
                    var copied=0L
                    apk.file.inputStream().buffered().use {input ->
                        while(true) {
                            val count=input.read(buffer)
                            if(count<0)break
                            zip.write(buffer,0,count);digest.update(buffer,0,count);copied+=count
                        }
                    }
                    check(copied>0 && copied==apk.file.length()) {"Launcher APK changed while creating report; retry"}
                    zip.closeEntry()
                    checksums.append(digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)})
                        .append("  ").append(apk.entry).append('\n')
                }
                zip.setLevel(6)
                text("SHA256SUMS.txt",checksums.toString())
            }
            check(partial.renameTo(destination)) {"Could not finish diagnostic archive"}
            return destination
        }catch(t:Throwable){partial.delete();throw t}
    }
}
