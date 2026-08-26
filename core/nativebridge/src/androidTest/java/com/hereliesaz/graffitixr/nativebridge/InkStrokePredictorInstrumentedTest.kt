package com.hereliesaz.graffitixr.nativebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkStrokePredictorInstrumentedTest {

    @Test
    fun straightMotionProducesForwardPredictionAndSurvivesReset() {
        InkStrokePredictor().use { predictor ->
            assertTrue(predictor.isAvailable)

            var t = 1_000L
            for (i in 0..7) {
                assertTrue(predictor.record(i * 10f, 40f, t, 0.7f))
                t += 10L
            }

            val first = predictor.predict()
            assertNotNull(first)
            assertTrue(first!!.x > 70f)
            assertTrue(first.uptimeMillis >= 1_070L)
            assertTrue(first.pressure in 0f..1f)

            assertTrue(predictor.reset())
            assertFalse(predictor.predict() != null)

            assertTrue(predictor.record(10f, 10f, 2_000L, 1f))
            // Google's Kalman predictor intentionally withholds predictions until enough real
            // samples make the estimate stable; one point must not invent a future.
            assertTrue(predictor.predict() == null)
        }
    }
}
