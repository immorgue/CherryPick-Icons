package com.cosmod.launcherglass.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipePredictionTest {
    @Test fun leadsBothDirectionsAndStopsWithoutResidualOffset(){
        assertEquals(5f,SwipePrediction.offset(10f,20f,100f,110f,15f,20f,100f,110f),0f)
        assertEquals(-5f,SwipePrediction.offset(15f,20f,100f,110f,10f,20f,100f,110f),0f)
        assertEquals(0f,SwipePrediction.offset(10f,20f,100f,110f,10f,20f,100f,110f),0f)
    }
    @Test fun rejectsPageJumpsAndAnimationScale(){
        assertEquals(0f,SwipePrediction.offset(10f,20f,100f,110f,110f,20f,100f,110f),0f)
        assertEquals(0f,SwipePrediction.offset(10f,20f,100f,110f,12f,20f,102f,112f),0f)
        assertEquals(0f,SwipePrediction.offset(10f,20f,100f,110f,12f,22f,100f,110f),0f)
        assertEquals(0f,SwipePrediction.offset(10f,20f,100f,110f,Float.NaN,20f,100f,110f),0f)
    }
}
