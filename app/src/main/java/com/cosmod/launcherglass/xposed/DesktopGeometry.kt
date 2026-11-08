package com.cosmod.launcherglass.xposed

import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import com.cosmod.launcherglass.util.DesktopSizing
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.WallpaperHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Native dimensions are changed before layout; no view scaling or layout-database writes. */
internal object DesktopGeometry {
    @Volatile private var values=DesktopSizing.defaults
    @Volatile private var dimensions:Map<Int,String> = emptyMap()
    private val profileDepth=ThreadLocal<Int>()
    private val profileOwner=ThreadLocal<Any>()
    private val iconDepth=ThreadLocal<Int>()
    private fun field(owner:Any,named:String,compact:String)=
        NativeIconAccess.field(owner,named) ?: NativeIconAccess.field(owner,compact) ?: error("Missing desktop field $named/$compact")
    private fun number(owner:Any,named:String,compact:String)=(field(owner,named,compact) as Number).toInt()
    private val indicatorPadding=setOf("workspace_page_indicator_bottom_padding_offset",
        "workspace_page_indicator_vertical_padding","workspace_page_indicator_vertical_padding_breeno")
    private val indicatorHeight=setOf("workspace_page_indicator_height","workspace_page_indicator_breeno_entry_height")
    private val widgetPadding=setOf("CellLayout_widget_padding_left_right","oplus_default_app_widget_padding_left",
        "oplus_default_app_widget_padding_right","oplus_default_app_widget_padding_top","oplus_default_app_widget_padding_bottom")
    // Resource IDs belong to the installed launcher and cannot be linked into this module's R class.
    @android.annotation.SuppressLint("DiscouragedApi")
    fun load(bundle:Bundle) {
        values=DesktopSizing.defaults.mapValues {(key,fallback) -> bundle.getInt(key,fallback).coerceIn(DesktopSizing.ranges.getValue(key))}
        if(dimensions.isEmpty())WallpaperHelper.appContext?.let {context ->
            dimensions=(indicatorPadding+indicatorHeight+widgetPadding).mapNotNull {name ->
                context.resources.getIdentifier(name,"dimen",context.packageName).takeIf {it!=0}?.let {it to name}
            }.toMap()
        }
    }
    fun install(loader:ClassLoader) {
        val profile=XposedHelpers.findClass("com.android.launcher3.DeviceProfile",loader)
        XposedBridge.hookAllConstructors(profile,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) {
                profileDepth.set((profileDepth.get() ?: 0)+1);profileOwner.set(param.thisObject)
            }
            override fun afterHookedMethod(param:MethodHookParam) {
                val depth=(profileDepth.get() ?: 1)-1
                if(depth>0){profileDepth.set(depth);return}
                profileDepth.remove();profileOwner.remove()
                if(PrefsHelper.enabled && !param.hasThrowable())try {lowerWorkspace(param.thisObject)}
                catch(t:Throwable){GlassLogger.limited("DesktopGeometry","workspace",100){"Workspace alignment unavailable: ${t.message}"}}
            }
        })
        XposedHelpers.findAndHookMethod(profile,"updateInsets",Rect::class.java,object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam) {
                if(param.args[0]==null || (profileDepth.get() ?: 0)>0 || !PrefsHelper.enabled || param.hasThrowable())return
                try {lowerWorkspace(param.thisObject)}
                catch(t:Throwable){GlassLogger.limited("DesktopGeometry","insets",100){"Workspace alignment unavailable: ${t.message}"}}
            }
        })
        val icons=XposedHelpers.findClassIfExists("com.android.launcher.theme.LauncherIconConfig",loader)
            ?: XposedHelpers.findClassIfExists("to5",loader)
        val iconHook=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) {iconDepth.set((iconDepth.get() ?: 0)+1)}
            override fun afterHookedMethod(param:MethodHookParam) {
                val depth=(iconDepth.get() ?: 1)-1
                if(depth>0){iconDepth.set(depth);return}
                iconDepth.remove()
                if(!PrefsHelper.enabled || (profileDepth.get() ?: 0)==0 || param.hasThrowable())return
                val percent=values.getValue(DesktopSizing.ICON_SCALE)
                if(percent==100)return
                val context=WallpaperHelper.appContext ?: return
                val base=param.result as? Int ?: return
                try {
                    val config=field(profileOwner.get(),"mConfig","mConfig")
                    val width=number(config,"widthPx","s")
                    if(width>=number(config,"heightPx","t"))return
                    val columns=Settings.System.getInt(context.contentResolver,"layout_cellcount_x",4).coerceAtLeast(1)
                    val result=DesktopSizing.iconPixels(base,percent,width/columns,(8*context.resources.displayMetrics.density).toInt())
                    param.result=result
                    GlassLogger.limited("DesktopGeometry","icons",100){"Native icon size $base -> $result px ($percent%)"}
                }catch(t:Throwable){GlassLogger.limited("DesktopGeometry","icons-failed",100){"Icon sizing unavailable: ${t.message}"}}
            }
        }
        val sizing=icons?.declaredMethods.orEmpty().filter {method ->
            method.returnType==Int::class.javaPrimitiveType &&
                ((method.name=="calculateIconSizeByUxDesign" && (method.parameterCount==0 || method.parameterTypes.contentEquals(arrayOf(IntArray::class.java)))) ||
                 (icons?.name=="to5" && method.name=="b" && method.parameterTypes.contentEquals(arrayOf(IntArray::class.java))))
        }
        sizing.forEach {XposedBridge.hookMethod(it,iconHook)}
        if(sizing.isEmpty())GlassLogger.i("DesktopGeometry","Native icon size contract unavailable")
        for(method in listOf("getDimensionPixelSize","getDimensionPixelOffset"))
            XposedHelpers.findAndHookMethod(Resources::class.java,method,Int::class.javaPrimitiveType,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam) {
                    if(!PrefsHelper.enabled || param.hasThrowable())return
                    val id=param.args[0] as Int
                    val name=dimensions[id] ?: return
                    val resources=param.thisObject as Resources
                    if(resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_PORTRAIT)return
                    if(resources.getResourcePackageName(id)!=WallpaperHelper.appContext?.packageName)return
                    val base=param.result as Int
                    val key=if(name in widgetPadding)DesktopSizing.WIDGET_GAPS else DesktopSizing.INDICATOR_GAPS
                    val percent=values.getValue(key)
                    if(percent==100)return
                    val scaled=DesktopSizing.padding(base,percent)
                    param.result=if(name in indicatorHeight)maxOf(scaled,minOf(base,(48*resources.displayMetrics.density).toInt()))else scaled
                }
            })
    }
    private fun lowerWorkspace(profile:Any) {
        val bias=values.getValue(DesktopSizing.BOTTOM_BIAS)
        if(bias==0)return
        val config=field(profile,"mConfig","mConfig")
        if(number(config,"widthPx","s")>=number(config,"heightPx","t"))return
        val workspace=field(profile,"mWorkspaceParam","mWorkspaceParam")
        val hotseat=field(profile,"mHotseatParam","mHotseatParam")
        val indicator=field(profile,"mPageIndicatorParam","mPageIndicatorParam")
        val insets=field(config,"insets","k") as Rect
        val padding=field(field(workspace,"mViewParam","b"),"mPadding","a") as Rect
        val barSize=number(hotseat,"hotseatBarSizePx","n")
        val indicatorSize=number(indicator,"workspacePageIndicatorHeight","d")
        val margin=when {
            hotseat.javaClass.name=="xg4" -> XposedHelpers.callMethod(hotseat,"b",field(config,"isLandscape","b") as Boolean) as Int
            hotseat.javaClass.declaredMethods.any {it.name=="getMarginBottom" && it.parameterCount==0} -> XposedHelpers.callMethod(hotseat,"getMarginBottom") as Int
            else -> XposedHelpers.callMethod(hotseat,"i") as Int
        }
        val total=number(config,"heightPx","t")-number(workspace,"mWorkspaceHeightPx","c")-barSize-indicatorSize-margin-insets.top
        val shift=DesktopSizing.downwardShift(padding.top-insets.top,total,bias)
            .coerceAtMost((padding.bottom-insets.bottom-barSize-indicatorSize).coerceAtLeast(0))
        padding.top+=shift;padding.bottom-=shift
        val bottom=if(XposedHelpers.findFieldIfExists(workspace.javaClass,"mWorkspacePaddingBottomAdjustment")!=null)"mWorkspacePaddingBottomAdjustment" else "d"
        XposedHelpers.setIntField(workspace,bottom,padding.bottom)
        GlassLogger.limited("DesktopGeometry","alignment",100){"Workspace moved down $shift px; navigation/dock bounds retained"}
    }
}
