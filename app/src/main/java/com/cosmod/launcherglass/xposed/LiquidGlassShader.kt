package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.util.GlassLogger

/** One retained GPU lens per sample. No CPU pixels, extra capture, or blur pass. */
@RequiresApi(33)
internal class LiquidGlassShader private constructor(private val shader:RuntimeShader,private val stateless:Boolean=false) {
    private var image:Bitmap?=null
    private var backdrop:Shader?=null
    private var previousStyle:GlassStyle?=null
    private var previousMask:Shader?=null
    private var previousWidth=0
    private var previousHeight=0
    private var previousRounding=Float.NaN
    private var previousFilter:ColorMatrixColorFilter?=null
    private var filterSet=false
    fun update(wallpaper:Bitmap,mask:Shader,source:RectF,width:Int,height:Int,filter:ColorMatrixColorFilter?,rounding:Float,style:GlassStyle=GlassStyle.icons(),backdropOverride:Shader?=null):Shader {
        if(backdropOverride!=null){
            shader.setInputShader("backdrop",backdropOverride)
            image=null
        }else if(image!==wallpaper){
            backdrop=GlassGeometry.wallpaperShader(wallpaper)
            shader.setInputShader("backdrop",backdrop!!)
            image=wallpaper
        }else if(stateless)shader.setInputShader("backdrop",backdrop!!)
        // A shared instance is re-armed completely for each snapshot because
        // the previous caller may have left another sample's state behind.
        if(stateless || previousMask!==mask){shader.setInputShader("mask",mask);previousMask=mask}
        if(stateless || previousStyle!=style || previousWidth!=width || previousHeight!=height || previousRounding!=rounding){
            geometry(width,height,rounding,style)
            previousStyle=style;previousWidth=width;previousHeight=height;previousRounding=rounding
        }
        shader.setFloatUniform("crop",source.left,source.top,source.width(),source.height())
        if(stateless || !filterSet || previousFilter!==filter){
            colors(filter)
            previousFilter=filter;filterSet=true
        }
        return shader
    }
    /** Lens over a RenderEffect input of [wrapperW]x[wrapperH] whose plate starts at [margin]. */
    fun updateLifted(mask:Shader,width:Int,height:Int,margin:Float,wrapperW:Int,wrapperH:Int,filter:ColorMatrixColorFilter?,rounding:Float,style:GlassStyle):RuntimeShader {
        shader.setInputShader("mask",mask)
        geometry(width,height,rounding,style)
        shader.setFloatUniform("origin",margin,margin)
        shader.setFloatUniform("limit",wrapperW.toFloat(),wrapperH.toFloat())
        colors(filter)
        return shader
    }
    private fun geometry(width:Int,height:Int,rounding:Float,style:GlassStyle){
        val edge=minOf(width,height).toFloat()
        shader.setFloatUniform("size",width.toFloat(),height.toFloat())
        shader.setFloatUniform("rounding",rounding)
        val kyant=style.kyant
        shader.setFloatUniform("kyantBand",kyant?.band(edge) ?: 0f)
        shader.setFloatUniform("kyantAmount",kyant?.amount(edge) ?: 0f)
        shader.setFloatUniform("kyantLight",kyant?.light ?: 0f)
        shader.setFloatUniform("kyantDispersion",kyant?.dispersionFactor ?: 0f)
        shader.setFloatUniform("kyantVibrancy",kyant?.vibrancyFactor ?: 1f)
    }
    private fun colors(filter:ColorMatrixColorFilter?){
        val matrix=ColorMatrix();filter?.getColorMatrix(matrix)
        val m=matrix.array
        shader.setFloatUniform("rowR",m[0],m[1],m[2],m[3])
        shader.setFloatUniform("rowG",m[5],m[6],m[7],m[8])
        shader.setFloatUniform("rowB",m[10],m[11],m[12],m[13])
        shader.setFloatUniform("bias",m[4]/255f,m[9]/255f,m[14]/255f)
    }
    companion object {
        @Volatile private var failed=false
        @Volatile private var liftedFailed=false
        private var sharedLens:LiquidGlassShader?=null
        private var liftedLens:LiquidGlassShader?=null
        fun create():LiquidGlassShader? {
            if(Build.VERSION.SDK_INT<33 || failed)return null
            return try {LiquidGlassShader(RuntimeShader(SOURCE))}catch(t:Throwable){
                failed=true;GlassLogger.e("LiquidGlass","GPU lens unavailable; keeping frosted material",t);null
            }
        }
        /** UI-thread lens for RenderEffect snapshots only, never for a Paint. */
        fun shared():LiquidGlassShader? {
            if(Build.VERSION.SDK_INT<33 || failed)return null
            sharedLens?.let {return it}
            return try {LiquidGlassShader(RuntimeShader(SOURCE),stateless=true).also {sharedLens=it}}catch(t:Throwable){
                failed=true;GlassLogger.e("LiquidGlass","GPU lens unavailable; keeping frosted material",t);null
            }
        }
        /** UI-thread lens for lifted RenderEffects; [updateLifted] re-arms every uniform. */
        fun lifted():LiquidGlassShader? {
            if(Build.VERSION.SDK_INT<33 || failed || liftedFailed)return null
            liftedLens?.let {return it}
            return try {LiquidGlassShader(RuntimeShader(LIFTED_SOURCE),stateless=true).also {liftedLens=it}}catch(t:Throwable){
                liftedFailed=true;GlassLogger.e("LiquidGlass","Lifted GPU lens unavailable; using frosted lifted material",t);null
            }
        }
        // Shared declarations and lens geometry. Both entry points evaluate
        // the same contour-normal refraction; only backdrop addressing differs.
        private const val HEADER="""
            uniform shader backdrop;
            uniform shader mask;
            uniform float2 size;
            uniform float rounding;
            uniform float kyantBand;
            uniform float kyantAmount;
            uniform float kyantLight;
            uniform float kyantDispersion;
            uniform float kyantVibrancy;
            uniform float4 rowR;
            uniform float4 rowG;
            uniform float4 rowB;
            uniform float3 bias;
        """
        // Kyant edge refraction adapted from AndroidLiquidGlass 2.0.1.
        // Copyright 2025 Kyant. Licensed under Apache-2.0.
        private const val LENS="""
            float3 kyantEdge(float2 xy) {
                float2 center=xy-size*.5;
                float r=min(size.x,size.y)*clamp(rounding,0.0,.5);
                float2 q=abs(center)-size*.5+r;
                float2 outside=max(q,float2(0.0));
                float len=length(outside);
                float d=len+min(max(q.x,q.y),0.0)-r;
                float2 axis=q.x>q.y ? float2(1.0,0.0) : float2(0.0,1.0);
                float2 normal=sign(center)*(len>.0001 ? outside/max(len,.0001) : axis);
                return float3(normal,d);
            }
            float2 lensOffset(float2 xy,float3 edge) {
                float t=clamp(1.0+edge.z/max(kyantBand,.5),0.0,1.0);
                return -edge.xy*(1.0-sqrt(max(0.0,1.0-t*t)))*kyantAmount;
            }
            half4 shade(half4 sampled,float2 xy,float3 glow,float3 edge) {
                float4 sampleColor=float4(sampled);
                if(abs(kyantVibrancy-1.0)>.01){
                    float luminance=dot(sampleColor.rgb,float3(.2126,.7152,.0722));
                    sampleColor.rgb=clamp(mix(float3(luminance),sampleColor.rgb,kyantVibrancy),0.0,1.0);
                }
                float3 color=clamp(float3(dot(sampleColor,rowR),dot(sampleColor,rowG),dot(sampleColor,rowB))+bias,0.0,1.0);
                if(kyantLight>0.0){
                    float light=abs(dot(edge.xy,float2(.7071,.7071)));
                    float falloff=1.0-smoothstep(0.0,max(1.0,min(size.x,size.y)*.035),abs(edge.z));
                    color=mix(color,float3(1.0),clamp(light*falloff*kyantLight,0.0,1.0));
                }

                // The shared contour texture supplies identical edge lighting
                // in Frosted and Liquid Glass, honoring the contour toggle.
                half alpha=mask.eval(xy).a;
                return half4(half3(color)*alpha,alpha);
            }
            float2 kyantDispersionOffset(float3 edge) {
                float t=clamp(1.0+edge.z/max(kyantBand,.5),0.0,1.0);
                return edge.xy*t*t*min(kyantAmount*.22,min(size.x,size.y)*.025)*kyantDispersion;
            }
            half4 dispersed(float2 q,float2 spread,float2 low,float2 high) {
                half4 c=backdrop.eval(clamp(q,low,high));
                if(dot(spread,spread)>.0001){
                    c.r=backdrop.eval(clamp(q+spread,low,high)).r;
                    c.b=backdrop.eval(clamp(q-spread,low,high)).b;
                }
                return c;
            }
        """
        private const val SOURCE=HEADER+"""
            uniform float4 crop;
        """+LENS+"""
            half4 main(float2 xy) {
                float3 edge=kyantEdge(xy);
                float2 uv=(xy+lensOffset(xy,edge))/size;
                float2 q=crop.xy+uv*crop.zw;
                if(kyantDispersion>.01)
                    return shade(dispersed(q,kyantDispersionOffset(edge)*crop.zw/size,float2(-1.0e10),float2(1.0e10)),xy,float3(0.0),edge);
                return shade(backdrop.eval(q),xy,float3(0.0),edge);
            }
        """
        // Lifted variant: "backdrop" is the RenderEffect input of a node that
        // extends the plate by [origin] on every side, so refraction may look
        // past the silhouette without sampling beyond the recorded backdrop.
        private const val LIFTED_SOURCE=HEADER+"""
            uniform float2 origin;
            uniform float2 limit;
        """+LENS+"""
            half4 main(float2 xy) {
                float2 p=xy-origin;
                if(p.x<0.0 || p.y<0.0 || p.x>size.x || p.y>size.y)return half4(0.0);
                float3 edge=kyantEdge(p);
                float2 q=clamp(origin+p+lensOffset(p,edge),float2(.5),limit-float2(.5));
                if(kyantDispersion>.01)
                    return shade(dispersed(q,kyantDispersionOffset(edge),float2(.5),limit-float2(.5)),p,float3(0.0),edge);
                return shade(backdrop.eval(q),p,float3(0.0),edge);
            }
        """
    }
}
