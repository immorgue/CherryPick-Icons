package com.cosmod.launcherglass.ui

/** Group switches never overwrite the user's individual feature settings. */
internal object ModuleFeatures {
    const val ICONS="feature_icons"
    const val DESKTOP="feature_desktop"
    const val LAYOUT="feature_layout"
    val keys=listOf(ICONS,DESKTOP,LAYOUT)
}
