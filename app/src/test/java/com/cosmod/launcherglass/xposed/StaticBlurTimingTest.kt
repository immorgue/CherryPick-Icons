package com.cosmod.launcherglass.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class StaticBlurTimingTest {
    @Test fun fadeStartsAtHandoffAndFinishesOnce() {
        assertEquals(0f,StaticBlurTiming.returnProgress(-1),0f)
        assertEquals(0f,StaticBlurTiming.returnProgress(0),0f)
        assertEquals(.5f,StaticBlurTiming.returnProgress(60_000_000L),0f)
        assertEquals(1f,StaticBlurTiming.returnProgress(120_000_000L),0f)
        assertEquals(1f,StaticBlurTiming.returnProgress(500_000_000L),0f)
    }
}
