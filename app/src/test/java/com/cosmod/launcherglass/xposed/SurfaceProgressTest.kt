package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class SurfaceProgressTest {
    @Test fun slowGestureKeepsItsLiveSurfaceAsLongAsNativePoseChanges(){
        val progress=SurfaceProgress(0)
        for(time in 0L..20000L step 100L)assertFalse(progress.expired(time,time/100L))
        assertFalse(progress.expired(24000,200))
        assertTrue(progress.expired(24001,200))
    }
    @Test fun abandonedSurfaceStillExpires(){
        val progress=SurfaceProgress(100)
        assertFalse(progress.expired(100,0))
        assertFalse(progress.expired(4100,0))
        assertTrue(progress.expired(4101,0))
    }
}
