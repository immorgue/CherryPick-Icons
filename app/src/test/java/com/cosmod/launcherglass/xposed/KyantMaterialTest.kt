package com.cosmod.launcherglass.xposed

import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider
import org.junit.Assert.*
import org.junit.Test

class KyantMaterialTest {
    @Test fun frostedRemovesEveryRefractionLayer(){
        val liquid=KyantOptics(200,300,250,150,300)
        val frosted=liquid.forMode(SettingsProvider.BLUR_STYLE_FROSTED)
        assertEquals(0f,frosted.amount(200f),0f)
        assertEquals(0f,frosted.light,0f)
        assertEquals(0f,frosted.dispersionFactor,0f)
        assertEquals(1f,frosted.vibrancyFactor,0f)
        assertFalse(frosted.needsBackdrop)
        assertEquals(liquid,liquid.forMode(SettingsProvider.BLUR_STYLE_KYANT))
    }
    @Test fun copiedDefaultsKeepAnimationsDisabledWithoutChangingTheirTuning(){
        assertFalse(SettingsDefaults.boolean(SettingsProvider.KEY_APP_WINDOW_TILT))
        assertEquals(300,SettingsDefaults.integer(SettingsProvider.KEY_APP_OPEN_RESPONSE))
        assertEquals(70,SettingsDefaults.integer(SettingsProvider.KEY_APP_CLOSE_SPEED))
        assertEquals(200,SettingsDefaults.integer(SettingsProvider.KEY_APP_GENIE_STRENGTH))
        assertEquals(200,SettingsDefaults.integer(SettingsProvider.KEY_FLIGHT_ICON_REVEAL))
        assertTrue(SettingsDefaults.boolean(SettingsProvider.KEY_DOCK_ENABLED))
        assertEquals(45,SettingsDefaults.integer(SettingsProvider.KEY_KYANT_WIDTH))
        assertEquals(300,SettingsDefaults.integer(SettingsProvider.KEY_KYANT_DISTORTION))
        assertEquals(80,SettingsDefaults.integer("dock_kyant_width"))
        assertEquals(100,SettingsDefaults.integer(SettingsProvider.KEY_DOCK_BLUR_HEIGHT))
    }
}
