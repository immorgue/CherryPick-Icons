package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class DockPresenceTest {
    @Test fun initialLayoutDoesNotAnimateAndLastRemovalKeepsOutgoingPlate() {
        val state=DockPresence()
        assertFalse(state.observe(true))
        assertEquals(1f,state.fraction,0f)
        assertTrue(state.observe(false))
        assertTrue(state.drawable)
        assertFalse(state.observe(false))
        state.update(.35f)
        assertTrue(state.drawable)
        state.update(0f)
        assertFalse(state.drawable)
    }
    @Test fun addingFirstIconAndReversingFadeKeepCurrentProgress() {
        val state=DockPresence()
        assertFalse(state.observe(false))
        assertFalse(state.drawable)
        assertTrue(state.observe(true))
        assertTrue(state.drawable)
        state.update(.4f)
        assertTrue(state.observe(false))
        assertEquals(.4f,state.fraction,0f)
        state.update(.2f)
        assertTrue(state.observe(true))
        assertEquals(.2f,state.fraction,0f)
        assertEquals(1f,state.target,0f)
        state.update(Float.NaN)
        assertEquals(.2f,state.fraction,0f)
    }
}
