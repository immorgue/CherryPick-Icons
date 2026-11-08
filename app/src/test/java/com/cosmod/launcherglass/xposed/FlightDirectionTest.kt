package com.cosmod.launcherglass.xposed

import org.junit.Assert.*
import org.junit.Test

class FlightDirectionTest {
    @Test fun closingSpringDoesNotDetachLiveMaterial() {
        val flight=FlightDirection()
        flight.update(4f)
        assertEquals(-1,flight.update(3f))
        flight.update(.15f)
        for(area in listOf(.155f,.152f,.16f,.15f,.154f))assertEquals(-1,flight.update(area))
        assertEquals(1,flight.update(.20f)) // a real interrupted launch
    }
    @Test fun openingCanReverseAndExplicitHomeStartsClosingImmediately() {
        val flight=FlightDirection()
        flight.update(.15f)
        assertEquals(1,flight.update(.3f))
        assertEquals(1,flight.update(.29f))
        assertEquals(-1,flight.update(.2f))
        flight.returning()
        assertEquals(-1,flight.update(.19f))
        assertEquals(-1,flight.update(Float.NaN))
    }
}
