package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.GlassVolume
import com.cosmod.launcherglass.util.WallpaperHelper
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Parent plate pixels expressed as shaders, without capturing any View/RenderNode. */
internal object ParentBackdrop {
    data class Sample(val shader:Shader,val revision:Long)
    private class Plate(val identity:WeakReference<Drawable>,val owner:WeakReference<View>,val bounds:RectF,val mask:Bitmap,
                        val radius:Float,val style:GlassStyle,val decorate:Boolean,val order:Long) {
        var key:List<Any?>?=null
        var sample:Sample?=null
        var lens:LiquidGlassShader?=null
        var colors:RuntimeShader?=null
        var frame=-1L
        var frameImage:Bitmap?=null
    }
    private val plates=WeakHashMap<View,WeakHashMap<Drawable,Plate>>()
    private var sequence=0L
    private var frame=0L
    private var revision=0L
    private var failed=false
    private val observed=HashSet<String>()
    fun beginFrame(){frame++}
    fun clear(){plates.clear();observed.clear();revision++;failed=false}
    fun record(view:View,identity:Drawable,bounds:RectF,mask:Bitmap,radius:Float,style:GlassStyle,decorate:Boolean) {
        // Expanded folder backgrounds are siblings of the icon content. Bind
        // their material to the folder scope, retaining the background's own
        // coordinate system rather than treating the folder as its canvas.
        var scope=view
        if(style.folder){
            var parent=view.parent as? View
            repeat(16){
                val current=parent ?: return@repeat
                if(current.javaClass.name.let {it.endsWith(".OplusFolder") || it.endsWith(".Folder") || it.endsWith(".FolderIcon")}){
                    scope=current;parent=null
                }else parent=current.parent as? View
            }
        }
        val entries=plates.getOrPut(scope){WeakHashMap()}
        val old=entries[identity]
        if(old!=null && old.bounds==bounds && old.mask===mask && old.radius==radius && old.style==style && old.decorate==decorate)return
        entries[identity]=Plate(WeakReference(identity),WeakReference(view),RectF(bounds),mask,radius,style,decorate,++sequence)
    }
    fun resolve(view:View,identity:Drawable,wallpaper:Bitmap):Sample? {
        if(failed || !PrefsHelper.enabled || !PrefsHelper.blurEnabled)return null
        var owner:View?=view
        repeat(16) {
            val current=owner ?: return null
            val plate=plates[current]?.values?.filter {it.owner.get()?.let {v -> v.visibility==View.VISIBLE && v.alpha>0f}==true}?.maxByOrNull {it.order}
            if(plate!=null && plate.identity.get()!==identity) {
                return try {
                    val source=plate.owner.get() ?: return null
                    val result=sample(source,plate,wallpaper)
                    if(result!=null && observed.size<16 && observed.add(source.javaClass.name+":"+view.javaClass.name))
                        GlassLogger.i("ParentBackdrop","Bound ${view.javaClass.simpleName} to ${source.javaClass.simpleName} plate shader")
                    result
                }catch(t:Throwable){
                    failed=true
                    GlassLogger.e("ParentBackdrop","Parent plate shader unavailable; keeping wallpaper",t)
                    null
                }
            }
            owner=current.parent as? View
        }
        return null
    }
    private fun sample(owner:View,plate:Plate,wallpaper:Bitmap):Sample? {
        if(plate.frame==frame && plate.frameImage===wallpaper)return plate.sample
        plate.frame=frame;plate.frameImage=wallpaper
        val image=if(plate.style.folder)WallpaperHelper.getFolderWallpaper(owner.context) ?: wallpaper
            else WallpaperHelper.getBlurredWallpaper(owner.context) ?: wallpaper
        if(image.isRecycled || plate.mask.isRecycled)return null
        val world=Matrix()
        XposedHelpers.callMethod(owner,"transformMatrixToGlobal",world)
        val values=FloatArray(9).also {world.getValues(it)}
        val key=listOf(wallpaper,image,values.toList(),PrefsHelper.gradientOpacity,PrefsHelper.experimentalLiquid,
            PrefsHelper.gpuEffects,MaterialFinish.key(),com.cosmod.launcherglass.util.LiveBlurEngine.wallpaperScrollRatio)
        if(plate.key==key)return plate.sample
        val dm=owner.resources.displayMetrics
        val screen=RectF(plate.bounds);world.mapRect(screen)
        val source=WallpaperFootprint.source(image,dm.widthPixels,dm.heightPixels,screen)
        val w=plate.mask.width;val h=plate.mask.height
        val local=RectF(0f,0f,w.toFloat(),h.toFloat())
        val white=LinearGradient(0f,0f,1f,0f,Color.WHITE,Color.WHITE,Shader.TileMode.CLAMP)
        val lens=if(Build.VERSION.SDK_INT>=33 && plate.style.liquid && PrefsHelper.gpuEffects) {
            val renderer=plate.lens ?: LiquidGlassShader.create()?.also {plate.lens=it}
            renderer?.update(image,white,source,w,h,null,
                (plate.radius/minOf(plate.bounds.width(),plate.bounds.height())).coerceIn(0f,.5f),plate.style)
        }else null
        var material:Shader=lens ?: GlassGeometry.wallpaperShader(image).apply {
            setLocalMatrix(Matrix().apply {setRectToRect(source,local,Matrix.ScaleToFit.FILL)})
        }
        if(Build.VERSION.SDK_INT>=33 && plate.style.filter!=null) {
            val colors=plate.colors ?: RuntimeShader(COLORS).also {plate.colors=it}
            val matrix=ColorMatrix().also {plate.style.filter.getColorMatrix(it)}.array
            colors.setInputShader("content",material)
            colors.setFloatUniform("rowR",matrix[0],matrix[1],matrix[2],matrix[3])
            colors.setFloatUniform("rowG",matrix[5],matrix[6],matrix[7],matrix[8])
            colors.setFloatUniform("rowB",matrix[10],matrix[11],matrix[12],matrix[13])
            colors.setFloatUniform("bias",matrix[4]/255f,matrix[9]/255f,matrix[14]/255f)
            material=colors
        }
        val color=(plate.style.opacity.coerceIn(0,100)*255/100 shl 24) or
            ((plate.style.plateColor ?: PrefsHelper.plateColor(SurfaceTone.base())) and 0xffffff)
        material=ComposeShader(material,LinearGradient(0f,0f,1f,0f,color,color,Shader.TileMode.CLAMP),PorterDuff.Mode.SRC_OVER)
        if(plate.decorate && PrefsHelper.gradientOpacity>0)
            material=ComposeShader(material,GlassVolume.shader(h.toFloat(),PrefsHelper.gradientOpacity),PorterDuff.Mode.SRC_OVER)
        val mask=BitmapShader(plate.mask,Shader.TileMode.DECAL,Shader.TileMode.DECAL)
        material=ComposeShader(material,mask,PorterDuff.Mode.DST_IN)
        val scale=maxOf(dm.widthPixels.toFloat()/wallpaper.width,dm.heightPixels.toFloat()/wallpaper.height)
        val offset=BackdropCoordinates.offset(wallpaper) ?: ((wallpaper.width-dm.widthPixels/scale).coerceAtLeast(0f)*
            com.cosmod.launcherglass.util.LiveBlurEngine.wallpaperScrollRatio)
        val toWallpaper=Matrix().apply {
            setRectToRect(local,plate.bounds,Matrix.ScaleToFit.FILL)
            postConcat(world);postScale(1f/scale,1f/scale);postTranslate(offset,0f)
        }
        material.setLocalMatrix(toWallpaper)
        val composed=ComposeShader(GlassGeometry.wallpaperShader(wallpaper),material,PorterDuff.Mode.SRC_OVER)
        return Sample(composed,++revision).also {plate.key=key;plate.sample=it}
    }
    private const val COLORS="""
        uniform shader content;
        uniform float4 rowR;
        uniform float4 rowG;
        uniform float4 rowB;
        uniform float3 bias;
        half4 main(float2 p) {
            half4 c=content.eval(p);
            return half4(clamp(float3(dot(c,rowR),dot(c,rowG),dot(c,rowB))+bias,0.0,1.0),c.a);
        }
    """
}
