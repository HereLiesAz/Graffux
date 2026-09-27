package com.hereliesaz.graffitixr.feature.editor.prediction

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun actualPositionIsInterpolatedBetweenBracketingSamples() {
        val actual = actualAt(
            GestureSample(Offset(0f, 0f), 0L), GestureSample(Offset(10f, 0f), 10L), 4L,
        )
        assertEquals(4f, actual.x, 0.001f)
    }
}

/** Always predicts `target * slope` along x, or nothing while [ready] is false. */
private class FixedPredictor(
    override val name: String,
    private val slope: Float,
    var ready: Boolean = true,
) : GesturePredictor {
    override fun reset() = Unit
    override fun record(sample: GestureSample) = Unit
    override fun predict(targetUptimeMillis: Long) =
        if (!ready) null else GesturePrediction(name, Offset(targetUptimeMillis * slope, 0f), targetUptimeMillis)
}

class PredictionTailTest {

    @Test
    fun tailComesFromTheFirstModelThatCanPredict() {
        val ink = FixedPredictor(PredictionTournament.GOOGLE_INK, slope = 1f, ready = false)
        val linear = FixedPredictor("linear", slope = 2f)
        val t = PredictionTournament(listOf(ink, linear), includeGoogleInk = false)
        t.record(GestureSample(Offset(0f, 0f), 0L))

        // Ink not stable yet: linear draws the tail.
        assertEquals("linear", t.predict(16L)!!.model)
        // Once Ink can predict, it takes over.
        ink.ready = true
        assertEquals(PredictionTournament.GOOGLE_INK, t.predict(16L)!!.model)
    }

    @Test
    fun tailIsACurveThroughEachFrameUpToItsReach() {
        val t = PredictionTournament(listOf(FixedPredictor("linear", slope = 2f)), includeGoogleInk = false)
        t.record(GestureSample(Offset(0f, 0f), 0L))
        // No measured lag: two frames (16 ms each), one point per frame.
        assertEquals(listOf(Offset(32f, 0f), Offset(64f, 0f)), t.predict(16L)!!.points)
    }

    @Test
    fun tailReachFollowsMeasuredLagClampedToOneToTwoFrames() {
        val t = PredictionTournament(listOf(FixedPredictor("linear", slope = 1f)), includeGoogleInk = false)
        t.record(GestureSample(Offset(0f, 0f), 0L))
        // 20 ms of lag: frame 1 (16 ms), then the reach at 20 ms.
        assertEquals(listOf(Offset(16f, 0f), Offset(20f, 0f)), t.predict(16L, tailLeadMs = 20L)!!.points)
        // Less than a frame: never shorter than one frame.
        assertEquals(listOf(Offset(16f, 0f)), t.predict(16L, tailLeadMs = 5L)!!.points)
        // A lot of lag: never past two frames.
        assertEquals(Offset(32f, 0f), t.predict(16L, tailLeadMs = 200L)!!.points.last())
    }

    @Test
    fun noModelReadyMeansNoTail() {
        val t = PredictionTournament(listOf(LinearGesturePredictor()), includeGoogleInk = false)
        t.record(GestureSample(Offset(0f, 0f), 0L))
        assertNull(t.predict(16L))
    }
}

class PredictionHorizonRankingTest {

    private fun tournament() = PredictionTournament(listOf(LinearGesturePredictor()), includeGoogleInk = false)

    @Test
    fun linearIsRankedAtAllFourHorizonsAndExactOnConstantVelocity() {
        val t = tournament()
        for (i in 0..20) {
            t.record(GestureSample(Offset(i * 5f, 0f), i * 10L))
            t.predict(i * 10L + 10L)
        }
        val rankings = t.rankings()
        assertEquals((1..PredictionTournament.HORIZON_FRAMES).toSet(), rankings.keys)
        rankings.forEach { (h, scores) ->
            assertEquals("horizon $h", listOf("linear"), scores.map { it.model })
            assertEquals("horizon $h linear is exact", 0f, scores.single().meanErrorPx, 0.01f)
        }
    }

    @Test
    fun errorGrowsWithDistanceAheadOnAcceleratingMotion() {
        val t = tournament()
        for (i in 0..30) {
            val time = i * 10L
            t.record(GestureSample(Offset(0.02f * time * time, 0f), time))
            t.predict(time + 10L)
        }
        val linear = t.rankings().mapValues { (_, s) -> s.single().meanErrorPx }
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
    fun reportNamesTheModelsTailAndCost() {
        val t = tournament()
        t.record(GestureSample(Offset(0f, 0f), 0L))
        t.record(GestureSample(Offset(5f, 0f), 10L))
        t.predict(20L)
        assertTrue(t.rankingReport().contains("cost per sample: mean "))
        assertTrue(t.rankingReport().startsWith("models: linear, ink: standard (tail: measured lag, max 2 frames)\n"))
    }
}

class PredictionSoloTest {
    @Test
    fun soloRunsOnlyTheNamedPredictor() {
        val t = PredictionTournament(
            listOf(FixedPredictor(PredictionTournament.GOOGLE_INK, 1f), LinearGesturePredictor()),
            includeGoogleInk = false,
            soloModel = "linear",
        )
        assertEquals(listOf("linear"), t.activeModels)
    }

    @Test
    fun unknownSoloFallsBackToAll() {
        val t = PredictionTournament(listOf(LinearGesturePredictor()), includeGoogleInk = false, soloModel = "nope")
        assertEquals(listOf("linear"), t.activeModels)
    }
}

class PredictionEndStrokeTest {
    @Test
    fun overshootPastTheLiftPointIsScoredNotDropped() {
        val t = PredictionTournament(listOf(LinearGesturePredictor()), includeGoogleInk = false)
        // Moving right at 1 px/ms, then the pen lifts where it is.
        for (i in 0..5) {
            t.record(GestureSample(Offset(i * 10f, 0f), i * 10L))
            t.predict(i * 10L + 16L)
        }
        val before = t.rankings().getValue(4).firstOrNull { it.model == "linear" }?.samples ?: 0
        t.endStroke(Offset(50f, 0f))
        val f4 = t.rankings().getValue(4).first { it.model == "linear" }
        assertTrue("pending f4 predictions scored at lift", f4.samples > before)
        // Linear keeps going past the lift point, so its lead must now show overshoot.
        assertTrue("lead was ${f4.meanLeadPx}", f4.meanLeadPx > 0f)
        // Nothing left to score twice.
        t.endStroke(Offset(50f, 0f))
        assertEquals(f4.samples, t.rankings().getValue(4).first { it.model == "linear" }.samples)
    }
}
