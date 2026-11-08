package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class SurfaceTransformUpdateTest {
    @Test fun mergingPositionDoesNotReplaceTheNewScaleWithAnOlderOne(){
        val scale=SurfaceTransformUpdate(matrix=listOf(.4f,0f,0f,.4f))
        val move=SurfaceTransformUpdate(position=100f to 200f)
        assertEquals(scale.matrix,scale.then(move).matrix)
        assertEquals(move.position,scale.then(move).position)
        assertEquals(scale.then(move),move.then(scale))
    }
    @Test fun laterWritesReplaceOnlyTheirOwnChannel(){
        val start=SurfaceTransformUpdate(10f to 20f,listOf(1f,0f,0f,1f))
        val next=start.then(SurfaceTransformUpdate(position=30f to 40f))
        assertEquals(start.matrix,next.matrix)
        assertEquals(30f to 40f,next.position)
    }
}
