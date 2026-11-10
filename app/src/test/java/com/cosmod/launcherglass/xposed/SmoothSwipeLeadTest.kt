package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SmoothSwipeLeadTest {
    private fun motion(stepMs:Int):Float {
        val lead=SmoothSwipeLead();var result=0f
        for(t in 0..300 step stepMs)result=lead.update(t*.1f,20f,100f,100f,1_000_000_000L+t*1_000_000L,true)
        return result
    }
    @Test fun predictionHorizonDoesNotGrowAtLowerSamplingRate(){
        assertEquals(1.6f,motion(8),.02f)
        assertEquals(motion(8),motion(16),.02f)
        assertEquals(motion(8),motion(33),.02f)
    }
    @Test fun stopsByReducingLeadWithoutSnappingPosition(){
        val lead=SmoothSwipeLead();var now=1_000_000_000L
        var before=0f
        for(i in 0..20){before=lead.update(i.toFloat(),20f,100f,100f,now,true);now+=16_000_000L}
        val first=lead.update(20f,20f,100f,100f,now,false)
        assertTrue(first>0f && first<before)
        var last=first
        repeat(20){now+=16_000_000L;val next=lead.update(20f,20f,100f,100f,now,false);assertTrue(next<=last);last=next}
        assertEquals(0f,last,0f)
    }
    @Test fun rejectsAnimationScaleVerticalMotionAndNewFlights(){
        val lead=SmoothSwipeLead();var now=1_000_000_000L
        lead.update(0f,20f,100f,100f,now,true)
        now+=16_000_000L;assertTrue(lead.update(3f,20f,100f,100f,now,true)>0f)
        now+=16_000_000L;assertEquals(0f,lead.update(4f,21f,100f,100f,now,true),0f)
        now+=16_000_000L;assertEquals(0f,lead.update(5f,21f,102f,100f,now,true),0f)
        lead.reset();now+=16_000_000L;assertEquals(0f,lead.update(1f,20f,100f,100f,now,true),0f)
        now+=16_000_000L;assertEquals(0f,lead.update(500f,20f,100f,100f,now,true),0f)
    }
    @Test fun boundedReversalAndInvalidCoordinatesCannotPoisonState(){
        val lead=SmoothSwipeLead();var now=1_000_000_000L;var last=0f
        for(i in 0..10){last=lead.update(i.toFloat(),20f,100f,100f,now,true);now+=16_000_000L}
        val first=lead.update(9f,20f,100f,100f,now,true)
        assertTrue(first<last && abs(first-last)<1f)
        repeat(20){now+=16_000_000L;last=lead.update(8f-it,20f,100f,100f,now,true);assertTrue(abs(last)<=8f)}
        assertTrue(last<0f)
        assertEquals(0f,lead.update(Float.NaN,20f,100f,100f,now+16_000_000L,true),0f)
        assertEquals(0f,lead.update(20f,20f,100f,100f,now+32_000_000L,true),0f)
    }
}
