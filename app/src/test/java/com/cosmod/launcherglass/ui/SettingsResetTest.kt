package com.cosmod.launcherglass.ui

import android.content.SharedPreferences
import com.cosmod.launcherglass.util.AutoIconStyle
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SettingsResetTest {
    @Test fun resetsUserChoicesWithoutDeletingDeviceState() {
        val values=mutableMapOf<String,Any>(
            SettingsProvider.KEY_ENABLED to true,
            ModuleFeatures.LAYOUT to false,
            SettingsProvider.KEY_HAZE_ENABLED to true,
            SettingsProvider.KEY_BLUR_TRANSPARENCY to 70,
            SettingsProvider.KEY_LEGACY_INTERFACE to true,
            SettingsProvider.KEY_BOOT_RESTART to false,
            WallpaperProfiles.ENABLED to false,
            FolderSettingsKeys.OWN to true,
            FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS) to 44,
            "ui_language" to "ru",
            "native_folder_preview_shape_v1" to "device-mask",
            "defaults_revision" to 38)
        lateinit var editor:SharedPreferences.Editor
        editor=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)) {_,method,args ->
            when(method.name){
                "putBoolean","putInt","putString" -> {values[args!![0] as String]=args[1];editor}
                "remove" -> {values.remove(args!![0] as String);editor}
                "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        val prefs=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)) {_,method,_ ->
            if(method.name=="edit")editor else null
        } as SharedPreferences
        SettingsReset.apply(prefs)
        assertEquals(false,values[SettingsProvider.KEY_ENABLED])
        assertEquals(true,values[ModuleFeatures.LAYOUT])
        assertEquals(false,values[SettingsProvider.KEY_HAZE_ENABLED])
        assertEquals(15,values[SettingsProvider.KEY_BLUR_TRANSPARENCY])
        assertEquals(2,values[SettingsProvider.KEY_ICON_COLOR_THEME])
        assertEquals(1,values[AutoIconStyle.ENGINE])
        assertEquals(AutoIconStyle.TINTED,values[AutoIconStyle.LOOK])
        assertEquals(true,values[SettingsProvider.KEY_DOCK_AUTO_APPEARANCE])
        assertEquals(80,values[SettingsProvider.KEY_GLYPH_BOTTOM_SHADE])
        assertEquals(false,values[SettingsProvider.KEY_LEGACY_INTERFACE])
        assertEquals(true,values[SettingsProvider.KEY_BOOT_RESTART])
        assertEquals(true,values[WallpaperProfiles.ENABLED])
        assertEquals(false,values[FolderSettingsKeys.OWN])
        assertFalse(values.containsKey(FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS)))
        assertFalse(values.containsKey("ui_language"))
        assertEquals("device-mask",values["native_folder_preview_shape_v1"])
        assertEquals(38,values["defaults_revision"])
    }
}
