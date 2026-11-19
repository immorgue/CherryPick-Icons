package com.cosmod.launcherglass.util

import android.content.Context
import android.net.Uri
import android.provider.Settings

internal object SystemIconColors {
    private const val COLOR_OS_KEY = "icon_material_style_and_seed_color"
    private const val ANDROID_THEME_KEY = "theme_customization_overlay_packages"

    data class Tones(val background: Int, val foreground: Int)

    fun changedUris(): List<Uri> = listOf(
        Settings.System.getUriFor(COLOR_OS_KEY), Settings.Secure.getUriFor(ANDROID_THEME_KEY)
    )

    fun resolve(context: Context, dark: Boolean): Tones {
        val colorOs = runCatching { Settings.System.getString(context.contentResolver, COLOR_OS_KEY) }.getOrNull()
        parseColorOs(colorOs, dark)?.let { return it }
        return Tones(
            context.getColor(if (dark) android.R.color.system_accent2_800 else android.R.color.system_accent2_100),
            context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_800)
        )
    }

    // ColorOS stores style, seed, light glyph, light plate, dark glyph, dark plate.
    // The native launcher consumes the selected plate/glyph pair directly.
    internal fun parseColorOs(value: String?, dark: Boolean): Tones? {
        val fields = value?.split(',', limit = 7) ?: return null
        if (fields.size < 6) return null
        val glyph = fields.getOrNull(if (dark) 4 else 2)?.trim()?.toIntOrNull() ?: return null
        val plate = fields.getOrNull(if (dark) 5 else 3)?.trim()?.toIntOrNull() ?: return null
        if (glyph ushr 24 != 255 || plate ushr 24 != 255) return null
        return Tones(plate, glyph)
    }
}
