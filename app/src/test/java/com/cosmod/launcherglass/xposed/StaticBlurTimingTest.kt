package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class StaticBlurTimingTest {
    @Test fun holdsBeforeFadeAndFinishesAtDeadline(){
        assertEquals(0f,StaticBlurTiming.progress(0),0f)
        assertEquals(0f,StaticBlurTiming.progress(StaticBlurTiming.HOLD_NS),0f)
        assertEquals(.5f,StaticBlurTiming.progress(StaticBlurTiming.HOLD_NS+StaticBlurTiming.FADE_NS/2),0f)
        assertEquals(1f,StaticBlurTiming.progress(StaticBlurTiming.TOTAL_NS),0f)
        assertEquals(1f,StaticBlurTiming.progress(StaticBlurTiming.TOTAL_NS*2),0f)
    }
    @Test fun noNegativeWait(){
        assertEquals(0L,StaticBlurTiming.holdRemaining(StaticBlurTiming.TOTAL_NS))
        assertEquals(StaticBlurTiming.HOLD_NS,StaticBlurTiming.holdRemaining(0))
        assertEquals(0L,StaticBlurTiming.holdRemaining(StaticBlurTiming.HOLD_NS))
    }
    @Test fun duplicateFocusDoesNotRestartFreezeButOppositeDirectionDoes(){
        assertFalse(StaticBlurTiming.restart(1,1,500_000_000L))
        assertTrue(StaticBlurTiming.restart(1,0,10_000_000L))
        assertTrue(StaticBlurTiming.restart(1,1,600_000_000L))
    }
}
