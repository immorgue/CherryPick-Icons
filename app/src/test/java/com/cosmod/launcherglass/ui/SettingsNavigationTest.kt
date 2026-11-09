package com.cosmod.launcherglass.ui

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun backRestoresCategoriesAndTabs() {
        val nav=SettingsNavigation()
        nav.open(SettingsScreen.MODULE);nav.open(SettingsScreen.FOLDERS)
        assertTrue(nav.back());assertEquals(SettingsScreen.MODULE,nav.current)
        nav.open(SettingsScreen.APP);assertTrue(nav.back());assertEquals(SettingsScreen.MODULE,nav.current)
        assertTrue(nav.back());assertEquals(SettingsScreen.HOME,nav.current);assertFalse(nav.back())
    }
    @Test fun recreationRestoresHistoryAndIgnoresInvalidDestinations() {
        val nav=SettingsNavigation();nav.open(SettingsScreen.MODULE);nav.open(SettingsScreen.WALLPAPERS)
        val restored=SettingsNavigation(nav.save()+intArrayOf(999))
        assertEquals(SettingsScreen.WALLPAPERS,restored.current)
        assertTrue(restored.back());assertEquals(SettingsScreen.MODULE,restored.current)
        assertFalse(restored.open(SettingsScreen.MODULE))
    }
}
