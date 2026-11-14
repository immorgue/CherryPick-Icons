package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AppWindowDeformationTest {
    private fun sample(motion: AppGenieMotion, minimized: Double, time: Long, row: Double = .8,
                       opening: Boolean = true, intensity: Double = 1.0) =
        motion.update(minimized, .25, .12, row, intensity, opening, .24, .336, 1.0, time)

    @Test fun centeredSourceAndRestingEndpointsUseNativeGeometry() {
        assertNull(sample(AppGenieMotion(), .5, 1L, row = 0.0))
        assertNull(sample(AppGenieMotion(), .5, 1L, intensity = 0.0))
        assertNull(sample(AppGenieMotion(), 0.0, 1L))
        assertNull(sample(AppGenieMotion(), 1.0, 1L, opening = false))
    }

    @Test fun springShapeChangesDirectionAboveAndBelowCenter() {
        val upper = sample(AppGenieMotion(), .5, 1L, row = -.8)!!
        val lower = sample(AppGenieMotion(), .5, 1L)!!
        val inner = sample(AppGenieMotion(), .5, 1L, row = -.2)!!
        assertTrue(upper.topWidth < upper.bottomWidth)
        assertTrue(lower.bottomWidth < lower.topWidth)
        assertTrue(abs(upper.topOffset) > abs(inner.topOffset))
    }

    @Test fun springsKeepMovingAfterNativeBoundsLand() {
        val motion = AppGenieMotion()
        sample(motion, .05, 1L)
        val first = sample(motion, 0.0, 8_333_334L)!!
        val second = sample(motion, 0.0, 16_666_667L)!!
        assertTrue(abs(second.bottomOffset) < abs(first.bottomOffset))
        for (frame in 3..119) sample(motion, 0.0, frame * 8_333_333L)
        assertNull(sample(motion, 0.0, 120 * 8_333_333L))
    }

    @Test fun projectiveMatrixMapsBothEdgesOfAnOffsetCrop() {
        val shape = sample(AppGenieMotion(), .48, 1L, row = -.8)!!
        val matrix = FloatArray(16)
        val left = 25f
        val top = 130f
        val width = 720f
        val height = 1100f
        shape.writeMatrix(matrix, left, top, width, height)
        fun x(x: Float, y: Float) = (matrix[0] * x + matrix[1] * y + matrix[3]) /
            (matrix[13] * y + matrix[15])
        fun y(y: Float) = (matrix[5] * y + matrix[7]) / (matrix[13] * y + matrix[15])
        assertEquals(top + shape.topOffset * height, y(top), .001f)
        assertEquals(top + height + shape.bottomOffset * height, y(top + height), .001f)
        assertEquals(width * shape.topWidth, x(left + width, top) - x(left, top), .001f)
        assertEquals(width * shape.bottomWidth,
            x(left + width, top + height) - x(left, top + height), .001f)
    }
}
