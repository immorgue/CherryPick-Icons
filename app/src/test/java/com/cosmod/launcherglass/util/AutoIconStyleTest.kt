package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class AutoIconStyleTest {
    @Test fun explicitThemeOverridesWallpaperButAutoFollowsIt() {
        assertTrue(AutoIconStyle.dark(0,.15f))
        assertFalse(AutoIconStyle.dark(0,.85f))
        assertFalse(AutoIconStyle.dark(IconColorTheme.LIGHT,.05f))
        assertTrue(AutoIconStyle.dark(IconColorTheme.DARK,.95f))
    }
    @Test fun clearHasLessPlateCoverageThanTintedInBothThemes() {
        for(dark in listOf(false,true))assertTrue(
            AutoIconStyle.opacity(AutoIconStyle.CLEAR,dark)<AutoIconStyle.opacity(AutoIconStyle.TINTED,dark))
    }
    @Test fun automaticTintIsSelectedByDefault() {
        assertEquals(1,AutoIconStyle.defaults.getValue(AutoIconStyle.ENGINE))
        assertEquals(AutoIconStyle.TINTED,AutoIconStyle.defaults.getValue(AutoIconStyle.LOOK))
        assertEquals(0,AutoIconStyle.defaults.getValue(AutoIconStyle.SOURCE))
    }
    @Test fun bitmapReliefPreservesContrastAndTransparency() {
        val source=intArrayOf(0,0xff202020.toInt(),0xff808080.toInt(),0xffeeeeee.toInt())
        val result=AutoGlyphTone.apply(source,2,2,0xff80c0ff.toInt(),true)
        assertEquals(0,result[0])
        assertTrue((result[1] and 255)<(result[2] and 255))
        assertTrue((result[2] and 255)<(result[3] and 255))
        assertEquals(255,result[3] ushr 24)
        assertEquals(0xffeeeeee.toInt(),source[3])
    }
    @Test fun uniformMasksKeepTheSelectedToneAndAlpha() {
        val result=AutoGlyphTone.apply(intArrayOf(0x80ffffff.toInt()),1,1,0xeaffffff.toInt(),false)
        assertEquals(128*234/255,result[0] ushr 24)
        assertEquals(0xffffff,result[0] and 0xffffff)
    }
    @Test fun autoMaterialKeepsStoredIdsAndOnlyLiquidUsesBlurTransparency() {
        assertEquals(MaterialMode.FROSTED,AutoIconStyle.materialMode(0,true))
        assertEquals(MaterialMode.KYANT,AutoIconStyle.materialMode(1,true))
        assertEquals(MaterialMode.FROSTED,AutoIconStyle.materialMode(1,false))
        assertEquals(MaterialMode.COLOR,AutoIconStyle.materialMode(2,true))
        assertTrue(2 in AutoIconStyle.range(AutoIconStyle.MATERIAL))
        assertEquals(0,MaterialMode.blurTransparency(MaterialMode.FROSTED,true,true,80))
        assertEquals(0,MaterialMode.blurTransparency(MaterialMode.COLOR,false,true,80))
        assertEquals(15,MaterialMode.blurTransparency(MaterialMode.KYANT,true,false,80))
        assertEquals(80,MaterialMode.blurTransparency(MaterialMode.KYANT,false,true,80))
    }
}
