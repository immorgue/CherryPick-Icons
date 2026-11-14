package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.util.LruCache
import java.util.WeakHashMap
import kotlin.math.*

/** Software counterpart of LiquidGlassShader: the same lens, native mask and linear sampling. */
internal object CpuLiquidGlass {
    private data class Key(val width:Int,val height:Int,val rounding:Float,val kyant:KyantOptics?)
    private data class Coordinates(val x:FloatArray,val y:FloatArray,val light:FloatArray?,
        val dispersionX:FloatArray?,val dispersionY:FloatArray?)
    private data class Pixels(val generation:Int,val pixels:IntArray)
    private val images=WeakHashMap<Bitmap,Pixels>()
    private val coordinates=object:LruCache<Key,Coordinates>(4*1024*1024){
        override fun sizeOf(key:Key,value:Coordinates)=value.x.size*(8+(if(value.light==null)0 else 4)+(if(value.dispersionX==null)0 else 8))
    }
    fun clear(){images.clear();coordinates.evictAll()}
    private fun smooth(a:Float,b:Float,value:Float):Float {
        val t=((value-a)/(b-a)).coerceIn(0f,1f);return t*t*(3f-2f*t)
    }
    private fun coordinates(key:Key):Coordinates {
        coordinates.get(key)?.let {return it}
        val w=key.width;val h=key.height;val edge=min(w,h).toFloat()
        val kyant=key.kyant
        val result=Coordinates(FloatArray(w*h),FloatArray(w*h),if(kyant!=null)FloatArray(w*h)else null,
            if(kyant!=null)FloatArray(w*h)else null,if(kyant!=null)FloatArray(w*h)else null)
        for(y in 0 until h)for(x in 0 until w){
            val px=x+.5f;val py=y+.5f
            if(kyant!=null){
                val centerX=px-w*.5f;val centerY=py-h*.5f
                val r=edge*key.rounding.coerceIn(0f,.5f)
                val qx=abs(centerX)-w*.5f+r;val qy=abs(centerY)-h*.5f+r
                val xOutside=max(qx,0f);val yOutside=max(qy,0f)
                val distanceLength=sqrt(xOutside*xOutside+yOutside*yOutside)
                val distance=distanceLength+min(max(qx,qy),0f)-r
                val ox=xOutside;val oy=yOutside
                val len=distanceLength
                val nx=sign(centerX)*(if(len>.0001f)ox/len else if(qx>qy)1f else 0f)
                val ny=sign(centerY)*(if(len>.0001f)oy/len else if(qx>qy)0f else 1f)
                val t=(1f+distance/max(kyant.band(edge),.5f)).coerceIn(0f,1f)
                val shift=(1f-sqrt(max(0f,1f-t*t)))*kyant.amount(edge)
                val i=y*w+x
                val lighting=(nx+ny)*.7071f
                val bendX=nx
                val bendY=ny
                if(kyant.dispersion>0){
                    val spread=t*t*min(kyant.amount(edge)*.22f,edge*.025f)*kyant.dispersionFactor
                    result.dispersionX!![i]=nx*spread/w
                    result.dispersionY!![i]=ny*spread/h
                }
                result.x[i]=(px-bendX*shift-0f)/w
                result.y[i]=(py-bendY*shift-0f)/h
                val light=abs(lighting)
                val lightWidth=edge*.035f
                result.light!![i]=light*(1f-smooth(0f,max(1f,lightWidth),abs(distance)))*kyant.light
                continue
            }
            val i=y*w+x
            result.x[i]=px/w;result.y[i]=py/h
        }
        coordinates.put(key,result);return result
    }
    private fun channelAt(pixels:IntArray,width:Int,height:Int,rawX:Float,rawY:Float,shift:Int):Float {
        val px=rawX.coerceIn(0f,(width-1).toFloat())
        val py=rawY.coerceIn(0f,(height-1).toFloat())
        val x=px.toInt();val y=py.toInt();val xx=min(x+1,width-1);val yy=min(y+1,height-1)
        val fx=px-x;val fy=py-y
        val top=((pixels[y*width+x] ushr shift) and 255)*(1f-fx)+((pixels[y*width+xx] ushr shift) and 255)*fx
        val bottom=((pixels[yy*width+x] ushr shift) and 255)*(1f-fx)+((pixels[yy*width+xx] ushr shift) and 255)*fx
        return (top*(1f-fy)+bottom*fy)/255f
    }
    fun shader(wallpaper:Bitmap,mask:Bitmap,width:Int,height:Int,source:RectF,rounding:Float,style:GlassStyle):Shader {
        val pixels=images[wallpaper]?.takeIf {it.generation==wallpaper.generationId}?.pixels ?: run {
            val readable=if(wallpaper.config==Bitmap.Config.HARDWARE)wallpaper.copy(Bitmap.Config.ARGB_8888,false)else wallpaper
            val data=IntArray(wallpaper.width*wallpaper.height)
            readable.getPixels(data,0,wallpaper.width,0,0,wallpaper.width,wallpaper.height)
            if(readable!==wallpaper)readable.recycle()
            images[wallpaper]=Pixels(wallpaper.generationId,data);data
        }
        val map=coordinates(Key(width,height,rounding,style.kyant))
        val alpha=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        Canvas(alpha).drawBitmap(mask,null,Rect(0,0,width,height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val output=IntArray(width*height);alpha.getPixels(output,0,width,0,0,width,height)
        val ww=wallpaper.width;val wh=wallpaper.height
        for(i in output.indices){
            val a=output[i] ushr 24
            if(a==0){output[i]=0;continue}
            val px=(source.left+map.x[i]*source.width()-.5f).coerceIn(0f,(ww-1).toFloat())
            val py=(source.top+map.y[i]*source.height()-.5f).coerceIn(0f,(wh-1).toFloat())
            val x=px.toInt();val y=py.toInt();val xx=min(x+1,ww-1);val yy=min(y+1,wh-1)
            val fx=px-x;val fy=py-y
            val c00=pixels[y*ww+x];val c10=pixels[y*ww+xx];val c01=pixels[yy*ww+x];val c11=pixels[yy*ww+xx]
            val veil=map.light?.get(i) ?: 0f
            fun channel(shift:Int):Float {
                val top=((c00 ushr shift) and 255)*(1f-fx)+((c10 ushr shift) and 255)*fx
                val bottom=((c01 ushr shift) and 255)*(1f-fx)+((c11 ushr shift) and 255)*fx
                return (top*(1f-fy)+bottom*fy)/255f
            }
            val luminance=.2126f*channel(16)+.7152f*channel(8)+.0722f*channel(0)
            var rgb=0
            for(shift in 16 downTo 0 step 8){
                val dx=map.dispersionX?.get(i) ?: 0f;val dy=map.dispersionY?.get(i) ?: 0f
                val disperse=if(shift==16)1f else if(shift==0)-1f else 0f
                val base=if(disperse!=0f && (dx!=0f || dy!=0f))
                    channelAt(pixels,ww,wh,px+dx*source.width()*disperse,py+dy*source.height()*disperse,shift)
                    else channel(shift)
                val vivid=(luminance+(base-luminance)*(style.kyant?.vibrancyFactor ?: 1f)).coerceIn(0f,1f)
                val finished=(vivid+(1f-vivid)*veil.coerceIn(0f,1f)).coerceIn(0f,1f)
                rgb=rgb or ((finished*255f).roundToInt().coerceIn(0,255) shl shift)
            }
            output[i]=(a shl 24) or rgb
        }
        alpha.setPixels(output,0,width,0,0,width,height)
        return BitmapShader(alpha,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
    }
}
