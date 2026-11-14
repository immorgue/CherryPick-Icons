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
            SettingsProvider.KEY_STATIC_ANIMATION_BLUR,SettingsProvider.KEY_SMOOTH_SWIPE_LEAD,
            SettingsProvider.KEY_MUSIC_ISLAND_EXPANSION,
            WallpaperProfiles.ENABLED)+com.cosmod.launcherglass.util.DesktopSizing.ranges.keys)
            assertFalse("Must not restore $key",key in keys)
        // This key also goes through the shared switch/reset UI schema.
        assertFalse(SettingsDefaults.boolean(WallpaperProfiles.ENABLED))
    }
    @Test fun retiredExperimentsCannotReturnThroughProfilesOrPresets() {
        val retired=setOf("dock_event_driven","experimental_compact_dock_effects","experimental_reuse_static_wallpaper",
            "glass_haze_enabled","experimental_haze_bloom","blur_color_depth_enabled","blur_color_depth_bits","blur_color_depth_stage")
        assertTrue(SettingsDefaults.portable().keys.intersect(retired).isEmpty())
        assertTrue(WallpaperProfiles.appearanceKeys().intersect(retired).isEmpty())
    }
    @Test fun developerDefaultsKeepCostlyExperimentsOffAndOptimizationsOn() {
        for(key in listOf(SettingsProvider.KEY_MODULE_LOGGING,SettingsProvider.KEY_SCENE_DRAG_BACKDROP,
            SettingsProvider.KEY_STATIC_ANIMATION_BLUR,SettingsProvider.KEY_SWIPE_LEAD))
            assertFalse(key,SettingsDefaults.boolean(key))
        for(key in listOf(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION,SettingsProvider.KEY_OPTIMIZE_MINI_ICONS,
            SettingsProvider.KEY_LIMIT_CLOSING_BLUR))
            assertTrue(key,SettingsDefaults.boolean(key))
    }
}
