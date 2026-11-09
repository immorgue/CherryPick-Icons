package com.cosmod.launcherglass.ui

import org.junit.Assert.*
import org.junit.Test

class WallpaperProfileSwitchTest {
    @Test fun roundTripSavesDepartingSettingsAndRestoresIncomingScopedInheritance() {
        val a=mapOf<String,Any>("radius" to 25,"folder_own" to false)
        val b=mapOf<String,Any>("radius" to 10,"folder_own" to true,"folder_radius" to 40)
        val first=WallpaperProfileSwitch.plan("a","b",true,a,b)
        assertEquals(a,first.savePrevious);assertEquals(b,first.restore);assertNull(first.saveNew)
        val returning=WallpaperProfileSwitch.plan("b","a",true,b,first.savePrevious)
        assertEquals(a,returning.restore);assertFalse(returning.restore!!.containsKey("folder_radius"))
    }
    @Test fun newWallpaperKeepsCurrentAppearanceAsItsInitialProfile() {
        val current=mutableMapOf<String,Any>("radius" to 25)
        val plan=WallpaperProfileSwitch.plan("a","new",true,current,null)
        current["radius"]=5
        assertEquals(25,plan.savePrevious!!["radius"])
        assertEquals(25,plan.saveNew!!["radius"]);assertNull(plan.restore)
    }
    @Test fun disabledAndRepeatedNotificationsDoNotOverwriteProfiles() {
        val current=mapOf<String,Any>("radius" to 25);val saved=mapOf<String,Any>("radius" to 5)
        for(plan in listOf(WallpaperProfileSwitch.plan("a","b",false,current,saved),
            WallpaperProfileSwitch.plan("a","a",true,current,saved))){
            assertNull(plan.savePrevious);assertNull(plan.restore);assertNull(plan.saveNew)
        }
    }
}
