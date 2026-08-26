package com.hereliesaz.graffitixr.feature.editor.prediction

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePredictionTest {

    @Test
    fun linearPredictorProjectsConstantVelocity() {
        val predictor = LinearGesturePredictor()
        predictor.record(GestureSample(Offset(0f, 0f), 0L))
        predictor.record(GestureSample(Offset(10f, 0f), 10L))

        val prediction = predictor.predict(20L)

        assertNotNull(prediction)
        assertEquals(20f, prediction!!.position.x, 0.001f)
        assertEquals(0f, prediction.position.y, 0.001f)
    }

    @Test
    fun accelerationPredictorExtendsAcceleratingMotion() {
        val predictor = AccelerationGesturePredictor()
        predictor.record(GestureSample(Offset(0f, 0f), 0L))
        predictor.record(GestureSample(Offset(10f, 0f), 10L))
        predictor.record(GestureSample(Offset(30f, 0f), 20L))

        val prediction = predictor.predict(30L)

        assertNotNull(prediction)
        assertTrue(prediction!!.position.x > 50f)
    }

    @Test
    fun tournamentLearnsLowerErrorModel() {
        val good = FixedPredictor("good") { target -> Offset(target.toFloat(), 0f) }
        val bad = FixedPredictor("bad") { target -> Offset(target.toFloat() + 100f, 0f) }
        val tournament = PredictionTournament(listOf(good, bad), errorSmoothing = 1f)

        tournament.record(GestureSample(Offset(0f, 0f), 0L))
        tournament.predict(10L)
        tournament.record(GestureSample(Offset(10f, 0f), 10L))

        val next = tournament.predict(20L)

        assertEquals("good", next?.model)
        val leaderboard = tournament.leaderboard()
        assertEquals("good", leaderboard.first().first)
        assertTrue(leaderboard.first().second < leaderboard.last().second)
    }

    private class FixedPredictor(
        override val name: String,
        private val point: (Long) -> Offset,
    ) : GesturePredictor {
        override fun reset() = Unit
        override fun record(sample: GestureSample) = Unit
        override fun predict(targetUptimeMillis: Long) = GesturePrediction(
            model = name,
            position = point(targetUptimeMillis),
            targetUptimeMillis = targetUptimeMillis,
        )
    }
}
