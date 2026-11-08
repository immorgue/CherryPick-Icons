package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Matrix

/** A continuing Canvas already contains its parent's transform; a new node does not. */
internal object PreviewCoordinates {
    @Suppress("DEPRECATION")
    fun matrix(canvas:Canvas,parentCanvas:Canvas?,parent:Matrix?):Matrix {
        val current=Matrix().also {canvas.getMatrix(it)}
        if(parent!=null && canvas!==parentCanvas)current.setConcat(parent,Matrix(current))
        return current
    }
}
