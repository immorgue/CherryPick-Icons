package com.cosmod.launcherglass.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import com.cosmod.launcherglass.ui.SettingsProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Export only the stock contour's alpha, never screen pixels or folder contents. */
internal object FolderPreviewShape {
    const val KEY = "native_folder_preview_shape_v2"
    const val METHOD = "reportRegularFolderPreviewShape"
    private val worker by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "CherryPick.folderShape").apply { isDaemon=true } } }
    private var reportedSpan = 0
    private var lastMask:Bitmap?=null
    fun reset(){reportedSpan=0;lastMask=null}

    fun report(view: View, mask: Bitmap, bounds: Rect, span: Int) {
        if (span !in 1..2 || span < reportedSpan || mask === lastMask) return
        if (bounds.height() <= 0 || kotlin.math.abs(bounds.width().toFloat() / bounds.height() - 1f) > .12f) return
        if (!view.isShown || view.alpha < .99f || kotlin.math.abs(view.scaleX - 1f) > .01f ||
            kotlin.math.abs(view.scaleY - 1f) > .01f) return
        if (generateSequence(view.javaClass as Class<*>?) { it.superclass }
                .none { it.name == "com.android.launcher3.folder.FolderIcon" }) return
        reportedSpan = span
        lastMask = mask
        val context=view.context.applicationContext
        worker.execute {
            runCatching {
                val scale=minOf(1f,256f/maxOf(mask.width,mask.height))
                val sample=if(scale<1f)Bitmap.createScaledBitmap(mask,(mask.width*scale).toInt().coerceAtLeast(1),
                    (mask.height*scale).toInt().coerceAtLeast(1),true)else mask
                val bytes=ByteArrayOutputStream().also { sample.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
                context.contentResolver.call(SettingsProvider.CONTENT_URI,METHOD,null,Bundle().apply {putByteArray("mask",bytes)})
                if(sample!==mask)sample.recycle()
            }
        }
    }
}
