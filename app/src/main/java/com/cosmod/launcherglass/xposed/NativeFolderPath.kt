package com.cosmod.launcherglass.xposed

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XposedHelpers

/** The native 17.x contour API, or the same OplusPath calls used by 16.x. */
internal object NativeFolderPath {
    private val logged=HashSet<String>()
    /** Fill geometry, not the separate optical border (which can use radius * .675). */
    fun fill(drawable:Drawable?,bounds:Rect):Path? {
        drawable ?: return null
        val old=Rect(drawable.bounds)
        return try {
            drawable.bounds=bounds
            val name=drawable.javaClass.name
            when {
                name.endsWith(".SmoothRoundDrawable") ->
                    (XposedHelpers.getObjectField(drawable,"mPath") as? Path)?.takeUnless {it.isEmpty}
                        ?.let {Path(it).apply {offset(bounds.left.toFloat(),bounds.top.toFloat())}}
                name.endsWith(".GlassDrawable") -> fill(XposedHelpers.getObjectField(drawable,"backgroundDrawable") as? Drawable,bounds)
                name.endsWith(".GradientDrawableWrapper") -> fill(XposedHelpers.getObjectField(drawable,"mBaseDrawable") as? Drawable,bounds)
                else -> {
                    val outline=Outline();drawable.getOutline(outline);val rect=Rect()
                    // ColorDrawable advertises an opaque rectangular outline;
                    // it is merely the paint under the owner's rounded clip.
                    // Treating that as a folder contour removes every corner.
                    if(outline.getRect(rect) && !rect.isEmpty && outline.radius>0f)
                        Path().apply {addRoundRect(RectF(rect),outline.radius,outline.radius,Path.Direction.CCW)}else null
                }
            }
        }catch(_:Throwable){null}finally{drawable.bounds=old}
    }
    fun intersectFill(path:Path,drawable:Drawable?,bounds:Rect){
        val fill=fill(drawable,bounds) ?: return
        val result=Path()
        if(result.op(path,fill,Path.Op.INTERSECT) && !result.isEmpty)path.set(result)
    }
    fun create(view:View,path:Path,bounds:RectF,radius:Float,sx:Int,sy:Int):Boolean = try {
        path.reset()
        val loader=view.context.classLoader
        val manager=XposedHelpers.findClassIfExists("com.android.launcher3.util.SmoothRoundedManager",loader)
        val modern=manager?.declaredMethods?.firstOrNull {it.name=="createIconFolderClipPath" &&
            it.parameterTypes.contentEquals(arrayOf(Path::class.java,RectF::class.java,Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType))}
        if(modern!=null){
            modern.invoke(null,path,bounds,radius,radius,sx,sy)
            if(logged.add("modern"))GlassLogger.i("Compat","Folder contour: SmoothRoundedManager")
        }else{
            val icons=XposedHelpers.findClass("com.android.common.util.IconUtils",loader)
            if(XposedHelpers.callStaticMethod(icons,"isIconRadiusRound")==true &&
                XposedHelpers.callStaticMethod(icons,"isMiddle",sx,sy)==true){
                path.addRoundRect(bounds,radius,radius,Path.Direction.CCW)
            }else{
                val type=XposedHelpers.findClass("com.oplus.graphics.OplusPath",loader)
                val wrapper=XposedHelpers.newInstance(type,path)
                XposedHelpers.callMethod(wrapper,"addSmoothRoundRect",bounds,radius,radius,1.1f,Path.Direction.CCW)
            }
            if(logged.add("legacy"))GlassLogger.i("Compat","Folder contour: legacy OplusPath")
        }
        !path.isEmpty
    }catch(t:Throwable){
        path.reset()
        if(logged.add(t.javaClass.name+":"+t.message))GlassLogger.e("Compat","Native folder contour unavailable; stock material kept",t)
        false
    }
}
