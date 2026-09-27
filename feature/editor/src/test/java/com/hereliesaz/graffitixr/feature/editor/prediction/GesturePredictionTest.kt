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

class PredictionHorizonRankingTest {

    private fun tournament() = PredictionTournament(
        listOf(LinearGesturePredictor(), AccelerationGesturePredictor()),
        includeGoogleInk = false,
    )

    @Test
    fun everyModelIsRankedAtAllFourHorizons() {
        val t = tournament()
        // Constant velocity, 10 ms samples, 10 ms frames: linear is exact at every horizon.
        for (i in 0..20) {
            t.record(GestureSample(Offset(i * 5f, 0f), i * 10L))
            t.predict(i * 10L + 10L)
        }
        val rankings = t.rankings()
        assertEquals((1..PredictionTournament.HORIZON_FRAMES).toSet(), rankings.keys)
        rankings.forEach { (h, scores) ->
            val models = scores.map { it.model }.filter { it != PredictionTournament.DAMPED_TAIL }.toSet()
            assertEquals("horizon $h", setOf("linear", "acceleration"), models)
            assertEquals("horizon $h linear is exact", 0f, scores.first { it.model == "linear" }.meanErrorPx, 0.01f)
        }
    }

    @Test
    fun accelerationWinsEveryHorizonOnAcceleratingMotion() {
        val t = tournament()
        for (i in 0..30) {
            val time = i * 10L
            t.record(GestureSample(Offset(0.02f * time * time, 0f), time))
            t.predict(time + 10L)
        }
        t.rankings().forEach { (h, scores) ->
            val best = scores.first { it.model != PredictionTournament.DAMPED_TAIL }
            assertEquals("horizon $h", "acceleration", best.model)
        }
        // Error grows with distance ahead for the model that ignores acceleration.
        val linear = t.rankings().mapValues { (_, s) -> s.first { it.model == "linear" }.meanErrorPx }
        assertTrue(linear.getValue(1) < linear.getValue(4))
    }

    @Test
    fun rankingsSurviveStrokeResetUntilCleared() {
        val t = tournament()
        for (i in 0..10) {
            t.record(GestureSample(Offset(i.toFloat(), 0f), i * 10L))
            t.predict(i * 10L + 10L)
        }
        t.reset()
        assertTrue(t.rankings().getValue(1).isNotEmpty())
        t.resetRankings()
        assertTrue(t.rankings().values.all { it.isEmpty() })
    }

    @Test
    fun actualPositionIsInterpolatedBetweenBracketingSamples() {
        val actual = actualAt(
            GestureSample(Offset(0f, 0f), 0L), GestureSample(Offset(10f, 0f), 10L), 4L,
        )
        assertEquals(4f, actual.x, 0.001f)
    }

    @Test
    fun ownHorizonPredictionIsRescaledOntoRequestedFrame() {
        val anchor = GestureSample(Offset(0f, 0f), 0L)
        val own = GesturePrediction("androidx", Offset(8f, 0f), targetUptimeMillis = 8L)
        val aligned = alignTo(anchor, own, 16L)
        assertEquals(16f, aligned!!.position.x, 0.001f)
        assertEquals(16L, aligned.targetUptimeMillis)
    }
}

class TailDampingTest {
    private fun s(x: Float, y: Float, t: Long) = GestureSample(Offset(x, y), t)

    @Test
    fun steadyStraightMotionKeepsFullTail() {
        val f = tailDamping(listOf(s(0f, 0f, 0), s(10f, 0f, 10), s(20f, 0f, 20)), Offset(30f, 0f))
        assertEquals(1f, f, 0.001f)
    }

    @Test
    fun brakingShortensTailBySpeedRatio() {
        val f = tailDamping(listOf(s(0f, 0f, 0), s(10f, 0f, 10), s(15f, 0f, 20)), Offset(20f, 0f))
        assertEquals(0.5f, f, 0.001f)
    }

    @Test
    fun rightAngleTurnHalvesAndReversalKillsTail() {
        val history = listOf(s(0f, 0f, 0), s(10f, 0f, 10), s(20f, 0f, 20))
        assertEquals(0.5f, tailDamping(history, Offset(20f, 10f)), 0.001f)
        assertEquals(0f, tailDamping(history, Offset(10f, 0f)), 0.001f)
    }

    @Test
    fun tooLittleHistoryKeepsFullTail() {
        assertEquals(1f, tailDamping(listOf(s(0f, 0f, 0), s(10f, 0f, 10)), Offset(0f, 50f)), 0.001f)
    }

    @Test
    fun leadIsPositiveWhenAModelRunsAheadAndDampedTailIsRanked() {
        val t = PredictionTournament(
            listOf(LinearGesturePredictor(), AccelerationGesturePredictor()),
            includeGoogleInk = false,
        )
        // Fast then braking to a stop: linear keeps going, so it overshoots (+ lead).
        val xs = listOf(0f, 20f, 40f, 60f, 80f, 95f, 105f, 110f, 112f, 113f, 113f, 113f)
        xs.forEachIndexed { i, x ->
            t.record(GestureSample(Offset(x, 0f), i * 10L))
            t.predict(i * 10L + 10L)
        }
        val f1 = t.rankings().getValue(1)
        assertTrue(f1.first { it.model == "linear" }.meanLeadPx > 0f)
        assertTrue(f1.any { it.model == PredictionTournament.DAMPED_TAIL })
        // Damping must not make the drawn tail worse than the undamped linear model here.
        assertTrue(
            f1.first { it.model == PredictionTournament.DAMPED_TAIL }.meanLeadPx <=
                f1.first { it.model == "linear" }.meanLeadPx,
        )
    }
}
