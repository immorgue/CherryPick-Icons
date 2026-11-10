package com.cosmod.launcherglass.ui

import org.junit.Assert.*
import org.junit.Test

class WallpaperProfileScopeTest {
    @Test fun switchingAppearanceCannotRestoreLayoutOrDeveloperChoices() {
        val keys=WallpaperProfiles.appearanceKeys()
        assertTrue(keys.contains(SettingsProvider.KEY_BLUR_RADIUS))
        assertTrue(keys.contains(FolderSettingsKeys.OWN))
        assertTrue(keys.contains(DockSettingsKeys.OWN))
        for(key in listOf(SettingsProvider.KEY_ENABLED,SettingsProvider.KEY_DEVELOPER_MODE,
            SettingsProvider.KEY_MD3_UI,SettingsProvider.KEY_SCENE_DRAG_BACKDROP,
            SettingsProvider.KEY_STATIC_ANIMATION_BLUR,SettingsProvider.KEY_HAZE_BLOOM,SettingsProvider.KEY_SMOOTH_SWIPE_LEAD,
            SettingsProvider.KEY_COMPACT_DOCK_EFFECTS,SettingsProvider.KEY_REUSE_STATIC_WALLPAPER,
            WallpaperProfiles.ENABLED)+com.cosmod.launcherglass.util.DesktopSizing.ranges.keys)
            assertFalse("Must not restore $key",key in keys)
        // This key also goes through the shared switch/reset UI schema.
        assertFalse(SettingsDefaults.boolean(WallpaperProfiles.ENABLED))
        assertFalse(SettingsDefaults.boolean(SettingsProvider.KEY_HAZE_BLOOM))
    }
}
