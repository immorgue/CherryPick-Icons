package com.cosmod.launcherglass.util

/** Automatic appearance settings. */
object AutoIconStyle {
    const val ENGINE = "auto_icons_enabled"
    const val LOOK = "auto_icons_look"
    const val THEME = "auto_icons_theme"
    const val SOURCE = "auto_icons_tint_source"
    const val HUE = "auto_icons_hue"
    const val SATURATION = "auto_icons_saturation"
    const val MATERIAL = "auto_icons_material"
    const val DEFAULT = 0
    const val DARK = 1
    const val CLEAR = 2
    const val TINTED = 3
    const val FROSTED = 0
    const val LIQUID = 1
    const val COLOR_ONLY = 2
    const val DEFAULT_ENGINE = 1
    const val DEFAULT_LOOK = TINTED
    val defaults = mapOf(ENGINE to DEFAULT_ENGINE, LOOK to DEFAULT_LOOK, THEME to 0, SOURCE to 0,
        HUE to 220, SATURATION to 55, MATERIAL to LIQUID)
    fun range(key: String): IntRange = when (key) {
        ENGINE, SOURCE -> 0..1
        LOOK -> 0..3
        THEME, MATERIAL -> 0..2
        HUE -> 0..360
        SATURATION -> 0..100
        else -> error("Unknown automatic appearance setting: $key")
    }
    internal fun materialMode(material:Int,hasLens:Boolean) = when {
        material==COLOR_ONLY -> MaterialMode.COLOR
        material==LIQUID && hasLens -> MaterialMode.KYANT
        else -> MaterialMode.FROSTED
    }
    // Wallpaper-relative automatic appearance, as requested; separate from system UI theme.
    fun dark(theme: Int, wallpaperLuminance: Float) = when (theme) {
        IconColorTheme.LIGHT -> false
        IconColorTheme.DARK -> true
        else -> wallpaperLuminance < .46f
    }
    fun opacity(look: Int, dark: Boolean): Int = when (look) {
        CLEAR -> if (dark) 36 else 20
        TINTED -> if (dark) 66 else 68
        DARK -> 70
        else -> 46
    }
}
