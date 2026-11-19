package com.cosmod.launcherglass.xposed

import android.graphics.Matrix
import android.view.View
import com.cosmod.launcherglass.util.WallpaperHelper
import com.cosmod.launcherglass.bridge.HookReflection

/** Sample a static wallpaper in desktop layout space, before OEM home transforms. */
internal object WallpaperSampleCoordinates {
    fun usesLayout(view: View): Boolean {
        if (WallpaperHelper.isLiveWallpaper(view.context)) return false
        if (IconMaterialRenderer.isWorkspaceSample(view, null)) return true
        var owner: View? = view
        while (owner != null) {
            if (owner.javaClass.name.endsWith(".OplusHotseat")) return true
            owner = owner.parent as? View
        }
        return false
    }
    fun world(view: View, result: Matrix) {
        if (!usesLayout(view)) {
            HookReflection.callMethod(view, "transformMatrixToGlobal", result)
            return
        }
        result.reset()
        var node: View? = view
        var desktop = false
        while (node != null) {
            val name = node.javaClass.name
            if (name.contains("CellLayout") || name.contains("ShortcutAndWidgetContainer") ||
                name.endsWith(".Workspace") || name.endsWith(".OplusHotseat")) desktop = true
            // Keep local icon size/folder-preview geometry. Only the desktop
            // and its ancestors carry the unrelated unlock/app zoom.
            if (!desktop && !node.matrix.isIdentity) result.postConcat(node.matrix)
            val parent = node.parent as? View
            result.postTranslate((node.left - (parent?.scrollX ?: 0)).toFloat(),
                (node.top - (parent?.scrollY ?: 0)).toFloat())
            node = parent
        }
    }
}
