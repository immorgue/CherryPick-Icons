package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.util.LruCache
import java.util.WeakHashMap
import kotlin.math.*
import com.cosmod.launcherglass.util.LiquidOptics

/** Software counterpart of LiquidGlassShader: the same lens, native mask and linear sampling. */
internal object CpuLiquidGlass {
    private data class Key(val width:Int,val height:Int,val rounding:Float,val edge:Int,val strength:Int,val extend:Boolean,val exactRadius:Boolean,
        val kyant:KyantOptics?)
    private data class Coordinates(val x:FloatArray,val y:FloatArray,val light:FloatArray?,
        val dispersionX:FloatArray?,val dispersionY:FloatArray?,val shadow:FloatArray?)
    private data class Pixels(val generation:Int,val pixels:IntArray)
    private val images=WeakHashMap<Bitmap,Pixels>()
    private val coordinates=object:LruCache<Key,Coordinates>(4*1024*1024){
        override fun sizeOf(key:Key,value:Coordinates)=value.x.size*(8+(if(value.light==null)0 else 4)+(if(value.shadow==null)0 else 12))
    }
    fun clear(){images.clear();coordinates.evictAll()}
    private fun smooth(a:Float,b:Float,value:Float):Float {
        val t=((value-a)/(b-a)).coerceIn(0f,1f);return t*t*(3f-2f*t)
    }
    private fun coordinates(key:Key):Coordinates {
        coordinates.get(key)?.let {return it}
        val w=key.width;val h=key.height;val edge=min(w,h).toFloat()
        val padX=if(key.extend)(w-edge)*.5f else 0f
        val padY=if(key.extend)(h-edge)*.5f else 0f
        val halfX=if(key.extend)edge*.5f else w*.5f
        val halfY=if(key.extend)edge*.5f else h*.5f
        val radius=edge*max(key.rounding,if(key.exactRadius)0f else .20f).coerceIn(0f,.5f)
        val cx=halfX-radius;val cy=halfY-radius
        val edgeStart=(1f-(.015f+key.edge*.0014f)*4f).coerceIn(.25f,.96f)
        val kyant=key.kyant
        val magnifier=kyant?.magnifier==true
        val controlCenter=kyant?.controlCenter==true
        val result=Coordinates(FloatArray(w*h),FloatArray(w*h),if(kyant!=null)FloatArray(w*h)else null,
            if(magnifier)FloatArray(w*h)else null,if(magnifier)FloatArray(w*h)else null,if(magnifier)FloatArray(w*h)else null)
        val eta=1f/1.45f
        for(y in 0 until h)for(x in 0 until w){
            val px=x+.5f;val py=y+.5f
            if(kyant!=null){
                val centerX=px-w*.5f;val centerY=py-h*.5f
                val r=edge*key.rounding.coerceIn(0f,.5f)
                val qx=abs(centerX)-w*.5f+r;val qy=abs(centerY)-h*.5f+r
                val xOutside=max(qx,0f);val yOutside=max(qy,0f)
                val distanceLength=sqrt(xOutside*xOutside+yOutside*yOutside)
                val distance=distanceLength+min(max(qx,qy),0f)-r
                val normalAdjustment=if(controlCenter)min(r*1.5f,edge*.5f)-r else 0f
                val ox=max(qx+normalAdjustment,0f);val oy=max(qy+normalAdjustment,0f)
                val len=if(normalAdjustment==0f)distanceLength else sqrt(ox*ox+oy*oy)
                val nx=sign(centerX)*(if(len>.0001f)ox/len else if(qx>qy)1f else 0f)
                val ny=sign(centerY)*(if(len>.0001f)oy/len else if(qx>qy)0f else 1f)
                val t=(1f+distance/max(kyant.band(edge),.5f)).coerceIn(0f,1f)
                val shift=(1f-sqrt(max(0f,1f-t*t)))*kyant.amount(edge)
                val i=y*w+x
                val lighting=(nx+ny)*.7071f
                var bendX=nx
                var bendY=ny
                if(magnifier || controlCenter){
                    val radial=(if(controlCenter)1f else .65f)/max(.001f,sqrt(centerX*centerX+centerY*centerY))
                    bendX+=centerX*radial;bendY+=centerY*radial
                    val normal=max(.001f,sqrt(bendX*bendX+bendY*bendY))
                    bendX/=normal;bendY/=normal
                }
                if(magnifier){
                    val spread=t*t*min(kyant.amount(edge)*.22f,edge*.025f)
                    result.dispersionX!![i]=nx*spread/w
                    result.dispersionY!![i]=ny*spread/h
                    result.shadow!![i]=(1f-smooth(0f,max(1f,kyant.band(edge)*.75f),abs(distance)))*.18f*max(lighting,0f)
                }
                result.x[i]=(px-bendX*shift-(if(magnifier)centerX*.28f else 0f))/w
                result.y[i]=(py-bendY*shift-(if(magnifier)centerY*.28f else 0f))/h
                val light=if(controlCenter)lighting*lighting else abs(lighting)
                val lightWidth=edge*(if(controlCenter).025f else .035f)
                result.light!![i]=light*(1f-smooth(0f,max(1f,lightWidth),abs(distance)))*kyant.light
                continue
            }
            if(key.exactRadius){
                val cx=px-w*.5f;val cy=py-h*.5f
                val r=edge*key.rounding.coerceIn(0f,.5f)
                val qx=abs(cx)-w*.5f+r;val qy=abs(cy)-h*.5f+r
                val ox=max(qx,0f);val oy=max(qy,0f);val len=sqrt(ox*ox+oy*oy)
                val distance=len+min(max(qx,qy),0f)-r
                val dx=if(len>.001f)sign(cx)*ox/len else if(qx>qy)sign(cx)else 0f
                val dy=if(len>.001f)sign(cy)*oy/len else if(qy>=qx)sign(cy)else 0f
                val band=max(edge*(.015f+key.edge*.0014f)*2f,.5f)
                val shoulder=1f-smooth(0f,band,max(-distance,0f))
                val corner=smooth(0f,.3f,min(ox,oy)/max(r,1f))*smooth(.2f,.5f,key.rounding)
                val slope=.95f*(1f-.45f*corner)*shoulder/sqrt(max(.15f,1f-.85f*shoulder*shoulder))
                val norm=sqrt(1f+slope*slope);val nx=dx*slope/norm;val ny=dy*slope/norm;val nz=1f/norm
                val dot=-nz;val factor=eta*dot+sqrt(max(1f-eta*eta*(1f-dot*dot),0f))
                val amount=edge*.22f*(key.strength/50f)/max(eta+factor*nz,.25f)
                val i=y*w+x;result.x[i]=(px-factor*nx*amount)/w;result.y[i]=(py-factor*ny*amount)/h
                continue
            }
            val centerX=sign(px-w*.5f)*max(abs(px-w*.5f)-padX,0f)
            val centerY=sign(py-h*.5f)*max(abs(py-h*.5f)-padY,0f)
            val distance=sqrt(centerX*centerX+centerY*centerY)
            val dx=centerX/max(distance,.001f);val dy=centerY/max(distance,.001f)
            var boundary=min(halfX/max(abs(dx),.0001f),halfY/max(abs(dy),.0001f))
            if(abs(dx)*boundary>cx && abs(dy)*boundary>cy){
                val projection=abs(dx)*cx+abs(dy)*cy
                boundary=projection+sqrt(max(projection*projection-cx*cx-cy*cy+radius*radius,0f))
            }
            val radial=(distance/max(boundary,1f)).coerceIn(0f,1f)
            val shoulder=smooth(edgeStart,1f,radial)
            val slope=.18f*radial+.95f*shoulder/sqrt(max(.15f,1f-.85f*shoulder*shoulder))
            val length=sqrt(slope*slope+1f)
            val nx=dx*slope/length;val ny=dy*slope/length;val nz=1f/length
            // GLSL refract(I,N,eta), with I=(0,0,-1).
            val dot=-nz
            val k=1f-eta*eta*(1f-dot*dot)
            val factor=eta*dot+sqrt(max(k,0f))
            val rx=-factor*nx;val ry=-factor*ny;val rz=-eta-factor*nz
            val amount=edge*.22f*(key.strength/50f)/max(-rz,.25f)
            val i=y*w+x
            result.x[i]=(px+rx*amount)/w
            result.y[i]=(py+ry*amount)/h
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
        val map=coordinates(Key(width,height,rounding,style.width,style.distortion,style.folder || style.extendedLens,style.exactLensRadius,style.kyant))
        val experimental=style.experimentalOptics
        val controlCenter=style.kyant?.controlCenter==true
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
            var glowR=0f;var glowG=0f;var glowB=0f
            val stepX=source.width()*.035f;val stepY=source.height()*.035f
            if(experimental)for(oy in -1..1)for(ox in -1..1){
                if(ox==0 && oy==0)continue
                val bx=(px+ox*stepX).roundToInt().coerceIn(0,ww-1)
                val by=(py+oy*stepY).roundToInt().coerceIn(0,wh-1)
                val c=pixels[by*ww+bx]
                val r=((c ushr 16) and 255)/255f;val g=((c ushr 8) and 255)/255f;val b=(c and 255)/255f
                val bright=LiquidOptics.highlight(r,g,b)*(c ushr 24)/255f*.125f
                glowR+=r*bright;glowG+=g*bright;glowB+=b*bright
            }
            val veil=if(experimental)LiquidOptics.veil((i%width+.5f)/width,(i/width+.5f)/height)else map.light?.get(i) ?: 0f
            fun channel(shift:Int):Float {
                val top=((c00 ushr shift) and 255)*(1f-fx)+((c10 ushr shift) and 255)*fx
                val bottom=((c01 ushr shift) and 255)*(1f-fx)+((c11 ushr shift) and 255)*fx
                return (top*(1f-fy)+bottom*fy)/255f
            }
            val luminance=if(controlCenter).2126f*channel(16)+.7152f*channel(8)+.0722f*channel(0) else 0f
            var rgb=0
            for(shift in 16 downTo 0 step 8){
                val glow=when(shift){16->glowR;8->glowG;else->glowB}
                val dx=map.dispersionX?.get(i) ?: 0f;val dy=map.dispersionY?.get(i) ?: 0f
                val disperse=if(shift==16)1f else if(shift==0)-1f else 0f
                val base=if(disperse!=0f && (dx!=0f || dy!=0f))
                    channelAt(pixels,ww,wh,px+dx*source.width()*disperse,py+dy*source.height()*disperse,shift)
                    else channel(shift)
                val vivid=if(controlCenter)(luminance+(base-luminance)*1.5f).coerceIn(0f,1f)else base
                val finished=LiquidOptics.channel(vivid,glow,veil)*(1f-(map.shadow?.get(i) ?: 0f))
                rgb=rgb or ((finished*255f).roundToInt().coerceIn(0,255) shl shift)
            }
            output[i]=(a shl 24) or rgb
        }
        alpha.setPixels(output,0,width,0,0,width,height)
        return BitmapShader(alpha,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
    }
}
