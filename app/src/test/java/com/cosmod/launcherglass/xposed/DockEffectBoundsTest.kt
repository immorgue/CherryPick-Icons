package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class DockEffectBoundsTest {
    @Test fun bottomDockKeepsPaddingWithoutScreenHighBuffer() {
        val top=DockEffectBounds.top(2670,2340f,2340f,384)
        assertEquals(1920,top)
        assertTrue(top<=2340-384)
        assertEquals(750,2670-top)
    }
    @Test fun unlockMovementAndExpandedDockRemainInsideEffectInput() {
        for(posed in listOf(-120f,600f,1300f,2200f,2800f)) {
            val top=DockEffectBounds.top(2670,2340f,posed,384)
            assertTrue(top<=maxOf(0f,minOf(2340f,posed)-384f))
        }
        assertEquals(896,DockEffectBounds.top(2670,1400f,1400f,450))
    }
    @Test fun invalidOrNotYetLaidOutGeometryKeepsFullInput() {
        assertEquals(0,DockEffectBounds.top(0,2000f,2000f,384))
        assertEquals(0,DockEffectBounds.top(2670,Float.NaN,2000f,384))
        assertEquals(0,DockEffectBounds.top(2670,2000f,Float.POSITIVE_INFINITY,384))
    }
}
