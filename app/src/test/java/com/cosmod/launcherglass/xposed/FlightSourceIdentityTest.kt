package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class FlightSourceIdentityTest {
    @Test fun missingOrClonedTagDoesNotRetireLiveFlight() {
        val source=FlightSourceIdentity(42,"work")
        assertFalse(source.replacedBy(null))
        assertFalse(source.replacedBy(FlightSourceIdentity(42,"work")))
        assertTrue(source.replacedBy(FlightSourceIdentity(43,"work")))
        assertTrue(source.replacedBy(FlightSourceIdentity(42,"personal")))
    }
}
