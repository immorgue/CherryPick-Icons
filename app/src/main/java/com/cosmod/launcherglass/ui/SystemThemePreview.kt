package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.Intent
import android.os.Parcelable

/** Launch the original OEM theme editor with its own parcelable, using root's
 * package context only to cross the OEM signature boundary. No theme DB writes. */
@androidx.annotation.Keep
object SystemThemePreview {
    @JvmStatic fun main(args:Array<String>){
        try {
            require(args.size==1);val id=args[0].toInt();require(id>0)
            android.os.Looper.prepareMainLooper()
            val thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
            val system=thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
            val context=system.createPackageContext("com.oplus.wallpapers",Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            val type=context.classLoader.loadClass("com.oplus.wallpapers.core.data.model.themes.InspirationThemeData")
            val model=type.getConstructor().newInstance() as Parcelable
            type.getMethod("setId",Integer::class.java).invoke(model,id)
            // The OEM default model is marked in-use. Its reconciliation
            // prefers the current theme over a different requested ID when
            // that flag is true. This is a saved selection, not current state.
            type.getMethod("setInUse",Boolean::class.javaPrimitiveType).invoke(model,false)
            val intent=Intent().setClassName("com.oplus.wallpapers","com.oplus.wallpapers.themes.edit.ThemeEditActivity")
                // The OEM editor is singleTop: CLEAR_TOP alone can retain its
                // old model. Start a fresh editor task for this selection.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("themeData",model)
                .putExtra("from_type",3).putExtra("editMode",2)
            // Match the system shell's activity launch path. A package Context
            // attributes the root process to the OEM UID and is rejected by
            // modern ColorOS before the activity receives its parcelable.
            val service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            val start=service.javaClass.methods.first {it.name=="startActivityAsUser" && it.parameterTypes.any {p ->p==Intent::class.java}}
            var stringIndex=0
            val parameters=start.parameterTypes.map {parameter ->when(parameter){
                Intent::class.java ->intent
                String::class.java ->if(stringIndex++==0)"com.android.shell" else null
                Int::class.javaPrimitiveType ->0
                Boolean::class.javaPrimitiveType ->false
                else ->null
            }}.toTypedArray()
            start.isAccessible=true
            val result=start.invoke(service,*parameters) as Number
            check(result.toInt()>=0){"System theme launch failed: $result"}
            kotlin.system.exitProcess(0)
        }catch(t:Throwable){t.printStackTrace();kotlin.system.exitProcess(1)}
    }
}
