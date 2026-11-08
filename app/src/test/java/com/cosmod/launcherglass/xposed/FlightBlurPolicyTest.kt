package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class FlightBlurPolicyTest {
    @Test fun nativeScaleDetectsClosingWithoutLifecycleHints(){
        assertEquals(-1,FlightBlurPolicy.direction(4f,3.8f))
        assertEquals(1,FlightBlurPolicy.direction(.7f,.8f))
        assertEquals(0,FlightBlurPolicy.direction(1f,1.001f))
        assertEquals(0,FlightBlurPolicy.direction(0f,1f))
        assertEquals(0,FlightBlurPolicy.direction(Float.NaN,1f))
    }
    @Test fun closingNeverExceedsThirtyOrTheUserLimit(){
        assertEquals(30,FlightBlurPolicy.fps(60))
        assertEquals(30,FlightBlurPolicy.fps(33))
        assertEquals(20,FlightBlurPolicy.fps(20))
        assertEquals(1,FlightBlurPolicy.fps(1))
    }
    @Test fun openingPreparationDoesNotStartLiveSubmissions(){
        assertFalse(FlightBlurPolicy.live(false,false))
        assertFalse(FlightBlurPolicy.live(false,true))
        assertFalse(FlightBlurPolicy.live(true,true))
        assertTrue(FlightBlurPolicy.live(true,false))
    }
}
