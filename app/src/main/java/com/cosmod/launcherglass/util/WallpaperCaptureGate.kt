package com.cosmod.launcherglass.util

/** Reject captures from before sleep or before the unlocked launcher has rendered. */
internal class WallpaperCaptureGate {
    private enum class Phase { READY, ASLEEP, LOCKED, UNLOCKED, DRAWING_HOME }
    @Volatile var epoch = 0L
        private set
    private var phase = Phase.READY

    @Synchronized fun screenOff() {
        phase = Phase.ASLEEP
        epoch++
    }

    @Synchronized fun screenOn(locked: Boolean) {
        // Fingerprint wakes can deliver SCREEN_ON after USER_PRESENT or
        // after the launcher has already received focus.
        if (phase == Phase.ASLEEP || (phase == Phase.LOCKED && !locked)) {
            phase = if (locked) Phase.LOCKED else Phase.UNLOCKED
        }
    }

    @Synchronized fun userPresent() {
        if (phase == Phase.LOCKED || phase == Phase.ASLEEP) phase = Phase.UNLOCKED
    }

    @Synchronized fun homeVisible(): Long? {
        if (phase == Phase.READY || phase == Phase.DRAWING_HOME) return null
        phase = Phase.DRAWING_HOME
        return epoch
    }

    @Synchronized fun homeFrameSubmitted(ticket: Long, stillVisible: Boolean): Boolean {
        if (ticket != epoch || phase != Phase.DRAWING_HOME) return false
        phase = if (stillVisible) Phase.READY else Phase.UNLOCKED
        return stillVisible
    }

    @Synchronized fun homeHidden() {
        if (phase != Phase.DRAWING_HOME) return
        phase = Phase.UNLOCKED
        epoch++
    }

    @Synchronized fun ready() = phase == Phase.READY
    @Synchronized fun accepts(ticket: Long) = ticket == epoch && phase == Phase.READY
}
