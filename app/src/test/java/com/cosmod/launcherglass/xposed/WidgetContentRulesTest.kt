package com.cosmod.launcherglass.xposed

import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.util.AutoIconStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetContentRulesTest {
    @Test fun clearAndTintedWidgetsFollowTheSelectedIconPlateAtDefaultOpacity() {
        for (kind in listOf(WidgetKind.CLOCK, WidgetKind.WEATHER)) {
            val center = SettingsDefaults.integer(requireNotNull(kind.opacityKey))
            for (look in listOf(AutoIconStyle.CLEAR, AutoIconStyle.TINTED)) {
                for (dark in listOf(false, true)) {
                    val icon = AutoIconStyle.opacity(look, dark)
                    assertEquals(icon, WidgetGlassSettings.opacity(icon, kind, look, center))
                    assertEquals(0, WidgetGlassSettings.opacity(icon, kind, look, 0))
                    assertEquals(100, WidgetGlassSettings.opacity(icon, kind, look, 100))
                }
            }
            assertEquals(37, WidgetGlassSettings.opacity(70, kind, -1, 37))
        }
    }

    @Test fun weatherClocksAndForecastsUseSeparateSettings() {
        assertEquals(WidgetKind.CLOCK, WidgetKind.fromName("Weather clock"))
        assertEquals(WidgetKind.WEATHER, WidgetKind.fromName("Current weather"))
        assertEquals(WidgetKind.WEATHER, WidgetKind.fromName("Прогноз погоды"))
        assertEquals(WidgetKind.WEATHER, WidgetKind.fromName("天气"))
        assertEquals(WidgetKind.OTHER, WidgetKind.fromName("Gallery"))
    }

    @Test fun typicalClockAndWeatherKeepTheirContent() {
        assertEquals(WidgetMaterial.GLASS, WidgetContentRules.material(false, false, true, false, false))
        assertEquals(WidgetMaterial.GLASS, WidgetContentRules.material(false, true, true, false, false))
    }

    @Test fun fullBleedPhotosWorkWithoutProviderOverrides() {
        assertEquals(WidgetMaterial.PHOTO, WidgetContentRules.material(false, true, false, false, true))
        assertEquals(WidgetMaterial.PHOTO, WidgetContentRules.material(true, false, true, false, false))
    }

    @Test fun leavesUnreplaceableBackgroundsAndSeparateSurfacesNative() {
        assertEquals(WidgetMaterial.NATIVE, WidgetContentRules.material(false, false, true, false, true))
        assertEquals(WidgetMaterial.NATIVE, WidgetContentRules.material(false, true, false, true, false))
    }

    @Test fun removesUniformDialBackingAndKeepsTicks() {
        val backing = 0xff282828.toInt()
        val tick = 0xffababab.toInt()
        val mixed = IntArray(80) { if (it < 64) backing else tick }
        assertEquals(backing, WidgetBitmapBackdrop.dominantColor(mixed))
        assertEquals(0, WidgetBitmapBackdrop.foreground(backing, backing))
        val detail = WidgetBitmapBackdrop.foreground(tick, backing)
        assertEquals(155, detail ushr 24)
        assertEquals(0xffffff, detail and 0xffffff)
        assertEquals(null, WidgetBitmapBackdrop.dominantColor(mixed.copyOfRange(54, 80)))
    }
}
