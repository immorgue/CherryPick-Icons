package com.cosmod.launcherglass.xposed

import android.graphics.*

/** Weighted addition preserves the original mask alpha throughout the fade. */
internal object StaticBlurBlend {
    private fun weighted(effect:RenderEffect,weight:Float)=RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply {setScale(1f,1f,1f,weight)}),effect)
    fun effect(frozen:RenderEffect,live:RenderEffect,progress:Float):RenderEffect = when {
        progress<=0f -> frozen
        progress>=1f -> live
        else ->RenderEffect.createBlendModeEffect(weighted(frozen,1f-progress),weighted(live,progress),BlendMode.PLUS)
    }
    fun draw(canvas:Canvas,frozen:RenderNode,progress:Float,live:()->Unit){
        if(progress<=0f){frozen.alpha=1f;canvas.drawRenderNode(frozen);return}
        if(progress>=1f){live();return}
        frozen.alpha=1f-progress;canvas.drawRenderNode(frozen)
        val paint=Paint().apply {alpha=(progress*255f).toInt();blendMode=BlendMode.PLUS}
        val saved=canvas.saveLayer(null,paint)
        try {live()}finally{canvas.restoreToCount(saved)}
    }
}
