package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class MiniIconRefractionTest {
    @Test fun optimizedMinisKeepThirtyFpsAndSkipOnlyRefraction(){
        assertFalse(MiniIconRefresh.refraction(true,true,true))
        assertEquals(30,MiniIconRefresh.fps(60,true,true))
        assertEquals(20,MiniIconRefresh.fps(20,true,true))
        assertEquals(30,MiniIconRefresh.fps(120,true,true))
        assertEquals(120,MiniIconRefresh.fps(120,false,true))
    }
    @Test fun fullIconsAndOptOutKeepLiquidMaterial(){
        assertTrue(MiniIconRefresh.refraction(true,false,true))
        assertTrue(MiniIconRefresh.refraction(true,true,false))
        assertFalse(MiniIconRefresh.refraction(false,false,true))
    }
}
