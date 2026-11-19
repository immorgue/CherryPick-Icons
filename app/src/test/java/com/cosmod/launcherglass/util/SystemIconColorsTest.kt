package com.cosmod.launcherglass.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemIconColorsTest {
    @Test fun usesNativePlateAndGlyphOrderInBothAppearances() {
        val saved = "NEUTRAL,-6636990,-10723251,-1841964,-3814991,-12171204"
        assertEquals(SystemIconColors.Tones(0xffe3e4d4.toInt(), 0xff5c604d.toInt()),
            SystemIconColors.parseColorOs(saved, dark = false))
        assertEquals(SystemIconColors.Tones(0xff46483c.toInt(), 0xffc5c9b1.toInt()),
            SystemIconColors.parseColorOs(saved, dark = true))
    }

    @Test fun ignoresIncompleteOrInvalidSystemValues() {
        assertNull(SystemIconColors.parseColorOs("NEUTRAL,1,-10723251,-1841964", dark = false))
        assertNull(SystemIconColors.parseColorOs("NEUTRAL,1,garbage,-1841964,-3814991,-12171204", dark = false))
    }
}
