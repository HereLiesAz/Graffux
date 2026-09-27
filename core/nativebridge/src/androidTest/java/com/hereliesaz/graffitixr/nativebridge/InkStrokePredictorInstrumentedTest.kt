package com.hereliesaz.graffitixr.nativebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkStrokePredictorInstrumentedTest {

    @Test
    fun straightMotionPredictsAheadOfThePenAndSurvivesReset() {
        InkStrokePredictor().use { predictor ->
            assertTrue(predictor.isAvailable)

            // 1000 px/s along x, 10 ms samples.
            var t = 1_000L
            for (i in 0..11) {
                assertTrue(predictor.record(i * 10f, 40f, t, 0.7f))
                t += 10L
            }
            val lastT = t - 10L
            val lastX = 110f

            val estimate = predictor.estimate()
            assertNotNull(estimate)
            assertEquals(lastT, estimate!!.uptimeMillis)
            assertTrue("velocity ~1000 px/s, was ${estimate.vx}", estimate.vx in 800f..1200f)

            // Ahead of the pen, not trailing it (the old StrokeModeler path lagged ~74 px).
            val oneFrame = predictor.predictAt(lastT + 16L)!!
            assertEquals(lastX + 16f, oneFrame.x, 4f)
            assertEquals(40f, oneFrame.y, 2f)
            val fourFrames = predictor.predictAt(lastT + 64L)!!
            assertEquals(lastX + 64f, fourFrames.x, 10f)

            assertTrue(predictor.reset())
            assertNull(predictor.estimate())
            assertTrue(predictor.record(10f, 10f, 2_000L, 1f))
            // Ink withholds an estimate until its Kalman filters are stable; one point is not enough.
            assertNull(predictor.predictAt(2_016L))
        }
    }
}
