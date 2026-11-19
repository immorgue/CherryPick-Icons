package com.cosmod.launcherglass.ui

import android.view.RoundedCorner
import android.view.View

internal object CouiGeometry {
    const val PAGE_INSET_DP = 16
    const val GROUP_INSET_DP = 8
    const val POPUP_RADIUS_DP = 26
    const val CONTROL_HEIGHT_DP = 48

    fun insetRadius(radius: Float, padding: Float) = com.cosmod.launcherglass.util.ContinuousCorners.innerRadius(radius, padding)

    fun cardRadius(view: View): Float {
        val density = view.resources.displayMetrics.density
        val corners = view.rootWindowInsets
        val outer = listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
            .mapNotNull { corners?.getRoundedCorner(it)?.radius?.takeIf { radius -> radius > 0 } }
            .minOrNull()?.toFloat() ?: (38 * density)
        return minOf(insetRadius(outer, PAGE_INSET_DP * density),
            minOf(view.width, view.height).coerceAtLeast(1) / 2f)
    }
}
