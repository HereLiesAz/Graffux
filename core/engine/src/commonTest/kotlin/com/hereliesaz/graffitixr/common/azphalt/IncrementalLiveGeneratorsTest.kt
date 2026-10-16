package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncrementalLiveGeneratorsTest {
    @Test
    fun dynamicDabsIsTheLiveStrokeWithItsOwnPrediction() {
        val brush = AzphaltBrush(
            name = "pressure",
            spacing = 0.2f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.SIZE,
                    outputMin = 0.5f,
                    outputMax = 1f,
                )
            ),
            taper = BrushTaper(startLengthPx = 12f, minSize = 0.2f, minOpacity = 0.4f),
            blot = BrushBlot(lengthPx = 8f, sizeMultiplier = 1.5f, dwellRampMs = 40f, sharpnessMultiplier = 1.4f),
        )
        // Accelerating stroke: a whole-stroke peak speed, a whole-stroke total or a whole-stroke
        // dwell/sharpness would all resolve the early dabs differently from the live stroke.
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, pressure = 0.4f, distancePx = 0f, drawingAngleDeg = 0f),
            BrushSample(0f, 0f, uptimeMillis = 30L, pressure = 0.5f, distancePx = 0f, drawingAngleDeg = 0f),
            BrushSample(10f, 0f, uptimeMillis = 40L, pressure = 0.7f, distancePx = 10f, speedPxPerMs = 0.5f, drawingAngleDeg = 0f),
            BrushSample(40f, 0f, uptimeMillis = 50L, pressure = 1f, distancePx = 40f, speedPxPerMs = 3f, drawingAngleDeg = 0f),
            BrushSample(90f, 0f, uptimeMillis = 60L, pressure = 0.8f, distancePx = 90f, speedPxPerMs = 5f, drawingAngleDeg = 0f),
        )
        val live = IncrementalDynamicDabGenerator(10f, brush, 77L).appendLive(samples)
        assertDabsEquivalent(live, BrushStamps.dynamicDabs(samples, 10f, brush, 77L))
    }

    @Test
    fun speedNeverChangesDabSizeOnTheDynamicPath() {
        // A trivial taper routes the brush through the dynamic generator; it must not then pick up a
        // speed-driven size the static path (which has no speed input) does not have.
        val brush = AzphaltBrush(name = "t", spacing = 0.25f, taper = BrushTaper(startLengthPx = 1f))
        val samples = listOf(
            BrushSample(0f, 0f, uptimeMillis = 0L, distancePx = 0f),
            BrushSample(50f, 0f, uptimeMillis = 10L, distancePx = 50f, speedPxPerMs = 0.1f),
            BrushSample(100f, 0f, uptimeMillis = 20L, distancePx = 100f, speedPxPerMs = 8f),
        )
        val dabs = BrushStamps.dynamicDabs(samples, 20f, brush, 3L)
        // Past the 1px start taper every dab is full size, slow segment or fast.
        val tapered = dabs.filter { it.x >= 1f }
        assertTrue(tapered.size > 4)
        tapered.forEach { assertEquals(10f, it.radius, 1e-4f) }
    }

    @Test
    fun staticGeneratorMatchesBatchIncludingTheFirstDabHeading() {
        val brush = AzphaltBrush(
            name = "follow",
            spacing = 0.3f,
            followStroke = true,
            scatter = 0.5f,
            scatterLongitudinal = 0.3f,
            sizeJitter = 0.2f,
        )
        val points = listOf(0f, 0f, 30f, 30f, 60f, 60f, 60f, 100f)
        val expected = BrushStamps.dabs(points, 10f, brush, 9L)
        val generator = IncrementalStaticDabGenerator(10f, brush, 9L)
        val actual = points.chunked(2).flatMap { (x, y) -> generator.appendPoint(x, y) }
        assertDabsEquivalent(expected, actual)
        assertEquals(0f, actual.first().angleDeg, 1e-4f)
    }

    @Test
    fun heldAirbrushGeneratorMatchesCanonicalWithoutReplayingHistory() {
        val brush = AzphaltBrush(name = "air", sizeJitter = 0.2f, opacityJitter = 0.1f)
        val samples = listOf(
            BrushSample(5f, 5f, uptimeMillis = 0L),
            BrushSample(5f, 5f, uptimeMillis = 120L),
            BrushSample(5f, 5f, uptimeMillis = 240L),
            BrushSample(20f, 5f, uptimeMillis = 260L),
            BrushSample(20f, 5f, uptimeMillis = 500L),
        )
        val expected = AirbrushEngine.heldDabs(samples, 20f, brush, 10f, 2f, 123L)
        val generator = IncrementalAirbrushGenerator(20f, brush, 10f, 2f, 123L)
        val actual = samples.flatMap(generator::append)
        assertDabsEquivalent(expected, actual)
    }

    @Test
    fun heldAirbrushKeepsItsCadenceAtLargeUptime() {
        // 30 h of uptime is past Float's whole-millisecond range; a 1 ms cadence must still advance.
        val t0 = 30L * 60 * 60 * 1000
        val brush = AzphaltBrush(name = "air")
        val samples = listOf(BrushSample(5f, 5f, uptimeMillis = t0), BrushSample(5f, 5f, uptimeMillis = t0 + 100))
        val generator = IncrementalAirbrushGenerator(20f, brush, 1000f, 2f, 1L)
        val live = samples.flatMap(generator::append)
        assertEquals(99, live.size)
        assertEquals(live.size, AirbrushEngine.heldDabs(samples, 20f, brush, 1000f, 2f, 1L).size)
    }

    private fun assertDabsEquivalent(expected: List<Dab>, actual: List<Dab>) {
        assertEquals(expected.size, actual.size, "dab count")
        expected.zip(actual).forEachIndexed { index, (a, b) ->
            assertEquals(a.x, b.x, 1e-3f, "x[$index]")
            assertEquals(a.y, b.y, 1e-3f, "y[$index]")
            assertEquals(a.radius, b.radius, 1e-4f, "radius[$index]")
            assertEquals(a.alpha, b.alpha, 1e-4f, "alpha[$index]")
            assertEquals(a.angleDeg, b.angleDeg, 1e-3f, "angle[$index]")
            assertEquals(a.flowMultiplier, b.flowMultiplier, 1e-4f, "flow[$index]")
            assertEquals(a.sourceRandom, b.sourceRandom, 1e-4f, "random[$index]")
        }
    }
}
