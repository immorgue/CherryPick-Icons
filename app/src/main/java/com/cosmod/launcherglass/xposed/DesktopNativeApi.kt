package com.cosmod.launcherglass.xposed

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/** Aliases verified against local 16.6.13, 16.6.20, 17.3.9, 17.3.12 and 17.5.4 DEX. */
internal class DesktopNativeApi(loader:ClassLoader) {
    private val int=Int::class.javaPrimitiveType!!
    private val bool=Boolean::class.javaPrimitiveType!!
    private val launcher=XposedHelpers.findClass("com.android.launcher.Launcher",loader)
    val layout=XposedHelpers.findClassIfExists("com.android.common.util.LauncherLayoutUtils",loader)
        ?: XposedHelpers.findClass("com.android.common.util.c",loader)
    val compact=layout.name=="com.android.common.util.c"
    val helper=XposedHelpers.findClass(if(compact)"tv5" else "com.android.launcher.togglebar.LayoutSettingsHelper",loader)
    val legacy=XposedHelpers.findClassIfExists("com.android.launcher.bean.WorkSpaceScreenData",loader)!=null
    private val prefix=arrayOf(Context::class.java,int,int,int,int,bool,bool,bool,bool)
    val calculate:Method=layout.getDeclaredMethod(if(legacy)"arrangeDesktopLayouts" else "c",
        *if(compact)prefix+arrayOf(ArrayList::class.java,bool,HashMap::class.java,ArrayList::class.java)
        else prefix+arrayOf(if(legacy)List::class.java else ArrayList::class.java))
    val apply:Method=helper.getDeclaredMethod(if(compact)"b" else "applyLayoutSettings",launcher,int,int,bool)
    val cleanup:Method=if(legacy)layout.getDeclaredMethod("clearEmptyGroupItem",ContentResolver::class.java,int,int)
        else layout.getDeclaredMethod("p",int,int,ContentResolver::class.java)
    val migrate:Method=layout.getDeclaredMethod(if(legacy)"switchCellsLayout" else if(compact)"F" else "B",
        Context::class.java,IntArray::class.java,IntArray::class.java,bool,bool,BooleanArray::class.java)
    val job=XposedHelpers.findClass(when {compact -> "sv5";legacy -> "com.android.launcher.togglebar.LayoutSettingsHelper\$SwitchLayoutTask";else -> "com.android.launcher.togglebar.LayoutSettingsHelper\$f"},loader)
    val owner=job.declaredFields.single {WeakReference::class.java.isAssignableFrom(it.type)}.apply {isAccessible=true}
    val completion=job.getDeclaredMethod("onPostExecute",Any::class.java)
    val screenCountSetter=if(compact)null else helper.getDeclaredMethod("setAddNewScreenQuantityWhenSwitchLayout",int)
    private val busyMethod=if(compact)null else helper.getDeclaredMethod("isInApplySwitchLayout")
    private val busyField=if(compact)helper.getDeclaredField("i").apply {check(type==bool);isAccessible=true} else null
    private val page=XposedHelpers.findClass(when {legacy -> "com.android.launcher.bean.WorkSpaceScreenData";compact -> "f4c";else -> "j3.d"},loader)
    private val hotseat=page.getDeclaredField(if(legacy)"mHotSeat" else "c").apply {check(type==bool);isAccessible=true}
    private val rank=page.getDeclaredField(if(legacy)"mScreenRank" else "b").apply {check(type==int);isAccessible=true}
    private val icons=page.getDeclaredField(if(legacy)"workspaceItems" else "d").apply {check(List::class.java.isAssignableFrom(type));isAccessible=true}
    private val widgets=page.getDeclaredField(if(legacy)"appWidgets" else "e").apply {check(List::class.java.isAssignableFrom(type));isAccessible=true}
    private val screenUri=if(compact)XposedHelpers.findClass("z5c",loader).getDeclaredField("a").apply {check(type==Uri::class.java);isAccessible=true} else null
    init {
        check(List::class.java.isAssignableFrom(calculate.returnType) && cleanup.returnType==IntArray::class.java && migrate.returnType==bool)
        listOf(calculate,apply,cleanup,migrate,completion).forEach {it.isAccessible=true}
    }
    fun busy()=if(compact)busyField!!.getBoolean(null) else busyMethod!!.invoke(null)==true
    fun isHotseat(value:Any)=hotseat.getBoolean(value)
    fun rank(value:Any)=rank.getInt(value)
    fun items(value:Any)=(icons.get(value) as List<*>)+(widgets.get(value) as List<*>)
    fun calculate(activity:Context,old:Pair<Int,Int>,columns:Int,rows:Int,unplaced:ArrayList<Any>):List<*> {
        val args=listOf<Any>(activity,old.first,old.second,columns,rows,false,false,false,false,unplaced)
        val complete=if(compact)args+listOf(false,HashMap<Any,Any>(),ArrayList<Any>()) else args
        return calculate.invoke(null,*complete.toTypedArray()) as List<*>
    }
    fun screens(items:Uri):Uri {
        if(compact)return screenUri!!.get(null) as Uri
        val mode=XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.android.launcher.mode.LauncherModeManager",helper.classLoader),"getInstance")
        val table=XposedHelpers.callMethod(mode,"getScreenTableName") as String
        return items.buildUpon().path(table).build()
    }
}
