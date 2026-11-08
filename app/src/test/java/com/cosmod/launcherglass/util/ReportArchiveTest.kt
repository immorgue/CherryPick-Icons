package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.zip.ZipFile

class ReportArchiveTest {
    @get:Rule val temporary=TemporaryFolder()

    @Test fun reportContainsExactBaseAndSplitBytesWithChecksums() {
        val base=temporary.newFile("base.apk").apply {writeBytes(ByteArray(300_000) {(it*37).toByte()})}
        val split=temporary.newFile("split.apk").apply {writeBytes(byteArrayOf(1,2,3,4))}
        val target=java.io.File(temporary.root,"report.zip")
        val files=listOf(ReportArchive.Apk("launcher/test/base.apk",base),ReportArchive.Apk("launcher/test/split_1.apk",split))
        ReportArchive.write(target,"Логи\nsettings=test","device info",files)
        ZipFile(target).use {zip ->
            assertEquals("Логи\nsettings=test",zip.getInputStream(zip.getEntry("diagnostics.txt")).reader(Charsets.UTF_8).readText())
            val checksums=zip.getInputStream(zip.getEntry("SHA256SUMS.txt")).reader().readText()
            for(apk in files) {
                assertArrayEquals(apk.file.readBytes(),zip.getInputStream(zip.getEntry(apk.entry)).readBytes())
                val hash=MessageDigest.getInstance("SHA-256").digest(apk.file.readBytes()).joinToString("") {"%02x".format(it.toInt() and 255)}
                assertTrue(checksums.contains("$hash  ${apk.entry}\n"))
            }
            assertEquals(5,zip.size())
        }
        assertFalse(java.io.File(target.path+".partial").exists())
    }

    @Test fun unreadableApkDoesNotPublishIncompleteReport() {
        val target=java.io.File(temporary.root,"report.zip")
        try {
            ReportArchive.write(target,"logs","device",listOf(ReportArchive.Apk("launcher/test/base.apk",java.io.File(temporary.root,"missing.apk"))))
            fail("Missing APK must be reported")
        }catch(_:java.io.IOException){}
        assertFalse(target.exists())
        assertFalse(java.io.File(target.path+".partial").exists())
    }
}
