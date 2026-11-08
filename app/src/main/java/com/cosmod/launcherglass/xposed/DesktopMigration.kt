package com.cosmod.launcherglass.xposed

import android.app.Activity
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.OperationApplicationException
import android.net.Uri
import android.provider.Settings
import com.cosmod.launcherglass.util.DesktopGrid
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/** Scopes private native APIs and forbids item deletion in designer-triggered transactions. */
internal object DesktopMigration {
    data class Preview(val pages:List<List<DesktopGrid.Cell>>,val safe:Boolean)
    private class Request(activity:Activity,val columns:Int,val rows:Int,val finished:(Boolean)->Unit) {
        val activity=WeakReference(activity)
        var blocked=false
    }
    private val previewing=ThreadLocal<Int>()
    private val applying=ThreadLocal<Request>()
    @Volatile private var request:Request?=null
    private lateinit var api:DesktopNativeApi
    private lateinit var helper:Class<*>
    private lateinit var calculate:Method
    private lateinit var apply:Method
    private lateinit var items:Uri
    private lateinit var screens:Uri

    fun install(loader:ClassLoader) {
        // Resolve the complete contract before changing any native behavior.
        api=DesktopNativeApi(loader)
        helper=api.helper;calculate=api.calculate;apply=api.apply
        XposedBridge.hookMethod(api.cleanup,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam) {
                    val count=previewing.get()
                    if(count!=null)previewing.set(count+1)
                    if(count!=null || applying.get()!=null)param.result=intArrayOf()
                }
            })
        api.screenCountSetter?.let {setter -> XposedBridge.hookMethod(setter,
            object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam) {
                    if(previewing.get()!=null)param.result=null
                }
            })
        }
        XposedBridge.hookMethod(api.migrate,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam) {
                    val task=request ?: return
                    if(param.args[0]!==task.activity.get())return
                    param.setObjectExtra("CherryPick.request",task)
                    val target=param.args[1] as IntArray
                    if(!target.contentEquals(intArrayOf(task.columns,task.rows))){task.blocked=true;param.result=false;return}
                    applying.set(task)
                }
                override fun afterHookedMethod(param:MethodHookParam) {
                    if(param.getObjectExtra("CherryPick.request")==null)return
                    applying.remove()
                    if(param.hasThrowable()) {
                        GlassLogger.e("DesktopDesigner","Native migration failed",param.throwable)
                        param.result=false
                    }
                }
            })
        XposedHelpers.findAndHookMethod(ContentResolver::class.java,"applyBatch",String::class.java,ArrayList::class.java,
            object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam) {
                    val task=applying.get() ?: return
                    val operations=param.args[1] as ArrayList<*>
                    val safe=param.args[0]==items.authority && operations.all {operation ->
                        val op=operation as? ContentProviderOperation ?: return@all false
                        DesktopGrid.allowsMutation(op.uri==items,op.uri==screens,op.isInsert,op.isUpdate,op.isDelete)
                    }
                    if(!safe) {
                        task.blocked=true
                        GlassLogger.e("DesktopDesigner","Rejected unsafe native layout transaction")
                        param.throwable=OperationApplicationException("CherryPick refused deletion or an unknown layout operation")
                    }
                }
                override fun afterHookedMethod(param:MethodHookParam) {
                    val task=applying.get() ?: return
                    val operations=param.args[1] as ArrayList<*>
                    val results=param.result as? Array<*>
                    // This provider can swallow a transaction failure and return null.
                    if(param.hasThrowable() || results==null || results.size!=operations.size) {
                        task.blocked=true
                        if(!param.hasThrowable())param.throwable=OperationApplicationException("Native layout transaction did not complete")
                    }
                }
            })
        XposedBridge.hookMethod(api.completion,object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam) {
                val task=request ?: return
                val owner=api.owner.get(param.thisObject) as WeakReference<*>
                val activity=task.activity.get()
                if(activity==null){request=null;return}
                if(owner.get()!==activity)return
                request=null
                val success=!param.hasThrowable() && param.args[0]==true && !task.blocked &&
                    dimensions(activity)==(task.columns to task.rows)
                GlassLogger.i("DesktopDesigner","Native migration completed: success=$success, grid=${task.columns}x${task.rows}")
                if(!activity.isFinishing && !activity.isDestroyed)task.finished(success)
            }
        })
    }

    fun dimensions(context:Context)=
        Settings.System.getInt(context.contentResolver,"layout_cellcount_x",4) to
            Settings.System.getInt(context.contentResolver,"layout_cellcount_y",6)
    fun busy()=request!=null || api.busy()
    fun loading(activity:Activity)=XposedHelpers.callMethod(activity,"isWorkspaceLoading")==true
    fun arrange(activity:Activity) {
        val state=XposedHelpers.getStaticObjectField(XposedHelpers.findClass("com.android.launcher3.LauncherState",helper.classLoader),"TOGGLE_BAR")
        XposedHelpers.callMethod(XposedHelpers.callMethod(activity,"getStateManager"),"goToState",state)
    }
    fun preview(activity:Activity,columns:Int,rows:Int):Preview {
        require(DesktopGrid.valid(columns,rows))
        check(!busy()) {"Native layout migration is busy"}
        val original=dimensions(activity)
        val unplaced=ArrayList<Any>()
        previewing.set(0)
        val native=try {
            api.calculate(activity,original,columns,rows,unplaced).also {
                GlassLogger.i("DesktopDesigner","Grid preview ${columns}x${rows}: ${unplaced.size} unplaced pages; ${previewing.get()} cleanup calls suppressed")
            }
        }finally{previewing.remove()}
        val pages=native.filterNotNull().filter { !api.isHotseat(it) }
            .sortedBy {api.rank(it)}.map {page ->
                api.items(page).filterNotNull().map {item ->
                    fun number(method:String)=XposedHelpers.callMethod(item,method) as Int
                    DesktopGrid.Cell(number("getCellX"),number("getCellY"),number("getSpanX"),number("getSpanY"))
                }
            }.ifEmpty {listOf(emptyList())}
        return Preview(pages,unplaced.isEmpty() && pages.all {DesktopGrid.fits(columns,rows,it)})
    }
    fun start(activity:Activity,columns:Int,rows:Int,finished:(Boolean)->Unit) {
        check(!busy() && !loading(activity)) {"Native layout migration is busy"}
        require(DesktopGrid.valid(columns,rows))
        // These native statics depend on Application initialization and the active launcher mode.
        items=XposedHelpers.getStaticObjectField(XposedHelpers.findClass("com.android.launcher3.LauncherSettings\$Favorites",helper.classLoader),"CONTENT_URI") as Uri
        screens=api.screens(items)
        val task=Request(activity,columns,rows,finished)
        request=task
        try {apply.invoke(null,activity,columns,rows,false)}catch(t:Throwable){request=null;throw t}
    }
}
