package com.cosmod.launcherglass.xposed

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.graphics.drawable.Drawable
import com.cosmod.launcherglass.util.DesktopSizing
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** Changes native cell metrics, so layout, touch targets and drop calculations share them. */
internal object DesktopCells {
    private data class Cell(val width:Int,val height:Int,val actualWidth:Int,val actualHeight:Int)
    private data class Icon(val base:Int,var applied:Int)
    private val cells=WeakHashMap<View,Cell>()
    private val icons=WeakHashMap<View,Icon>()
    private val measuring=ThreadLocal<View?>()
    private var horizontal=100
    private var vertical=100
    private var iconScale=100
    private fun number(view:View,name:String)=(NativeIconAccess.field(view,name) as? Number)?.toInt() ?: 0
    private fun workspace(view:View)=PrefsHelper.enabled && number(view,"mContainerType")==0 &&
        view.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT
    fun load(bundle:Bundle) {
        val width=bundle.getInt(DesktopSizing.CELL_WIDTH,100).coerceIn(80,120)
        val height=bundle.getInt(DesktopSizing.CELL_HEIGHT,100).coerceIn(80,120)
        val scale=bundle.getInt(DesktopSizing.ICON_SCALE,100).coerceIn(100,160)
        val changed=horizontal!=width || vertical!=height || iconScale!=scale
        horizontal=width;vertical=height;iconScale=scale
        if(changed){
            cells.keys.weakSnapshot().forEach {it.requestLayout()}
            icons.keys.weakSnapshot().forEach {it.requestLayout()}
        }
    }
    fun install(loader:ClassLoader) {
        val layout=XposedHelpers.findClassIfExists("com.android.launcher3.CellLayout",loader) ?: return
        ModuleHooks.hookAllMethods(layout,"onMeasure",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                param.setObjectExtra("cellMeasureBefore",measuring.get());measuring.set(view)
            }
            override fun afterHookedMethod(param:MethodHookParam){measuring.set(param.getObjectExtra("cellMeasureBefore") as? View)}
        })
        ModuleHooks.hookAllMethods(layout,"setCellDimensionsInner",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                if(!workspace(view) || param.args.size!=2)return
                val w=param.args[0] as? Int ?: return;val h=param.args[1] as? Int ?: return
                if(w<=0 || h<=0)return
                val prior=cells[view]
                // A native repeated setter outside measure can reuse its current metrics.
                val baseW=if(measuring.get()!==view && prior?.actualWidth==w)prior.width else w
                val baseH=if(measuring.get()!==view && prior?.actualHeight==h)prior.height else h
                val nw=DesktopSizing.cellPixels(baseW,horizontal);val nh=DesktopSizing.cellPixels(baseH,vertical)
                cells[view]=Cell(baseW,baseH,nw,nh);param.args[0]=nw;param.args[1]=nh
            }
        })
        for((method,width) in listOf("getLayoutPaddingStart" to true,"getLayoutPaddingTop" to false))
            ModuleHooks.hookAllMethods(layout,method,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as? View ?: return
                    if(!workspace(view) || measuring.get()===view || param.hasThrowable())return
                    val cell=cells[view] ?: return;val base=param.result as? Int ?: return
                    val shift=if(width)DesktopSizing.cellOriginShift(cell.width,cell.actualWidth,number(view,"mCountX"))
                        else DesktopSizing.cellOriginShift(cell.height,cell.actualHeight,number(view,"mCountY"))
                    param.result=base+shift
                }
            })
        val bubble=XposedHelpers.findClassIfExists("com.android.launcher3.BubbleTextView",loader) ?: return
        ModuleHooks.hookAllMethods(bubble,"onMeasure",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? TextView ?: return
                try {resizeDockIcon(view)}catch(t:Throwable){
                    GlassLogger.limited("DesktopGeometry","dock-size-failed",100){"Dock sizing unavailable: ${t.message}"}
                }
            }
        })
    }
    private fun resizeDockIcon(view:TextView) {
        val current=number(view,"mIconSize");if(current<=0)return
        var parent=view.parent as? View
        var dock=false
        while(parent!=null){
            if(parent.javaClass.name.endsWith(".OplusHotseat") || parent.javaClass.name.endsWith(".Hotseat")){dock=true;break}
            parent=parent.parent as? View
        }
        val old=icons[view]
        if(!dock && old==null)return
        val base=if(old==null || current!=old.applied)current else old.base
        val percent=if(dock && PrefsHelper.enabled)iconScale else 100
        val container=view.parent as? View
        val cell=container?.parent as? View
        val count=cell?.let {number(it,"mCountX")}?.coerceAtLeast(1) ?: 4
        val available=cell?.width?.takeIf {it>0}?.div(count)
            ?: (view.width.takeIf {it>0} ?: view.resources.displayMetrics.widthPixels/4)
        val target=DesktopSizing.iconPixels(base,percent,available,(8*view.resources.displayMetrics.density).toInt())
        if(dock)icons[view]=Icon(base,target)else icons.remove(view)
        if(current==target)return
        XposedHelpers.setIntField(view,"mIconSize",target)
        (NativeIconAccess.field(view,"mIcon") as? Drawable)?.let {
            XposedHelpers.callMethod(view,"applyCompoundDrawables",it)
        }
    }
}
