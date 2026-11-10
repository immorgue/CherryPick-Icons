package com.cosmod.launcherglass.xposed

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XposedHelpers

/** The native 17.x contour API, or the same OplusPath calls used by 16.x. */
internal object NativeFolderPath {
    private val logged=HashSet<String>()
    /** OEM optical contour compensates for the GPU corner implementation. */
    fun policyPath(policy:Drawable?,bounds:Rect,radius:Float):Path? {
        policy ?: return null
        val old=Rect(policy.bounds)
        return try {
            XposedHelpers.callMethod(policy,"setCornerRadius",radius)
            policy.setBounds(0,0,bounds.width(),bounds.height())
            (NativeIconAccess.field(policy,"mStrokePath") as? Path)?.takeUnless {it.isEmpty}
                ?.let {Path(it).apply {offset(bounds.left.toFloat(),bounds.top.toFloat())}}
        }catch(_:Throwable){null}finally{policy.bounds=old}
    }
    /** Theme PNGs/nine-patches carry their real corners in alpha, not Outline. */
    fun drawableMask(drawable:Drawable?,bounds:Rect):Bitmap? {
        drawable ?: return null
        if(bounds.isEmpty)return null
        val scale=minOf(1f,384f/maxOf(bounds.width(),bounds.height()))
        val w=(bounds.width()*scale).toInt().coerceAtLeast(1)
        val h=(bounds.height()*scale).toInt().coerceAtLeast(1)
        val image=Bitmap.createBitmap(w+2,h+2,Bitmap.Config.ARGB_8888)
        val old=Rect(drawable.bounds)
        try {
            val canvas=Canvas(image)
            canvas.translate(1f,1f);canvas.scale(w.toFloat()/bounds.width(),h.toFloat()/bounds.height())
            canvas.translate(-bounds.left.toFloat(),-bounds.top.toFloat())
            drawable.bounds=bounds;drawable.draw(canvas)
            val pixels=IntArray(image.width*image.height)
            image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
            val peak=pixels.maxOfOrNull {it ushr 24} ?: 0
            val center=pixels[(h/2+1)*image.width+w/2+1] ushr 24
            val corners=intArrayOf(pixels[image.width+1],pixels[image.width+w],
                pixels[h*image.width+1],pixels[h*image.width+w])
            // Reject paint-only rectangles and contour-only drawables.
            if(peak==0 || center<peak*.75f || corners.all {(it ushr 24)>=peak*.98f}){image.recycle();return null}
            for(i in pixels.indices)pixels[i]=(((pixels[i] ushr 24)*255/peak) shl 24) or 0xffffff
            image.setPixels(pixels,0,image.width,0,0,image.width,image.height)
            return image
        }catch(_:Throwable){image.recycle();return null}
        finally{drawable.bounds=old}
    }
    /** Ask the stock view to clip its own drawable; no inferred radius/weight. */
    fun gridMask(view:View,native:Drawable,bounds:Rect):Bitmap? { return try {
        val scale=minOf(1f,384f/maxOf(bounds.width(),bounds.height()))
        val w=(bounds.width()*scale).toInt().coerceAtLeast(1)
        val h=(bounds.height()*scale).toInt().coerceAtLeast(1)
        val image=Bitmap.createBitmap(w+2,h+2,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image)
        canvas.translate(1f,1f);canvas.scale(w.toFloat()/bounds.width(),h.toFloat()/bounds.height())
        canvas.translate(-bounds.left.toFloat(),-bounds.top.toFloat())
        val old=Rect(native.bounds)
        try {
            val clip=generateSequence(view.javaClass as Class<*>?){it.superclass}
                .flatMap {it.declaredMethods.asSequence()}
                .firstOrNull {it.name=="clipRoundBackground" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))}
            if(clip!=null){
                clip.isAccessible=true;clip.invoke(view,canvas)
                native.bounds=bounds;native.draw(canvas)
            }else{
                val mask=NativeIconAccess.call(view,"createClipSmoothRoundBitmap") as? Bitmap
                if(mask==null){image.recycle();return null}
                canvas.drawBitmap(mask,null,RectF(bounds),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
        }finally{native.bounds=old}
        // Only coverage belongs to the material mask; the native tint does not.
        val pixels=IntArray(image.width*image.height)
        image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
        val peak=pixels.maxOfOrNull {it ushr 24} ?: 0
        if(peak==0){image.recycle();return null}
        for(i in pixels.indices)pixels[i]=(((pixels[i] ushr 24)*255/peak) shl 24) or (Color.WHITE and 0xffffff)
        image.setPixels(pixels,0,image.width,0,0,image.width,image.height)
        image
    }catch(t:Throwable){
        if(logged.add("grid:"+t.javaClass.name))GlassLogger.e("Compat","Stock grid mask unavailable",t)
        null
    }
    }
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
