package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class FlyingIconPoseTest {
    @Test fun launchOriginEasesIntoTheOriginalTrajectory() {
        assertEquals(1f, FlyingIconPose.launchOriginWeight(0f), 0f)
        assertEquals(.5f, FlyingIconPose.launchOriginWeight(.16f), 0f)
        assertTrue(FlyingIconPose.launchOriginWeight(.319f) < .0001f)
        assertEquals(0f, FlyingIconPose.launchOriginWeight(.32f), 0f)
        assertEquals(0f, FlyingIconPose.launchOriginWeight(1f), 0f)
    }

    @Test fun openingStartsAtTheIconAndCatchesUpWithoutReversing() {
        assertEquals(0f,FlyingIconPose.openingGrowth(0f,1f),0f)
        assertTrue(FlyingIconPose.openingGrowth(.2f,1f) in .13f.. .15f)
        assertEquals(.48f,FlyingIconPose.openingGrowth(.48f,1f),0f)
        assertEquals(.2f,FlyingIconPose.openingGrowth(.2f,0f),0f)
        val visible=FlyingIconPose.openingGrowth(.2f,1f)
        assertTrue(FlyingIconPose.growthScale(.2f,visible,710f,235f) in .77f.. .83f)
        assertEquals(1f,FlyingIconPose.growthScale(0f,0f,235f,235f),0f)
        var last=0f
        for (frame in 1..100) {
            val next=FlyingIconPose.openingGrowth(frame/100f,1f)
            assertTrue(next>last)
            last=next
        }
    }
    @Test fun windowAndIconConvergeFromTheSameRestingPose() {
        assertEquals(0f, FlyingIconPose.windowFollowWeight(0f), 0f)
        assertTrue(FlyingIconPose.windowFollowWeight(.05f) in 0f..1f)
        assertEquals(1f, FlyingIconPose.windowFollowWeight(.24f), 0f)
    }

    @Test fun windowStartHeightInterpolatesBetweenTopAndDock() {
        fun offset(row: Float?, progress: Float = .13f) =
            FlyingIconPose.openingWindowOffsetY(row, progress, 231f, 170f, 170f)
        val top = offset(-.67f)
        val dock = offset(.82f)
        assertEquals(-.27f * 231f, top, .001f)
        assertEquals(.25f * 231f, dock, .001f)
        assertEquals((top + dock) * .5f, offset(.075f), .001f)
        assertEquals(dock, offset(.9f), .001f)
        assertEquals(10f, FlyingIconPose.openingWindowOffsetY(.9f, .13f, 231f, 170f, 10f), 0f)
        assertEquals(0f, offset(null), 0f)
        assertEquals(0f, offset(-.8f, 0f), 0f)
        assertEquals(0f, offset(.9f, 1f), 0f)
    }

    @Test fun closingDockOffsetEasesIntoLandingAndReversesWithoutAJump() {
        fun closing(progress: Float) = FlyingIconPose.closingWindowOffsetY(.82f, progress, 231f, 130f)
        assertEquals(0f, closing(0f), 0f)
        assertEquals(0f, closing(1f), 0f)
        assertTrue(closing(.012f) < .3f)
        assertTrue(closing(.3f) > 40f)
        assertEquals(0f, FlyingIconPose.closingWindowOffsetY(-.78f, .3f, 231f, 130f), 0f)
        assertEquals(closing(.2f), FlyingIconPose.windowOffsetY(58f, closing(.2f), 0f), 0f)
        assertEquals(58f, FlyingIconPose.windowOffsetY(58f, closing(.2f), 1f), .001f)
        assertEquals(closing(.2f), FlyingIconPose.windowOffsetY(closing(.2f), closing(.2f), .5f), 0f)
    }

    @Test fun iconWindowAndWorkspaceFollowTheSameProgress() {
        assertEquals(0f, FlyingIconPose.windowReveal(1f, 0f), 0f)
        assertEquals(1f, FlyingIconPose.iconReveal(0f), 0f)
        assertEquals(0f, FlyingIconPose.workspaceBlur(1f, 0f), 0f)
        assertEquals(1f, FlyingIconPose.windowReveal(1f, 1f), 0f)
        assertEquals(0f, FlyingIconPose.iconReveal(1f), 0f)
        assertEquals(1f, FlyingIconPose.workspaceBlur(1f, 1f), 0f)
        assertTrue(FlyingIconPose.windowReveal(.2f, .18f) in .24f.. .29f)
        assertEquals(.5f, FlyingIconPose.iconReveal(.75f), .001f)
        assertTrue(FlyingIconPose.windowReveal(0f, .18f) in .10f.. .13f)
        assertEquals(0f, FlyingIconPose.windowReveal(0f, .5f), 0f)
        assertTrue(FlyingIconPose.windowReveal(.02f, .18f)-FlyingIconPose.windowReveal(0f, .18f) < .01f)
        assertEquals(0f, FlyingIconPose.workspaceBlur(0f, .8f), 0f)
    }
    @Test fun releaseIsContinuousAndCannotOutliveTheLanding(){
        var last=0f
        for(i in 0..300){
            val weight=FlyingIconPose.windowFollowWeight(i/1000f)
            assertTrue(weight>=last)
            assertTrue(weight-last<.01f)
            last=weight
        }
        assertEquals(1f,last,.00001f)
    }
}
