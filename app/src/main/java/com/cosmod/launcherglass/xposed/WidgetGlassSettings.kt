package com.cosmod.launcherglass.xposed

import android.os.Bundle
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider as Keys
import com.cosmod.launcherglass.util.AutoIconStyle
import kotlin.math.roundToInt

internal enum class WidgetKind(val switchKey: String?, val opacityKey: String?) {
    PHOTO(Keys.KEY_PHOTO_WIDGET_BLUR, Keys.KEY_PHOTO_WIDGET_OPACITY),
    CLOCK(Keys.KEY_CLOCK_WIDGET_GLASS, Keys.KEY_CLOCK_WIDGET_OPACITY),
    WEATHER(Keys.KEY_WEATHER_WIDGET_GLASS, Keys.KEY_WEATHER_WIDGET_OPACITY),
    OTHER(null, null);

    companion object {
        fun fromName(name: String?): WidgetKind {
            val value = name.orEmpty()
            return when {
                clocks.any { value.contains(it, ignoreCase = true) } -> CLOCK
                weather.any { value.contains(it, ignoreCase = true) } -> WEATHER
                else -> OTHER
            }
        }

        private val clocks = listOf("clock", "horloge", "часы", "時計", "时钟", "時鐘", "시계")
        private val weather = listOf("weather", "forecast", "погод", "прогноз", "météo", "wetter", "天気", "天气", "天氣", "날씨")
    }
}

internal data class WidgetGlassOptions(val enabled: Boolean, val opacity: Int) {
    companion object {
        fun read(kind: WidgetKind, bundle: Bundle, desktop: Boolean): WidgetGlassOptions {
            val key = requireNotNull(kind.switchKey)
            val opacity = requireNotNull(kind.opacityKey)
            // Older builds had one switch for every detected widget.
            val enabled = bundle.getBoolean(key, bundle.getBoolean(Keys.KEY_PHOTO_WIDGET_BLUR, false))
            return WidgetGlassOptions(desktop && enabled, bundle.getInt(opacity, SettingsDefaults.integer(opacity)).coerceIn(0, 100))
        }
    }
}

internal object WidgetGlassSettings {
    fun style(base: GlassStyle, kind: WidgetKind, look: Int, tintOpacity: Int, photoWidth: Int, photoDistortion: Int): GlassStyle =
        when (kind) {
            WidgetKind.CLOCK, WidgetKind.WEATHER -> base.copy(opacity = opacity(base.opacity, kind, look, tintOpacity), extendedLens = true)
            WidgetKind.PHOTO -> if (look >= AutoIconStyle.DEFAULT) base.copy(extendedLens = true)
                else base.copy(width = photoWidth, distortion = photoDistortion, extendedLens = true)
            WidgetKind.OTHER -> base.copy(extendedLens = true)
        }

    internal fun opacity(iconOpacity: Int, kind: WidgetKind, look: Int, value: Int): Int {
        if (look != AutoIconStyle.CLEAR && look != AutoIconStyle.TINTED) return value
        // Keep the saved slider's range. At its default position it inherits
        // the active preset's tint instead of replacing it with a fixed alpha.
        val center = SettingsDefaults.integer(requireNotNull(kind.opacityKey))
        return if (value <= center) (iconOpacity * value / center.toFloat()).roundToInt()
        else iconOpacity + ((100 - iconOpacity) * (value - center) / (100 - center).toFloat()).roundToInt()
    }
}
