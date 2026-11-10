package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class MaterialModeTest {
    @Test fun colorModeNeverSamplesEvenWhenGlassWasEnabled() {
        assertFalse(MaterialMode.needsBackdrop(MaterialMode.COLOR,true))
        assertFalse(MaterialMode.needsBackdrop(MaterialMode.COLOR,false))
    }
    @Test fun existingGlassPresetsKeepTheirMeaning() {
        for(mode in listOf(MaterialMode.FROSTED,MaterialMode.LIQUID)) {
            assertTrue(MaterialMode.needsBackdrop(mode,true))
            assertFalse(MaterialMode.needsBackdrop(mode,false))
            assertEquals(mode,MaterialMode.normalize(mode))
        }
    }
    @Test fun optionalSurfaceGlassDoesNotEnableIconSampling() {
        assertTrue(MaterialMode.needsBackdrop(MaterialMode.COLOR,true,folderGlass=true))
        assertTrue(MaterialMode.needsBackdrop(MaterialMode.COLOR,true,dockGlass=true))
        assertFalse(MaterialMode.needsBackdrop(MaterialMode.COLOR,false,true,true))
        assertFalse(MaterialMode.needsBackdrop(MaterialMode.COLOR,true))
    }
}
