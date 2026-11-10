package com.cosmod.launcherglass.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import com.cosmod.launcherglass.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object DiagnosticReport {
    private val launchers=listOf("com.android.launcher","com.oppo.launcher","com.oplus.launcher")

    @Suppress("DEPRECATION")
    fun create(context:Context,prefs:SharedPreferences):File {
        val pm=context.packageManager
        val home=pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        val apks=ArrayList<ReportArchive.Apk>()
        val device=buildString {
            appendLine("CherryPick Icons ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}; ${Build.DEVICE}")
            appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
            appendLine("Build: ${Build.DISPLAY}\nFingerprint: ${Build.FINGERPRINT}")
            appendLine("Date: ${Date()}\nDefault HOME: $home")
            for(name in launchers) {
                val info=try {pm.getPackageInfo(name,0)}catch(_:PackageManager.NameNotFoundException){continue}
                val app=checkNotNull(info.applicationInfo) {"No application info for $name"}
                appendLine("\nLauncher: $name ${info.versionName} (${info.longVersionCode})")
                val sources=listOf(app.sourceDir)+app.splitSourceDirs.orEmpty().toList()
                sources.forEachIndexed {index,path ->
                    val entry=if(index==0)"base.apk" else "split_${index}.apk"
                    apks.add(ReportArchive.Apk("launcher/$name/$entry",File(path)))
                    appendLine("$entry <- $path")
                }
            }
        }
        check(apks.isNotEmpty()) {"Installed supported launcher APK was not found"}
        val directory=File(context.cacheDir,"diagnostics").apply {check(isDirectory || mkdirs())}
        // Capture before collecting logs/APKs, so history ends at the report request.
        val frameTrace=if(prefs.getBoolean("developer_mode",false))PassiveFrameTrace.snapshot(directory)else null
        val report=buildString {
            appendLine(device)
            appendLine("\nSettings:")
            appendLine("Passive FrameTimeline: ${if(frameTrace!=null)"attached" else "not available; no recording started"}")
            prefs.all.toSortedMap().forEach {(key,value)->appendLine("$key=$value")}
            appendLine("\nLogs:");append(GlassLogger.readLogs(context))
        }
        val timestamp=SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS",Locale.US).format(Date())
        return try {ReportArchive.write(File(directory,"CherryPick_report_$timestamp.zip"),report,device,apks,frameTrace)}
        finally{frameTrace?.delete()}
    }
}
