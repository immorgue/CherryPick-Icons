package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class IconColorThemeTest {
    @Test fun explicitChoiceSurvivesBothNativeThemes(){
        for(native in listOf(false,true)){
            assertFalse(IconColorTheme.isDark(IconColorTheme.LIGHT,native))
            assertTrue(IconColorTheme.isDark(IconColorTheme.DARK,native))
        }
    }
    @Test fun existingSettingsKeepTheirAppearanceUntilAChoiceIsMade(){
        assertFalse(IconColorTheme.isDark(IconColorTheme.FOLLOW_SYSTEM,false))
        assertTrue(IconColorTheme.isDark(IconColorTheme.FOLLOW_SYSTEM,true))
    }
}
