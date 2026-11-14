package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class LiquidOpticsTest {
    @Test fun kyantRetainsAdjustableBlurInsteadOfTheRetiredSharpBackdropExperiment(){
        for(height in listOf(600,1800))for(radius in listOf(0,7,50)){
            assertEquals(radius*height/800f*.28f,LiquidOptics.blurRadius(radius,height,true),.001f)
            assertEquals(radius*height/800f,LiquidOptics.blurRadius(radius,height,false),.001f)
        }
    }
}
