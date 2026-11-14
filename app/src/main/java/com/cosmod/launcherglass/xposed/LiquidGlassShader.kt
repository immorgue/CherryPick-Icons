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
        shader.setFloatUniform("experimental",if(style.experimentalOptics)1f else 0f)
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
        shader.setFloatUniform("experimental",if(style.experimentalOptics)1f else 0f)
        shader.setInputShader("mask",mask)
        geometry(width,height,rounding,style)
        shader.setFloatUniform("origin",margin,margin)
        shader.setFloatUniform("limit",wrapperW.toFloat(),wrapperH.toFloat())
        colors(filter)
        return shader
    }
    private fun geometry(width:Int,height:Int,rounding:Float,style:GlassStyle){
        val edge=minOf(width,height).toFloat()
        val extend=style.folder || style.extendedLens
        shader.setFloatUniform("capPadding",if(extend)(width-edge)*.5f else 0f,if(extend)(height-edge)*.5f else 0f)
        shader.setFloatUniform("capSize",if(extend)edge else width.toFloat(),if(extend)edge else height.toFloat())
        shader.setFloatUniform("size",width.toFloat(),height.toFloat())
        shader.setFloatUniform("rounding",rounding)
        shader.setFloatUniform("exactRadius",if(style.exactLensRadius)1f else 0f)
        shader.setFloatUniform("minimumRounding",if(style.exactLensRadius)0f else .20f)
        shader.setFloatUniform("bevelWidth",.015f+style.width*.0014f)
        shader.setFloatUniform("distortion",style.distortion/50f)
        val kyant=style.kyant
        shader.setFloatUniform("kyant",if(kyant==null)0f else 1f)
        shader.setFloatUniform("kyantBand",kyant?.band(edge) ?: 0f)
        shader.setFloatUniform("kyantAmount",kyant?.amount(edge) ?: 0f)
        shader.setFloatUniform("kyantLight",kyant?.light ?: 0f)
        shader.setFloatUniform("kyantMagnifier",if(kyant?.magnifier==true)1f else 0f)
        shader.setFloatUniform("kyantControlCenter",if(kyant?.controlCenter==true)1f else 0f)
        shader.setFloatUniform("kyantDispersion",kyant?.dispersionFactor ?: 0f)
        shader.setFloatUniform("kyantZoom",kyant?.zoomFactor ?: .28f)
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
            uniform float experimental;
            uniform shader mask;
            uniform float2 capPadding;
            uniform float2 capSize;
            uniform float2 size;
            uniform float rounding;
            uniform float minimumRounding;
            uniform float exactRadius;
            uniform float bevelWidth;
            uniform float distortion;
            uniform float kyant;
            uniform float kyantBand;
            uniform float kyantAmount;
            uniform float kyantLight;
            uniform float kyantMagnifier;
            uniform float kyantControlCenter;
            uniform float kyantDispersion;
            uniform float kyantZoom;
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
                if(kyantControlCenter>.5){
                    q=abs(center)-size*.5+min(r*1.5,min(size.x,size.y)*.5);
                    outside=max(q,float2(0.0));
                    len=length(outside);
                }
                float2 axis=q.x>q.y ? float2(1.0,0.0) : float2(0.0,1.0);
                float2 normal=sign(center)*(len>.0001 ? outside/max(len,.0001) : axis);
                return float3(normal,d);
            }
            float2 lensOffset(float2 xy,float3 edge) {
                if(kyant>.5){
                    float t=clamp(1.0+edge.z/max(kyantBand,.5),0.0,1.0);
                    if(t<=0.0 && kyantMagnifier<.5)return float2(0.0);
                    float2 center=xy-size*.5;
                    float2 normal=edge.xy;
                    if(kyantMagnifier>.5 || kyantControlCenter>.5){
                        normal+=center/max(length(center),.001)*(kyantControlCenter>.5 ? 1.0 : .65);
                        normal/=max(length(normal),.001);
                    }
                    return -normal*(1.0-sqrt(max(0.0,1.0-t*t)))*kyantAmount
                        - center*(kyantMagnifier*kyantZoom);
                }
                float edgeSize=min(size.x,size.y);
                float2 center=xy-size*.5;
                if(exactRadius>.5){
                    float radius=edgeSize*clamp(rounding,0.0,.5);
                    float2 q=abs(center)-size*.5+radius;
                    float2 outside=max(q,float2(0.0));
                    float len=length(outside);
                    float distance=len+min(max(q.x,q.y),0.0)-radius;
                    float2 direction=len>.001 ? sign(center)*outside/len :
                        (q.x>q.y ? float2(sign(center.x),0.0) : float2(0.0,sign(center.y)));
                    float band=max(edgeSize*bevelWidth*2.0,.5);
                    float shoulder=1.0-smoothstep(0.0,band,max(-distance,0.0));
                    float corner=smoothstep(0.0,.3,min(outside.x,outside.y)/max(radius,1.0))*smoothstep(.2,.5,rounding);
                    float slope=.95*mix(1.0,.55,corner)*shoulder/sqrt(max(.15,1.0-.85*shoulder*shoulder));
                    float3 normal=normalize(float3(direction*slope,1.0));
                    float3 ray=refract(float3(0.0,0.0,-1.0),normal,1.0/1.45);
                    return ray.xy/max(-ray.z,.25)*(edgeSize*.22)*distortion;
                }
                // Reuse the icon lens unchanged at each rounded end. The excess
                // length is a straight extrusion of its central cross-section,
                // so wide/tall folders are neither stretched ellipses nor a
                // different contour-normal refraction model.
                center=sign(center)*max(abs(center)-capPadding,float2(0.0));
                float radialDistance=length(center);
                float2 direction=center/max(radialDistance,.001);
                // Intersect a radial ray with a rounded rectangle, instead of
                // selecting the nearest side. The optical cap stays rounded
                // even when the silhouette is deliberately almost square.
                float radius=edgeSize*clamp(max(rounding,minimumRounding),0.0,.5);
                float2 halfSize=capSize*.5;
                float2 absDirection=abs(direction);
                float2 sideDistances=halfSize/max(absDirection,float2(.0001));
                float boundary=min(sideDistances.x,sideDistances.y);
                float2 sidePoint=absDirection*boundary;
                float2 cornerCenter=halfSize-radius;
                if(sidePoint.x>cornerCenter.x && sidePoint.y>cornerCenter.y){
                    float projection=dot(absDirection,cornerCenter);
                    float discriminant=projection*projection-dot(cornerCenter,cornerCenter)+radius*radius;
                    boundary=projection+sqrt(max(discriminant,0.0));
                }
                float radial=clamp(radialDistance/max(boundary,1.0),0.0,1.0);
                float edgeStart=clamp(1.0-bevelWidth*4.0,.25,.96);
                float shoulder=smoothstep(edgeStart,1.0,radial);

                // A curved cap normal changes continuously around the whole
                // lens. Refraction through it magnifies/bends the backdrop;
                // there are no four independent planar side displacements.
                float slope=.18*radial+.95*shoulder/sqrt(max(.15,1.0-.85*shoulder*shoulder));
                float3 normal=normalize(float3(direction*slope,1.0));
                float3 ray=refract(float3(0.0,0.0,-1.0),normal,1.0/1.45);
                return ray.xy/max(-ray.z,.25)*(edgeSize*.22)*distortion;
            }
            float3 highlights(float2 q) {
                half4 c=backdrop.eval(q);
                float3 rgb=float3(c.rgb)/max(float(c.a),.00001);
                float bright=clamp((dot(rgb,float3(.2126,.7152,.0722))-.55)/.45,0.0,1.0);
                return rgb*bright*float(c.a);
            }
            float3 bloom(float2 q,float2 stepSize) {
                return (highlights(q+float2(stepSize.x,0.0))+highlights(q-float2(stepSize.x,0.0))+
                    highlights(q+float2(0.0,stepSize.y))+highlights(q-float2(0.0,stepSize.y))+
                    highlights(q+stepSize)+highlights(q-stepSize)+
                    highlights(q+float2(stepSize.x,-stepSize.y))+highlights(q+float2(-stepSize.x,stepSize.y)))*.125;
            }
            half4 shade(half4 sampled,float2 xy,float3 glow,float3 edge) {
                float4 sampleColor=float4(sampled);
                if(kyant>.5 && abs(kyantVibrancy-1.0)>.01){
                    float luminance=dot(sampleColor.rgb,float3(.2126,.7152,.0722));
                    sampleColor.rgb=clamp(mix(float3(luminance),sampleColor.rgb,kyantVibrancy),0.0,1.0);
                }
                float3 color=clamp(float3(dot(sampleColor,rowR),dot(sampleColor,rowG),dot(sampleColor,rowB))+bias,0.0,1.0);
                float2 delta=(xy/size-float2(.18,.08))*float2(1.0,.85);
                float veil=experimental*(.025+.045*exp(-dot(delta,delta)*2.4));
                color=clamp(color+(1.0-color)*veil+glow*.18,0.0,1.0);
                if(kyant>.5 && kyantLight>0.0){
                    float light=abs(dot(edge.xy,float2(.7071,.7071)));
                    if(kyantControlCenter>.5)light*=light;
                    float falloff=1.0-smoothstep(0.0,max(1.0,min(size.x,size.y)*(kyantControlCenter>.5 ? .025 : .035)),abs(edge.z));
                    color=mix(color,float3(1.0),light*falloff*kyantLight);
                }
                if(kyantMagnifier>.5){
                    float inward=1.0-smoothstep(0.0,max(1.0,kyantBand*.75),abs(edge.z));
                    color*=1.0-inward*.18*max(dot(edge.xy,float2(.7071,.7071)),0.0);
                }
                // The shared contour texture supplies identical edge lighting
                // in Frosted and Liquid Glass, honoring the contour toggle.
                half alpha=mask.eval(xy).a;
                return half4(half3(color)*alpha,alpha);
            }
            float2 kyantDispersion(float3 edge) {
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
                float3 edge=kyant>.5 ? kyantEdge(xy) : float3(0.0);
                float2 uv=(xy+lensOffset(xy,edge))/size;
                float2 q=crop.xy+uv*crop.zw;
                if(kyant>.5 && kyantDispersion>.01)
                    return shade(dispersed(q,kyantDispersion(edge)*crop.zw/size,float2(-1.0e10),float2(1.0e10)),xy,float3(0.0),edge);
                if(experimental<.5)return shade(backdrop.eval(q),xy,float3(0.0),edge);
                return shade(backdrop.eval(q),xy,bloom(q,crop.zw*.035),edge);
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
                float3 edge=kyant>.5 ? kyantEdge(p) : float3(0.0);
                float2 q=clamp(origin+p+lensOffset(p,edge),float2(.5),limit-float2(.5));
                if(kyant>.5 && kyantDispersion>.01)
                    return shade(dispersed(q,kyantDispersion(edge),float2(.5),limit-float2(.5)),p,float3(0.0),edge);
                if(experimental<.5)return shade(backdrop.eval(q),p,float3(0.0),edge);
                return shade(backdrop.eval(q),p,bloom(q,size*.035),edge);
            }
        """
    }
}
