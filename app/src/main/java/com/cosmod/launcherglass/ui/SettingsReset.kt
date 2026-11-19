package com.cosmod.launcherglass.ui

import android.content.SharedPreferences

/** Reset user choices, preserving device reports, activation/scope and migration state. */
internal object SettingsReset {
    fun apply(prefs: SharedPreferences) {
        SettingsTransfer.apply(prefs, emptyMap())
        val editor=prefs.edit()
        for(key in listOf(WallpaperProfiles.ENABLED,SettingsProvider.KEY_BOOT_RESTART,
            SettingsProvider.KEY_BOOT_RESTART_TOAST,SettingsProvider.KEY_LEGACY_INTERFACE)) {
            editor.putBoolean(key,SettingsDefaults.boolean(key))
        }
        editor.remove("ui_language")
        check(editor.commit()) { "Could not reset app preferences" }
    }
}
