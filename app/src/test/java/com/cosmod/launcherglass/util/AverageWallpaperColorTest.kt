package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class AverageWallpaperColorTest {
    @Test fun computesTheWholeImageMeanRatherThanTheBrightestColor(){
        assertEquals(0xff7f7f7f.toInt(),AverageWallpaperColor.of(intArrayOf(0xff000000.toInt(),0xffffffff.toInt())))
        val pixels=IntArray(100){if(it<5)0xffff6600.toInt()else 0xff303944.toInt()}
        val mean=AverageWallpaperColor.of(pixels)!!
        assertTrue((mean ushr 16 and 255)<65)
    }
    @Test fun transparentAreasCannotIntroduceBlack(){
        assertEquals(0xff80a0c0.toInt(),AverageWallpaperColor.of(intArrayOf(0,0xff80a0c0.toInt())))
        assertNull(AverageWallpaperColor.of(intArrayOf(0)))
    }
    @Test fun broadMutedColorWinsOverTinySaturatedAccent(){
        val blue=0xff405977.toInt()
        val pixels=IntArray(100){if(it<5)0xffff6600.toInt()else blue}
        assertEquals(blue,AverageWallpaperColor.accent(pixels,AverageWallpaperColor.of(pixels)!!))
    }
    @Test fun isolatedColorDoesNotTintAnOtherwiseNeutralWallpaper(){
        val neutral=0xff858585.toInt()
        val pixels=IntArray(100){if(it<4)0xff24ff30.toInt()else neutral}
        assertEquals(neutral,AverageWallpaperColor.accent(pixels,neutral))
    }
    @Test fun redsOnBothSidesOfHueWrapCountTogether(){
        val pixels=IntArray(100){when {
            it<30 ->0xffc64748.toInt()
            it<60 ->0xffc64d47.toInt()
            else ->0xff3c70c5.toInt()
        }}
        val picked=AverageWallpaperColor.accent(pixels,0)
        assertEquals(198,(picked ushr 16) and 255)
    }
    @Test fun tintedPicturesRetainTheBackgroundInsteadOfASeparatePaleRim(){
        val result=AutoGlyphTone.apply(intArrayOf(0xff101010.toInt(),0xffeeeeee.toInt()),2,1,
            0xffffffff.toInt(),false,0xff70b090.toInt())
        assertEquals(0xff70b090.toInt(),result[0])
        assertEquals(0xffffffff.toInt(),result[1])
    }
    @Test fun flatArtworkExposesGlassAndPreservesItsAntialiasedSilhouette(){
        val input=intArrayOf(0x00eeeeee,0x30eeeeee,0xff202020.toInt(),0xff888888.toInt(),0xffeeeeee.toInt())
        val alpha=AutoGlyphTone.apply(input,input.size,1,0xffffffff.toInt(),false,0xff8090a0.toInt(),translucent=true)
            .map {it ushr 24}
        assertEquals(0,alpha[0])
        assertTrue(alpha[1]<0x30)
        assertTrue(alpha[2]<alpha[3])
        assertTrue(alpha[3]<alpha[4])
        assertTrue(alpha[4]<215)
    }
}
