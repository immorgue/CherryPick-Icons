package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class LiveSamplePolicyTest {
    @Test fun hiddenPagesNeverRefreshEvenWithEdgeOverride(){
        for(override in listOf(false,true))for(icon in listOf(false,true)){
            assertFalse(LiveSamplePolicy.refresh(1200f,0f,1410f,210f,1200,2670,icon,override))
            assertFalse(LiveSamplePolicy.refresh(-210f,0f,0f,210f,1200,2670,icon,override))
            assertFalse(LiveSamplePolicy.refresh(0f,2670f,210f,2880f,1200,2670,icon,override))
            assertFalse(LiveSamplePolicy.refresh(0f,-210f,210f,0f,1200,2670,icon,override))
        }
    }
    @Test fun edgeOverrideKeepsPartiallyVisibleIconsLive(){
        assertTrue(LiveSamplePolicy.refresh(-10f,200f,200f,410f,1200,2670,true,true))
        assertFalse(LiveSamplePolicy.refresh(-10f,200f,200f,410f,1200,2670,true,false))
        assertTrue(LiveSamplePolicy.refresh(1100f,200f,1310f,410f,1200,2670,true,true))
        assertFalse(LiveSamplePolicy.refresh(1100f,200f,1310f,410f,1200,2670,true,false))
    }
    @Test fun visibleMaterialsAndNonIconSurfacesKeepTheirNormalUpdates(){
        assertTrue(LiveSamplePolicy.refresh(10f,200f,220f,410f,1200,2670,true,false))
        assertTrue(LiveSamplePolicy.refresh(10f,200f,220f,410f,1200,2670,true,true))
        assertTrue(LiveSamplePolicy.refresh(-10f,200f,220f,410f,1200,2670,false,false))
    }
}
