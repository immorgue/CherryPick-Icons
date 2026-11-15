package com.cosmod.launcherglass.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IslandSpringTest {
    @Test fun startsWithoutPositionOrVelocityJump(){
        val spring=IslandSpring(.43f,-1.7f,1f,true)
        assertEquals(.43f,spring.position(0.0),.00001f)
        assertEquals(-1.7f,spring.velocity(0.0),.00001f)
    }
    @Test fun openingOvershootsAndSettles(){
        val spring=IslandSpring(0f,0f,1f,true)
        assertTrue((0..1100).maxOf {spring.position(it/1000.0)}>1.01f)
        assertEquals(1f,spring.position(1.1),.0001f)
        assertTrue(abs(spring.velocity(1.1))<.001f)
    }
    @Test fun closingSettlesAtCapsuleWithoutEndCorrection(){
        val spring=IslandSpring(1f,0f,0f,false)
        assertTrue(abs(spring.position(.95))<.0001f)
        assertTrue(abs(spring.velocity(.95))<.001f)
    }
    @Test fun closingDoesNotBounceAndHeightRetreatsBeforeWidth(){
        val width=IslandSpring(1f,0f,0f,false)
        val height=IslandSpring(1f,0f,0f,false,1.65)
        var previous=1f
        for(frame in 1..120){
            val time=frame/120.0
            val position=width.position(time)
            assertTrue(position>=0f && position<=previous)
            assertTrue(height.position(time)<position)
            previous=position
        }
    }
    @Test fun reversalPreservesMomentum(){
        val opening=IslandSpring(0f,0f,1f,true)
        val position=opening.position(.09)
        val velocity=opening.velocity(.09)
        val closing=IslandSpring(position,velocity,0f,false)
        assertEquals(position,closing.position(0.0),.00001f)
        assertEquals(velocity,closing.velocity(0.0),.00001f)
        assertTrue(closing.position(.005)>position)
        assertTrue(abs(closing.position(.95))<.0001f)
    }
}
