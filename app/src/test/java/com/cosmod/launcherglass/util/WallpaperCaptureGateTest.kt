package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class WallpaperCaptureGateTest {
    @Test fun fingerprintWakeWaitsForHomeFrameWithoutAddingATimer() {
        val gate = WallpaperCaptureGate()
        val beforeSleep = gate.epoch
        gate.screenOff()
        assertFalse(gate.accepts(beforeSleep))
        gate.screenOn(locked = true)
        gate.userPresent()
        assertFalse(gate.ready())
        val ticket = requireNotNull(gate.homeVisible())
        assertFalse(gate.accepts(ticket))
        assertTrue(gate.homeFrameSubmitted(ticket, stillVisible = true))
        assertTrue(gate.accepts(ticket))
        assertFalse(gate.accepts(beforeSleep))
    }

    @Test fun lateOrDuplicateScreenOnDoesNotRearmAnUnlock() {
        val gate = WallpaperCaptureGate()
        gate.screenOff()
        gate.userPresent()
        val ticket = requireNotNull(gate.homeVisible())
        gate.screenOn(locked = true)
        assertTrue(gate.homeFrameSubmitted(ticket, stillVisible = true))
        gate.userPresent()
        gate.screenOn(locked = false)
        assertTrue(gate.accepts(ticket))
        assertNull(gate.homeVisible())
    }

    @Test fun secondLockInvalidatesPendingUnlockAndItsWork() {
        val gate = WallpaperCaptureGate()
        gate.screenOff()
        gate.screenOn(locked = false)
        val ticket = requireNotNull(gate.homeVisible())
        gate.screenOff()
        assertFalse(gate.homeFrameSubmitted(ticket, stillVisible = true))
        gate.screenOn(locked = false)
        val second = requireNotNull(gate.homeVisible())
        assertTrue(gate.homeFrameSubmitted(second, stillVisible = true))
        assertFalse(gate.accepts(ticket))
    }

    @Test fun delayedFrameFromAnUnfocusedLauncherCannotOpenANewGate() {
        val gate = WallpaperCaptureGate()
        gate.screenOff()
        val first = requireNotNull(gate.homeVisible())
        gate.homeHidden()
        val second = requireNotNull(gate.homeVisible())
        assertFalse(gate.homeFrameSubmitted(first, stillVisible = true))
        assertFalse(gate.ready())
        assertTrue(gate.homeFrameSubmitted(second, stillVisible = true))
    }
}
