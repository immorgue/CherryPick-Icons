package com.cosmod.launcherglass.xposed

import android.appwidget.AppWidgetHostView
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Point
import android.graphics.drawable.Drawable
import com.cosmod.launcherglass.util.DesktopSizing
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Changes native cell metrics, so layout, touch targets and drop calculations share them. */
internal object DesktopCells {
    private data class Cell(val width:Int,val height:Int,val actualWidth:Int,val actualHeight:Int)
    private data class Icon(val base:Int,val applied:Int)
    private val cells=WeakHashMap<View,Cell>()
    private val icons=WeakHashMap<View,Icon>()
    private val verticalShifts=WeakHashMap<View,Int>()
    private val measuring=ThreadLocal<View?>()
    private var horizontal=100
    private var vertical=100
    private var iconScale=100
    private fun number(view:View,name:String)=(NativeIconAccess.field(view,name) as? Number)?.toInt() ?: 0
    private fun dock(view:View)=number(view,"mContainerType")==1
    private fun active(view:View)=PrefsHelper.enabled &&
        view.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT
    fun load(bundle:Bundle) {
        val width=bundle.getInt(DesktopSizing.CELL_WIDTH,100).coerceIn(80,120)
        val height=bundle.getInt(DesktopSizing.CELL_HEIGHT,100).coerceIn(80,120)
        val scale=bundle.getInt(DesktopSizing.ICON_SCALE,100).coerceIn(100,160)
        val changed=horizontal!=width || vertical!=height || iconScale!=scale
        horizontal=width;vertical=height;iconScale=scale
        if(changed){
            cells.keys.weakSnapshot().forEach {cell ->
                // OEM cells measure their children through a separate container
                // whose outer MeasureSpecs often stay unchanged.
                container(cell)?.forceLayout()
                cell.requestLayout()
            }
            icons.keys.weakSnapshot().forEach {it.forceLayout();it.requestLayout()}
        }
    }
    fun install(loader:ClassLoader) {
        val layout=XposedHelpers.findClassIfExists("com.android.launcher3.CellLayout",loader) ?: return
        ModuleHooks.hookAllMethods(layout,"onMeasure",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                param.setObjectExtra("cellMeasureBefore",measuring.get())
                measuring.set(view)
                val cell=cells[view] ?: return
                val width=cellWidth(view,cell.width)
                val height=cellHeight(view,cell.height)
                if(active(view) && (width!=cell.actualWidth || height!=cell.actualHeight))
                    XposedHelpers.callMethod(view,"setCellDimensionsInner",cell.width,cell.height)
            }
            override fun afterHookedMethod(param:MethodHookParam){measuring.set(param.getObjectExtra("cellMeasureBefore") as? View)}
        })
        ModuleHooks.hookAllMethods(layout,"setCellDimensionsInner",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                if(!active(view) || param.args.size!=2)return
                val type=(NativeIconAccess.field(view,"mContainerType") as? Number)?.toInt() ?: return
                if(type!=0 && type!=1)return
                val width=param.args[0] as? Int ?: return
                val height=param.args[1] as? Int ?: return
                if(width<=0 || height<=0)return
                val previous=cells[view]
                // Fixed OEM dimensions may be reapplied outside the measure pass.
                val baseWidth=if(measuring.get()!==view && previous?.actualWidth==width)previous.width else width
                val baseHeight=if(measuring.get()!==view && previous?.actualHeight==height)previous.height else height
                val actualWidth=cellWidth(view,baseWidth)
                val actualHeight=cellHeight(view,baseHeight)
                cells[view]=Cell(baseWidth,baseHeight,actualWidth,actualHeight)
                param.args[0]=actualWidth
                param.args[1]=actualHeight
            }
        })
        ModuleHooks.hookAllMethods(layout,"getUnusedHorizontalSpace",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                val cell=cells[view] ?: return
                if(!active(view) || cell.width==cell.actualWidth || param.hasThrowable())return
                val count=number(view,"mCountX").coerceAtLeast(1)
                val gap=(NativeIconAccess.field(view,"mBorderSpace") as? Point)?.x ?: return
                param.result=view.measuredWidth-view.paddingLeft-view.paddingRight-cell.actualWidth*count-gap*(count-1)
            }
        })
        ModuleHooks.hookAllMethods(layout,"onLayout",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                val previous=verticalShifts.remove(view) ?: return
                container(view)?.offsetTopAndBottom(-previous)
            }
            override fun afterHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                val cell=cells[view] ?: return
                if(!active(view) || cell.height==cell.actualHeight || param.hasThrowable())return
                val count=if(dock(view))1 else number(view,"mCountY")
                val centered=DesktopSizing.cellOriginShift(cell.height,cell.actualHeight,count)
                val room=if(dock(view) || centered>=0)null else workspaceRoom(view,cell.height,count)
                val shift=if(room==null)centered else {
                    val added=(cell.actualHeight-cell.height)*count
                    centered.coerceIn(-room.first, maxOf(-room.first,room.second-added))
                }
                container(view)?.let {
                    it.offsetTopAndBottom(shift)
                    verticalShifts[view]=shift
                }
            }
        })
        for((name,horizontalAxis) in listOf("getLayoutPaddingStart" to true,"getLayoutPaddingTop" to false)){
            ModuleHooks.hookAllMethods(layout,name,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as? View ?: return
                    val cell=cells[view] ?: return
                    if(!active(view) || measuring.get()===view || param.hasThrowable())return
                    val row=container(view) ?: return
                    // Drag geometry uses the very same origin as the laid-out cells.
                    if(horizontalAxis && cell.width!=cell.actualWidth)
                        param.result=if(view.layoutDirection==View.LAYOUT_DIRECTION_RTL)view.width-row.right else row.left
                    else if(!horizontalAxis && verticalShifts.containsKey(view))
                        param.result=row.top
                }
            })
        }
        val bubble=XposedHelpers.findClassIfExists("com.android.launcher3.BubbleTextView",loader) ?: return
        val row=XposedHelpers.findClassIfExists("com.android.launcher3.ShortcutAndWidgetContainer",loader) ?: return
        installWidgetSizing(loader,row)
        ModuleHooks.hookAllMethods(row,"measureChild",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val child=param.args.firstOrNull() as? TextView ?: return
                if(!bubble.isInstance(child))return
                resizeDockIcon(child)
            }
        })
        ModuleHooks.hookAllMethods(bubble,"applyCompoundDrawables",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val child=param.thisObject as? TextView ?: return
                resizeDockIcon(child,rebind=false)
            }
        })
    }
    private fun container(view:View)=NativeIconAccess.field(view,"mShortcutsAndWidgets") as? View

    private fun cellWidth(view:View,baseWidth:Int):Int {
        if(dock(view))return baseWidth
        val desired=DesktopSizing.cellPixels(baseWidth,horizontal)
        if(desired<=baseWidth)return desired
        val metrics=view.resources.displayMetrics
        val count=number(view,"mCountX").coerceAtLeast(1)
        val gap=(NativeIconAccess.field(view,"mBorderSpace") as? Point)?.x ?: 0
        val edge=(8*metrics.density).roundToInt()
        val visibleWidth=metrics.widthPixels-2*edge-gap*(count-1)
        return desired.coerceAtMost(maxOf(baseWidth,visibleWidth/count))
    }

    private fun cellHeight(view:View,baseHeight:Int):Int {
        val desired=DesktopSizing.cellPixels(baseHeight,vertical)
        if(dock(view) || desired<=baseHeight)return desired
        val count=number(view,"mCountY").coerceAtLeast(1)
        val room=workspaceRoom(view,baseHeight,count) ?: return desired
        return desired.coerceAtMost(baseHeight+(room.first+room.second)/count)
    }

    /** Available growth above and below the stock grid without covering system UI or the dots. */
    private fun workspaceRoom(view:View,baseHeight:Int,count:Int):Pair<Int,Int>? {
        if(!view.isAttachedToWindow || view.height<=0)return null
        val indicator=DesktopOffsets.find(view.rootView,"page_indicator") ?: return null
        if(indicator.height<=0 || !indicator.isShown)return null
        val location=IntArray(2)
        view.getLocationOnScreen(location)
        val stockTop=location[1]+view.paddingTop
        val gap=(NativeIconAccess.field(view,"mBorderSpace") as? Point)?.y ?: 0
        val stockBottom=stockTop+baseHeight*count+gap*(count-1)
        indicator.getLocationOnScreen(location)
        val margin=(8*view.resources.displayMetrics.density).roundToInt()
        val status=view.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0
        return (stockTop-status-margin).coerceAtLeast(0) to (location[1]-margin-stockBottom).coerceAtLeast(0)
    }

    private fun installWidgetSizing(loader:ClassLoader,row:Class<*>) {
        val params=XposedHelpers.findClassIfExists("com.android.launcher3.celllayout.CellLayoutLayoutParams",loader) ?: return
        val areaWidget=XposedHelpers.findClassIfExists("com.android.launcher3.card.IAreaWidget",loader)
        val launcherCard=XposedHelpers.findClassIfExists("com.android.launcher3.card.LauncherCardView",loader)
        val folder=XposedHelpers.findClassIfExists("com.android.launcher3.folder.FolderIcon",loader)
        val measuringWidget=ThreadLocal<View?>()
        val sizingWidget=ThreadLocal<Boolean>()
        for(name in listOf("measureChild","setupLp"))ModuleHooks.hookAllMethods(row,name,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                param.setObjectExtra("previousWidget",measuringWidget.get())
                val child=param.args.firstOrNull() as? View
                measuringWidget.set(child?.takeIf {
                    it is AppWidgetHostView || areaWidget?.isInstance(it)==true || launcherCard?.isInstance(it)==true ||
                        folder?.isInstance(it)==true
                })
            }
            override fun afterHookedMethod(param:MethodHookParam){
                measuringWidget.set(param.getObjectExtra("previousWidget") as? View)
            }
        })
        ModuleHooks.hookAllMethods(params,"setup",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(sizingWidget.get()==true || measuringWidget.get()?.layoutParams!==param.thisObject)return
                sizingWidget.set(true)
                param.setObjectExtra("widgetSlot",true)
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("widgetSlot")!=true)return
                try {preserveAspect(param)}finally{sizingWidget.remove()}
            }
            private fun preserveAspect(param:MethodHookParam){
                val child=measuringWidget.get() ?: return
                val lp=param.thisObject as? ViewGroup.LayoutParams ?: return
                if(child.layoutParams!==lp || param.hasThrowable())return
                val cell=(child.parent as? View)?.parent as? View ?: return
                val size=cells[cell] ?: return
                if(!active(cell) || (size.width==size.actualWidth && size.height==size.actualHeight))return
                if(param.args.getOrNull(0)!=size.actualWidth || param.args.getOrNull(1)!=size.actualHeight)return
                val width=lp.width;val height=lp.height
                if(width<=0 || height<=0 || NativeIconAccess.field(lp,"isLockedToGrid")==false)return
                val left=XposedHelpers.getIntField(lp,"x")
                val top=XposedHelpers.getIntField(lp,"y")
                try {
                    val stockArgs=param.args.copyOf()
                    stockArgs[0]=size.width;stockArgs[1]=size.height
                    XposedBridge.invokeOriginalMethod(param.method,lp,stockArgs)
                    val stockWidth=lp.width.coerceAtLeast(1);val stockHeight=lp.height.coerceAtLeast(1)
                    val scale=minOf(1f,width.toFloat()/stockWidth,height.toFloat()/stockHeight)
                    lp.width=(stockWidth*scale).roundToInt().coerceAtLeast(1)
                    lp.height=(stockHeight*scale).roundToInt().coerceAtLeast(1)
                }catch(t:Throwable){
                    lp.width=width;lp.height=height
                    GlassLogger.limited("DesktopGeometry","widget-size-failed",100){"Widget sizing unavailable: ${t.message}"}
                }finally{
                    XposedHelpers.setIntField(lp,"x",left+(width-lp.width)/2)
                    XposedHelpers.setIntField(lp,"y",top+(height-lp.height)/2)
                }
            }
        })
    }

    private fun resizeDockIcon(view:TextView,rebind:Boolean=true) {
        val cell=(view.parent as? View)?.parent as? View
        val isDock=cell!=null && dock(cell) && container(cell)===view.parent
        val previous=icons[view]
        if(!isDock && previous==null)return
        try {
            val current=number(view,"mIconSize")
            if(current<=0)return
            val base=if(previous?.applied==current)previous.base else current
            val percent=if(isDock && PrefsHelper.enabled)iconScale else 100
            val metrics=cell?.let {cells[it]}
            val width=metrics?.actualWidth ?: cell?.let {number(it,"mCellWidth")} ?: 0
            val height=metrics?.actualHeight ?: cell?.let {number(it,"mCellHeight")} ?: 0
            val available=listOf(width,height).filter {it>0}.minOrNull() ?: view.resources.displayMetrics.widthPixels/4
            val target=DesktopSizing.iconPixels(base,percent,available,(8*view.resources.displayMetrics.density).toInt())
            if(isDock)icons[view]=Icon(base,target)else icons.remove(view)
            if(current==target)return
            XposedHelpers.setIntField(view,"mIconSize",target)
            if(rebind)(NativeIconAccess.field(view,"mIcon") as? Drawable)?.let {
                XposedHelpers.callMethod(view,"applyCompoundDrawables",it)
            }
            GlassLogger.limited("DesktopGeometry","dock-size",100){"Dock icon size: $current -> $target px"}
        }catch(t:Throwable){
            GlassLogger.limited("DesktopGeometry","dock-size-failed",100){"Dock sizing unavailable: ${t.message}"}
        }
    }
}
