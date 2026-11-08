package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class SampleFrameClockTest {
    @Test fun materialCadenceSurvivesDifferentDisplayRates(){
        for(display in listOf(60,90,120))for(fps in listOf(1,30,33,42,60)){
            val clock=SampleFrameClock()
            var frames=0
            for(tick in 0 until display*5){
                val now=tick*1_000_000_000L/display
                if(clock.remainingNs(now,fps)==0L){clock.record(now,fps);frames++}
            }
            assertTrue("$display Hz/$fps FPS produced $frames",kotlin.math.abs(frames-fps*5)<=1)
        }
    }
    @Test fun lateFrameDoesNotCreateCatchUpBurst(){
        val clock=SampleFrameClock()
        clock.record(1_000_000_000,33)
        assertEquals(0L,clock.remainingNs(2_000_000_000,33))
        clock.record(2_000_000_000,33)
        assertTrue(clock.remainingNs(2_000_000_000,33)>0)
        assertEquals(0L,clock.remainingNs(2_000_000_000,60))
    }
}
